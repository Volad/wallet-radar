package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Heuristic classifier for bridge contract calls without logs.
 * Emits EXTERNAL_TRANSFER_OUT for native value sent by wallet.
 */
@Component
@Order(75)
public class BridgeCallClassifier implements TxClassifier {

    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final Set<String> KNOWN_BRIDGE_METHOD_IDS = Set.of(
            "0x3ce33bff", // MetaMask bridge(...)
            "0xfc5f1003", // observed Stargate/LI.FI bridge call
            "0xae328590", // observed Stargate/LI.FI bridge call
            "0x84d61c97", // relay request ingestion
            "0xd7a08473", // relay/diamond execution wrapper
            "0xcfc32570", // intent execution wrapper
            "0xdeff4b24", // relay fill
            "0xe2de2a03"  // relay redeem
    );
    private static final Set<String> KNOWN_NATIVE_BRIDGE_METHOD_IDS = Set.of(
            "0xf9068677"
    );
    private static final Set<String> KNOWN_BRIDGE_ROUTER_ADDRESSES = Set.of(
            // MetaMask Bridge Router
            "0x23981fc34e69eedfe2bd9a0a9fcb0719fe09dbfc",
            // LI.FI / Stargate router patterns seen in BASE/ARBITRUM history
            "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae",
            "0x1a44076050125825900e736c501f859c50fe728c"
    );
    private static final Set<String> KNOWN_NATIVE_BRIDGE_CONTRACT_ADDRESSES = Set.of(
            "0x10417734001162ea139e8b044dfe28dbb8b28ad0"
    );

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
        if (!isBridgeCall(txView)) {
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

    private static boolean isBridgeCall(RawTransactionNormalizationView txView) {
        if (txView == null || !txView.hasRawData()) {
            return false;
        }
        if ("SOLANA".equalsIgnoreCase(txView.networkId())) {
            return false;
        }
        if (txView.hasLogs() && hasErc20TransferLogs(txView)) {
            return false;
        }
        return isBridgeSignature(txView)
                || isKnownBridgeRouterCall(txView)
                || isNativeBridgeDepositSignature(txView)
                || isKnownNativeBridgeContractCall(txView);
    }

    private static boolean isBridgeSignature(RawTransactionNormalizationView txView) {
        String functionName = txView.readRawOrExplorerLower("functionName");
        if (functionName != null && functionName.contains("bridge")) {
            return true;
        }
        String selector = txView.selector();
        return selector != null && KNOWN_BRIDGE_METHOD_IDS.contains(selector);
    }

    private static boolean isKnownBridgeRouterCall(RawTransactionNormalizationView txView) {
        String to = txView.readRawOrExplorerAddress("to");
        return to != null && KNOWN_BRIDGE_ROUTER_ADDRESSES.contains(to);
    }

    private static boolean isNativeBridgeDepositSignature(RawTransactionNormalizationView txView) {
        String functionName = txView.readRawOrExplorerLower("functionName");
        if (functionName != null && functionName.contains("depositnative")) {
            return true;
        }
        String selector = txView.selector();
        return selector != null && KNOWN_NATIVE_BRIDGE_METHOD_IDS.contains(selector);
    }

    private static boolean isKnownNativeBridgeContractCall(RawTransactionNormalizationView txView) {
        String to = txView.readRawOrExplorerAddress("to");
        return to != null && KNOWN_NATIVE_BRIDGE_CONTRACT_ADDRESSES.contains(to);
    }

    private static boolean hasErc20TransferLogs(RawTransactionNormalizationView txView) {
        for (Document log : txView.logs()) {
            List<String> topics = txView.getLogTopics(log);
            if (topics == null || topics.isEmpty()) {
                continue;
            }
            String topic0 = topics.get(0);
            if (topic0 != null && ERC20_TRANSFER_TOPIC.equalsIgnoreCase(topic0)) {
                return true;
            }
        }
        return false;
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
            case "UNICHAIN" -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
            case "ZKSYNC" -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
            default -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        };
    }

    private record NativeAsset(String contract, String symbol) {}
}
