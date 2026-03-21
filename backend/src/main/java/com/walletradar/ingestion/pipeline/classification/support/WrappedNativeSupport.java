package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Helper for selector-driven wrapped-native wrap/unwrap handling.
 */
public final class WrappedNativeSupport {

    private static final String WRAP_SELECTOR = "0xd0e30db0";
    private static final String UNWRAP_SELECTOR = "0x2e1a7d4d";

    private WrappedNativeSupport() {
    }

    public static Optional<NormalizedTransactionType> detectType(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        if (view == null || nativeAssetSymbolResolver == null) {
            return Optional.empty();
        }
        if (!nativeAssetSymbolResolver.isWrappedNative(view.networkId(), view.toAddress())) {
            return Optional.empty();
        }
        return switch (view.methodId()) {
            case WRAP_SELECTOR -> Optional.of(NormalizedTransactionType.WRAP);
            case UNWRAP_SELECTOR -> Optional.of(NormalizedTransactionType.UNWRAP);
            default -> Optional.empty();
        };
    }

    public static List<RawLeg> enrichLegs(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            List<RawLeg> extractedLegs
    ) {
        Optional<NormalizedTransactionType> detectedType = detectType(view, nativeAssetSymbolResolver);
        if (detectedType.isEmpty()) {
            return extractedLegs;
        }

        String wrappedContract = nativeAssetSymbolResolver.wrappedNativeContract(view.networkId());
        String wrappedSymbol = nativeAssetSymbolResolver.wrappedNativeSymbol(view.networkId());
        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
        if (wrappedContract == null || wrappedSymbol == null || nativeSymbol == null) {
            return extractedLegs;
        }

        List<RawLeg> legs = new ArrayList<>(extractedLegs);
        if (detectedType.get() == NormalizedTransactionType.WRAP) {
            addSyntheticWrapInbound(view, legs, wrappedContract, wrappedSymbol, nativeSymbol);
        } else {
            addSyntheticUnwrapInbound(view, legs, wrappedContract, nativeSymbol);
        }
        return legs;
    }

    private static void addSyntheticWrapInbound(
            OnChainRawTransactionView view,
            List<RawLeg> legs,
            String wrappedContract,
            String wrappedSymbol,
            String nativeSymbol
    ) {
        if (hasInboundWrapped(legs, wrappedContract) || !hasOutboundNative(legs, nativeSymbol)) {
            return;
        }
        BigInteger rawValue = view.rawValue();
        if (rawValue == null || rawValue.signum() <= 0) {
            return;
        }
        BigDecimal quantity = new BigDecimal(rawValue).movePointLeft(18);
        if (quantity.signum() > 0) {
            legs.add(RawLeg.asset(wrappedContract, wrappedSymbol, quantity));
        }
    }

    private static void addSyntheticUnwrapInbound(
            OnChainRawTransactionView view,
            List<RawLeg> legs,
            String wrappedContract,
            String nativeSymbol
    ) {
        if (hasInboundNative(legs, nativeSymbol) || !hasOutboundWrapped(legs, wrappedContract)) {
            return;
        }
        BigDecimal quantity = decodeFirstUint256Quantity(view.inputData());
        if (quantity != null && quantity.signum() > 0) {
            legs.add(RawLeg.nativeAsset(nativeSymbol, quantity));
        }
    }

    private static BigDecimal decodeFirstUint256Quantity(String inputData) {
        BigInteger value = CalldataDecodingSupport.decodeUint256Argument(inputData, 0);
        if (value == null) {
            return null;
        }
        return new BigDecimal(value).movePointLeft(18);
    }

    private static boolean hasInboundWrapped(List<RawLeg> legs, String wrappedContract) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0
                        && wrappedContract.equals(normalizeContract(leg.assetContract())));
    }

    private static boolean hasOutboundWrapped(List<RawLeg> legs, String wrappedContract) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() < 0
                        && wrappedContract.equals(normalizeContract(leg.assetContract())));
    }

    private static boolean hasOutboundNative(List<RawLeg> legs, String nativeSymbol) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() < 0
                        && leg.assetContract() == null
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
    }

    private static boolean hasInboundNative(List<RawLeg> legs, String nativeSymbol) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0
                        && leg.assetContract() == null
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
    }

    private static String normalizeContract(String assetContract) {
        return assetContract == null ? null : assetContract.trim().toLowerCase(Locale.ROOT);
    }
}
