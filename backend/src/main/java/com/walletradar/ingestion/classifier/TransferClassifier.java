package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.adapter.evm.EvmTokenDecimalsResolver;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies ERC20 Transfer logs. Emits EXTERNAL_TRANSFER_OUT for sends, EXTERNAL_INBOUND for receives.
 * InternalTransferDetector later reclassifies to INTERNAL_TRANSFER when counterparty is in session.
 * Uses token decimals (e.g. WBTC=8, USDC=6) so quantityDelta is correct.
 */
@Component
@RequiredArgsConstructor
public class TransferClassifier implements TxClassifier {

    /** ERC20 Transfer(address,address,uint256) topic. */
    public static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    /** Uniswap V2 Swap(address,uint256,uint256,uint256,uint256) */
    private static final String SWAP_TOPIC_V2 = "0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822";
    /** Uniswap V3 Swap(address,address,int256,int256,uint160,uint128,int24) */
    private static final String SWAP_TOPIC_V3 = "0xc42079f94a6350d7e6235f29174924f928cc2ac818eb64fed8004e115fbcca67";

    private static boolean hasSwapTopic(List<String> topics) {
        if (topics == null || topics.isEmpty()) return false;
        String t0 = topics.get(0);
        return SWAP_TOPIC_V2.equalsIgnoreCase(t0) || SWAP_TOPIC_V3.equalsIgnoreCase(t0);
    }

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @Override
    public List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress) {
        List<RawClassifiedEvent> out = new ArrayList<>();
        if (tx.getRawData() == null || !tx.getRawData().containsKey("logs")) {
            return out;
        }
        Object logsObj = tx.getRawData().get("logs");
        if (!(logsObj instanceof List)) {
            return out;
        }
        @SuppressWarnings("unchecked")
        List<Document> logs = (List<Document>) logsObj;
        // Skip if this tx is a DEX swap (SwapClassifier handles it)
        boolean hasSwap = logs.stream()
                .map(log -> log.getList("topics", String.class))
                .filter(t -> t != null && !t.isEmpty())
                .anyMatch(TransferClassifier::hasSwapTopic);
        if (hasSwap) {
            return out;
        }
        String walletTopic = padAddressForTopic(walletAddress);
        for (Document log : logs) {
            List<String> topics = log.getList("topics", String.class);
            if (topics == null || topics.size() < 3) {
                continue;
            }
            if (!TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            String tokenAddress = log.getString("address");
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = parseAmount(log.getString("data"));
            if (amount == null) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.getNetworkId(), tokenAddress);
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            String symbol = evmTokenDecimalsResolver.getSymbol(tx.getNetworkId(), tokenAddress);
            String protocolName = protocolRegistry.getProtocolName(tokenAddress).orElse(null);
            Integer logIndex = parseLogIndex(log);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                RawClassifiedEvent e = new RawClassifiedEvent();
                e.setEventType(EconomicEventType.EXTERNAL_TRANSFER_OUT);
                e.setWalletAddress(walletAddress);
                e.setAssetContract(tokenAddress);
                e.setAssetSymbol(symbol != null ? symbol : "");
                e.setQuantityDelta(quantity.negate());
                e.setCounterpartyAddress(topicToAddress(topics.get(2)));
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
                e.setCounterpartyAddress(topicToAddress(topics.get(1)));
                e.setProtocolName(protocolName);
                e.setLogIndex(logIndex);
                out.add(e);
            }
        }
        return out;
    }

    private static String padAddressForTopic(String address) {
        if (address == null) return "";
        String hex = address.toLowerCase().startsWith("0x") ? address.substring(2) : address;
        return "0x" + "0".repeat(24) + hex.toLowerCase();
    }

    private static String topicToAddress(String topic) {
        if (topic == null || topic.length() < 66) return "";
        return "0x" + topic.substring(topic.length() - 40);
    }

    private static BigInteger parseAmount(String data) {
        if (data == null || data.length() < 2) return null;
        try {
            return new BigInteger(data.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseLogIndex(Document log) {
        Object li = log.get("logIndex");
        if (li == null) return null;
        try {
            if (li instanceof String s) {
                if (s.startsWith("0x") || s.startsWith("0X")) {
                    return Integer.parseInt(s.substring(2), 16);
                }
                return Integer.parseInt(s, 10);
            }
            if (li instanceof Number n) {
                return n.intValue();
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}
