package com.walletradar.costbasis.support;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

/**
 * Shared mapping helpers for immutable asset-ledger timeline points.
 */
public final class AssetLedgerSupport {

    private AssetLedgerSupport() {
    }

    public static String accountingAssetIdentity(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return AccountingAssetIdentitySupport.positionAssetIdentity(transaction, flow);
    }

    public static String accountingWalletAddress(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return AccountingAssetIdentitySupport.replayPositionWalletAddress(transaction, flow);
    }

    public static String accountingFamilyIdentity(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (familyIdentity != null && !familyIdentity.isBlank()) {
            return familyIdentity;
        }
        String assetIdentity = accountingAssetIdentity(transaction, flow);
        if (assetIdentity != null && !assetIdentity.isBlank()) {
            return assetIdentity;
        }
        String assetSymbol = flow == null ? null : flow.getAssetSymbol();
        return assetSymbol == null || assetSymbol.isBlank() ? null : "SYMBOL:" + assetSymbol.trim().toUpperCase();
    }

    public static String familyDisplaySymbol(String accountingFamilyIdentity, String assetSymbol) {
        if (accountingFamilyIdentity != null && accountingFamilyIdentity.startsWith("FAMILY:")) {
            return accountingFamilyIdentity.substring("FAMILY:".length());
        }
        return assetSymbol == null ? null : assetSymbol.trim().toUpperCase();
    }

    public static AssetLedgerPoint.LifecycleKind lifecycleKind(NormalizedTransactionType type) {
        if (type == null) {
            return AssetLedgerPoint.LifecycleKind.UNKNOWN;
        }
        return switch (type) {
            case SWAP -> AssetLedgerPoint.LifecycleKind.SPOT;
            case EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT, SPONSORED_GAS_IN, INTERNAL_TRANSFER, APPROVE, ADMIN_CONFIG,
                    NFT_MINT -> AssetLedgerPoint.LifecycleKind.TRANSFER;
            case BRIDGE_IN, BRIDGE_OUT -> AssetLedgerPoint.LifecycleKind.BRIDGE;
            case PROTOCOL_CUSTODY_DEPOSIT, PROTOCOL_CUSTODY_WITHDRAW -> AssetLedgerPoint.LifecycleKind.CUSTODY;
            case LENDING_DEPOSIT, LENDING_WITHDRAW, BORROW, REPAY -> AssetLedgerPoint.LifecycleKind.LENDING;
            case STAKING_DEPOSIT, STAKING_WITHDRAW_REQUEST, STAKING_WITHDRAW -> AssetLedgerPoint.LifecycleKind.STAKING;
            case VAULT_DEPOSIT, VAULT_WITHDRAW -> AssetLedgerPoint.LifecycleKind.VAULT;
            case LP_ENTRY_REQUEST, LP_ENTRY_SETTLEMENT, LP_EXIT_REQUEST, LP_EXIT_SETTLEMENT, LP_ENTRY,
                    LP_EXIT, LP_EXIT_PARTIAL, LP_EXIT_FINAL, LP_ADJUST, LP_POSITION_STAKE,
                    LP_POSITION_UNSTAKE, LP_FEE_CLAIM -> AssetLedgerPoint.LifecycleKind.LP;
            case DEX_ORDER_REQUEST, DEX_ORDER_SETTLEMENT -> AssetLedgerPoint.LifecycleKind.ORDER;
            case LENDING_LOOP_OPEN, LENDING_LOOP_REBALANCE, LENDING_LOOP_DECREASE, LENDING_LOOP_CLOSE ->
                    AssetLedgerPoint.LifecycleKind.LOOP;
            case WRAP, UNWRAP -> AssetLedgerPoint.LifecycleKind.WRAP;
            case REWARD_CLAIM -> AssetLedgerPoint.LifecycleKind.REWARD;
            case FEE -> AssetLedgerPoint.LifecycleKind.UNKNOWN;
            case DERIVATIVE_ORDER_REQUEST, DERIVATIVE_ORDER_EXECUTION, DERIVATIVE_ORDER_CANCEL,
                    DERIVATIVE_POSITION_INCREASE, DERIVATIVE_POSITION_DECREASE -> AssetLedgerPoint.LifecycleKind.DERIVATIVE;
            case MANUAL_COMPENSATING -> AssetLedgerPoint.LifecycleKind.MANUAL;
            case UNKNOWN -> AssetLedgerPoint.LifecycleKind.UNKNOWN;
        };
    }

    public static AssetLedgerPoint.LifecycleStage lifecycleStage(NormalizedTransactionType type) {
        if (type == null) {
            return AssetLedgerPoint.LifecycleStage.SINGLE;
        }
        return switch (type) {
            case BRIDGE_OUT -> AssetLedgerPoint.LifecycleStage.SOURCE;
            case BRIDGE_IN -> AssetLedgerPoint.LifecycleStage.DESTINATION;
            case LP_ENTRY_REQUEST, LP_EXIT_REQUEST, STAKING_WITHDRAW_REQUEST, DEX_ORDER_REQUEST ->
                    AssetLedgerPoint.LifecycleStage.REQUEST;
            case LP_ENTRY_SETTLEMENT, LP_EXIT_SETTLEMENT, STAKING_WITHDRAW, DEX_ORDER_SETTLEMENT ->
                    AssetLedgerPoint.LifecycleStage.SETTLEMENT;
            default -> AssetLedgerPoint.LifecycleStage.SINGLE;
        };
    }
}
