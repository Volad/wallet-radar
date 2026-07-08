package com.walletradar.application.cex.normalization.venue.bybit;

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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapserSupport.*;

/**
 * Cycle/7 S1: Collapses Bybit mirror documents that describe the same economic event across
 * multiple ledger streams (FUNDING_HISTORY, INTERNAL_TRANSFER, TRANSACTION_LOG, EARN_FLEXIBLE_SAVING,
 * UNIVERSAL_TRANSFER).
 *
 * <p>Direct Mongo inspection on the user's universe found that one FUND&lt;-&gt;UTA transfer is recorded
 * up to four times — once per stream — with separate {@code _id}s and (because of cross-stream
 * timestamp drift) separate {@code bybit-econ-v1} correlation ids. All four are {@code
 * basisRelevant=true}, all four dispatch through {@code ReplayDispatcher}, and the net effect is
 * inflated quantity, destroyed basis carry, and a monotonically growing {@code quantityShortfall}.</p>
 *
 * <p>This service runs after {@link BybitInternalTransferPairer} and:
 * <ol>
 *   <li>Loads every Bybit {@code INTERNAL_TRANSFER} document.</li>
 *   <li>Groups documents by signature
 *       {@code (uid, accountingFamilyIdentity, abs(qty rounded to 10dp), bucketMinute, walletSubAccount, signDirection)}
 *       where {@code bucketMinute = floor(blockTimestamp / 60s)}.</li>
 *   <li>If a group contains more than one document (a "mirror set"), picks ONE canonical document
 *       per the source-file priority policy and demotes the rest to
 *       {@code excludedFromAccounting=true} with reason {@code BYBIT_STREAM_MIRROR_<sourceFile>}.</li>
 *   <li>Then unifies the canonical correlation id across paired senders / receivers that share the
 *       wider {@code (uid, family, |qty|, bucketMinute±drift, opposing-sub-accounts)} signature, so
 *       both sides land on the same {@code corr-family} replay queue.</li>
 * </ol>
 *
 * <p>Idempotency: re-running on already-collapsed groups is a no-op because mirror documents have
 * {@code excludedFromAccounting=true} and are skipped on subsequent passes.</p>
 *
 * <p>Stream priority policy (highest authority first):</p>
 * <table>
 *   <tr><th>Sub-account</th><th>Sign</th><th>Canonical source</th></tr>
 *   <tr><td>EARN</td><td>any</td><td>EARN_FLEXIBLE_SAVING</td></tr>
 *   <tr><td>UTA</td><td>any</td><td>INTERNAL_TRANSFER (selfTransfer) &gt; TRANSACTION_LOG</td></tr>
 *   <tr><td>FUND</td><td>any</td><td>INTERNAL_TRANSFER (selfTransfer / deposit_c2c) &gt; FUNDING_HISTORY</td></tr>
 * </table>
 *
 * <p>The collapser preserves all {@code EXTERNAL_TRANSFER_IN/OUT} rows (they are independently
 * authoritative under N17). It also leaves {@code SWAP}/{@code REWARD_CLAIM}/{@code BORROW}/{@code REPAY}
 * untouched.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitStreamAuthorityCollapser {

    public static final String COLLAPSED_CORR_PREFIX = "bybit-collapsed-v1:";
    /** Cycle/15 round 2: orphan cont=false leg with a collapsed-pair neighbor outside the bucket. */
    private static final String EXCLUSION_REASON_DRIFT = EXCLUSION_REASON_PREFIX + "DRIFT_GT_BUCKET";
    /**
     * Cycle/13: tighten from 6 minutes — real UTA↔FUND transfers can occur 2–3 minutes after a
     * deposit auto-route artifact with the same |qty|; a wide window falsely collapses them.
     */
    private static final Duration BUCKET_DRIFT_WINDOW = Duration.ofSeconds(30);
    /** Covers FH/TX_LOG mirrors lagging canonical IT pairs by up to ~2 days on prod. */
    private static final Duration ORPHAN_DRIFT_WINDOW = Duration.ofHours(48);

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final BybitStreamCorridorCycleCollapser corridorCycleCollapser;

    public int collapseMirrors() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> docs = mongoOperations
                .find(query.with(Sort.by(Sort.Direction.ASC, "_id")), NormalizedTransaction.class)
                .stream()
                .filter(doc -> !isCorridorLeg(doc))
                .toList();

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int mirrorRows = 0;
        int corrRewrites = 0;
        int residualMirrors = 0;
        int eventCountMirrors = 0;
        int driftMirrors = 0;

        if (docs.size() >= 2) {
            Map<String, List<NormalizedTransaction>> mirrorGroups = new LinkedHashMap<>();
            for (NormalizedTransaction tx : docs) {
                String signature = mirrorSignature(tx);
                if (signature == null) {
                    continue;
                }
                mirrorGroups.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(tx);
            }

            Set<String> collapsedIds = new HashSet<>();
            for (List<NormalizedTransaction> group : mirrorGroups.values()) {
                if (group.size() < 2) {
                    continue;
                }
                NormalizedTransaction canonical = pickCanonical(group);
                for (NormalizedTransaction tx : group) {
                    if (tx == canonical) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                        continue;
                    }
                    propagateCorrelationMetadata(tx, canonical, now, dirty);
                    String reason = EXCLUSION_REASON_PREFIX + sourceFileTag(tx);
                    tx.setExcludedFromAccounting(true);
                    tx.setAccountingExclusionReason(reason);
                    tx.setUpdatedAt(now);
                    dirty.add(tx);
                    collapsedIds.add(tx.getId());
                    mirrorRows++;
                }
            }

            corrRewrites = unifyOpposingCorrelations(docs, collapsedIds, now, dirty);
            residualMirrors = demoteResidualMirrors(docs, collapsedIds, now, dirty);
            eventCountMirrors = demoteEventCountMirrors(docs, collapsedIds, now, dirty);
            driftMirrors = collapseOrphanMirrorsAdjacentToCollapsedPairs(docs, collapsedIds, now, dirty);
        }

        int symmetryRestores = enforceCollapsedUtFundPairSymmetry(now, dirty);

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_STREAM_AUTHORITY_COLLAPSER candidates={} mirrors_demoted={} residual_mirrors={} event_count_mirrors={} drift_mirrors={} corr_rewrites={} symmetry_restores={}",
                    docs.size(), mirrorRows, residualMirrors, eventCountMirrors, driftMirrors, corrRewrites, symmetryRestores
            );
        }
        return dirty.size();
    }

    /**
     * Fix A.2 entry point — a terminal linking pass, run AFTER the deterministic corridor projection
     * ({@code BybitTransferContinuityRepairService}) has stamped the {@code BYBIT-CORRIDOR:} deposits.
     */
    public int suppressCorridorDepositStakeCycles() {
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int suppressed = corridorCycleCollapser.suppressCorridorDepositStakeCycleMirrors(now, dirty);
        int symmetryRestores = corridorCycleCollapser.enforceEarnCorridorExclusionSymmetry(now, dirty);
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_CORRIDOR_STAKE_CYCLE_SUPPRESSION suppressed_legs={} earn_symmetry_restores={}",
                    suppressed, symmetryRestores
            );
        }
        return suppressed + symmetryRestores;
    }

    private int enforceCollapsedUtFundPairSymmetry(
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("correlationId").regex("^" + java.util.regex.Pattern.quote(COLLAPSED_CORR_PREFIX))
        ));
        List<NormalizedTransaction> allDocs = mongoOperations.find(query, NormalizedTransaction.class);
        Map<String, List<NormalizedTransaction>> byCorr = new LinkedHashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            String corrId = tx.getCorrelationId();
            if (corrId == null || !corrId.startsWith(COLLAPSED_CORR_PREFIX)) {
                continue;
            }
            byCorr.computeIfAbsent(corrId, ignored -> new ArrayList<>()).add(tx);
        }
        int restored = 0;
        for (List<NormalizedTransaction> group : byCorr.values()) {
            NormalizedTransaction activeDebit = null;
            NormalizedTransaction activeCredit = null;
            for (NormalizedTransaction tx : group) {
                int sign = principalQuantitySign(tx);
                if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                    continue;
                }
                if (sign < 0) {
                    activeDebit = tx;
                } else if (sign > 0) {
                    activeCredit = tx;
                }
            }
            if (activeDebit != null && activeCredit == null) {
                restored += restoreCanonicalExcludedLeg(group, 1, activeDebit, now, dirtyAccumulator);
            }
            if (activeCredit != null && activeDebit == null) {
                restored += restoreCanonicalExcludedLeg(group, -1, activeCredit, now, dirtyAccumulator);
            }
        }
        return restored;
    }

    private int restoreCanonicalExcludedLeg(
            List<NormalizedTransaction> group,
            int sign,
            NormalizedTransaction opposingLeg,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        NormalizedTransaction canonical = null;
        for (NormalizedTransaction tx : group) {
            if (!Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            if (principalQuantitySign(tx) != sign) {
                continue;
            }
            if (canonical == null || comparePriorityThenId(tx, canonical) < 0) {
                canonical = tx;
            }
        }
        if (canonical == null) {
            return 0;
        }
        canonical.setExcludedFromAccounting(false);
        canonical.setAccountingExclusionReason(null);
        canonical.setContinuityCandidate(true);
        if (canonical.getMatchedCounterparty() == null || canonical.getMatchedCounterparty().isBlank()) {
            canonical.setMatchedCounterparty(opposingLeg.getWalletAddress());
        }
        canonical.setUpdatedAt(now);
        dirtyAccumulator.add(canonical);
        return 1;
    }

    private int collapseOrphanMirrorsAdjacentToCollapsedPairs(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Map<String, List<NormalizedTransaction>> bucketsByKey = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            String key = walletSignSignature(tx);
            if (key == null) {
                continue;
            }
            bucketsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int demoted = 0;
        for (List<NormalizedTransaction> bucket : bucketsByKey.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            for (NormalizedTransaction orphan : bucket) {
                if (!isDriftOrphanCandidate(orphan) || collapsedIds.contains(orphan.getId())) {
                    continue;
                }
                if ("EARN".equals(extractSubAccount(orphan.getWalletAddress()))) {
                    continue;
                }
                Instant orphanTs = orphan.getBlockTimestamp();
                if (orphanTs == null) {
                    continue;
                }
                boolean hasCollapsedNeighbor = false;
                for (NormalizedTransaction neighbor : bucket) {
                    if (neighbor == orphan || neighbor.getBlockTimestamp() == null) {
                        continue;
                    }
                    if (neighbor.getCorrelationId() == null
                            || !neighbor.getCorrelationId().startsWith(COLLAPSED_CORR_PREFIX)) {
                        continue;
                    }
                    Duration delta = Duration.between(orphanTs, neighbor.getBlockTimestamp()).abs();
                    if (delta.compareTo(ORPHAN_DRIFT_WINDOW) <= 0) {
                        hasCollapsedNeighbor = true;
                        break;
                    }
                }
                if (!hasCollapsedNeighbor) {
                    continue;
                }
                orphan.setExcludedFromAccounting(true);
                orphan.setAccountingExclusionReason(EXCLUSION_REASON_DRIFT);
                orphan.setUpdatedAt(now);
                collapsedIds.add(orphan.getId());
                dirtyAccumulator.add(orphan);
                demoted++;
            }
        }
        return demoted;
    }

    private int demoteEventCountMirrors(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Map<String, List<NormalizedTransaction>> buckets = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            if (collapsedIds.contains(tx.getId())) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            String key = walletSignSignature(tx);
            if (key == null) {
                continue;
            }
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int demoted = 0;
        for (List<NormalizedTransaction> bucket : buckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            Map<String, List<NormalizedTransaction>> bySource = new LinkedHashMap<>();
            for (NormalizedTransaction tx : bucket) {
                bySource.computeIfAbsent(sourceFileTag(tx), ignored -> new ArrayList<>()).add(tx);
            }
            if (bySource.size() < 2) {
                continue;
            }
            String subAccount = extractSubAccount(bucket.get(0).getWalletAddress());
            String canonicalSource = null;
            int canonicalPriority = Integer.MAX_VALUE;
            for (String source : bySource.keySet()) {
                int priority = canonicalPriority(source, subAccount);
                if (priority < canonicalPriority
                        || (priority == canonicalPriority && (canonicalSource == null || source.compareTo(canonicalSource) < 0))) {
                    canonicalPriority = priority;
                    canonicalSource = source;
                }
            }
            if (canonicalSource == null) {
                continue;
            }
            int canonicalCount = bySource.get(canonicalSource).size();
            for (Map.Entry<String, List<NormalizedTransaction>> entry : bySource.entrySet()) {
                String source = entry.getKey();
                if (source.equals(canonicalSource)) {
                    continue;
                }
                List<NormalizedTransaction> mirrors = entry.getValue();
                if (mirrors.size() > canonicalCount) {
                    continue;
                }
                for (NormalizedTransaction tx : mirrors) {
                    if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                        continue;
                    }
                    String reason = EXCLUSION_REASON_PREFIX + source;
                    tx.setExcludedFromAccounting(true);
                    tx.setAccountingExclusionReason(reason);
                    tx.setUpdatedAt(now);
                    collapsedIds.add(tx.getId());
                    dirtyAccumulator.add(tx);
                    demoted++;
                }
            }
        }
        return demoted;
    }

    private int demoteResidualMirrors(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Map<String, List<NormalizedTransaction>> broadGroups = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            String key = broadSignature(tx);
            if (key == null) {
                continue;
            }
            broadGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int residual = 0;
        for (List<NormalizedTransaction> bucket : broadGroups.values()) {
            if (bucket.size() < 3) {
                continue;
            }
            // RC-b determinism fix: broadSignature's grouping key has no subAccount component, so
            // 2+ same-sign bybit-collapsed-v1: candidates in one bucket can legitimately come from
            // DIFFERENT subAccounts (each with its own canonicalPriority scale). Comparing
            // canonicalPriority across subAccounts is not economically meaningful here, unlike at
            // pickCanonical/demoteEventCountMirrors where the grouping key already pins a single
            // subAccount. Use a local timestamp-then-_id comparator instead of comparePriorityThenId.
            Comparator<NormalizedTransaction> residualCanonicalOrder = Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak());
            NormalizedTransaction canonicalDebit = null;
            NormalizedTransaction canonicalCredit = null;
            for (NormalizedTransaction tx : bucket) {
                if (tx.getCorrelationId() == null || !tx.getCorrelationId().startsWith(COLLAPSED_CORR_PREFIX)) {
                    continue;
                }
                int sign = principalSign(tx);
                if (sign < 0 && (canonicalDebit == null || residualCanonicalOrder.compare(tx, canonicalDebit) < 0)) {
                    canonicalDebit = tx;
                } else if (sign > 0 && (canonicalCredit == null || residualCanonicalOrder.compare(tx, canonicalCredit) < 0)) {
                    canonicalCredit = tx;
                }
            }
            if (canonicalDebit == null || canonicalCredit == null) {
                continue;
            }
            Instant anchor = canonicalDebit.getBlockTimestamp() != null
                    ? canonicalDebit.getBlockTimestamp()
                    : canonicalCredit.getBlockTimestamp();
            if (anchor == null) {
                continue;
            }
            for (NormalizedTransaction tx : bucket) {
                if (tx == canonicalDebit || tx == canonicalCredit) {
                    continue;
                }
                if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                    continue;
                }
                if (tx.getBlockTimestamp() == null) {
                    continue;
                }
                Duration delta = Duration.between(anchor, tx.getBlockTimestamp()).abs();
                if (delta.compareTo(BUCKET_DRIFT_WINDOW) > 0) {
                    continue;
                }
                String reason = EXCLUSION_REASON_PREFIX + sourceFileTag(tx);
                tx.setExcludedFromAccounting(true);
                tx.setAccountingExclusionReason(reason);
                tx.setCorrelationId(canonicalDebit.getCorrelationId());
                tx.setUpdatedAt(now);
                collapsedIds.add(tx.getId());
                dirtyAccumulator.add(tx);
                residual++;
            }
        }
        return residual;
    }

    private int unifyOpposingCorrelations(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Map<String, List<NormalizedTransaction>> byBroadSignature = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            if (collapsedIds.contains(tx.getId())) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            String key = economicUnifySignature(tx);
            if (key == null) {
                continue;
            }
            byBroadSignature.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int rewrites = 0;
        Set<String> reKeyed = new HashSet<>();
        for (List<NormalizedTransaction> bucket : byBroadSignature.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            bucket.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak()));
            for (int i = 0; i < bucket.size(); i++) {
                NormalizedTransaction left = bucket.get(i);
                if (reKeyed.contains(left.getId())) {
                    continue;
                }
                int leftSign = principalSign(left);
                if (leftSign == 0) {
                    continue;
                }
                NormalizedTransaction bestRight = null;
                Duration bestDelta = BUCKET_DRIFT_WINDOW.plusSeconds(1);
                for (int j = i + 1; j < bucket.size(); j++) {
                    NormalizedTransaction right = bucket.get(j);
                    if (reKeyed.contains(right.getId())) {
                        continue;
                    }
                    int rightSign = principalSign(right);
                    if (rightSign == 0 || rightSign == leftSign) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (delta.compareTo(BUCKET_DRIFT_WINDOW) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestRight = right;
                    }
                }
                if (bestRight == null) {
                    continue;
                }
                String canonicalCorr = collapsedCorrelationId(left, bestRight);
                if (canonicalCorr.equals(left.getCorrelationId())
                        && canonicalCorr.equals(bestRight.getCorrelationId())) {
                    // Already correctly paired from a prior run: guard both against re-processing at a
                    // later index i. Without this, left would escape reKeyed and be paired again with a
                    // different same-signature document (e.g. a TRANSACTION_LOG leg of the same |qty|),
                    // producing a second, spurious bybit-collapsed-v1: corridor that steals the UTA leg
                    // away from its FUND partner and breaks basis conservation on every repeat run.
                    reKeyed.add(left.getId());
                    reKeyed.add(bestRight.getId());
                    continue;
                }
                left.setCorrelationId(canonicalCorr);
                bestRight.setCorrelationId(canonicalCorr);
                left.setContinuityCandidate(true);
                bestRight.setContinuityCandidate(true);
                if (left.getMatchedCounterparty() == null) {
                    left.setMatchedCounterparty(bestRight.getWalletAddress());
                }
                if (bestRight.getMatchedCounterparty() == null) {
                    bestRight.setMatchedCounterparty(left.getWalletAddress());
                }
                left.setUpdatedAt(now);
                bestRight.setUpdatedAt(now);
                reKeyed.add(left.getId());
                reKeyed.add(bestRight.getId());
                dirtyAccumulator.add(left);
                dirtyAccumulator.add(bestRight);
                rewrites += 2;
            }
        }
        return rewrites;
    }
}
