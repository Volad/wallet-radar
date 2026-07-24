package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapserSupport.idTiebreak;

/**
 * Cycle/7 S2: Fuses orphan Bybit liquid-staking conversion legs (e.g., ETH-debit + METH-credit)
 * into a single {@link NormalizedTransactionType#STAKING_DEPOSIT}/{@link
 * NormalizedTransactionType#STAKING_WITHDRAW} document with two TRANSFER flows.
 *
 * <p>Symptom this addresses (direct Mongo inspection): a single-leg
 * {@code STAKING_DEPOSIT REALLOCATE_IN sym=METH dQty=0.6687 dBasis=$1483} appears without the
 * corresponding {@code REALLOCATE_OUT sym=ETH} leg because the two Bybit ledger rows arrive in
 * different streams/minutes and {@code BybitTradePairer.findLiquidStakingCounterLeg} (extraction
 * time pairing) misses them. Replay then materialises phantom METH coverage at zero basis.</p>
 *
 * <p>Once fused, the resulting two-flow {@code STAKING_DEPOSIT} is processed by
 * {@code LiquidStakingReplayHandler.selectPrincipalFlows}, which detects the family-equivalent
 * outbound + inbound pair (FAMILY:ETH on both sides via {@link AccountingAssetFamilySupport}) and
 * carries the cost basis from the debit asset to the credit asset.</p>
 *
 * <p>Pairing policy:
 * <ol>
 *   <li>Load all unexcluded {@code STAKING_DEPOSIT} / {@code STAKING_WITHDRAW} / {@code
 *       NEEDS_REVIEW (BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND)} Bybit docs.</li>
 *   <li>Compute the principal flow per doc; skip docs without a non-zero principal flow.</li>
 *   <li>Group by {@code (uid, family identity, walletAddress)} and inside each group pair opposite
 *       signs whose timestamps differ by &le; 5 minutes and whose asset symbols differ.</li>
 *   <li>For each pair: keep the negative-sign (debit) doc as canonical {@code STAKING_DEPOSIT};
 *       copy the positive-sign sibling's principal flow onto it as a second {@code TRANSFER}; set
 *       the canonical doc's {@code type} to {@code STAKING_DEPOSIT}, status to
 *       {@code CONFIRMED}, {@code correlationId} to {@code bybit-staking-conv-v1:<sha>}.</li>
 *   <li>Mark the sibling doc {@code excludedFromAccounting=true} reason
 *       {@code BYBIT_STAKING_PAIRED_SIBLING}.</li>
 * </ol>
 *
 * <p>Idempotency: a doc already excluded with reason {@code BYBIT_STAKING_PAIRED_SIBLING} or with
 * &gt;1 flow on a {@code STAKING_DEPOSIT} is skipped on subsequent passes.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitStakingConversionPairer {

    private static final String CORR_PREFIX = "bybit-staking-conv-v1:";
    private static final String EXCLUSION_REASON = "BYBIT_STAKING_PAIRED_SIBLING";
    private static final Duration PAIR_WINDOW = Duration.ofMinutes(5);

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int pairConversions() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("excludedFromAccounting").ne(true),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.STAKING_DEPOSIT),
                        Criteria.where("type").is(NormalizedTransactionType.STAKING_WITHDRAW),
                        new Criteria().andOperator(
                                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                                Criteria.where("missingDataReasons").is("BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND")
                        )
                )
        ));
        List<NormalizedTransaction> docs = mongoOperations.find(query, NormalizedTransaction.class);
        if (docs.size() < 2) {
            return 0;
        }

        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (NormalizedTransaction tx : docs) {
            Candidate candidate = toCandidate(tx);
            if (candidate == null) {
                continue;
            }
            // Already fused (2+ principal flows of opposite signs on same doc).
            if (hasFusedBothLegs(tx)) {
                continue;
            }
            String key = candidate.groupKey();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        Set<String> consumedIds = new HashSet<>();
        int paired = 0;
        for (List<Candidate> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator
                    .comparing((Candidate c) -> c.timestamp)
                    .thenComparing(Candidate::tx, idTiebreak()));
            for (int i = 0; i < group.size(); i++) {
                Candidate left = group.get(i);
                if (consumedIds.contains(left.tx.getId()) || left.signum >= 0) {
                    continue;
                }
                Candidate bestRight = null;
                Duration bestDelta = PAIR_WINDOW.plusSeconds(1);
                for (int j = 0; j < group.size(); j++) {
                    if (j == i) {
                        continue;
                    }
                    Candidate right = group.get(j);
                    if (consumedIds.contains(right.tx.getId())) {
                        continue;
                    }
                    if (right.signum <= 0) {
                        continue;
                    }
                    if (sameAssetIdentity(left, right)) {
                        // Two debit/credit on same asset symbol are not a staking conversion.
                        continue;
                    }
                    Duration delta = Duration.between(left.timestamp, right.timestamp).abs();
                    if (delta.compareTo(PAIR_WINDOW) > 0) {
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
                fusePair(left, bestRight, now, dirty);
                consumedIds.add(left.tx.getId());
                consumedIds.add(bestRight.tx.getId());
                paired++;
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_STAKING_CONVERSION_PAIRER candidates={} pairs={} dirty_docs={}",
                    docs.size(), paired, dirty.size()
            );
        }
        return paired;
    }

    private void fusePair(Candidate debit, Candidate credit, Instant now, List<NormalizedTransaction> dirty) {
        // Canonical = debit-leg doc.
        NormalizedTransaction canonical = debit.tx;
        canonical.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        canonical.setMissingDataReasons(new ArrayList<>());
        canonical.setExcludedFromAccounting(false);
        canonical.setAccountingExclusionReason(null);

        // Replace canonical flows with two TRANSFER flows.
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(transferFlow(debit.principal));
        flows.add(transferFlow(credit.principal));
        canonical.setFlows(flows);

        // D1 (ADR-054 §9): when the fused legs are a cross-canonical staking identity change (e.g.
        // ETH → mETH), both TRANSFER legs must be priced before replay, so route to PENDING_PRICE and
        // stamp the venue-neutral flag instead of confirming with an unpriced acquisition leg. A
        // same-family fusion (e.g. mETH → cmETH) still confirms directly and carries basis.
        if (AccountingAssetClassificationSupport.isCrossCanonicalStakingVaultConversion(canonical)) {
            canonical.setCrossCanonicalStakingConversion(Boolean.TRUE);
            canonical.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            canonical.setConfirmedAt(null);
        } else {
            canonical.setStatus(NormalizedTransactionStatus.CONFIRMED);
            if (canonical.getConfirmedAt() == null) {
                canonical.setConfirmedAt(now);
            }
        }

        String corrId = CORR_PREFIX + sha256Hex(canonical.getId() + "|" + credit.tx.getId());
        canonical.setCorrelationId(corrId);
        canonical.setContinuityCandidate(true);
        canonical.setUpdatedAt(now);

        // Exclude sibling from accounting; mark with shared corrId for traceability.
        NormalizedTransaction sibling = credit.tx;
        sibling.setExcludedFromAccounting(true);
        sibling.setAccountingExclusionReason(EXCLUSION_REASON);
        sibling.setCorrelationId(corrId);
        sibling.setUpdatedAt(now);

        dirty.add(canonical);
        dirty.add(sibling);
    }

    private NormalizedTransaction.Flow transferFlow(NormalizedTransaction.Flow source) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetContract(source.getAssetContract());
        flow.setAssetSymbol(source.getAssetSymbol());
        flow.setQuantityDelta(source.getQuantityDelta());
        flow.setUnitPriceUsd(null);
        flow.setValueUsd(null);
        flow.setPriceSource(null);
        flow.setAvcoAtTimeOfSale(null);
        flow.setRealisedPnlUsd(null);
        flow.setLogIndex(source.getLogIndex());
        flow.setAccountRef(source.getAccountRef());
        flow.setCounterpartyAddress(source.getCounterpartyAddress());
        flow.setCounterpartyType(source.getCounterpartyType());
        return flow;
    }

    private Candidate toCandidate(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null || tx.getBlockTimestamp() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        int sign = principal.getQuantityDelta().signum();
        if (sign == 0) {
            return null;
        }
        String cluster = AccountingAssetClassificationSupport.normalizationClusterForSymbol(principal.getAssetSymbol());
        String family = cluster != null
                ? cluster
                : AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            return null;
        }
        return new Candidate(tx, uid, tx.getWalletAddress(), family, principal, sign, tx.getBlockTimestamp());
    }

    private boolean hasFusedBothLegs(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return false;
        }
        int positives = 0;
        int negatives = 0;
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            BigDecimal qty = flow.getQuantityDelta();
            if (qty == null) {
                continue;
            }
            int sign = qty.signum();
            if (sign > 0) {
                positives++;
            } else if (sign < 0) {
                negatives++;
            }
        }
        return positives >= 1 && negatives >= 1;
    }

    private static boolean sameAssetIdentity(Candidate left, Candidate right) {
        String leftSym = symbolKey(left.principal);
        String rightSym = symbolKey(right.principal);
        return leftSym != null && leftSym.equals(rightSym);
    }

    private static String symbolKey(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        String sym = flow.getAssetSymbol();
        return sym == null ? null : sym.trim().toUpperCase(Locale.ROOT);
    }

    private static NormalizedTransaction.Flow principalFlow(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return null;
        }
        NormalizedTransaction.Flow best = null;
        BigDecimal bestAbs = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            BigDecimal qty = flow.getQuantityDelta();
            if (qty == null || qty.signum() == 0) {
                continue;
            }
            BigDecimal abs = qty.abs();
            if (abs.compareTo(bestAbs) > 0) {
                bestAbs = abs;
                best = flow;
            }
        }
        return best;
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Candidate(
            NormalizedTransaction tx,
            String uid,
            String walletAddress,
            String family,
            NormalizedTransaction.Flow principal,
            int signum,
            Instant timestamp
    ) {
        String groupKey() {
            return uid + "|" + walletAddress + "|" + family;
        }
    }
}
