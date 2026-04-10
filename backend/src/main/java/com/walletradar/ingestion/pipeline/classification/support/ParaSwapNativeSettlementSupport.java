package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recover exact-out ParaSwap native settlement legs when explorer transfers only persist
 * source-token spend plus source-token refund, but calldata proves native output and unwrap.
 */
public final class ParaSwapNativeSettlementSupport {

    private static final String PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR = "0x7f457675";
    private static final String NATIVE_SENTINEL = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String WRAPPED_NATIVE_WITHDRAW_SELECTOR = "0x2e1a7d4d";
    private static final int DST_TOKEN_ARGUMENT_INDEX = 2;
    private static final int EXACT_OUT_AMOUNT_ARGUMENT_INDEX = 4;
    private static final int BENEFICIARY_ARGUMENT_INDEX = 7;

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
        if (!isWalletNativeSettlementSwap(view)) {
            return extractedLegs;
        }

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

    private static boolean isWalletNativeSettlementSwap(OnChainRawTransactionView view) {
        if (!PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(view.methodId())) {
            return false;
        }
        String inputData = view.inputData();
        String walletAddress = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String dstToken = CalldataDecodingSupport.decodeAddressArgument(inputData, DST_TOKEN_ARGUMENT_INDEX);
        String beneficiary = CalldataDecodingSupport.decodeAddressArgument(inputData, BENEFICIARY_ARGUMENT_INDEX);
        return NATIVE_SENTINEL.equals(dstToken)
                && walletAddress != null
                && (walletAddress.equals(beneficiary) || ZERO_ADDRESS.equals(beneficiary));
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
