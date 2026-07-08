package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared flow builder for runtime paths that must preserve legacy-compatible flow roles.
 */
public final class ParityFlowSupport {

    private static final String PANCAKE_SMARTCHEF_CONTRACT = "0x7816f1711828c52eb3ca5a2f075a0c06e0548bd6";
    private static final String PANCAKE_SMARTCHEF_DEPOSIT_SELECTOR = "0xb6b55f25";

    private ParityFlowSupport() {
    }

    public static List<NormalizedTransaction.Flow> flows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        List<RawLeg> effectiveLegs = movementLegs;
        if (type == NormalizedTransactionType.REWARD_CLAIM) {
            effectiveLegs = removeExactSelfCancelingPairs(movementLegs);
        }
        List<NormalizedTransaction.Flow> classicSmartChefFlows =
                classifyClassicSmartChefStakingFlows(view, effectiveLegs, type);
        if (classicSmartChefFlows != null) {
            return classicSmartChefFlows;
        }
        return OnChainClassificationSupport.toFlows(effectiveLegs, type);
    }

    private static List<NormalizedTransaction.Flow> classifyClassicSmartChefStakingFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        if (!isClassicSmartChefStaking(view, type)) {
            return null;
        }
        Set<String> principalQuantityKeys = principalQuantityKeysFromCalldataAmount(view);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (RawLeg leg : movementLegs) {
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            flow.setRole(resolveClassicSmartChefRole(leg, principalQuantityKeys));
            flows.add(flow);
        }
        return flows;
    }

    private static boolean isClassicSmartChefStaking(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null || view.toAddress() == null) {
            return false;
        }
        if (!PANCAKE_SMARTCHEF_CONTRACT.equalsIgnoreCase(view.toAddress())) {
            return false;
        }
        String functionKey = functionKey(view.functionName());
        return switch (type) {
            case STAKING_DEPOSIT -> PANCAKE_SMARTCHEF_DEPOSIT_SELECTOR.equals(view.methodId())
                    || "deposit".equals(functionKey);
            case STAKING_WITHDRAW -> "withdraw".equals(functionKey)
                    || "emergencywithdraw".equals(functionKey);
            default -> false;
        };
    }

    private static Set<String> principalQuantityKeysFromCalldataAmount(OnChainRawTransactionView view) {
        BigInteger rawAmount = CalldataDecodingSupport.decodeUint256Argument(view.inputData(), 0);
        if (rawAmount == null || rawAmount.signum() <= 0) {
            return Set.of();
        }
        Set<String> quantityKeys = new LinkedHashSet<>();
        String walletAddress = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        for (Document transfer : view.explorerTokenTransfers()) {
            String from = OnChainRawTransactionView.normalizeAddress(view.tokenTransferFrom(transfer));
            String to = OnChainRawTransactionView.normalizeAddress(view.tokenTransferTo(transfer));
            if (!walletAddress.equals(from) && !walletAddress.equals(to)) {
                continue;
            }
            Object rawValue = transfer.get("value");
            if (rawValue == null || !rawAmount.equals(parseBigInteger(rawValue))) {
                continue;
            }
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            quantityKeys.add(quantity.abs().stripTrailingZeros().toPlainString());
        }
        return quantityKeys;
    }

    private static NormalizedLegRole resolveClassicSmartChefRole(
            RawLeg leg,
            Set<String> principalQuantityKeys
    ) {
        if (leg.fee()) {
            return NormalizedLegRole.FEE;
        }
        if (leg.quantityDelta().signum() < 0) {
            return NormalizedLegRole.TRANSFER;
        }
        String quantityKey = leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
        return principalQuantityKeys.contains(quantityKey)
                ? NormalizedLegRole.TRANSFER
                : NormalizedLegRole.BUY;
    }

    private static BigInteger parseBigInteger(Object value) {
        try {
            return new BigInteger(value.toString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static List<RawLeg> removeExactSelfCancelingPairs(List<RawLeg> movementLegs) {
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

    private static String legIdentity(RawLeg leg) {
        if (leg.assetContract() != null && !leg.assetContract().isBlank()) {
            return leg.assetContract().toLowerCase(Locale.ROOT);
        }
        return "symbol:" + (leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT));
    }

    private static String functionKey(String functionName) {
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
}
