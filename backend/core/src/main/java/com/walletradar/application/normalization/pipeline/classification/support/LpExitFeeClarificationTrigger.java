package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.EnumSet;
import java.util.Set;

/**
 * Clarification trigger for CL LP exits (V3/Slipstream and V4/Infinity) that have not yet had
 * their full receipt fetched, signalling that the full-receipt path must run so that relevant
 * event logs ({@code DecreaseLiquidity}/{@code Collect} for V3, {@code ModifyLiquidity} for V4)
 * are persisted before the fee/principal split is computed.
 *
 * <p>Fires only when ALL of the following hold:</p>
 * <ul>
 *   <li>Transaction type is a principal-exit family ({@code LP_EXIT*}).</li>
 *   <li>Calldata contains the V3 {@code decreaseLiquidity} selector ({@code 0x0c49ccbe}) OR
 *       the V4/Infinity {@code decreaseLiquidity} selector ({@code 0xdd46508f}).</li>
 *   <li>Full-receipt clarification evidence is not yet present (idempotent guard).</li>
 *   <li>No split evidence is already available in the currently persisted logs.</li>
 * </ul>
 *
 * <p>This reuses the existing full-receipt clarification path. After the receipt is fetched,
 * {@link LpExitFeeDecomposer} (V3) or {@link LpV4ExitFeeDecomposer} (V4) decodes the amounts
 * and {@link LpNftClFlowMaterializer} splits the flow legs into principal + {@code LP_FEE_INCOME}.</p>
 */
public final class LpExitFeeClarificationTrigger {

    private static final Set<NormalizedTransactionType> TARGET_TYPES = EnumSet.of(
            NormalizedTransactionType.LP_EXIT,
            NormalizedTransactionType.LP_EXIT_PARTIAL,
            NormalizedTransactionType.LP_EXIT_FINAL
    );

    /** V3 / Slipstream {@code decreaseLiquidity(uint256,uint128,uint128,uint128,uint256)} */
    private static final String DECREASE_LIQUIDITY_V3_SELECTOR = "0x0c49ccbe";
    /** V4 / Pancake Infinity {@code decreaseLiquidity(uint256,uint128,uint128,uint128,bytes)} */
    static final String DECREASE_LIQUIDITY_V4_SELECTOR = "0xdd46508f";

    private LpExitFeeClarificationTrigger() {
    }

    /**
     * Returns {@code true} when the transaction requires a full-receipt fetch to enable the LP
     * exit fee split, and the fetch has not yet been performed.
     *
     * @param view raw transaction view
     * @param type classified transaction type (after lifecycle refinement)
     * @return {@code true} if {@code PENDING_CLARIFICATION} should be set for full-receipt fetch
     */
    public static boolean requiresReceiptClarification(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null) {
            return false;
        }
        if (!TARGET_TYPES.contains(type)) {
            return false;
        }
        if (view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        if (!hasDecreaseLiquidityCalldata(view)) {
            return false;
        }
        // V3/Slipstream: evidence = DecreaseLiquidity + Collect events already in persisted logs
        if (LpExitFeeDecomposer.hasFeeSplitEvidence(view)) {
            return false;
        }
        // V4/Infinity: evidence = ModifyLiquidity event already in persisted logs
        return !LpV4ExitFeeDecomposer.hasModifyLiquidityEvidence(view);
    }

    /** Returns true if the calldata identifies this as a V4/Infinity decreaseLiquidity call. */
    public static boolean isV4DecreaseLiquidity(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        String methodId = view.methodId();
        if (DECREASE_LIQUIDITY_V4_SELECTOR.equals(methodId)) {
            return true;
        }
        String inputData = view.inputData();
        return inputData != null
                && CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_V4_SELECTOR);
    }

    private static boolean hasDecreaseLiquidityCalldata(OnChainRawTransactionView view) {
        String methodId = view.methodId();
        if (DECREASE_LIQUIDITY_V3_SELECTOR.equals(methodId) || DECREASE_LIQUIDITY_V4_SELECTOR.equals(methodId)) {
            return true;
        }
        String inputData = view.inputData();
        if (inputData == null) {
            return false;
        }
        return CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_V3_SELECTOR)
                || CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_V4_SELECTOR);
    }
}
