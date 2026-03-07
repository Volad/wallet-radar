package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies ERC20 Transfer logs. Emits EXTERNAL_TRANSFER_OUT for sends, EXTERNAL_INBOUND for receives.
 * When exactly one distinct asset flows out and exactly one distinct asset flows in (different assets),
 * emits SWAP_SELL and SWAP_BUY instead (heuristic swap, ADR-019).
 * Uses token decimals (e.g. WBTC=8, USDC=6) so quantityDelta is correct.
 * TODO: Protocol precedence — do not apply heuristic when tx has BORROW/REPAY/LEND_* etc. (follow-up).
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class TransferClassifier implements TxClassifier {

    /** ERC20 Transfer(address,address,uint256) topic. */
    public static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String ZERO_ADDRESS_TOPIC = "0x0000000000000000000000000000000000000000000000000000000000000000";
    /** Uniswap V2 Swap(address,uint256,uint256,uint256,uint256) */
    private static final String SWAP_TOPIC_V2 = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
    /** Uniswap V3 Swap(address,address,int256,int256,uint160,uint128,int24) */
    private static final String SWAP_TOPIC_V3 = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67";
    /** Uniswap V3–style Swap with protocol fees (Velora, etc.): Swap(...,uint128,uint128) */
    private static final String SWAP_TOPIC_V3_FEES = "0x19b47279256b2a23a1665c810c8d55a1758940ee09377d4f8d26497a3577dc83";
    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);
    private static final Set<String> CLAIM_METHOD_IDS = Set.of("0x71ee95c0");
    private static final Set<String> KNOWN_SWAP_CALL_METHOD_IDS = Set.of(
            "0x07ed2379",
            "0x90411a32",
            "0x73fc4457",
            "0x7f457675",
            "0xe21fd0e9"
    );

    private static final Set<String> SWAP_TOPICS = Set.of(
            SWAP_TOPIC_V2, SWAP_TOPIC_V3, SWAP_TOPIC_V3_FEES
    );

    private static boolean hasSwapTopic(List<String> topics) {
        if (topics == null || topics.isEmpty()) return false;
        String t0 = topics.get(0);
        return SWAP_TOPICS.stream().anyMatch(t -> t.equalsIgnoreCase(t0));
    }

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;
    private final LendClassifier lendClassifier;

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        List<RawClassifiedEvent> out = new ArrayList<>();
        List<Document> logs = tx.logs();
        if (logs.isEmpty()) {
            return out;
        }
        if (NativeTransferClassifier.isLikelyWrappedNativeConversion(tx, walletAddress)) {
            return out;
        }
        // Skip if this tx is a DEX swap (SwapClassifier handles it)
        boolean hasSwap = logs.stream()
                .map(tx::getLogTopics)
                .filter(t -> t != null && !t.isEmpty())
                .anyMatch(TransferClassifier::hasSwapTopic);
        if (hasSwap) {
            return out;
        }
        if (lendClassifier.isLikelyLendPattern(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpEntryPattern(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpExitPattern(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpPositionPattern(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpEntryFromPositionContext(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpEntryWithoutMintPattern(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpExitFromPositionContext(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpExitWithoutBurnPattern(tx, walletAddress, logs)
                || LpClassifier.isLikelyLpFeeClaimPattern(tx, walletAddress, logs)) {
            return out;
        }
        if (isLikelyClaimCall(tx)) {
            List<RawClassifiedEvent> claimEvents = classifyClaimInbounds(tx, walletAddress, logs);
            if (!claimEvents.isEmpty()) {
                return claimEvents;
            }
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);

        // Heuristic (ADR-019): exactly one distinct asset out, exactly one distinct asset in, different → swap
        Map<String, BigDecimal> outflowQtyByContract = new LinkedHashMap<>();
        Map<String, TransferMeta> outflowMetaByContract = new LinkedHashMap<>();
        Map<String, BigDecimal> inflowQtyByContract = new LinkedHashMap<>();
        Map<String, TransferMeta> inflowMetaByContract = new LinkedHashMap<>();
        boolean hasWalletInboundMint = false;
        boolean hasWalletOutboundBurn = false;

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            String tokenAddress = tx.getLogAddress(log);
            if (tokenAddress == null) continue;
            BigDecimal quantity = transferQuantity(tx, log, topics, tokenAddress);
            if (quantity == null) continue;
            String symbol = evmTokenDecimalsResolver.getSymbol(tx.networkId(), tokenAddress);
            String protocolName = protocolRegistry.getProtocolName(tokenAddress).orElse(null);
            Integer logIndex = tx.getLogIndex(log);
            TransferMeta meta = new TransferMeta(symbol != null ? symbol : "", protocolName, logIndex);

            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                outflowQtyByContract.merge(tokenAddress, quantity.negate(), BigDecimal::add);
                outflowMetaByContract.putIfAbsent(tokenAddress, meta);
                if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                    hasWalletOutboundBurn = true;
                }
            } else if (walletTopic.equalsIgnoreCase(toTopic)) {
                inflowQtyByContract.merge(tokenAddress, quantity, BigDecimal::add);
                inflowMetaByContract.putIfAbsent(tokenAddress, meta);
                if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)) {
                    hasWalletInboundMint = true;
                }
            }
        }

        if (!hasWalletInboundMint && !hasWalletOutboundBurn && isLikelySwapCall(tx)) {
            List<RawClassifiedEvent> netSwap = classifyNetSwapForKnownSwapCall(
                    walletAddress,
                    outflowQtyByContract,
                    outflowMetaByContract,
                    inflowQtyByContract,
                    inflowMetaByContract
            );
            if (!netSwap.isEmpty()) {
                return netSwap;
            }
        }

        if (!hasWalletInboundMint && !hasWalletOutboundBurn
                && outflowQtyByContract.size() == 1 && inflowQtyByContract.size() == 1) {
            String outflowContract = outflowQtyByContract.keySet().iterator().next();
            String inflowContract = inflowQtyByContract.keySet().iterator().next();
            if (!outflowContract.equals(inflowContract)) {
                TransferMeta outMeta = outflowMetaByContract.get(outflowContract);
                TransferMeta inMeta = inflowMetaByContract.get(inflowContract);
                RawClassifiedEvent sell = new RawClassifiedEvent();
                sell.setEventType(EconomicEventType.SWAP_SELL);
                sell.setWalletAddress(walletAddress);
                sell.setAssetContract(outflowContract);
                sell.setAssetSymbol(outMeta != null ? outMeta.symbol : "");
                sell.setQuantityDelta(outflowQtyByContract.get(outflowContract));
                sell.setProtocolName(outMeta != null ? outMeta.protocolName : null);
                sell.setLogIndex(outMeta != null ? outMeta.logIndex : null);
                out.add(sell);
                RawClassifiedEvent buy = new RawClassifiedEvent();
                buy.setEventType(EconomicEventType.SWAP_BUY);
                buy.setWalletAddress(walletAddress);
                buy.setAssetContract(inflowContract);
                buy.setAssetSymbol(inMeta != null ? inMeta.symbol : "");
                buy.setQuantityDelta(inflowQtyByContract.get(inflowContract));
                buy.setProtocolName(inMeta != null ? inMeta.protocolName : null);
                buy.setLogIndex(inMeta != null ? inMeta.logIndex : null);
                out.add(buy);
                return out;
            }
        }
        if (!hasWalletInboundMint && !hasWalletOutboundBurn
                && outflowQtyByContract.size() == 1 && inflowQtyByContract.isEmpty()
                && isLikelySwapCall(tx)) {
            BigDecimal nativeInbound = nativeInboundFromInternalTransfers(tx, walletAddress);
            if (nativeInbound.signum() > 0) {
                String outflowContract = outflowQtyByContract.keySet().iterator().next();
                TransferMeta outMeta = outflowMetaByContract.get(outflowContract);
                RawClassifiedEvent sell = new RawClassifiedEvent();
                sell.setEventType(EconomicEventType.SWAP_SELL);
                sell.setWalletAddress(walletAddress);
                sell.setAssetContract(outflowContract);
                sell.setAssetSymbol(outMeta != null ? outMeta.symbol : "");
                sell.setQuantityDelta(outflowQtyByContract.get(outflowContract));
                sell.setProtocolName(outMeta != null ? outMeta.protocolName : null);
                sell.setLogIndex(outMeta != null ? outMeta.logIndex : null);
                out.add(sell);

                NativeAsset nativeAsset = nativeAssetOf(tx.networkId());
                RawClassifiedEvent buy = new RawClassifiedEvent();
                buy.setEventType(EconomicEventType.SWAP_BUY);
                buy.setWalletAddress(walletAddress);
                buy.setAssetContract(nativeAsset.contract());
                buy.setAssetSymbol(nativeAsset.symbol());
                buy.setQuantityDelta(nativeInbound);
                out.add(buy);
                return out;
            }
        }
        if (hasWalletInboundMint
                && outflowQtyByContract.isEmpty()
                && inflowQtyByContract.size() == 1
                && isWalletSender(tx, walletAddress)) {
            BigInteger rawValue = tx.readRawOrExplorerUnsigned("value");
            if (rawValue != null && rawValue.signum() > 0) {
                BigDecimal nativeOut = new BigDecimal(rawValue).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);
                String inflowContract = inflowQtyByContract.keySet().iterator().next();
                TransferMeta inMeta = inflowMetaByContract.get(inflowContract);

                NativeAsset nativeAsset = nativeAssetOf(tx.networkId());
                RawClassifiedEvent sell = new RawClassifiedEvent();
                sell.setEventType(EconomicEventType.SWAP_SELL);
                sell.setWalletAddress(walletAddress);
                sell.setAssetContract(nativeAsset.contract());
                sell.setAssetSymbol(nativeAsset.symbol());
                sell.setQuantityDelta(nativeOut.negate());
                out.add(sell);

                RawClassifiedEvent buy = new RawClassifiedEvent();
                buy.setEventType(EconomicEventType.SWAP_BUY);
                buy.setWalletAddress(walletAddress);
                buy.setAssetContract(inflowContract);
                buy.setAssetSymbol(inMeta != null ? inMeta.symbol : "");
                buy.setQuantityDelta(inflowQtyByContract.get(inflowContract));
                buy.setProtocolName(inMeta != null ? inMeta.protocolName : null);
                buy.setLogIndex(inMeta != null ? inMeta.logIndex : null);
                out.add(buy);
                return out;
            }
        }

        // Default: emit EXTERNAL_* per Transfer log
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3) {
                continue;
            }
            if (!TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            String tokenAddress = tx.getLogAddress(log);
            if (tokenAddress == null) {
                continue;
            }
            BigDecimal quantity = transferQuantity(tx, log, topics, tokenAddress);
            if (quantity == null) {
                continue;
            }
            String symbol = evmTokenDecimalsResolver.getSymbol(tx.networkId(), tokenAddress);
            String protocolName = protocolRegistry.getProtocolName(tokenAddress).orElse(null);
            Integer logIndex = tx.getLogIndex(log);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                RawClassifiedEvent e = new RawClassifiedEvent();
                e.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
                e.setWalletAddress(walletAddress);
                e.setAssetContract(tokenAddress);
                e.setAssetSymbol(symbol != null ? symbol : "");
                e.setQuantityDelta(quantity.negate());
                e.setCounterpartyAddress(tx.topicToAddress(topics.get(2)));
                e.setProtocolName(protocolName);
                e.setLogIndex(logIndex);
                out.add(e);
            } else if (walletTopic.equalsIgnoreCase(toTopic)) {
                RawClassifiedEvent e = new RawClassifiedEvent();
                e.setEventType(EconomicEventType.EXTERNAL_INBOUND);
                e.setWalletAddress(walletAddress);
                e.setAssetContract(tokenAddress);
                e.setAssetSymbol(symbol != null ? symbol : "");
                e.setQuantityDelta(quantity);
                e.setCounterpartyAddress(tx.topicToAddress(topics.get(1)));
                e.setProtocolName(protocolName);
                e.setLogIndex(logIndex);
                out.add(e);
            }
        }
        return out;
    }

    private static List<RawClassifiedEvent> classifyNetSwapForKnownSwapCall(
            String walletAddress,
            Map<String, BigDecimal> outflowQtyByContract,
            Map<String, TransferMeta> outflowMetaByContract,
            Map<String, BigDecimal> inflowQtyByContract,
            Map<String, TransferMeta> inflowMetaByContract
    ) {
        Map<String, BigDecimal> netByContract = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : outflowQtyByContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : inflowQtyByContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }

        String sellContract = null;
        String buyContract = null;
        BigDecimal sellQty = null;
        BigDecimal buyQty = null;
        for (Map.Entry<String, BigDecimal> e : netByContract.entrySet()) {
            BigDecimal qty = e.getValue();
            if (qty == null || qty.signum() == 0) {
                continue;
            }
            if (qty.signum() < 0) {
                if (sellContract != null) {
                    return List.of();
                }
                sellContract = e.getKey();
                sellQty = qty;
            } else {
                if (buyContract != null) {
                    return List.of();
                }
                buyContract = e.getKey();
                buyQty = qty;
            }
        }
        if (sellContract == null || buyContract == null || sellContract.equalsIgnoreCase(buyContract)) {
            return List.of();
        }

        TransferMeta sellMeta = outflowMetaByContract.getOrDefault(sellContract, inflowMetaByContract.get(sellContract));
        TransferMeta buyMeta = inflowMetaByContract.getOrDefault(buyContract, outflowMetaByContract.get(buyContract));

        RawClassifiedEvent sell = new RawClassifiedEvent();
        sell.setEventType(EconomicEventType.SWAP_SELL);
        sell.setWalletAddress(walletAddress);
        sell.setAssetContract(sellContract);
        sell.setAssetSymbol(sellMeta != null ? sellMeta.symbol : "");
        sell.setQuantityDelta(sellQty);
        sell.setProtocolName(sellMeta != null ? sellMeta.protocolName : null);
        sell.setLogIndex(sellMeta != null ? sellMeta.logIndex : null);

        RawClassifiedEvent buy = new RawClassifiedEvent();
        buy.setEventType(EconomicEventType.SWAP_BUY);
        buy.setWalletAddress(walletAddress);
        buy.setAssetContract(buyContract);
        buy.setAssetSymbol(buyMeta != null ? buyMeta.symbol : "");
        buy.setQuantityDelta(buyQty);
        buy.setProtocolName(buyMeta != null ? buyMeta.protocolName : null);
        buy.setLogIndex(buyMeta != null ? buyMeta.logIndex : null);
        return List.of(sell, buy);
    }

    private List<RawClassifiedEvent> classifyClaimInbounds(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> netByContract = new LinkedHashMap<>();
        Map<String, TransferMeta> inboundMetaByContract = new LinkedHashMap<>();

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String contract = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (contract == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), contract);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            String symbol = evmTokenDecimalsResolver.getSymbol(tx.networkId(), contract);
            String protocolName = protocolRegistry.getProtocolName(contract).orElse(null);
            Integer logIndex = tx.getLogIndex(log);

            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                netByContract.merge(contract, quantity.negate(), BigDecimal::add);
            }
            if (walletTopic.equalsIgnoreCase(toTopic)) {
                netByContract.merge(contract, quantity, BigDecimal::add);
                inboundMetaByContract.putIfAbsent(
                        contract,
                        new TransferMeta(symbol != null ? symbol : "", protocolName, logIndex)
                );
            }
        }

        if (netByContract.isEmpty()) {
            return List.of();
        }

        List<RawClassifiedEvent> events = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : netByContract.entrySet()) {
            BigDecimal net = entry.getValue();
            if (net == null || net.signum() <= 0) {
                continue;
            }
            String contract = entry.getKey();
            TransferMeta meta = inboundMetaByContract.get(contract);
            if (meta == null) {
                continue;
            }
            RawClassifiedEvent inbound = new RawClassifiedEvent();
            inbound.setEventType(EconomicEventType.EXTERNAL_INBOUND);
            inbound.setWalletAddress(walletAddress);
            inbound.setAssetContract(contract);
            inbound.setAssetSymbol(meta.symbol);
            inbound.setQuantityDelta(net);
            inbound.setProtocolName(meta.protocolName);
            inbound.setLogIndex(meta.logIndex);
            events.add(inbound);
        }
        return events;
    }

    private static boolean isLikelySwapCall(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null && KNOWN_SWAP_CALL_METHOD_IDS.contains(selector)) {
            return true;
        }
        String functionName = tx.readRawOrExplorerTx("functionName");
        if (functionName == null) {
            return false;
        }
        return functionName.toLowerCase(Locale.ROOT).contains("swap");
    }

    private static boolean isLikelyClaimCall(RawTransactionNormalizationView tx) {
        String selector = tx.selector();
        if (selector != null && CLAIM_METHOD_IDS.contains(selector)) {
            return true;
        }
        String functionName = tx.readRawOrExplorerTx("functionName");
        if (functionName == null) {
            return false;
        }
        String normalized = functionName.toLowerCase(Locale.ROOT);
        return normalized.startsWith("claim(") || normalized.contains(" claim(");
    }

    private static BigDecimal nativeInboundFromInternalTransfers(
            RawTransactionNormalizationView tx, String walletAddress
    ) {
        if (tx == null || !tx.hasRawData() || walletAddress == null || walletAddress.isBlank()) {
            return BigDecimal.ZERO;
        }
        List<Document> internalTransfers = tx.explorerInternalTransfers();
        if (internalTransfers.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        if (wallet == null) {
            return BigDecimal.ZERO;
        }

        BigInteger totalWei = BigInteger.ZERO;
        for (Document transfer : internalTransfers) {
            String to = tx.internalTransferTo(transfer);
            if (to == null || !to.equals(wallet)) {
                continue;
            }
            String isError = tx.internalTransferIsError(transfer);
            if ("1".equals(isError)) {
                continue;
            }
            BigInteger value = tx.internalTransferValue(transfer);
            if (value != null && value.signum() > 0) {
                totalWei = totalWei.add(value);
            }
        }
        if (totalWei.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(totalWei).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);
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

    private BigDecimal transferQuantity(
            RawTransactionNormalizationView tx,
            Document log,
            List<String> topics,
            String tokenAddress
    ) {
        BigInteger amount = tx.getLogAmount(log);
        if (amount != null && amount.signum() > 0) {
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            return new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
        }
        if (isLikelyErc721Transfer(topics, tx.getLogData(log))) {
            return BigDecimal.ONE;
        }
        return null;
    }

    private static boolean isLikelyErc721Transfer(List<String> topics, String data) {
        if (topics == null || topics.size() < 4) {
            return false;
        }
        if (data == null) {
            return true;
        }
        String normalized = data.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || "0x".equals(normalized) || "0x0".equals(normalized);
    }

    private static boolean isWalletSender(RawTransactionNormalizationView tx, String walletAddress) {
        String wallet = tx.normalizeAddressValue(walletAddress);
        String from = tx.readRawOrExplorerAddress("from");
        return wallet != null && wallet.equals(from);
    }

    private record NativeAsset(String contract, String symbol) {}
    private record TransferMeta(String symbol, String protocolName, Integer logIndex) {}
}
