package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.ArrayList;
import java.util.List;

public final class OnChainClassificationSupport {

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
            case BORROW, REWARD_CLAIM, EXTERNAL_INBOUND, STAKING_DEPOSIT, STAKING_WITHDRAW ->
                    leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL;
            case REPAY, EXTERNAL_TRANSFER_OUT ->
                    leg.quantityDelta().signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.BUY;
            case LP_ENTRY -> leg.quantityDelta().signum() < 0 ? NormalizedLegRole.SELL : NormalizedLegRole.TRANSFER;
            case LP_EXIT -> leg.quantityDelta().signum() > 0 ? NormalizedLegRole.BUY : NormalizedLegRole.TRANSFER;
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
}
