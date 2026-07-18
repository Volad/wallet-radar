package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * B-ETH-02 — links a {@code LENDING_LOOP_OPEN} to its subsequent
 * {@code LENDING_LOOP_DECREASE} / {@code LENDING_LOOP_CLOSE} legs by stamping a shared
 * {@code lending-loop:{openTxHash}} correlation id.
 *
 * <p>Without this link the open parks no basis and replay re-prices the returned collateral at
 * market on close instead of restoring the carried cost basis. The correlation is consumed at
 * replay time by {@code ReplayTransferClassifier}/{@code ReplayPendingTransferKeyFactory} to route
 * the collateral principal through a network-agnostic continuity bucket (park on open, restore
 * pro-rata on decrease/close).
 *
 * <p><b>Matching anchors</b> (network equality is deliberately NOT required — a loop may open on
 * one network and close on another, and may stay open for months):
 * <ul>
 *   <li>same {@code walletAddress};</li>
 *   <li>same {@code protocolName} (case-insensitive);</li>
 *   <li>same position identity — {@code metadata.positionKey} when both legs carry one, else the
 *       collateral asset's continuity-family identity (the "collateral asset symbol/contract" of
 *       the design). The lending market asset is side-dependent for loop OPEN vs CLOSE, so it is
 *       intentionally not used as a hard key; protocol + collateral family + wallet +
 *       time-ordering provide the deterministic anchor;</li>
 *   <li>monotonic open-before-close (open {@code blockTimestamp} at or before the candidate);</li>
 *   <li>a single still-open OPEN in the window after the last close of the same identity — two
 *       overlapping loops on the same wallet+protocol+identity yield no deterministic match and are
 *       left unlinked rather than mis-paired.</li>
 * </ul>
 *
 * <p><b>Per-open-instance correlation.</b> The correlation id is derived from the OPEN tx hash, so
 * a position reopened after a full close receives a fresh correlation and parked basis never bleeds
 * across loop lifecycles. Supports 1→N (one OPEN, several partial DECREASEs plus a final CLOSE):
 * each DECREASE/CLOSE independently resolves the same still-open OPEN and stamps the same id.
 *
 * <p>Registered as a convergent linking pass; converges to zero once every reachable
 * DECREASE/CLOSE has been stamped.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LendingLoopOpenClosePairLinkService {

    /** Loops can stay open for months; bound the lookback generously but keep it finite. */
    private static final Duration OPEN_LOOKBACK = Duration.ofDays(730);

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcileOutstandingLoops(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction candidate : loadUnlinkedCloseCandidates(batchSize)) {
            if (pair(candidate)) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("LendingLoopOpenClosePairLink: linked={}", changed);
        }
        return changed;
    }

    boolean pair(NormalizedTransaction candidate) {
        if (!isUnlinkedCloseCandidate(candidate)) {
            return false;
        }
        NormalizedTransaction open = findMatchingOpen(candidate);
        if (open == null || open.getTxHash() == null || open.getTxHash().isBlank()) {
            return false;
        }
        return stampCorrelation(open, candidate);
    }

    private List<NormalizedTransaction> loadUnlinkedCloseCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(
                        NormalizedTransactionType.LENDING_LOOP_DECREASE,
                        NormalizedTransactionType.LENDING_LOOP_CLOSE),
                Criteria.where("walletAddress").exists(true).ne(""),
                Criteria.where("blockTimestamp").exists(true),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is(""),
                        Criteria.where("correlationId").not().regex("^" + CorrelationContract.LENDING_LOOP_PREFIX)
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::isUnlinkedCloseCandidate)
                .toList();
    }

    private boolean isUnlinkedCloseCandidate(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.ON_CHAIN
                || transaction.getWalletAddress() == null
                || transaction.getWalletAddress().isBlank()
                || transaction.getBlockTimestamp() == null) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type != NormalizedTransactionType.LENDING_LOOP_DECREASE
                && type != NormalizedTransactionType.LENDING_LOOP_CLOSE) {
            return false;
        }
        if (hasLendingLoopCorrelation(transaction.getCorrelationId())) {
            return false;
        }
        return collateralFamily(transaction, 1) != null;
    }

    /**
     * Resolves the single still-open {@code LENDING_LOOP_OPEN} for {@code candidate}: the latest
     * OPEN of the same identity that lies after the last close of that identity preceding the
     * candidate. Returns {@code null} when there is no such OPEN or when two or more OPENs remain
     * open in that window (overlapping loops — abstain rather than mis-pair).
     */
    private NormalizedTransaction findMatchingOpen(NormalizedTransaction candidate) {
        Instant candidateTime = candidate.getBlockTimestamp();
        Instant lookbackStart = candidateTime.minus(OPEN_LOOKBACK);

        List<NormalizedTransaction> matchingOpens = loadIdentityMatchingLoops(
                candidate,
                NormalizedTransactionType.LENDING_LOOP_OPEN,
                lookbackStart,
                candidateTime
        ).stream()
                .filter(open -> isBeforeOrTieBreak(open, candidate))
                .sorted(chronological().reversed())
                .toList();
        if (matchingOpens.isEmpty()) {
            return null;
        }

        Instant lastCloseBefore = loadIdentityMatchingLoops(
                candidate,
                NormalizedTransactionType.LENDING_LOOP_CLOSE,
                lookbackStart,
                candidateTime
        ).stream()
                .filter(close -> !Objects.equals(close.getId(), candidate.getId()))
                .filter(close -> isStrictlyBefore(close, candidate))
                .map(NormalizedTransaction::getBlockTimestamp)
                .max(Instant::compareTo)
                .orElse(null);

        List<NormalizedTransaction> stillOpen = new ArrayList<>();
        for (NormalizedTransaction open : matchingOpens) {
            if (lastCloseBefore == null || open.getBlockTimestamp().isAfter(lastCloseBefore)) {
                stillOpen.add(open);
            }
        }
        if (stillOpen.size() != 1) {
            if (stillOpen.size() > 1) {
                log.debug("LendingLoopOpenClosePairLink: ambiguous overlapping loops wallet={} candidate={} stillOpen={}",
                        candidate.getWalletAddress(), candidate.getTxHash(), stillOpen.size());
            }
            return null;
        }
        return stillOpen.getFirst();
    }

    private List<NormalizedTransaction> loadIdentityMatchingLoops(
            NormalizedTransaction candidate,
            NormalizedTransactionType type,
            Instant lookbackStart,
            Instant candidateTime
    ) {
        int openSign = type == NormalizedTransactionType.LENDING_LOOP_OPEN ? -1 : 1;
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(type),
                Criteria.where("walletAddress").is(candidate.getWalletAddress()),
                Criteria.where("blockTimestamp").gte(lookbackStart).lte(candidateTime)
        ));
        query.with(Sort.by(
                Sort.Order.desc("blockTimestamp"),
                Sort.Order.desc("transactionIndex"),
                Sort.Order.desc("_id")
        ));
        query.limit(64);
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(loop -> identityMatches(candidate, loop, openSign))
                .toList();
    }

    private boolean identityMatches(NormalizedTransaction candidate, NormalizedTransaction loop, int loopCollateralSign) {
        if (!protocolEquals(candidate, loop)) {
            return false;
        }
        String candidateFamily = collateralFamily(candidate, 1);
        String loopFamily = collateralFamily(loop, loopCollateralSign);
        if (candidateFamily == null || !candidateFamily.equals(loopFamily)) {
            return false;
        }
        String candidateKey = positionKey(candidate);
        String loopKey = positionKey(loop);
        if (candidateKey != null && loopKey != null) {
            return candidateKey.equals(loopKey);
        }
        return true;
    }

    private boolean stampCorrelation(NormalizedTransaction open, NormalizedTransaction candidate) {
        String correlationId = lendingLoopCorrelationId(open.getTxHash());
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        if (applyCorrelation(open, correlationId, now)) {
            dirty.add(open);
        }
        if (applyCorrelation(candidate, correlationId, now)) {
            dirty.add(candidate);
        }
        if (dirty.isEmpty()) {
            return false;
        }
        log.info("LENDING_LOOP_OPEN_CLOSE_LINK corrId={} open={} leg={} legType={}",
                correlationId, open.getTxHash(), candidate.getTxHash(), candidate.getType());
        normalizedTransactionRepository.saveAll(dirty);
        return true;
    }

    /**
     * Stamps the shared correlation id. {@code continuityCandidate} is intentionally left untouched:
     * replay routes the collateral principal through the continuity bucket via the
     * {@code lending-loop:} correlation prefix alone, and setting {@code continuityCandidate=true}
     * would additionally route the borrowed debt leg (e.g. USDC) into the guarded {@code corr-family:}
     * queue, which would double-count / mis-match the borrow against the collateral. See
     * {@code ReplayTransferClassifier#isBucketOutbound}.
     */
    private static boolean applyCorrelation(NormalizedTransaction transaction, String correlationId, Instant now) {
        if (Objects.equals(transaction.getCorrelationId(), correlationId)) {
            return false;
        }
        transaction.setCorrelationId(correlationId);
        transaction.setUpdatedAt(now);
        return true;
    }

    private static String lendingLoopCorrelationId(String openTxHash) {
        return CorrelationContract.LENDING_LOOP_PREFIX + openTxHash.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasLendingLoopCorrelation(String correlationId) {
        return correlationId != null
                && correlationId.startsWith(CorrelationContract.LENDING_LOOP_PREFIX);
    }

    private static boolean protocolEquals(NormalizedTransaction left, NormalizedTransaction right) {
        String leftProtocol = normalizeProtocol(left.getProtocolName());
        String rightProtocol = normalizeProtocol(right.getProtocolName());
        if (leftProtocol == null || rightProtocol == null) {
            return false;
        }
        return leftProtocol.equals(rightProtocol);
    }

    private static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        return protocol.trim().toUpperCase(Locale.ROOT);
    }

    private static String positionKey(NormalizedTransaction transaction) {
        if (transaction.getMetadata() == null) {
            return null;
        }
        Object value = transaction.getMetadata().get("positionKey");
        if (value == null) {
            return null;
        }
        String key = value.toString().trim();
        return key.isEmpty() ? null : key;
    }

    /**
     * Continuity-family identity of the dominant collateral principal leg of the requested sign,
     * excluding the debt/borrow receipt. Stable across OPEN (collateral outbound, sign -1) and
     * DECREASE/CLOSE (collateral inbound, sign +1) because it is derived from the asset family.
     */
    private static String collateralFamily(NormalizedTransaction transaction, int requiredSign) {
        NormalizedTransaction.Flow dominant = dominantCollateralPrincipal(transaction, requiredSign);
        return dominant == null ? null : AccountingAssetFamilySupport.continuityIdentity(dominant);
    }

    private static NormalizedTransaction.Flow dominantCollateralPrincipal(
            NormalizedTransaction transaction,
            int requiredSign
    ) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        NormalizedTransaction.Flow best = null;
        BigDecimal bestValue = null;
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() != NormalizedLegRole.TRANSFER
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() != requiredSign) {
                continue;
            }
            if (AccountingAssetIdentitySupport.isDebtIdentity(candidate.getAssetSymbol())) {
                continue;
            }
            BigDecimal value = principalRankingValue(candidate);
            if (best == null || value.compareTo(bestValue) > 0) {
                best = candidate;
                bestValue = value;
            }
        }
        return best;
    }

    private static BigDecimal principalRankingValue(NormalizedTransaction.Flow flow) {
        if (flow.getValueUsd() != null && flow.getValueUsd().abs().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null
                && flow.getUnitPriceUsd().signum() > 0
                && flow.getQuantityDelta() != null) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd());
        }
        return flow.getQuantityDelta() == null ? BigDecimal.ZERO : flow.getQuantityDelta().abs();
    }

    private static boolean isBeforeOrTieBreak(NormalizedTransaction open, NormalizedTransaction candidate) {
        if (Objects.equals(open.getId(), candidate.getId())) {
            return false;
        }
        int cmp = open.getBlockTimestamp().compareTo(candidate.getBlockTimestamp());
        if (cmp < 0) {
            return true;
        }
        if (cmp > 0) {
            return false;
        }
        return transactionIndex(open) < transactionIndex(candidate);
    }

    private static boolean isStrictlyBefore(NormalizedTransaction close, NormalizedTransaction candidate) {
        int cmp = close.getBlockTimestamp().compareTo(candidate.getBlockTimestamp());
        if (cmp < 0) {
            return true;
        }
        if (cmp > 0) {
            return false;
        }
        return transactionIndex(close) < transactionIndex(candidate);
    }

    private static java.util.Comparator<NormalizedTransaction> chronological() {
        return java.util.Comparator
                .comparing(NormalizedTransaction::getBlockTimestamp)
                .thenComparingInt(LendingLoopOpenClosePairLinkService::transactionIndex)
                .thenComparing(loop -> loop.getId() == null ? "" : loop.getId());
    }

    private static int transactionIndex(NormalizedTransaction transaction) {
        return transaction.getTransactionIndex() == null ? 0 : transaction.getTransactionIndex();
    }
}
