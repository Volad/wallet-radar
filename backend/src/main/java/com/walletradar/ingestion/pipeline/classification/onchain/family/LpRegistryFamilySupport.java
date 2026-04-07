package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

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
