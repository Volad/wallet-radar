package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class LpRegistryFamilySupport {

    private LpRegistryFamilySupport() {
    }

    static NormalizedTransactionType resolvePositionManagerType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryService protocolRegistryService
    ) {
        Optional<ProtocolRegistryEntry> decodedFromEntry = protocolRegistryService.lookup(
                view.networkId(),
                LpPositionLifecycleSupport.decodeSafeTransferFromAddress(view)
        );
        Optional<ProtocolRegistryEntry> decodedToEntry = protocolRegistryService.lookup(
                view.networkId(),
                LpPositionLifecycleSupport.decodeSafeTransferToAddress(view)
        );
        return LpPositionLifecycleSupport.resolvePositionManagerType(
                view,
                movementLegs,
                decodedFromEntry,
                decodedToEntry
        );
    }

    /**
     * Fallback type resolution for vault-style LP POSITION_MANAGER contracts that use
     * non-standard method selectors (no known Uniswap V3 mint/burn/collect/modify selectors).
     *
     * <p>Classification rules (applied to non-fee, non-dust legs):
     * <ol>
     *   <li>If only outbound legs → {@code LP_ENTRY}</li>
     *   <li>If only inbound legs → {@code LP_EXIT}</li>
     *   <li>If mixed legs: use the sign of the <em>dominant</em> leg (largest absolute quantity
     *       among legs of the same asset symbol). Vault-style LP entries send a large principal
     *       outbound (e.g. ETH) and may receive tiny incidental inflow tokens (harvested yield)
     *       of a completely different asset type. The dominant-leg heuristic reliably separates
     *       these from true LP exits (where the wallet receives its principal back).</li>
     *   <li>Tied magnitude → {@code null} (cannot determine)</li>
     * </ol>
     */
    private static final java.math.BigDecimal MIN_LEG_QTY = new java.math.BigDecimal("0.000000001"); // 1e-9

    static NormalizedTransactionType resolveByMovementLegsOnly(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return null;
        }
        java.math.BigDecimal maxOutbound = java.math.BigDecimal.ZERO;
        java.math.BigDecimal maxInbound = java.math.BigDecimal.ZERO;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            java.math.BigDecimal abs = leg.quantityDelta().abs();
            if (abs.compareTo(MIN_LEG_QTY) < 0) {
                continue; // sub-threshold dust — ignore
            }
            if (leg.quantityDelta().signum() < 0) {
                if (abs.compareTo(maxOutbound) > 0) {
                    maxOutbound = abs;
                }
            } else {
                if (abs.compareTo(maxInbound) > 0) {
                    maxInbound = abs;
                }
            }
        }
        if (maxOutbound.signum() == 0 && maxInbound.signum() == 0) {
            return null; // no significant movement
        }
        int cmp = maxOutbound.compareTo(maxInbound);
        if (cmp > 0) {
            return NormalizedTransactionType.LP_ENTRY;  // dominant outbound
        }
        if (cmp < 0) {
            return NormalizedTransactionType.LP_EXIT;   // dominant inbound
        }
        return null; // tied — cannot determine
    }

    static List<RawLeg> removeExactSelfCancelingPairs(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return List.of();
        }
        Map<String, List<Integer>> positiveIndices = new LinkedHashMap<>();
        Map<String, List<Integer>> negativeIndices = new LinkedHashMap<>();
        for (int index = 0; index < movementLegs.size(); index++) {
            RawLeg leg = movementLegs.get(index);
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            String key = legIdentity(leg) + ":" + leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
            if (leg.quantityDelta().signum() > 0) {
                positiveIndices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
            } else {
                negativeIndices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
            }
        }

        Set<Integer> removed = new LinkedHashSet<>();
        for (Map.Entry<String, List<Integer>> entry : positiveIndices.entrySet()) {
            List<Integer> negatives = negativeIndices.get(entry.getKey());
            if (negatives == null || negatives.isEmpty()) {
                continue;
            }
            int pairCount = Math.min(entry.getValue().size(), negatives.size());
            for (int i = 0; i < pairCount; i++) {
                removed.add(entry.getValue().get(i));
                removed.add(negatives.get(i));
            }
        }

        List<RawLeg> filtered = new ArrayList<>();
        for (int index = 0; index < movementLegs.size(); index++) {
            if (!removed.contains(index)) {
                filtered.add(movementLegs.get(index));
            }
        }
        return filtered;
    }

    static String functionKey(String functionName) {
        if (functionName == null) {
            return "";
        }
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int signatureSeparator = normalized.indexOf('(');
        if (signatureSeparator > 0) {
            return normalized.substring(0, signatureSeparator);
        }
        return normalized;
    }

    private static String legIdentity(RawLeg leg) {
        if (leg.assetContract() != null && !leg.assetContract().isBlank()) {
            return leg.assetContract().toLowerCase(Locale.ROOT);
        }
        return "symbol:" + (leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT));
    }
}
