package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.linking.pipeline.clarification.CorridorCorrelationKeyFactory;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapserSupport.*;

/**
 * Corridor-deposit-and-stake cycle suppression and earn-principal exclusion symmetry,
 * extracted from {@link BybitStreamAuthorityCollapser}.
 */
@Component
@RequiredArgsConstructor
class BybitStreamCorridorCycleCollapser {

    private static final String EXCLUSION_REASON_CORRIDOR_STAKE_CYCLE =
            EXCLUSION_REASON_PREFIX + "CORRIDOR_STAKE_CYCLE";
    private static final Duration CORRIDOR_STAKE_CYCLE_LOOKBACK = Duration.ofHours(6);
    private static final Duration CORRIDOR_STAKE_CYCLE_MARGIN = Duration.ofMinutes(10);
    private static final BigDecimal CORRIDOR_STAKE_CYCLE_QTY_TOLERANCE = new BigDecimal("0.0001");

    private final MongoOperations mongoOperations;

    int suppressCorridorDepositStakeCycleMirrors(Instant now, List<NormalizedTransaction> dirty) {
        List<NormalizedTransaction> corridorIns = mongoOperations.find(Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("correlationId").regex("^" + java.util.regex.Pattern.quote("BYBIT-CORRIDOR")),
                Criteria.where("excludedFromAccounting").ne(true)
        )), NormalizedTransaction.class).stream().filter(this::isFundInboundCorridor).toList();
        if (corridorIns.isEmpty()) {
            return 0;
        }
        List<NormalizedTransaction> stakingOuts = mongoOperations.find(Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").in(
                        NormalizedTransactionType.STAKING_DEPOSIT,
                        NormalizedTransactionType.EARN_FLEXIBLE_SAVING,
                        NormalizedTransactionType.LENDING_DEPOSIT,
                        NormalizedTransactionType.VAULT_DEPOSIT),
                Criteria.where("excludedFromAccounting").ne(true)
        )), NormalizedTransaction.class).stream().filter(this::hasFundPrincipalOutbound).toList();
        if (stakingOuts.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> collapseGroups = new LinkedHashMap<>();
        for (NormalizedTransaction tx : mongoOperations.find(Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("correlationId").regex("^" + java.util.regex.Pattern.quote(
                        BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)),
                Criteria.where("excludedFromAccounting").ne(true)
        )), NormalizedTransaction.class)) {
            String corr = tx.getCorrelationId();
            if (corr != null && corr.startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)) {
                collapseGroups.computeIfAbsent(corr, ignored -> new ArrayList<>()).add(tx);
            }
        }
        if (collapseGroups.isEmpty()) {
            return 0;
        }

        int suppressed = 0;
        for (NormalizedTransaction staking : stakingOuts) {
            String uid = extractBybitUid(staking.getWalletAddress());
            String family = principalFamily(staking);
            BigDecimal stakedQty = fundPrincipalOutboundQty(staking);
            Instant stakedAt = staking.getBlockTimestamp();
            if (uid == null || family == null || stakedQty == null || stakedAt == null) {
                continue;
            }
            Instant lookbackStart = stakedAt.minus(CORRIDOR_STAKE_CYCLE_LOOKBACK);
            Instant windowStart = null;
            BigDecimal corridorSum = BigDecimal.ZERO;
            for (NormalizedTransaction corridorIn : corridorIns) {
                if (!uid.equals(extractBybitUid(corridorIn.getWalletAddress()))
                        || !family.equals(principalFamily(corridorIn))) {
                    continue;
                }
                Instant ts = corridorIn.getBlockTimestamp();
                if (ts == null || ts.isBefore(lookbackStart) || ts.isAfter(stakedAt.plus(CORRIDOR_STAKE_CYCLE_MARGIN))) {
                    continue;
                }
                BigDecimal qty = principalAbsQty(corridorIn);
                if (qty == null) {
                    continue;
                }
                corridorSum = corridorSum.add(qty);
                if (windowStart == null || ts.isBefore(windowStart)) {
                    windowStart = ts;
                }
            }
            if (windowStart == null || !quantitiesMatch(corridorSum, stakedQty)) {
                continue;
            }
            Instant cycleStart = windowStart.minus(CORRIDOR_STAKE_CYCLE_MARGIN);
            Instant cycleEnd = stakedAt.plus(CORRIDOR_STAKE_CYCLE_MARGIN);
            for (List<NormalizedTransaction> group : collapseGroups.values()) {
                if (!isFundUtaInternalTransferGroup(group, uid, family)) {
                    continue;
                }
                boolean withinCycle = group.stream().anyMatch(leg -> {
                    Instant ts = leg.getBlockTimestamp();
                    return ts != null && !ts.isBefore(cycleStart) && !ts.isAfter(cycleEnd);
                });
                if (!withinCycle) {
                    continue;
                }
                for (NormalizedTransaction leg : group) {
                    if (Boolean.TRUE.equals(leg.getExcludedFromAccounting())) {
                        continue;
                    }
                    leg.setExcludedFromAccounting(true);
                    leg.setAccountingExclusionReason(EXCLUSION_REASON_CORRIDOR_STAKE_CYCLE);
                    leg.setUpdatedAt(now);
                    dirty.add(leg);
                    suppressed++;
                }
            }
        }
        return suppressed;
    }

    int enforceEarnCorridorExclusionSymmetry(Instant now, List<NormalizedTransaction> dirty) {
        Criteria pairerEligible = Criteria.where("type").in(
                NormalizedTransactionType.EARN_FLEXIBLE_SAVING,
                NormalizedTransactionType.LENDING_DEPOSIT,
                NormalizedTransactionType.LENDING_WITHDRAW
        );
        List<NormalizedTransaction> booked = mongoOperations.find(Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                pairerEligible,
                Criteria.where("excludedFromAccounting").ne(true)
        )), NormalizedTransaction.class);
        if (booked.isEmpty()) {
            return 0;
        }
        Map<String, Set<Integer>> bookedSignsBySignature = new HashMap<>();
        for (NormalizedTransaction leg : booked) {
            String sig = earnCorridorSignature(leg);
            int sign = principalSign(leg);
            if (sig == null || sign == 0) {
                continue;
            }
            bookedSignsBySignature.computeIfAbsent(sig, ignored -> new HashSet<>()).add(sign);
        }

        List<NormalizedTransaction> excluded = mongoOperations.find(Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                pairerEligible,
                Criteria.where("excludedFromAccounting").is(true)
        )), NormalizedTransaction.class);
        int restored = 0;
        for (NormalizedTransaction leg : excluded) {
            String sig = earnCorridorSignature(leg);
            int sign = principalSign(leg);
            if (sig == null || sign == 0) {
                continue;
            }
            Set<Integer> bookedSigns = bookedSignsBySignature.get(sig);
            if (bookedSigns == null) {
                continue;
            }
            if (bookedSigns.contains(-sign) && !bookedSigns.contains(sign)) {
                leg.setExcludedFromAccounting(false);
                leg.setAccountingExclusionReason(null);
                leg.setContinuityCandidate(true);
                leg.setUpdatedAt(now);
                dirty.add(leg);
                restored++;
                bookedSigns.add(sign);
            }
        }
        return restored;
    }

    private boolean isFundInboundCorridor(NormalizedTransaction tx) {
        if (tx == null || tx.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String corr = tx.getCorrelationId();
        if (corr == null || !corr.startsWith(CorridorCorrelationKeyFactory.CORRIDOR_PREFIX)) {
            return false;
        }
        if (!"FUND".equals(extractSubAccount(tx.getWalletAddress()))) {
            return false;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        return principal != null
                && principal.getQuantityDelta() != null
                && principal.getQuantityDelta().signum() > 0;
    }

    private boolean hasFundPrincipalOutbound(NormalizedTransaction tx) {
        return isFundPrincipalDepositType(tx == null ? null : tx.getType())
                && fundPrincipalOutboundQty(tx) != null;
    }

    private static boolean isFundPrincipalDepositType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.STAKING_DEPOSIT
                || type == NormalizedTransactionType.EARN_FLEXIBLE_SAVING
                || type == NormalizedTransactionType.LENDING_DEPOSIT
                || type == NormalizedTransactionType.VAULT_DEPOSIT;
    }

    private BigDecimal fundPrincipalOutboundQty(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return null;
        }
        boolean walletFund = "FUND".equals(extractSubAccount(tx.getWalletAddress()));
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE || flow.getQuantityDelta() == null) {
                continue;
            }
            if (flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            boolean flowFund = "FUND".equals(extractSubAccount(flow.getAccountRef()));
            if (walletFund || flowFund) {
                return flow.getQuantityDelta().abs();
            }
        }
        return null;
    }

    private boolean isFundUtaInternalTransferGroup(
            List<NormalizedTransaction> group,
            String uid,
            String family
    ) {
        if (group == null || group.size() < 2) {
            return false;
        }
        boolean sawFund = false;
        boolean sawUta = false;
        for (NormalizedTransaction leg : group) {
            if (leg.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
                return false;
            }
            if (!uid.equals(extractBybitUid(leg.getWalletAddress())) || !family.equals(principalFamily(leg))) {
                return false;
            }
            String sub = extractSubAccount(leg.getWalletAddress());
            if ("FUND".equals(sub)) {
                sawFund = true;
            } else if ("UTA".equals(sub)) {
                sawUta = true;
            } else {
                return false;
            }
        }
        return sawFund && sawUta;
    }

    private static boolean quantitiesMatch(BigDecimal left, BigDecimal right) {
        if (left == null || right == null || left.signum() <= 0 || right.signum() <= 0) {
            return false;
        }
        BigDecimal diff = left.subtract(right).abs();
        BigDecimal tolerance = right.multiply(CORRIDOR_STAKE_CYCLE_QTY_TOLERANCE);
        return diff.compareTo(tolerance) <= 0;
    }
}
