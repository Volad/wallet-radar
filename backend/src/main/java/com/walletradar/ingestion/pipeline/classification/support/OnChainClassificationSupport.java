package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OnChainClassificationSupport {

    private static final String ZKSYNC_NATIVE_TOKEN_CONTRACT = "0x000000000000000000000000000000000000800a";
    private static final Set<String> BRIDGE_ROUTE_START_SELECTORS = Set.of(
            "0x30c48952",
            "0xa6010a66",
            "0xa8f66666"
    );
    private static final Set<String> BRIDGE_ROUTE_START_FUNCTION_KEYS = Set.of(
            "swapandstartbridgetokensviamayan",
            "swapandstartbridgetokensviastargate",
            "swapandstartbridgetokensviasquid"
    );
    private static final Set<String> AUDITED_AAVE_ETH_RECEIPT_SYMBOLS = Set.of(
            "AETHWETH",
            "AARBWETH",
            "ALINWETH",
            "AMANWETH",
            "AZKSWETH"
    );

    private OnChainClassificationSupport() {
    }

    public static List<NormalizedTransaction.Flow> toFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        List<NormalizedTransaction.Flow> auditedAaveRebasingFlows = auditedAaveRebasingFlows(movementLegs, type);
        if (auditedAaveRebasingFlows != null) {
            return auditedAaveRebasingFlows;
        }
        List<NormalizedTransaction.Flow> routeFundedBridgeSourceFlows =
                routeFundedBridgeSourceFlows(view, movementLegs, type);
        if (routeFundedBridgeSourceFlows != null) {
            return routeFundedBridgeSourceFlows;
        }
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        if (type == NormalizedTransactionType.APPROVE) {
            return flows;
        }
        boolean feeOnlyType = type == NormalizedTransactionType.ADMIN_CONFIG;
        Set<String> liquidStakingFamilies = liquidStakingContinuityFamilies(movementLegs, type);
        for (RawLeg leg : movementLegs) {
            if (feeOnlyType && !leg.fee()) {
                continue;
            }
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            flow.setRole(resolveRole(type, leg, liquidStakingFamilies));
            flows.add(flow);
        }
        return flows;
    }

    public static List<NormalizedTransaction.Flow> toFlows(
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        return toFlows(null, movementLegs, type);
    }

    private static List<NormalizedTransaction.Flow> auditedAaveRebasingFlows(
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        if (movementLegs == null
                || movementLegs.isEmpty()
                || (type != NormalizedTransactionType.LENDING_DEPOSIT
                && type != NormalizedTransactionType.LENDING_WITHDRAW)) {
            return null;
        }
        List<RawLeg> effectiveLegs = movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() != 0)
                .toList();
        if (effectiveLegs.size() != 2) {
            return null;
        }

        RawLeg outbound = effectiveLegs.stream()
                .filter(leg -> leg.quantityDelta().signum() < 0)
                .findFirst()
                .orElse(null);
        RawLeg inbound = effectiveLegs.stream()
                .filter(leg -> leg.quantityDelta().signum() > 0)
                .findFirst()
                .orElse(null);
        if (outbound == null || inbound == null) {
            return null;
        }

        if (type == NormalizedTransactionType.LENDING_DEPOSIT) {
            if (!isAuditedAaveReceipt(outbound) && isAuditedAaveReceipt(inbound)
                    && sameAuditedAaveEthFamily(outbound, inbound)) {
                BigDecimal principalQuantity = outbound.quantityDelta().abs();
                BigDecimal receiptQuantity = inbound.quantityDelta().abs();
                BigDecimal excessQuantity = receiptQuantity.subtract(principalQuantity);
                if (excessQuantity.signum() <= 0) {
                    return null;
                }
                List<NormalizedTransaction.Flow> flows = new ArrayList<>();
                flows.add(buildFlow(
                        NormalizedLegRole.TRANSFER,
                        outbound.assetContract(),
                        outbound.assetSymbol(),
                        outbound.quantityDelta()
                ));
                flows.add(buildFlow(
                        NormalizedLegRole.TRANSFER,
                        inbound.assetContract(),
                        inbound.assetSymbol(),
                        principalQuantity
                ));
                flows.add(buildFlow(
                        NormalizedLegRole.BUY,
                        inbound.assetContract(),
                        inbound.assetSymbol(),
                        excessQuantity
                ));
                appendFeeFlows(flows, movementLegs);
                return flows;
            }
            return null;
        }

        if (isAuditedAaveReceipt(outbound) && !isAuditedAaveReceipt(inbound)
                && sameAuditedAaveEthFamily(outbound, inbound)) {
            BigDecimal receiptQuantity = outbound.quantityDelta().abs();
            BigDecimal principalQuantity = inbound.quantityDelta().abs();
            BigDecimal excessQuantity = principalQuantity.subtract(receiptQuantity);
            if (excessQuantity.signum() <= 0) {
                return null;
            }
            List<NormalizedTransaction.Flow> flows = new ArrayList<>();
            flows.add(buildFlow(
                    NormalizedLegRole.TRANSFER,
                    outbound.assetContract(),
                    outbound.assetSymbol(),
                    outbound.quantityDelta()
            ));
            flows.add(buildFlow(
                    NormalizedLegRole.TRANSFER,
                    inbound.assetContract(),
                    inbound.assetSymbol(),
                    receiptQuantity
            ));
            flows.add(buildFlow(
                    NormalizedLegRole.BUY,
                    inbound.assetContract(),
                    inbound.assetSymbol(),
                    excessQuantity
            ));
            appendFeeFlows(flows, movementLegs);
            return flows;
        }
        return null;
    }

    private static List<NormalizedTransaction.Flow> routeFundedBridgeSourceFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        if (type != NormalizedTransactionType.BRIDGE_OUT
                || view == null
                || movementLegs == null
                || movementLegs.isEmpty()
                || !isRouteFundedBridgeSource(view)) {
            return null;
        }
        RouteFundedBridgeSourceSelection selection = selectRouteFundedBridgeSourceLegs(view, movementLegs);
        if (selection == null) {
            return null;
        }
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(buildFlow(
                NormalizedLegRole.TRANSFER,
                selection.principalLeg().assetContract(),
                selection.principalLeg().assetSymbol(),
                selection.principalLeg().quantityDelta()
        ));
        flows.add(buildFlow(
                NormalizedLegRole.FEE,
                selection.routeFundingLeg().assetContract(),
                selection.routeFundingLeg().assetSymbol(),
                selection.routeFundingLeg().quantityDelta()
        ));
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private static boolean isRouteFundedBridgeSource(OnChainRawTransactionView view) {
        if (view == null || view.rawValue() == null || view.rawValue().signum() <= 0) {
            return false;
        }
        if (LiFiRouteSupport.hasRouteTag(view)) {
            return true;
        }
        if (BRIDGE_ROUTE_START_SELECTORS.contains(view.methodId())) {
            return true;
        }
        return BRIDGE_ROUTE_START_FUNCTION_KEYS.contains(functionKey(view.functionName()));
    }

    private static RouteFundedBridgeSourceSelection selectRouteFundedBridgeSourceLegs(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        List<RawLeg> effectiveLegs = movementLegs.stream()
                .filter(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() < 0)
                .toList();
        if (effectiveLegs.size() != 2) {
            return null;
        }
        BigDecimal rawValueQuantity = nativeValueQuantity(view.rawValue());
        if (rawValueQuantity == null || rawValueQuantity.signum() <= 0) {
            return null;
        }

        RawLeg routeFundingLeg = effectiveLegs.stream()
                .filter(leg -> leg.assetContract() == null || leg.assetContract().isBlank())
                .filter(leg -> leg.quantityDelta().abs().compareTo(rawValueQuantity) == 0)
                .findFirst()
                .orElse(null);
        if (routeFundingLeg == null) {
            return null;
        }

        RawLeg principalLeg = effectiveLegs.stream()
                .filter(leg -> leg != routeFundingLeg)
                .filter(leg -> leg.assetContract() != null && !leg.assetContract().isBlank())
                .findFirst()
                .orElse(null);
        if (principalLeg == null) {
            return null;
        }
        return new RouteFundedBridgeSourceSelection(principalLeg, routeFundingLeg);
    }

    private static BigDecimal nativeValueQuantity(BigInteger rawValue) {
        if (rawValue == null || rawValue.signum() <= 0) {
            return null;
        }
        return new BigDecimal(rawValue).movePointLeft(18);
    }

    public static NormalizedTransactionStatus initialStatus(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            ConfidenceLevel confidence
    ) {
        if (type == NormalizedTransactionType.UNKNOWN) {
            return NormalizedTransactionStatus.NEEDS_REVIEW;
        }
        if (type == NormalizedTransactionType.APPROVE
                || type == NormalizedTransactionType.ADMIN_CONFIG
                || type == NormalizedTransactionType.INTERNAL_TRANSFER
                || type == NormalizedTransactionType.SPONSORED_GAS_IN
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE
                || type == NormalizedTransactionType.WRAP
                || type == NormalizedTransactionType.UNWRAP) {
            return NormalizedTransactionStatus.CONFIRMED;
        }
        if (type == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST
                || type == NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION
                || type == NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL
                || type == NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE
                || type == NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE) {
            return ClarificationEligibilitySupport.requiresClarification(view, type)
                    ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                    : NormalizedTransactionStatus.PENDING_PRICE;
        }
        return ClarificationEligibilitySupport.requiresClarification(view, type)
                ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                : NormalizedTransactionStatus.PENDING_PRICE;
    }

    public static NormalizedTransactionStatus initialStatus(
            NormalizedTransactionType type,
            ConfidenceLevel confidence
    ) {
        return initialStatus(null, type, confidence);
    }

    public static boolean hasAaveDebtMarker(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return false;
        }
        return movementLegs.stream().anyMatch(OnChainClassificationSupport::isDebtMarkerLeg);
    }

    private static NormalizedLegRole resolveRole(
            NormalizedTransactionType type,
            RawLeg leg,
            Set<String> liquidStakingFamilies
    ) {
        if (leg.fee()) {
            return NormalizedLegRole.FEE;
        }
        if ((type == NormalizedTransactionType.STAKING_DEPOSIT || type == NormalizedTransactionType.STAKING_WITHDRAW)
                && isLiquidStakingContinuityLeg(leg, liquidStakingFamilies)) {
            return NormalizedLegRole.TRANSFER;
        }
        return switch (type) {
            case SWAP -> leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            case BORROW -> resolveBorrowRole(leg);
            case REWARD_CLAIM,
                    EXTERNAL_TRANSFER_IN,
                    DEX_ORDER_REQUEST,
                    DEX_ORDER_SETTLEMENT ->
                    leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            case STAKING_DEPOSIT,
                    STAKING_WITHDRAW -> leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            case REPAY -> resolveRepayRole(leg);
            case EXTERNAL_TRANSFER_OUT -> leg.quantityDelta().signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.BUY;
            case STAKING_WITHDRAW_REQUEST,
                    LP_ENTRY_REQUEST,
                    LP_ENTRY_SETTLEMENT,
                    LP_EXIT_REQUEST,
                    LP_EXIT_SETTLEMENT,
                    LP_ENTRY,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL,
                    LP_ADJUST,
                    LENDING_LOOP_OPEN,
                    LENDING_LOOP_REBALANCE,
                    LENDING_LOOP_DECREASE,
                    LENDING_LOOP_CLOSE,
                    DERIVATIVE_ORDER_REQUEST,
                    DERIVATIVE_ORDER_EXECUTION,
                    DERIVATIVE_ORDER_CANCEL,
                    DERIVATIVE_POSITION_INCREASE,
                    DERIVATIVE_POSITION_DECREASE -> NormalizedLegRole.TRANSFER;
            case LP_FEE_CLAIM -> leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.TRANSFER;
            case LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    LP_POSITION_STAKE,
                    LP_POSITION_UNSTAKE,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW,
                    BRIDGE_OUT,
                    BRIDGE_IN,
                    SPONSORED_GAS_IN,
                    PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    INTERNAL_TRANSFER,
                    ADMIN_CONFIG,
                    WRAP,
                    UNWRAP,
                    UNKNOWN,
                    APPROVE -> NormalizedLegRole.TRANSFER;
            default -> leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
        };
    }

    private static NormalizedLegRole resolveBorrowRole(RawLeg leg) {
        if (isDebtMarkerLeg(leg) || isZkSyncSettlementNativeLeg(leg)) {
            return NormalizedLegRole.TRANSFER;
        }
        return leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.TRANSFER;
    }

    private static NormalizedLegRole resolveRepayRole(RawLeg leg) {
        if (isDebtMarkerLeg(leg) || isZkSyncSettlementNativeLeg(leg)) {
            return NormalizedLegRole.TRANSFER;
        }
        return leg.quantityDelta().signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.TRANSFER;
    }

    private static boolean isDebtMarkerLeg(RawLeg leg) {
        if (leg == null || leg.assetSymbol() == null) {
            return false;
        }
        String normalized = leg.assetSymbol().trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("variabledebt") || normalized.startsWith("stabledebt");
    }

    private static boolean isZkSyncSettlementNativeLeg(RawLeg leg) {
        return leg != null
                && leg.assetContract() != null
                && ZKSYNC_NATIVE_TOKEN_CONTRACT.equalsIgnoreCase(leg.assetContract());
    }

    private static boolean isAuditedAaveReceipt(RawLeg leg) {
        if (leg == null || leg.assetSymbol() == null) {
            return false;
        }
        return AUDITED_AAVE_ETH_RECEIPT_SYMBOLS.contains(leg.assetSymbol().trim().toUpperCase(Locale.ROOT));
    }

    private static boolean sameAuditedAaveEthFamily(RawLeg left, RawLeg right) {
        String leftIdentity = AccountingAssetFamilySupport.continuityIdentity(left.assetSymbol(), left.assetContract());
        String rightIdentity = AccountingAssetFamilySupport.continuityIdentity(right.assetSymbol(), right.assetContract());
        return Objects.equals("FAMILY:ETH", leftIdentity) && Objects.equals(leftIdentity, rightIdentity);
    }

    private static void appendFeeFlows(
            List<NormalizedTransaction.Flow> flows,
            List<RawLeg> movementLegs
    ) {
        for (RawLeg leg : movementLegs) {
            if (leg == null || !leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            flows.add(buildFlow(
                    NormalizedLegRole.FEE,
                    leg.assetContract(),
                    leg.assetSymbol(),
                    leg.quantityDelta()
            ));
        }
    }

    private static NormalizedTransaction.Flow buildFlow(
            NormalizedLegRole role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(assetContract);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(quantityDelta);
        return flow;
    }

    private static boolean isLiquidStakingContinuityLeg(RawLeg leg, Set<String> liquidStakingFamilies) {
        if (leg == null || liquidStakingFamilies.isEmpty()) {
            return false;
        }
        String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(leg.assetSymbol(), leg.assetContract());
        return continuityIdentity != null && liquidStakingFamilies.contains(continuityIdentity);
    }

    private static Set<String> liquidStakingContinuityFamilies(
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        if (movementLegs == null
                || movementLegs.isEmpty()
                || (type != NormalizedTransactionType.STAKING_DEPOSIT && type != NormalizedTransactionType.STAKING_WITHDRAW)) {
            return Set.of();
        }
        Map<String, Boolean> hasInbound = new LinkedHashMap<>();
        Map<String, Boolean> hasOutbound = new LinkedHashMap<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(leg.assetSymbol(), leg.assetContract());
            if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
                continue;
            }
            if (leg.quantityDelta().signum() > 0) {
                hasInbound.put(continuityIdentity, true);
            } else {
                hasOutbound.put(continuityIdentity, true);
            }
        }
        Set<String> matchedFamilies = new LinkedHashSet<>();
        for (String continuityIdentity : hasInbound.keySet()) {
            if (Boolean.TRUE.equals(hasOutbound.get(continuityIdentity))) {
                matchedFamilies.add(continuityIdentity);
            }
        }
        return matchedFamilies;
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

    private record RouteFundedBridgeSourceSelection(
            RawLeg principalLeg,
            RawLeg routeFundingLeg
    ) {
    }
}
