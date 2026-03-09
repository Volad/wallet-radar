package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.session.SessionBridgeStatus;
import com.walletradar.domain.transaction.session.SessionTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies a conservative bridge lifecycle on top of session-scoped transfer rows.
 * Matching is intentionally narrow so repeated rebuilds stay deterministic and safe.
 */
@Component
public class SessionBridgeLifecycleResolver {

    private static final Duration MATCH_WINDOW = Duration.ofDays(3);
    private static final BigDecimal QUANTITY_RELATIVE_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal QUANTITY_ABSOLUTE_TOLERANCE = new BigDecimal("0.000001");
    private static final BigDecimal VALUE_RELATIVE_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal VALUE_ABSOLUTE_TOLERANCE = new BigDecimal("5.00");

    public void apply(List<SessionTransaction> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<BridgeCandidate> outbound = new ArrayList<>();
        List<BridgeCandidate> inbound = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            SessionTransaction row = rows.get(index);
            row.setBridgeStatus(null);
            row.setBridgePairKey(null);

            Optional<BridgeCandidate> candidateOpt = toCandidate(index, row);
            if (candidateOpt.isEmpty()) {
                continue;
            }

            BridgeCandidate candidate = candidateOpt.get();
            row.setBridgeStatus(candidate.status());
            if (candidate.status() == SessionBridgeStatus.BRIDGE_OUT) {
                outbound.add(candidate);
            } else {
                inbound.add(candidate);
            }
        }

        if (outbound.isEmpty() || inbound.isEmpty()) {
            return;
        }

        Map<Integer, Set<Integer>> outboundEdges = new HashMap<>();
        Map<Integer, Set<Integer>> inboundEdges = new HashMap<>();
        for (BridgeCandidate outboundCandidate : outbound) {
            outboundEdges.put(outboundCandidate.index(), new LinkedHashSet<>());
        }
        for (BridgeCandidate inboundCandidate : inbound) {
            inboundEdges.put(inboundCandidate.index(), new LinkedHashSet<>());
        }

        for (BridgeCandidate outboundCandidate : outbound) {
            for (BridgeCandidate inboundCandidate : inbound) {
                if (!isPotentialPair(outboundCandidate, inboundCandidate)) {
                    continue;
                }
                outboundEdges.get(outboundCandidate.index()).add(inboundCandidate.index());
                inboundEdges.get(inboundCandidate.index()).add(outboundCandidate.index());
            }
        }

        Set<Integer> matchedOutbound = new HashSet<>();
        Set<Integer> matchedInbound = new HashSet<>();
        boolean progress;
        do {
            progress = false;
            for (BridgeCandidate outboundCandidate : outbound) {
                if (matchedOutbound.contains(outboundCandidate.index())) {
                    continue;
                }
                Set<Integer> unresolvedInbound = unresolvedEdges(outboundEdges.get(outboundCandidate.index()), matchedInbound);
                if (unresolvedInbound.size() != 1) {
                    continue;
                }

                Integer inboundIndex = unresolvedInbound.iterator().next();
                Set<Integer> unresolvedOutbound = unresolvedEdges(inboundEdges.get(inboundIndex), matchedOutbound);
                if (unresolvedOutbound.size() != 1 || !unresolvedOutbound.contains(outboundCandidate.index())) {
                    continue;
                }

                SessionTransaction outboundRow = rows.get(outboundCandidate.index());
                SessionTransaction inboundRow = rows.get(inboundIndex);
                String bridgePairKey = buildPairKey(outboundRow, inboundRow);
                outboundRow.setBridgeStatus(SessionBridgeStatus.MATCHED);
                outboundRow.setBridgePairKey(bridgePairKey);
                inboundRow.setBridgeStatus(SessionBridgeStatus.MATCHED);
                inboundRow.setBridgePairKey(bridgePairKey);
                matchedOutbound.add(outboundCandidate.index());
                matchedInbound.add(inboundIndex);
                progress = true;
            }
        } while (progress);

        markAmbiguous(outbound, outboundEdges, matchedOutbound, matchedInbound, rows);
        markAmbiguous(inbound, inboundEdges, matchedInbound, matchedOutbound, rows);
    }

    private void markAmbiguous(
            List<BridgeCandidate> candidates,
            Map<Integer, Set<Integer>> adjacency,
            Set<Integer> matchedCandidates,
            Set<Integer> matchedOpposites,
            List<SessionTransaction> rows
    ) {
        for (BridgeCandidate candidate : candidates) {
            if (matchedCandidates.contains(candidate.index())) {
                continue;
            }
            Set<Integer> unresolved = unresolvedEdges(adjacency.get(candidate.index()), matchedOpposites);
            if (unresolved.isEmpty()) {
                continue;
            }
            rows.get(candidate.index()).setBridgeStatus(SessionBridgeStatus.REVIEW);
            rows.get(candidate.index()).setBridgePairKey(null);
        }
    }

    private Optional<BridgeCandidate> toCandidate(int index, SessionTransaction row) {
        if (row.getType() == null || row.getFlows() == null || row.getFlows().isEmpty() || row.getBlockTimestamp() == null) {
            return Optional.empty();
        }
        if (row.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return buildCandidate(index, row, SessionBridgeStatus.BRIDGE_OUT, true);
        }
        if (row.getType() == NormalizedTransactionType.EXTERNAL_INBOUND) {
            return buildCandidate(index, row, SessionBridgeStatus.BRIDGE_IN, false);
        }
        return Optional.empty();
    }

    private Optional<BridgeCandidate> buildCandidate(
            int index,
            SessionTransaction row,
            SessionBridgeStatus status,
            boolean outbound
    ) {
        List<SessionTransaction.Flow> materialFlows = row.getFlows().stream()
                .filter(flow -> flow.getQuantityDelta() != null)
                .filter(flow -> flow.getQuantityDelta().compareTo(BigDecimal.ZERO) != 0)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .toList();
        if (materialFlows.isEmpty()) {
            return Optional.empty();
        }

        List<SessionTransaction.Flow> positiveFlows = materialFlows.stream()
                .filter(flow -> flow.getQuantityDelta().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        List<SessionTransaction.Flow> negativeFlows = materialFlows.stream()
                .filter(flow -> flow.getQuantityDelta().compareTo(BigDecimal.ZERO) < 0)
                .toList();

        SessionTransaction.Flow primaryFlow;
        if (outbound) {
            if (!positiveFlows.isEmpty() || negativeFlows.size() != 1) {
                return Optional.empty();
            }
            primaryFlow = negativeFlows.getFirst();
        } else {
            if (!negativeFlows.isEmpty() || positiveFlows.size() != 1) {
                return Optional.empty();
            }
            primaryFlow = positiveFlows.getFirst();
        }

        String assetSymbol = normalizeText(primaryFlow.getAssetSymbol());
        if (assetSymbol == null || row.getNetworkId() == null) {
            return Optional.empty();
        }

        return Optional.of(new BridgeCandidate(
                index,
                row,
                status,
                assetSymbol,
                primaryFlow.getQuantityDelta().abs(),
                absoluteValue(primaryFlow.getValueUsd()),
                row.getBlockTimestamp()
        ));
    }

    private boolean isPotentialPair(BridgeCandidate outboundCandidate, BridgeCandidate inboundCandidate) {
        SessionTransaction outboundRow = outboundCandidate.row();
        SessionTransaction inboundRow = inboundCandidate.row();
        if (outboundRow.getNetworkId() == inboundRow.getNetworkId()) {
            return false;
        }
        if (!outboundCandidate.assetSymbol().equals(inboundCandidate.assetSymbol())) {
            return false;
        }
        if (!isQuantityCompatible(outboundCandidate.quantity(), inboundCandidate.quantity())) {
            return false;
        }
        if (!isValueCompatible(outboundCandidate.valueUsd(), inboundCandidate.valueUsd())) {
            return false;
        }
        return Duration.between(outboundCandidate.blockTimestamp(), inboundCandidate.blockTimestamp()).abs()
                .compareTo(MATCH_WINDOW) <= 0;
    }

    private boolean isQuantityCompatible(BigDecimal left, BigDecimal right) {
        BigDecimal diff = left.subtract(right).abs();
        BigDecimal max = left.max(right);
        BigDecimal allowedDiff = max.multiply(QUANTITY_RELATIVE_TOLERANCE).max(QUANTITY_ABSOLUTE_TOLERANCE);
        return diff.compareTo(allowedDiff) <= 0;
    }

    private boolean isValueCompatible(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return true;
        }
        BigDecimal diff = left.subtract(right).abs();
        BigDecimal max = left.max(right);
        BigDecimal allowedDiff = max.multiply(VALUE_RELATIVE_TOLERANCE).max(VALUE_ABSOLUTE_TOLERANCE);
        return diff.compareTo(allowedDiff) <= 0;
    }

    private static Set<Integer> unresolvedEdges(Set<Integer> edges, Set<Integer> matchedOpposites) {
        if (edges == null || edges.isEmpty()) {
            return Set.of();
        }
        return edges.stream()
                .filter(index -> !matchedOpposites.contains(index))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    private static BigDecimal absoluteValue(BigDecimal value) {
        return value != null ? value.abs() : null;
    }

    private static String buildPairKey(SessionTransaction outboundRow, SessionTransaction inboundRow) {
        return resolvePairSegment(outboundRow) + "::" + resolvePairSegment(inboundRow);
    }

    private static String resolvePairSegment(SessionTransaction row) {
        if (row.getSourceId() != null && !row.getSourceId().isBlank()) {
            return row.getSourceId();
        }
        if (row.getId() != null && !row.getId().isBlank()) {
            return row.getId();
        }
        return "unknown";
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private record BridgeCandidate(
            int index,
            SessionTransaction row,
            SessionBridgeStatus status,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            Instant blockTimestamp
    ) {
    }
}
