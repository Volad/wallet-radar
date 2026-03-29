package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OnChainClassificationSupport {

    private static final String ZKSYNC_NATIVE_TOKEN_CONTRACT = "0x000000000000000000000000000000000000800a";

    private OnChainClassificationSupport() {
    }

    public static List<NormalizedTransaction.Flow> toFlows(
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        if (type == NormalizedTransactionType.APPROVE) {
            return flows;
        }
        boolean feeOnlyType = type == NormalizedTransactionType.ADMIN_CONFIG;
        for (RawLeg leg : movementLegs) {
            if (feeOnlyType && !leg.fee()) {
                continue;
            }
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            flow.setRole(resolveRole(type, leg));
            flows.add(flow);
        }
        return flows;
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

    private static NormalizedLegRole resolveRole(NormalizedTransactionType type, RawLeg leg) {
        if (leg.fee()) {
            return NormalizedLegRole.FEE;
        }
        return switch (type) {
            case SWAP -> leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            case BORROW -> resolveBorrowRole(leg);
            case REWARD_CLAIM,
                    EXTERNAL_TRANSFER_IN,
                    STAKING_DEPOSIT,
                    STAKING_WITHDRAW,
                    DEX_ORDER_REQUEST,
                    DEX_ORDER_SETTLEMENT ->
                    leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
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
}
