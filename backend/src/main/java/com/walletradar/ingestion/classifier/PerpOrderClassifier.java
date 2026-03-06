package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Heuristic classifier for perp order-creation calls without logs.
 * Treats native value attached to createOrder() as an outbound flow.
 */
@Component
@Order(70)
public class PerpOrderClassifier implements TxClassifier {

    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);

    private static final Set<String> KNOWN_PERP_ORDER_METHOD_IDS = Set.of(
            "0x322bba21" // createOrder(tuple order)
    );

    private static final Set<String> KNOWN_PERP_ROUTER_ADDRESSES = Set.of(
            // Arbitrum router observed for createOrder(tuple order) flows.
            "0xba3cb449bd2b4adddbc894d8697f5170800eadec"
    );

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
        if (!isPerpOrderWithoutLogs(txView)) {
            return List.of();
        }

        String wallet = txView.normalizeAddressValue(walletAddress);
        String from = txView.readRawOrExplorerAddress("from");
        String to = txView.readRawOrExplorerAddress("to");
        if (wallet == null || from == null || to == null || !wallet.equals(from) || wallet.equals(to)) {
            return List.of();
        }

        BigInteger valueRaw = txView.readRawOrExplorerUnsigned("value");
        if (valueRaw == null || valueRaw.signum() <= 0) {
            return List.of();
        }
        BigDecimal quantity = new BigDecimal(valueRaw).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);

        NativeAsset nativeAsset = nativeAssetOf(txView.networkId());
        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(nativeAsset.contract());
        out.setAssetSymbol(nativeAsset.symbol());
        out.setQuantityDelta(quantity.negate());
        out.setCounterpartyAddress(to);
        return List.of(out);
    }

    private static boolean isPerpOrderWithoutLogs(RawTransactionNormalizationView txView) {
        if (txView == null || !txView.hasRawData()) {
            return false;
        }
        if ("SOLANA".equalsIgnoreCase(txView.networkId())) {
            return false;
        }
        if (txView.hasLogs()) {
            return false;
        }
        return isPerpOrderSignature(txView) || isKnownPerpRouterCall(txView);
    }

    private static boolean isPerpOrderSignature(RawTransactionNormalizationView txView) {
        String selector = txView.selector();
        if (selector != null && KNOWN_PERP_ORDER_METHOD_IDS.contains(selector)) {
            return true;
        }
        String functionName = txView.readRawOrExplorerLower("functionName");
        return functionName != null
                && (functionName.startsWith("createorder(") || functionName.contains(" createorder("));
    }

    private static boolean isKnownPerpRouterCall(RawTransactionNormalizationView txView) {
        String to = txView.readRawOrExplorerAddress("to");
        return to != null && KNOWN_PERP_ROUTER_ADDRESSES.contains(to);
    }

    private static NativeAsset nativeAssetOf(String networkId) {
        if (networkId == null) {
            return new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        }
        return switch (networkId.toUpperCase(Locale.ROOT)) {
            case "POLYGON" -> new NativeAsset("0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270", "MATIC");
            case "BSC" -> new NativeAsset("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", "BNB");
            case "AVALANCHE" -> new NativeAsset("0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", "AVAX");
            case "MANTLE" -> new NativeAsset("0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8", "MNT");
            case "LINEA" -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
            default -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        };
    }

    private record NativeAsset(String contract, String symbol) {}
}
