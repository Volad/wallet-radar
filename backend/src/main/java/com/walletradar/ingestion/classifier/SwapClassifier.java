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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies DEX swap events (e.g. Uniswap Swap). Emits SWAP_BUY and SWAP_SELL from token flows.
 * Detects swap by presence of Swap event topic or multiple Transfer logs in same tx with router.
 * Uses token decimals (e.g. WBTC=8, USDC=6) so quantityDelta is correct.
 */
@Component
@Order(99)
@RequiredArgsConstructor
public class SwapClassifier implements TxClassifier {

    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    /** Uniswap V2 Swap(address,uint256,uint256,uint256,uint256) */
    private static final String SWAP_TOPIC_V2 = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
    /** Uniswap V3 Swap(address,address,int256,int256,uint160,uint128,int24) */
    private static final String SWAP_TOPIC_V3 = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67";
    /** Uniswap V3–style Swap with protocol fees (Velora, etc.): Swap(...,uint128,uint128) */
    private static final String SWAP_TOPIC_V3_FEES = "0x19b47279256b2a23a1665c810c8d55a1758940ee09377d4f8d26497a3577dc83";

    private static final Set<String> SWAP_TOPICS = Set.of(
            SWAP_TOPIC_V2, SWAP_TOPIC_V3, SWAP_TOPIC_V3_FEES
    );
    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);

    private static boolean hasSwapTopic(List<String> topics) {
        if (topics == null || topics.isEmpty()) return false;
        String t0 = topics.get(0);
        return SWAP_TOPICS.stream().anyMatch(t -> t.equalsIgnoreCase(t0));
    }

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        List<RawClassifiedEvent> out = new ArrayList<>();
        List<Document> logs = tx.logs();
        if (logs.isEmpty()) {
            return out;
        }
        boolean hasSwap = logs.stream()
                .map(tx::getLogTopics)
                .filter(t -> t != null && !t.isEmpty())
                .anyMatch(SwapClassifier::hasSwapTopic);
        if (!hasSwap) {
            return out;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        List<RawClassifiedEvent> transfers = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            String tokenAddress = tx.getLogAddress(log);
            if (tokenAddress == null) continue;
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null) continue;
            TokenMetaFromSources tokenMeta = resolveTokenMeta(tx, tokenAddress);
            int decimals = tokenMeta.decimals();
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal qty = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            String symbol = tokenMeta.symbol();
            String protocolName = protocolRegistry.getProtocolName(tokenAddress).orElse(null);
            Integer logIndex = tx.getLogIndex(log);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                RawClassifiedEvent e = new RawClassifiedEvent();
                e.setEventType(EconomicEventType.SWAP_SELL);
                e.setWalletAddress(walletAddress);
                e.setAssetContract(tokenAddress);
                e.setAssetSymbol(symbol != null ? symbol : "");
                e.setQuantityDelta(qty.negate());
                e.setProtocolName(protocolName);
                e.setLogIndex(logIndex);
                transfers.add(e);
            } else if (walletTopic.equalsIgnoreCase(toTopic)) {
                RawClassifiedEvent e = new RawClassifiedEvent();
                e.setEventType(EconomicEventType.SWAP_BUY);
                e.setWalletAddress(walletAddress);
                e.setAssetContract(tokenAddress);
                e.setAssetSymbol(symbol != null ? symbol : "");
                e.setQuantityDelta(qty);
                e.setProtocolName(protocolName);
                e.setLogIndex(logIndex);
                transfers.add(e);
            }
        }
        // Fallback: aggregator pays user in native ETH (internal tx), so no ERC-20 Transfer(to=wallet).
        // Infer SWAP_BUY from Swap log (V3 fees: amount0, amount1) when we have exactly one SWAP_SELL.
        if (transfers.size() == 1 && transfers.get(0).getEventType() == EconomicEventType.SWAP_SELL) {
            tryAddSwapBuyFromSwapLog(tx, logs, walletAddress, transfers.get(0), transfers);
        }
        if (transfers.size() == 1 && transfers.get(0).getEventType() == EconomicEventType.SWAP_BUY) {
            tryAddSwapSellFromNativeValue(tx, walletAddress, transfers);
        }
        if (transfers.size() == 1 && transfers.get(0).getEventType() == EconomicEventType.SWAP_SELL) {
            if (!tryAddSwapBuyFromInternalNative(tx, walletAddress, transfers)) {
                downgradeOneLegSwapSellToExternalTransferOut(transfers.getFirst());
            }
        }
        return transfers;
    }

    /**
     * When wallet has one SWAP_SELL but no SWAP_BUY (e.g. aggregator sends native ETH, not WETH Transfer),
     * parse the Swap log (Velora/Uniswap V3 fees: amount0, amount1 int256) and emit synthetic SWAP_BUY.
     */
    private void tryAddSwapBuyFromSwapLog(RawTransactionNormalizationView tx, List<Document> logs, String walletAddress,
                                           RawClassifiedEvent sellEvent, List<RawClassifiedEvent> out) {
        Document swapLog = null;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics != null && !topics.isEmpty() && SWAP_TOPIC_V3_FEES.equalsIgnoreCase(topics.get(0))) {
                swapLog = log;
                break;
            }
        }
        if (swapLog == null) return;
        String data = tx.getLogData(swapLog);
        if (data == null || data.length() < 2 + 128) return;
        BigInteger amount0 = parseSignedHex(data.substring(2, 2 + 64));
        BigInteger amount1 = parseSignedHex(data.substring(2 + 64, 2 + 128));
        if (amount0 == null || amount1 == null) return;
        String sellContract = sellEvent.getAssetContract();
        if (sellContract == null) return;
        BigDecimal sellQty = sellEvent.getQuantityDelta() == null ? null : sellEvent.getQuantityDelta().abs();
        if (sellQty == null || sellQty.signum() == 0) return;
        TokenMetaFromSources sellMeta = resolveTokenMeta(tx, sellContract);
        int sellDecimals = sellMeta.decimals();
        BigInteger sellRaw = sellQty.multiply(BigDecimal.TEN.pow(sellDecimals)).toBigInteger();
        BigInteger buyRaw;
        if (amount0.abs().equals(sellRaw)) {
            buyRaw = amount1.abs();
        } else if (amount1.abs().equals(sellRaw)) {
            buyRaw = amount0.abs();
        } else {
            return;
        }
        if (buyRaw.signum() == 0) return;
        Set<String> tokenAddresses = new LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) continue;
            String addr = tx.getLogAddress(log);
            if (addr != null) tokenAddresses.add(addr);
        }
        tokenAddresses.remove(sellContract);
        if (tokenAddresses.size() != 1) return;
        String buyContract = tokenAddresses.iterator().next();
        TokenMetaFromSources buyMeta = resolveTokenMeta(tx, buyContract);
        int buyDecimals = buyMeta.decimals();
        BigDecimal buyQty = new BigDecimal(buyRaw).divide(BigDecimal.TEN.pow(buyDecimals), 18, RoundingMode.HALF_UP);
        String symbol = buyMeta.symbol();
        RawClassifiedEvent buy = new RawClassifiedEvent();
        buy.setEventType(EconomicEventType.SWAP_BUY);
        buy.setWalletAddress(walletAddress);
        buy.setAssetContract(buyContract);
        buy.setAssetSymbol(symbol != null ? symbol : "");
        buy.setQuantityDelta(buyQty);
        buy.setProtocolName(protocolRegistry.getProtocolName(buyContract).orElse(null));
        buy.setLogIndex(tx.getLogIndex(swapLog));
        out.add(buy);
    }

    private void tryAddSwapSellFromNativeValue(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<RawClassifiedEvent> out
    ) {
        String wallet = tx.normalizeAddressValue(walletAddress);
        String from = tx.readRawOrExplorerAddress("from");
        if (wallet == null || from == null || !wallet.equals(from)) {
            return;
        }
        BigInteger txValue = tx.readRawOrExplorerUnsigned("value");
        if (txValue == null || txValue.signum() <= 0) {
            return;
        }
        BigDecimal quantity = new BigDecimal(txValue).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);
        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());

        RawClassifiedEvent sell = new RawClassifiedEvent();
        sell.setEventType(EconomicEventType.SWAP_SELL);
        sell.setWalletAddress(walletAddress);
        sell.setAssetContract(nativeAsset.contract());
        sell.setAssetSymbol(nativeAsset.symbol());
        sell.setQuantityDelta(quantity.negate());
        out.add(sell);
    }

    private boolean tryAddSwapBuyFromInternalNative(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<RawClassifiedEvent> out
    ) {
        BigDecimal inbound = nativeInboundFromInternalTransfers(tx, walletAddress);
        if (inbound.signum() <= 0) {
            return false;
        }
        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());
        RawClassifiedEvent buy = new RawClassifiedEvent();
        buy.setEventType(EconomicEventType.SWAP_BUY);
        buy.setWalletAddress(walletAddress);
        buy.setAssetContract(nativeAsset.contract());
        buy.setAssetSymbol(nativeAsset.symbol());
        buy.setQuantityDelta(inbound);
        out.add(buy);
        return true;
    }

    private static void downgradeOneLegSwapSellToExternalTransferOut(RawClassifiedEvent sellEvent) {
        if (sellEvent == null || sellEvent.getEventType() != EconomicEventType.SWAP_SELL) {
            return;
        }
        sellEvent.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
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
            default -> new NativeAsset("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ETH");
        };
    }

    private static BigInteger parseSignedHex(String hex) {
        if (hex == null || hex.length() < 2) return null;
        try {
            BigInteger raw = new BigInteger(hex, 16);
            if (hex.length() >= 64 && raw.testBit(255)) {
                return raw.subtract(BigInteger.TWO.pow(256));
            }
            return raw;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TokenMetaFromSources resolveTokenMeta(RawTransactionNormalizationView tx, String tokenAddress) {
        int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
        String symbol = evmTokenDecimalsResolver.getSymbol(tx.networkId(), tokenAddress);

        if (decimals != EvmTokenDecimalsResolver.DEFAULT_DECIMALS && symbol != null && !symbol.isBlank()) {
            return new TokenMetaFromSources(decimals, symbol);
        }

        ExplorerTokenMeta fallback = findExplorerTokenMeta(tx, tokenAddress);
        if (fallback != null) {
            if (decimals == EvmTokenDecimalsResolver.DEFAULT_DECIMALS && fallback.decimals() != null) {
                decimals = fallback.decimals();
            }
            if ((symbol == null || symbol.isBlank()) && fallback.symbol() != null && !fallback.symbol().isBlank()) {
                symbol = fallback.symbol();
            }
        }
        return new TokenMetaFromSources(decimals, symbol != null ? symbol : "");
    }

    private ExplorerTokenMeta findExplorerTokenMeta(RawTransactionNormalizationView tx, String tokenAddress) {
        if (tx == null || !tx.hasRawData() || tokenAddress == null) {
            return null;
        }
        List<Document> transfers = tx.explorerTokenTransfers();
        if (transfers == null || transfers.isEmpty()) {
            return null;
        }
        String normalizedContract = tx.normalizeAddressValue(tokenAddress);
        for (Document transfer : transfers) {
            if (transfer == null) {
                continue;
            }
            String transferContract = tx.tokenTransferContract(transfer);
            if (!normalizedContract.equals(transferContract)) {
                continue;
            }
            Integer decimals = tx.tokenTransferDecimals(transfer);
            String symbol = tx.tokenTransferSymbol(transfer);
            return new ExplorerTokenMeta(decimals, symbol);
        }
        return null;
    }

    private record TokenMetaFromSources(int decimals, String symbol) {}

    private record ExplorerTokenMeta(Integer decimals, String symbol) {}
    private record NativeAsset(String contract, String symbol) {}
}
