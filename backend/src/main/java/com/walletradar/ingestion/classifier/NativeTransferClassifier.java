package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * Classifies native EVM transfers without relying on receipt enrichment:
 * - simple native transfer envelope (value>0, no logs / no token transfers / no internal transfers);
 * - internal-transfers-only net native movement (no tokenTransfers / no logs).
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class NativeTransferClassifier implements TxClassifier {

    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);
    private static final String WRAP_SELECTOR = "0xd0e30db0"; // deposit()
    private static final String UNWRAP_SELECTOR = "0x2e1a7d4d"; // withdraw(uint256)
    private static final String DEPOSIT_TOPIC = "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c";
    private static final String WITHDRAWAL_TOPIC = "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95f7f1727f2";
    private static final Map<String, WrappedNativeAsset> WRAPPED_NATIVE_BY_NETWORK = Map.of(
            "ETHEREUM", new WrappedNativeAsset("0xc02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH"),
            "ARBITRUM", new WrappedNativeAsset("0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "WETH"),
            "OPTIMISM", new WrappedNativeAsset("0x4200000000000000000000000000000000000006", "WETH"),
            "BASE", new WrappedNativeAsset("0x4200000000000000000000000000000000000006", "WETH"),
            "POLYGON", new WrappedNativeAsset("0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270", "WMATIC"),
            "BSC", new WrappedNativeAsset("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", "WBNB"),
            "AVALANCHE", new WrappedNativeAsset("0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", "WAVAX"),
            "MANTLE", new WrappedNativeAsset("0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8", "WMNT")
    );

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        List<RawClassifiedEvent> wrappedNativeConversion = classifyWrappedNativeConversion(tx, walletAddress);
        if (!wrappedNativeConversion.isEmpty()) {
            return wrappedNativeConversion;
        }
        List<RawClassifiedEvent> simple = classifySimpleNativeTransfer(tx, walletAddress);
        if (!simple.isEmpty()) {
            return simple;
        }
        return classifyInternalNativeNetTransfer(tx, walletAddress);
    }

    public static boolean isLikelyWrappedNativeConversion(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || walletAddress == null || walletAddress.isBlank()) {
            return false;
        }
        WrappedNativeAsset wrapped = wrappedNativeOf(tx.networkId());
        if (wrapped == null) {
            return false;
        }
        String to = tx.readRawOrExplorerAddress("to");
        if (to == null || !to.equalsIgnoreCase(wrapped.contract())) {
            return false;
        }
        String selector = tx.selector();
        String functionName = tx.readRawOrExplorerLower("functionName");
        String walletTopic = tx.padAddressForTopic(walletAddress);
        BigInteger txValue = tx.readRawOrExplorerUnsigned("value");
        boolean wrapSignature = WRAP_SELECTOR.equals(selector)
                || (functionName != null && functionName.contains("deposit"));
        boolean likelyWrap = wrapSignature
                && txValue != null
                && txValue.signum() > 0;
        boolean unwrapSignature = UNWRAP_SELECTOR.equals(selector)
                || (functionName != null && functionName.contains("withdraw"));
        boolean likelyUnwrap = unwrapSignature
                && (hasWrappedNativeEvent(tx, walletTopic, wrapped.contract(), WITHDRAWAL_TOPIC)
                || parseUnwrapAmountFromInput(tx) != null);
        return likelyWrap || likelyUnwrap;
    }

    private List<RawClassifiedEvent> classifyWrappedNativeConversion(
            RawTransactionNormalizationView tx, String walletAddress
    ) {
        if (!isLikelyWrappedNativeConversion(tx, walletAddress)) {
            return List.of();
        }
        WrappedNativeAsset wrapped = wrappedNativeOf(tx.networkId());
        if (wrapped == null) {
            return List.of();
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String selector = tx.selector();
        String functionName = tx.readRawOrExplorerLower("functionName");
        boolean isWrap = WRAP_SELECTOR.equals(selector) || (functionName != null && functionName.contains("deposit"));
        BigInteger amountWei = isWrap
                ? extractWrappedEventAmount(tx, walletTopic, wrapped.contract(), DEPOSIT_TOPIC)
                : extractWrappedEventAmount(tx, walletTopic, wrapped.contract(), WITHDRAWAL_TOPIC);
        if ((amountWei == null || amountWei.signum() <= 0) && isWrap) {
            amountWei = tx.readRawOrExplorerUnsigned("value");
        } else if ((amountWei == null || amountWei.signum() <= 0)) {
            amountWei = parseUnwrapAmountFromInput(tx);
        }
        if (amountWei == null || amountWei.signum() <= 0) {
            return List.of();
        }

        BigDecimal amount = new BigDecimal(amountWei).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);
        Integer logIndex = extractWrappedEventLogIndex(tx, walletTopic, wrapped.contract(), isWrap ? DEPOSIT_TOPIC : WITHDRAWAL_TOPIC);
        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());

        RawClassifiedEvent sell = new RawClassifiedEvent();
        sell.setWalletAddress(walletAddress);
        sell.setEventType(EconomicEventType.SWAP_SELL);
        sell.setLogIndex(logIndex);
        RawClassifiedEvent buy = new RawClassifiedEvent();
        buy.setWalletAddress(walletAddress);
        buy.setEventType(EconomicEventType.SWAP_BUY);
        buy.setLogIndex(logIndex);

        if (isWrap) {
            sell.setAssetContract(nativeAsset.contract());
            sell.setAssetSymbol(nativeAsset.symbol());
            sell.setQuantityDelta(amount.negate());

            buy.setAssetContract(wrapped.contract());
            buy.setAssetSymbol(wrapped.symbol());
            buy.setQuantityDelta(amount);
        } else {
            sell.setAssetContract(wrapped.contract());
            sell.setAssetSymbol(wrapped.symbol());
            sell.setQuantityDelta(amount.negate());

            buy.setAssetContract(nativeAsset.contract());
            buy.setAssetSymbol(nativeAsset.symbol());
            buy.setQuantityDelta(amount);
        }
        return List.of(sell, buy);
    }

    private List<RawClassifiedEvent> classifySimpleNativeTransfer(
            RawTransactionNormalizationView tx, String walletAddress
    ) {
        if (!isSimpleNativeTransfer(tx)) {
            return List.of();
        }

        String wallet = tx.normalizeAddressValue(walletAddress);
        String from = tx.readRawOrExplorerAddress("from");
        String to = tx.readRawOrExplorerAddress("to");
        if (wallet == null || from == null || to == null || from.equals(to)) {
            return List.of();
        }

        BigInteger rawValue = tx.readRawOrExplorerUnsigned("value");
        if (rawValue == null || rawValue.signum() <= 0) {
            return List.of();
        }
        BigDecimal quantity = new BigDecimal(rawValue).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);

        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());
        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setWalletAddress(walletAddress);
        event.setAssetContract(nativeAsset.contract());
        event.setAssetSymbol(nativeAsset.symbol());
        if (wallet.equals(to) && !wallet.equals(from)) {
            event.setEventType(EconomicEventType.EXTERNAL_INBOUND);
            event.setQuantityDelta(quantity);
            event.setCounterpartyAddress(from);
            return List.of(event);
        }
        if (wallet.equals(from) && !wallet.equals(to)) {
            event.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
            event.setQuantityDelta(quantity.negate());
            event.setCounterpartyAddress(to);
            return List.of(event);
        }
        return List.of();
    }

    private List<RawClassifiedEvent> classifyInternalNativeNetTransfer(
            RawTransactionNormalizationView tx, String walletAddress
    ) {
        if (!isInternalOnlyNativeTransfer(tx)) {
            return List.of();
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        if (wallet == null) {
            return List.of();
        }
        List<Document> internalTransfers = tx.explorerInternalTransfers();
        if (internalTransfers.isEmpty()) {
            return List.of();
        }

        BigInteger inboundWei = BigInteger.ZERO;
        BigInteger outboundWei = BigInteger.ZERO;
        String inboundCounterparty = null;
        boolean inboundSingleCounterparty = true;
        String outboundCounterparty = null;
        boolean outboundSingleCounterparty = true;

        for (Document transfer : internalTransfers) {
            String isError = tx.internalTransferIsError(transfer);
            if ("1".equals(isError)) {
                continue;
            }

            String from = tx.internalTransferFrom(transfer);
            String to = tx.internalTransferTo(transfer);
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            BigInteger value = tx.internalTransferValue(transfer);
            if (value == null || value.signum() <= 0) {
                continue;
            }

            if (wallet.equals(to) && !wallet.equals(from)) {
                inboundWei = inboundWei.add(value);
                if (inboundCounterparty == null) {
                    inboundCounterparty = from;
                } else if (!inboundCounterparty.equals(from)) {
                    inboundSingleCounterparty = false;
                }
            } else if (wallet.equals(from) && !wallet.equals(to)) {
                outboundWei = outboundWei.add(value);
                if (outboundCounterparty == null) {
                    outboundCounterparty = to;
                } else if (!outboundCounterparty.equals(to)) {
                    outboundSingleCounterparty = false;
                }
            }
        }

        BigInteger netWei = inboundWei.subtract(outboundWei);
        if (netWei.signum() == 0) {
            return List.of();
        }
        BigDecimal quantity = new BigDecimal(netWei.abs()).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);
        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setWalletAddress(walletAddress);
        event.setAssetContract(nativeAsset.contract());
        event.setAssetSymbol(nativeAsset.symbol());
        if (netWei.signum() > 0) {
            event.setEventType(EconomicEventType.EXTERNAL_INBOUND);
            event.setQuantityDelta(quantity);
            if (outboundWei.signum() == 0 && inboundSingleCounterparty && inboundCounterparty != null) {
                event.setCounterpartyAddress(inboundCounterparty);
            }
        } else {
            event.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
            event.setQuantityDelta(quantity.negate());
            if (inboundWei.signum() == 0 && outboundSingleCounterparty && outboundCounterparty != null) {
                event.setCounterpartyAddress(outboundCounterparty);
            }
        }
        return List.of(event);
    }

    private static boolean isSimpleNativeTransfer(RawTransactionNormalizationView tx) {
        if (tx == null || !tx.hasRawData()) {
            return false;
        }
        String networkId = tx.networkId();
        if (networkId == null || networkId.equalsIgnoreCase("SOLANA")) {
            return false;
        }
        if (tx.hasLogs()) {
            return false;
        }
        if (!tx.explorerTokenTransfers().isEmpty()) {
            return false;
        }
        return tx.explorerInternalTransfers().isEmpty();
    }

    private static boolean isInternalOnlyNativeTransfer(RawTransactionNormalizationView tx) {
        if (tx == null || !tx.hasRawData()) {
            return false;
        }
        String networkId = tx.networkId();
        if (networkId == null || networkId.equalsIgnoreCase("SOLANA")) {
            return false;
        }
        if (tx.hasLogs()) {
            return false;
        }
        if (!tx.explorerTokenTransfers().isEmpty()) {
            return false;
        }
        return !tx.explorerInternalTransfers().isEmpty();
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
            default -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        };
    }

    private static WrappedNativeAsset wrappedNativeOf(String networkId) {
        if (networkId == null) {
            return null;
        }
        return WRAPPED_NATIVE_BY_NETWORK.get(networkId.toUpperCase(Locale.ROOT));
    }

    private static BigInteger parseUnwrapAmountFromInput(RawTransactionNormalizationView tx) {
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || !input.startsWith(UNWRAP_SELECTOR) || input.length() < 74) {
            return null;
        }
        String word = input.substring(10, 74);
        try {
            return new BigInteger(word, 16);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasWrappedNativeEvent(
            RawTransactionNormalizationView tx,
            String walletTopic,
            String wrappedContract,
            String eventTopic
    ) {
        return extractWrappedEventAmount(tx, walletTopic, wrappedContract, eventTopic) != null;
    }

    private static BigInteger extractWrappedEventAmount(
            RawTransactionNormalizationView tx,
            String walletTopic,
            String wrappedContract,
            String eventTopic
    ) {
        for (Document log : tx.logs()) {
            if (log == null) {
                continue;
            }
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (address == null || !address.equalsIgnoreCase(wrappedContract)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 2 || !eventTopic.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount != null && amount.signum() > 0) {
                return amount;
            }
        }
        return null;
    }

    private static Integer extractWrappedEventLogIndex(
            RawTransactionNormalizationView tx,
            String walletTopic,
            String wrappedContract,
            String eventTopic
    ) {
        for (Document log : tx.logs()) {
            if (log == null) {
                continue;
            }
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (address == null || !address.equalsIgnoreCase(wrappedContract)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 2 || !eventTopic.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            return tx.getLogIndex(log);
        }
        return null;
    }

    private record NativeAsset(String contract, String symbol) {}
    private record WrappedNativeAsset(String contract, String symbol) {}
}
