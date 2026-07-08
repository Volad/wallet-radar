package com.walletradar.application.normalization.pipeline.classification.lp;

import com.walletradar.accounting.support.LpReceiptSymbolSupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Materializes NFT concentrated-liquidity receipt legs for Pancake/Uni/Velodrome/Aerodrome flows.
 */
public final class LpNftClFlowMaterializer {

    private LpNftClFlowMaterializer() {
    }

    public static List<NormalizedTransaction.Flow> enrich(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String correlationId,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        if (view == null || type == null || !supportsMaterialization(type)) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        if (correlationId == null) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        String receiptSymbol = receiptSymbol(correlationId);
        if (receiptSymbol == null) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        if (type == NormalizedTransactionType.LP_ENTRY) {
            return enrichEntry(view, movementLegs, type, receiptSymbol, baseFlows);
        }
        if (isPrincipalExitType(type)) {
            return enrichPrincipalExit(view, movementLegs, type, receiptSymbol, baseFlows);
        }
        return baseFlows == null ? List.of() : baseFlows;
    }

    public static boolean supportsMaterialization(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static List<NormalizedTransaction.Flow> enrichEntry(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        if (!LpPositionLifecycleSupport.hasPositionNftMintLog(view)) {
            return baseFlows == null ? List.of() : baseFlows;
        }
        List<NormalizedTransaction.Flow> flows = new ArrayList<>(baseFlows == null ? List.of() : baseFlows);
        if (!containsReceiptSymbol(flows, receiptSymbol)) {
            NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
            receipt.setAssetSymbol(receiptSymbol);
            receipt.setQuantityDelta(BigDecimal.ONE);
            receipt.setRole(NormalizedLegRole.TRANSFER);
            flows.add(receipt);
        }
        return mergePrincipalLegsFromRaw(view, movementLegs, type, flows);
    }

    private static List<NormalizedTransaction.Flow> enrichPrincipalExit(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String receiptSymbol,
            List<NormalizedTransaction.Flow> baseFlows
    ) {
        List<NormalizedTransaction.Flow> flows = mergePrincipalLegsFromRaw(
                view,
                movementLegs,
                type,
                new ArrayList<>(baseFlows == null ? List.of() : baseFlows)
        );
        if (!containsReceiptSymbol(flows, receiptSymbol)) {
            NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
            receipt.setAssetSymbol(receiptSymbol);
            receipt.setQuantityDelta(BigDecimal.ONE.negate());
            receipt.setRole(NormalizedLegRole.TRANSFER);
            flows.add(receipt);
        }
        return flows;
    }

    private static List<NormalizedTransaction.Flow> mergePrincipalLegsFromRaw(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows
    ) {
        List<NormalizedTransaction.Flow> rawPrincipalFlows =
                OnChainClassificationSupport.toFlows(view, movementLegs, type);
        Map<String, NormalizedTransaction.Flow> merged = new LinkedHashMap<>();
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null) {
                continue;
            }
            merged.put(flowKey(flow), flow);
        }
        for (NormalizedTransaction.Flow flow : rawPrincipalFlows) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getAssetSymbol() != null && flow.getAssetSymbol().startsWith("LP-RECEIPT:")) {
                continue;
            }
            merged.putIfAbsent(flowKey(flow), flow);
        }
        return new ArrayList<>(merged.values());
    }

    private static boolean containsReceiptSymbol(List<NormalizedTransaction.Flow> flows, String receiptSymbol) {
        if (flows == null || receiptSymbol == null) {
            return false;
        }
        return flows.stream()
                .anyMatch(flow -> flow != null && receiptSymbol.equalsIgnoreCase(flow.getAssetSymbol()));
    }

    private static String receiptSymbol(String correlationId) {
        return LpReceiptSymbolSupport.fromLpPositionCorrelation(correlationId);
    }

    private static boolean isPrincipalExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static String flowKey(NormalizedTransaction.Flow flow) {
        String contract = flow.getAssetContract() == null
                ? ""
                : flow.getAssetContract().trim().toLowerCase(Locale.ROOT);
        String symbol = flow.getAssetSymbol() == null
                ? ""
                : flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        String role = flow.getRole() == null ? "" : flow.getRole().name();
        String quantity = flow.getQuantityDelta() == null
                ? "0"
                : flow.getQuantityDelta().stripTrailingZeros().toPlainString();
        return contract + "|" + symbol + "|" + role + "|" + quantity;
    }
}
