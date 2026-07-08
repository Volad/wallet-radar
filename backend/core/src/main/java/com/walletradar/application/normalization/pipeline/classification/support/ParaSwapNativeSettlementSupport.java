package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recover ParaSwap native settlement legs when explorer transfers only persist
 * source-token spend, but calldata proves native output and unwrap.
 *
 * <p>Handles two Paraswap V6 selectors:
 * <ul>
 *   <li>{@code swapExactAmountOut} (0x7f457675) — exact out, source surplus may be refunded</li>
 *   <li>{@code swapExactAmountIn} (0xe3ead59e) — exact in, destAmount is the guaranteed ETH output</li>
 * </ul>
 *
 * <p>This synthesizer fires only when there is no existing inbound native leg (i.e. the explorer
 * internal transfers are missing due to indexer lag). When internal transfers are properly indexed
 * the guard {@code hasInboundNative} prevents double-counting.
 */
public final class ParaSwapNativeSettlementSupport {

    private static final String PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR = "0x7f457675";
    private static final String PARASWAP_SWAP_EXACT_AMOUNT_IN_SELECTOR = "0xe3ead59e";
    private static final String NATIVE_SENTINEL = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String WRAPPED_NATIVE_WITHDRAW_SELECTOR = "0x2e1a7d4d";

    // swapExactAmountOut argument indices (flat ABI layout after selector)
    private static final int EXACT_OUT_DST_TOKEN_ARGUMENT_INDEX = 2;
    private static final int EXACT_OUT_AMOUNT_ARGUMENT_INDEX = 4;
    private static final int BENEFICIARY_ARGUMENT_INDEX = 7;

    // swapExactAmountIn argument indices — struct fields inlined directly after executor address:
    //   [0] executor, [1] srcToken, [2] destToken, [3] srcAmount, [4] destAmount, ...
    private static final int SWAP_IN_DST_TOKEN_ARGUMENT_INDEX = 2;
    private static final int SWAP_IN_DEST_AMOUNT_ARGUMENT_INDEX = 4;

    private ParaSwapNativeSettlementSupport() {
    }

    public static List<RawLeg> enrichLegs(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            List<RawLeg> extractedLegs
    ) {
        if (view == null || nativeAssetSymbolResolver == null || extractedLegs == null || extractedLegs.isEmpty()) {
            return extractedLegs;
        }

        if (isWalletNativeSettlementSwap(view)) {
            return enrichSwapExactAmountOut(view, nativeAssetSymbolResolver, extractedLegs);
        }

        if (isSwapExactAmountInWithNativeOutput(view)) {
            return enrichSwapExactAmountIn(view, nativeAssetSymbolResolver, extractedLegs);
        }

        return extractedLegs;
    }

    private static List<RawLeg> enrichSwapExactAmountOut(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            List<RawLeg> extractedLegs
    ) {
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
        if (nativeSymbol == null || hasInboundNative(extractedLegs, nativeSymbol)) {
            return extractedLegs;
        }

        BigDecimal recoveredNativeSettlement = recoverExactOutNativeSettlementQuantity(view);
        if (recoveredNativeSettlement == null || recoveredNativeSettlement.signum() <= 0) {
            return extractedLegs;
        }

        SameAssetRefundSelection refundSelection = findSameAssetRefundSelection(extractedLegs);
        List<RawLeg> enriched = refundSelection == null
                ? new ArrayList<>(extractedLegs)
                : netSameAssetRefund(extractedLegs, refundSelection);
        enriched.add(RawLeg.nativeAsset(nativeSymbol, recoveredNativeSettlement));
        return enriched;
    }

    private static List<RawLeg> enrichSwapExactAmountIn(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            List<RawLeg> extractedLegs
    ) {
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
        if (nativeSymbol == null || hasInboundNative(extractedLegs, nativeSymbol)) {
            return extractedLegs;
        }

        BigDecimal destAmount = recoverSwapInNativeDestAmount(view);
        if (destAmount == null || destAmount.signum() <= 0) {
            return extractedLegs;
        }

        List<RawLeg> enriched = new ArrayList<>(extractedLegs);
        enriched.add(RawLeg.nativeAsset(nativeSymbol, destAmount));
        return enriched;
    }

    private static boolean isWalletNativeSettlementSwap(OnChainRawTransactionView view) {
        if (!PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(view.methodId())) {
            return false;
        }
        String inputData = view.inputData();
        String walletAddress = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String dstToken = CalldataDecodingSupport.decodeAddressArgument(inputData, EXACT_OUT_DST_TOKEN_ARGUMENT_INDEX);
        String beneficiary = CalldataDecodingSupport.decodeAddressArgument(inputData, BENEFICIARY_ARGUMENT_INDEX);
        return NATIVE_SENTINEL.equals(dstToken)
                && walletAddress != null
                && (walletAddress.equals(beneficiary) || ZERO_ADDRESS.equals(beneficiary));
    }

    private static boolean isSwapExactAmountInWithNativeOutput(OnChainRawTransactionView view) {
        if (!PARASWAP_SWAP_EXACT_AMOUNT_IN_SELECTOR.equals(view.methodId())) {
            return false;
        }
        String inputData = view.inputData();
        String dstToken = CalldataDecodingSupport.decodeAddressArgument(inputData, SWAP_IN_DST_TOKEN_ARGUMENT_INDEX);
        if (!NATIVE_SENTINEL.equals(dstToken)) {
            return false;
        }
        return CalldataDecodingSupport.containsEmbeddedSelector(inputData, WRAPPED_NATIVE_WITHDRAW_SELECTOR);
    }

    private static BigDecimal recoverSwapInNativeDestAmount(OnChainRawTransactionView view) {
        BigInteger destAmountRaw = CalldataDecodingSupport.decodeUint256Argument(
                view.inputData(),
                SWAP_IN_DEST_AMOUNT_ARGUMENT_INDEX
        );
        if (destAmountRaw == null || destAmountRaw.signum() <= 0) {
            return null;
        }
        return new BigDecimal(destAmountRaw).movePointLeft(18);
    }

    private static BigDecimal recoverExactOutNativeSettlementQuantity(OnChainRawTransactionView view) {
        BigInteger exactOutRaw = CalldataDecodingSupport.decodeUint256Argument(
                view.inputData(),
                EXACT_OUT_AMOUNT_ARGUMENT_INDEX
        );
        if (exactOutRaw == null || exactOutRaw.signum() <= 0) {
            return null;
        }
        BigInteger embeddedUnwrapAmount = extractSingleEmbeddedUnwrapAmount(view.inputData());
        if (embeddedUnwrapAmount == null || embeddedUnwrapAmount.signum() <= 0 || !exactOutRaw.equals(embeddedUnwrapAmount)) {
            return null;
        }
        return new BigDecimal(exactOutRaw).movePointLeft(18);
    }

    private static BigInteger extractSingleEmbeddedUnwrapAmount(String inputData) {
        if (inputData == null || !inputData.startsWith("0x") || inputData.length() <= 10) {
            return null;
        }
        String payload = inputData.substring(10).toLowerCase(Locale.ROOT);
        String selector = WRAPPED_NATIVE_WITHDRAW_SELECTOR.substring(2);
        BigInteger matchedAmount = null;
        int matches = 0;
        int cursor = payload.indexOf(selector);
        while (cursor >= 0) {
            int amountStart = cursor + selector.length();
            int amountEnd = amountStart + 64;
            if (amountEnd <= payload.length()) {
                try {
                    BigInteger candidate = new BigInteger(payload.substring(amountStart, amountEnd), 16);
                    if (candidate.signum() > 0) {
                        matchedAmount = candidate;
                        matches++;
                        if (matches > 1) {
                            return null;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            cursor = payload.indexOf(selector, cursor + selector.length());
        }
        return matches == 1 ? matchedAmount : null;
    }

    private static boolean hasInboundNative(List<RawLeg> legs, String nativeSymbol) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.assetContract() == null
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
    }

    private static SameAssetRefundSelection findSameAssetRefundSelection(List<RawLeg> legs) {
        SameAssetRefundSelection match = null;
        for (int outboundIndex = 0; outboundIndex < legs.size(); outboundIndex++) {
            RawLeg outbound = legs.get(outboundIndex);
            if (!isTokenOutbound(outbound)) {
                continue;
            }
            for (int inboundIndex = 0; inboundIndex < legs.size(); inboundIndex++) {
                RawLeg inbound = legs.get(inboundIndex);
                if (!isTokenInbound(inbound) || !sameToken(outbound, inbound)) {
                    continue;
                }
                BigDecimal outboundQuantity = outbound.quantityDelta().abs();
                BigDecimal inboundQuantity = inbound.quantityDelta().abs();
                if (inboundQuantity.compareTo(outboundQuantity) >= 0) {
                    continue;
                }
                SameAssetRefundSelection candidate = new SameAssetRefundSelection(
                        outboundIndex,
                        inboundIndex,
                        outbound.assetContract(),
                        outbound.assetSymbol(),
                        outboundQuantity.subtract(inboundQuantity)
                );
                if (match != null) {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }

    private static List<RawLeg> netSameAssetRefund(List<RawLeg> legs, SameAssetRefundSelection selection) {
        List<RawLeg> netted = new ArrayList<>(legs.size());
        for (int index = 0; index < legs.size(); index++) {
            RawLeg leg = legs.get(index);
            if (index == selection.refundIndex()) {
                continue;
            }
            if (index == selection.outboundIndex()) {
                netted.add(RawLeg.asset(
                        selection.assetContract(),
                        selection.assetSymbol(),
                        selection.netOutboundQuantity().negate()
                ));
                continue;
            }
            netted.add(leg);
        }
        return netted;
    }

    private static boolean isTokenOutbound(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.assetContract() != null
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() < 0;
    }

    private static boolean isTokenInbound(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.assetContract() != null
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() > 0;
    }

    private static boolean sameToken(RawLeg left, RawLeg right) {
        String leftContract = left.assetContract();
        String rightContract = right.assetContract();
        return leftContract != null && leftContract.equalsIgnoreCase(rightContract);
    }

    private record SameAssetRefundSelection(
            int outboundIndex,
            int refundIndex,
            String assetContract,
            String assetSymbol,
            BigDecimal netOutboundQuantity
    ) {
    }
}
