package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;

/**
 * Detects Blockscout LP lifecycle rows that are economically known but may still miss
 * transaction-level native settlement transfers.
 *
 * Wallet-scoped Blockscout transfer pages can omit internal native payouts for router/position-manager
 * exits while tx-level clarification endpoints still expose them. These rows must go through
 * receipt clarification before they are considered final for movement continuity.
 */
public final class BlockScoutNativeSettlementClarificationSupport {

    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String MODIFY_LIQUIDITIES_SELECTOR = "0xdd46508f";
    private static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
    private static final String COLLECT_SELECTOR = "0xfc6f7865";
    private static final String BURN_SELECTOR = "0x00f714ce";

    private BlockScoutNativeSettlementClarificationSupport() {
    }

    public static boolean requiresReceiptClarification(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        if (view == null
                || movementLegs == null
                || nativeAssetSymbolResolver == null
                || (type != NormalizedTransactionType.LP_EXIT
                && type != NormalizedTransactionType.LP_FEE_CLAIM)) {
            return false;
        }
        if (view.syncMethod() != RawSyncMethod.BLOCKSCOUT
                || view.hasFullReceiptClarificationEvidence()
                || !view.explorerInternalTransfers().isEmpty()
                || view.explorerTokenTransfers().isEmpty()) {
            return false;
        }
        String walletAddress = view.walletAddress();
        String toAddress = view.toAddress();
        if (walletAddress == null || toAddress == null || walletAddress.equals(toAddress)) {
            return false;
        }
        if (!hasExitLikeSelector(view)) {
            return false;
        }
        return !hasInboundNativeSettlementLeg(movementLegs, view, nativeAssetSymbolResolver);
    }

    private static boolean hasExitLikeSelector(OnChainRawTransactionView view) {
        String methodId = view.methodId();
        if (DECREASE_LIQUIDITY_SELECTOR.equals(methodId)
                || COLLECT_SELECTOR.equals(methodId)
                || BURN_SELECTOR.equals(methodId)
                || MODIFY_LIQUIDITIES_SELECTOR.equals(methodId)) {
            return true;
        }
        if (!MULTICALL_SELECTOR.equals(methodId)) {
            return false;
        }
        String inputData = view.inputData();
        return CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, COLLECT_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, BURN_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, MODIFY_LIQUIDITIES_SELECTOR);
    }

    private static boolean hasInboundNativeSettlementLeg(
            List<RawLeg> movementLegs,
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
        String wrappedNativeSymbol = nativeAssetSymbolResolver.wrappedNativeSymbol(view.networkId());
        return movementLegs.stream()
                .filter(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0)
                .anyMatch(leg -> sameSymbol(leg.assetSymbol(), nativeSymbol)
                        || sameSymbol(leg.assetSymbol(), wrappedNativeSymbol));
    }

    private static boolean sameSymbol(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
