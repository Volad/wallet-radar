package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Synthesizes a native-ETH inbound {@link RawLeg} when a PancakeSwap V3 (or similar) multicall
 * LP exit uses {@code sweepToken} + {@code WETH.withdraw()} instead of a direct ERC-20 transfer
 * to the user's wallet.
 *
 * <p>The explorer never surfaces the native settlement in this case (no internal-transfer trace,
 * no direct ERC-20 to user), so {@link MovementLegExtractor} would otherwise produce zero inbound
 * legs and the transaction would be misclassified as {@code UNKNOWN}.
 */
public final class LpNativeExitLegEnricher {

    static final String WETH_WITHDRAWAL_TOPIC =
            "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

    private static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
    private static final String BURN_SELECTOR = "0x00f714ce";

    private LpNativeExitLegEnricher() {
    }

    /**
     * Returns an enriched leg list that includes a synthesized native inbound leg derived from
     * WETH Withdrawal logs, or the original list unchanged if the guards do not match.
     */
    public static List<RawLeg> enrichLegs(
            OnChainRawTransactionView view,
            NativeAssetSymbolResolver nativeAssetSymbolResolver,
            List<RawLeg> legs
    ) {
        if (view == null || nativeAssetSymbolResolver == null || legs == null) {
            return legs;
        }

        String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());

        // Guard 1: skip if an inbound non-fee native leg already exists
        if (hasInboundNonFeeNative(legs, nativeSymbol)) {
            return legs;
        }

        // Guard 2: input data must contain decreaseLiquidity or burn selector
        String inputData = view.inputData();
        if (inputData == null) {
            return legs;
        }
        boolean hasLpSelector =
                CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR)
                        || CalldataDecodingSupport.containsEmbeddedSelector(inputData, BURN_SELECTOR);
        if (!hasLpSelector) {
            return legs;
        }

        // Scan persisted logs for WETH Withdrawal events, summing all wad amounts where src != wallet
        String userWallet = normalizeAddress(view.walletAddress());
        BigDecimal totalWad = BigDecimal.ZERO;

        for (Document log : view.persistedLogs()) {
            List<?> topics = log.getList("topics", Object.class, List.of());
            if (topics.size() < 2) {
                continue;
            }
            String topic0 = normalizeTopic(stringValue(topics.getFirst()));
            if (!WETH_WITHDRAWAL_TOPIC.equals(topic0)) {
                continue;
            }
            // topic1 encodes src (who called WETH.withdraw); must NOT be user's wallet
            String src = normalizeIndexedAddress(stringValue(topics.get(1)));
            if (src == null || src.equals(userWallet)) {
                continue;
            }
            BigDecimal wad = parseUnsignedQuantity(stringValue(log.get("data")), 18);
            if (wad != null && wad.signum() > 0) {
                totalWad = totalWad.add(wad);
            }
        }

        if (totalWad.signum() <= 0) {
            return legs;
        }

        List<RawLeg> enriched = new ArrayList<>(legs);
        enriched.add(RawLeg.nativeAsset(nativeSymbol, totalWad));
        return enriched;
    }

    private static boolean hasInboundNonFeeNative(List<RawLeg> legs, String nativeSymbol) {
        return legs.stream()
                .anyMatch(leg -> !leg.fee()
                        && leg.assetContract() == null
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() > 0
                        && nativeSymbol.equalsIgnoreCase(leg.assetSymbol()));
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

    private static String normalizeIndexedAddress(String topicValue) {
        String normalized = normalizeTopic(topicValue);
        if (normalized == null || normalized.length() != 66) {
            return null;
        }
        return normalizeAddress("0x" + normalized.substring(normalized.length() - 40));
    }

    private static String normalizeAddress(String value) {
        return OnChainRawTransactionView.normalizeAddress(value);
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

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
