package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recover native-settlement buy legs for 1inch swap routes when raw calldata proves native output
 * to the tracked wallet but explorer evidence only persists wrapped-native unwrap traces.
 */
public final class OneInchNativeSettlementSupport {

    private static final String ONE_INCH_SWAP_SELECTOR = "0x07ed2379";
    private static final String NATIVE_SENTINEL = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String WRAPPED_NATIVE_WITHDRAWAL_TOPIC =
            "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";
    private static final int DST_TOKEN_ARGUMENT_INDEX = 2;
    private static final int DST_RECEIVER_ARGUMENT_INDEX = 4;

    private OneInchNativeSettlementSupport() {
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
        if (nativeSymbol == null || hasInboundNative(extractedLegs, nativeSymbol) || !onlyOutboundWalletShape(extractedLegs)) {
            return extractedLegs;
        }
        String wrappedNativeContract = nativeAssetSymbolResolver.wrappedNativeContract(view.networkId());
        if (wrappedNativeContract == null) {
            return extractedLegs;
        }

        BigDecimal recoveredNativeSettlement = recoverWrappedNativeUnwrapQuantity(view, wrappedNativeContract);
        if (recoveredNativeSettlement == null || recoveredNativeSettlement.signum() <= 0) {
            return extractedLegs;
        }

        List<RawLeg> enriched = new ArrayList<>(extractedLegs);
        enriched.add(RawLeg.nativeAsset(nativeSymbol, recoveredNativeSettlement));
        return enriched;
    }

    private static boolean isWalletNativeSettlementSwap(OnChainRawTransactionView view) {
        if (!ONE_INCH_SWAP_SELECTOR.equals(view.methodId())) {
            return false;
        }
        String inputData = view.inputData();
        String walletAddress = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String dstToken = CalldataDecodingSupport.decodeAddressArgument(inputData, DST_TOKEN_ARGUMENT_INDEX);
        String dstReceiver = CalldataDecodingSupport.decodeAddressArgument(inputData, DST_RECEIVER_ARGUMENT_INDEX);
        return NATIVE_SENTINEL.equals(dstToken) && walletAddress != null && walletAddress.equals(dstReceiver);
    }

    private static boolean hasInboundNative(List<RawLeg> legs, String nativeSymbol) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.assetContract() == null
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
    }

    private static boolean onlyOutboundWalletShape(List<RawLeg> legs) {
        boolean hasInbound = false;
        boolean hasOutbound = false;
        for (RawLeg leg : legs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() > 0) {
                hasInbound = true;
            } else {
                hasOutbound = true;
            }
        }
        return hasOutbound && !hasInbound;
    }

    private static BigDecimal recoverWrappedNativeUnwrapQuantity(
            OnChainRawTransactionView view,
            String wrappedNativeContract
    ) {
        BigDecimal matchedQuantity = null;
        int matchedPairs = 0;
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!wrappedNativeContract.equals(view.tokenTransferContract(transfer))) {
                continue;
            }
            String intermediary = view.tokenTransferTo(transfer);
            if (intermediary == null || intermediary.equals(view.walletAddress())) {
                continue;
            }
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!hasMatchingWrappedNativeBurn(view, wrappedNativeContract, intermediary, quantity)) {
                continue;
            }
            matchedQuantity = quantity;
            matchedPairs++;
            if (matchedPairs > 1) {
                return null;
            }
        }
        return matchedPairs == 1 ? matchedQuantity : null;
    }

    private static boolean hasMatchingWrappedNativeBurn(
            OnChainRawTransactionView view,
            String wrappedNativeContract,
            String intermediary,
            BigDecimal quantity
    ) {
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!wrappedNativeContract.equals(view.tokenTransferContract(transfer))) {
                continue;
            }
            if (!intermediary.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            if (!ZERO_ADDRESS.equals(view.tokenTransferTo(transfer))) {
                continue;
            }
            BigDecimal burnQuantity = view.tokenTransferQuantity(transfer);
            if (burnQuantity != null && burnQuantity.compareTo(quantity) == 0) {
                return true;
            }
        }
        return hasMatchingWrappedNativeWithdrawalLog(view, wrappedNativeContract, intermediary, quantity);
    }

    private static boolean hasMatchingWrappedNativeWithdrawalLog(
            OnChainRawTransactionView view,
            String wrappedNativeContract,
            String intermediary,
            BigDecimal quantity
    ) {
        for (Document log : view.persistedLogs()) {
            if (!wrappedNativeContract.equals(normalizeAddress(stringValue(log.get("address"))))) {
                continue;
            }
            List<?> topics = log.getList("topics", Object.class, List.of());
            if (topics.isEmpty() || !WRAPPED_NATIVE_WITHDRAWAL_TOPIC.equals(normalizeTopic(stringValue(topics.getFirst())))) {
                continue;
            }
            if (topics.size() < 2 || !intermediary.equals(normalizeIndexedAddress(stringValue(topics.get(1))))) {
                continue;
            }
            BigDecimal withdrawalQuantity = parseUnsignedQuantity(stringValue(log.get("data")), 18);
            if (withdrawalQuantity != null && withdrawalQuantity.compareTo(quantity) == 0) {
                return true;
            }
        }
        return false;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalizeAddress(String value) {
        return OnChainRawTransactionView.normalizeAddress(value);
    }

    private static String normalizeIndexedAddress(String topicValue) {
        String normalized = normalizeTopic(topicValue);
        if (normalized == null || normalized.length() != 66) {
            return null;
        }
        return normalizeAddress("0x" + normalized.substring(normalized.length() - 40));
    }

    private static String normalizeTopic(String value) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        return normalized;
    }

    private static BigDecimal parseUnsignedQuantity(String value, int decimals) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            BigInteger raw = text.startsWith("0x") || text.startsWith("0X")
                    ? new BigInteger(text.substring(2), 16)
                    : new BigInteger(text);
            return new BigDecimal(raw).movePointLeft(Math.max(0, decimals));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
