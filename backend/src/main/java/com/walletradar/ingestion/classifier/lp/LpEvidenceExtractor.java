package com.walletradar.ingestion.classifier.lp;

import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.classifier.RawTransactionNormalizationView;
import com.walletradar.ingestion.classifier.TransferClassifier;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LpEvidenceExtractor {

    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);
    private static final Set<String> LP_POSITION_ID_INPUT_SELECTORS = Set.of(
            "0x00f714ce",
            "0x18fccc76",
            "0x219f5d17",
            "0x0c49ccbe",
            "0xfc6f7865",
            "0x42842e0e"
    );
    private static final Set<String> ENTRY_SELECTOR_HINTS = Set.of("0x88316456", "0x219f5d17");

    private final LpProtocolRegistry lpProtocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    public LpEvidenceExtractor(LpProtocolRegistry lpProtocolRegistry, EvmTokenDecimalsResolver evmTokenDecimalsResolver) {
        this.lpProtocolRegistry = lpProtocolRegistry;
        this.evmTokenDecimalsResolver = evmTokenDecimalsResolver;
    }

    public FlowSummary collectFlowSummary(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> outflows = new LinkedHashMap<>();
        Map<String, BigDecimal> inflows = new LinkedHashMap<>();
        Map<String, Integer> outflowLogIndex = new LinkedHashMap<>();
        Map<String, Integer> inflowLogIndex = new LinkedHashMap<>();
        Set<String> mintToWallet = new LinkedHashSet<>();
        Set<String> burnFromWallet = new LinkedHashSet<>();
        Map<String, TokenMeta> metaByContract = tokenMetaByContract(tx);

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() < 3 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            BigInteger amount = tx.getLogAmount(log);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
            BigDecimal quantity = new BigDecimal(amount).divide(BigDecimal.TEN.pow(decimals), 18, RoundingMode.HALF_UP);
            Integer logIndex = tx.getLogIndex(log);
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);

            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                outflows.merge(tokenAddress, quantity.negate(), BigDecimal::add);
                outflowLogIndex.putIfAbsent(tokenAddress, logIndex);
                if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                    burnFromWallet.add(tokenAddress);
                }
            } else if (walletTopic.equalsIgnoreCase(toTopic)) {
                inflows.merge(tokenAddress, quantity, BigDecimal::add);
                inflowLogIndex.putIfAbsent(tokenAddress, logIndex);
                if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic)) {
                    mintToWallet.add(tokenAddress);
                }
            }
        }

        return new FlowSummary(outflows, inflows, outflowLogIndex, inflowLogIndex, mintToWallet, burnFromWallet, metaByContract);
    }

    public String resolvePositionId(RawTransactionNormalizationView tx, List<Document> logs) {
        String fromLogs = resolvePositionIdFromLogs(tx, logs);
        if (fromLogs != null) {
            return fromLogs;
        }
        return resolvePositionIdFromInput(tx);
    }

    public boolean hasMintedPositionNftToWallet(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        for (Document log : logs) {
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (!lpProtocolRegistry.isKnownPositionManager(address)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))
                    && walletTopic.equalsIgnoreCase(topics.get(2))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasLpPositionNftTransferLog(RawTransactionNormalizationView tx, List<Document> logs) {
        for (Document log : logs) {
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (!lpProtocolRegistry.isKnownPositionManager(address)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() >= 4 && TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasWalletOutboundErc20(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || topics.size() >= 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (walletTopic.equalsIgnoreCase(topics.get(1)) && !LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasWalletInboundErc20(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || topics.size() >= 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (walletTopic.equalsIgnoreCase(topics.get(2))
                    && !walletTopic.equalsIgnoreCase(topics.get(1))
                    && !LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasEntryLifecycleEvidence(RawTransactionNormalizationView tx, List<Document> logs) {
        if (logs != null) {
            for (Document log : logs) {
                List<String> topics = tx.getLogTopics(log);
                if (!topics.isEmpty() && lpProtocolRegistry.isKnownLpLifecycleTopic(topics.get(0))) {
                    return true;
                }
            }
        }
        String functionName = tx.readRawOrExplorerLower("functionName");
        if (functionName != null && (functionName.contains("mint") || functionName.contains("increaseliquidity") || functionName.contains("modifyliquidities"))) {
            return true;
        }
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String selector : ENTRY_SELECTOR_HINTS) {
            if (inputContainsSelector(input, selector)) {
                return true;
            }
        }
        return false;
    }

    public boolean isManagerInboundErc20Transfer(RawTransactionNormalizationView tx, Document log, String managerTopic) {
        List<String> topics = tx.getLogTopics(log);
        if (topics == null || topics.size() < 3 || topics.size() >= 4) {
            return false;
        }
        if (!TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
            return false;
        }
        return managerTopic != null && managerTopic.equalsIgnoreCase(topics.get(2));
    }

    public boolean isFinalLpExitByBurn(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs, String positionId) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String positionTopic = positionId != null ? "0x" + String.format("%064x", new BigInteger(positionId)) : null;
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String nftContract = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (!lpProtocolRegistry.isKnownPositionManager(nftContract)) {
                continue;
            }
            if (!LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            boolean isWalletSource = walletTopic.equalsIgnoreCase(topics.get(1));
            boolean isSamePosition = positionTopic != null && positionTopic.equalsIgnoreCase(topics.get(3));
            if (isWalletSource || isSamePosition) {
                return true;
            }
        }
        return false;
    }

    public WrappedNativeEvidence detectNativeEntryEvidence(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs,
            String txTo,
            String wrappedContract,
            boolean allowTxValueFallback
    ) {
        String managerTopic = txTo != null ? tx.padAddressForTopic(txTo) : null;
        if (managerTopic != null) {
            for (Document log : logs) {
                String address = tx.normalizeAddressValue(tx.getLogAddress(log));
                if (address == null || !address.equalsIgnoreCase(wrappedContract)) {
                    continue;
                }
                List<String> topics = tx.getLogTopics(log);
                if (topics.size() >= 2
                        && LpProtocolRegistry.DEPOSIT_TOPIC.equalsIgnoreCase(topics.get(0))
                        && managerTopic.equalsIgnoreCase(topics.get(1))) {
                    BigInteger amount = tx.getLogAmount(log);
                    if (amount != null && amount.signum() > 0) {
                        return new WrappedNativeEvidence(amount, tx.getLogIndex(log));
                    }
                }
            }
        }

        BigInteger netWei = positiveNetNativeValue(tx, walletAddress);
        if (!allowTxValueFallback || netWei.signum() <= 0) {
            return null;
        }
        Integer logIndex = managerWrappedNativeOutflowLogIndex(tx, walletAddress, logs, txTo, wrappedContract);
        if (logIndex == null) {
            logIndex = firstKnownLpEvidenceLogIndex(tx, logs);
        }
        return new WrappedNativeEvidence(netWei, logIndex);
    }

    public NativePayoutEvidence detectNativePayoutEvidence(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        BigInteger amountWei = inboundInternalNativeToWallet(tx, walletAddress);
        if (amountWei.signum() <= 0) {
            return null;
        }
        Integer maxLogIndex = maxLogIndex(logs);
        Integer logIndex = maxLogIndex != null ? maxLogIndex + 1 : null;
        return new NativePayoutEvidence(amountWei, logIndex);
    }

    public BigInteger positiveNetNativeValue(RawTransactionNormalizationView tx, String walletAddress) {
        BigInteger txValue = tx.readRawOrExplorerUnsigned("value");
        if (txValue == null || txValue.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger netWei = txValue.subtract(nativeRefundToWallet(tx, walletAddress));
        return netWei.signum() > 0 ? netWei : BigInteger.ZERO;
    }

    public BigInteger inboundInternalNativeToWallet(RawTransactionNormalizationView tx, String walletAddress) {
        String wallet = tx.normalizeAddressValue(walletAddress);
        if (wallet == null) {
            return BigInteger.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (Document transfer : tx.explorerInternalTransfers()) {
            if ("1".equals(tx.internalTransferIsError(transfer))) {
                continue;
            }
            String from = tx.internalTransferFrom(transfer);
            String to = tx.internalTransferTo(transfer);
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            if (!wallet.equals(to) || wallet.equals(from)) {
                continue;
            }
            BigInteger value = tx.internalTransferValue(transfer);
            if (value != null && value.signum() > 0) {
                total = total.add(value);
            }
        }
        return total;
    }

    private BigInteger nativeRefundToWallet(RawTransactionNormalizationView tx, String walletAddress) {
        return inboundInternalNativeToWallet(tx, walletAddress);
    }

    private Integer managerWrappedNativeOutflowLogIndex(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs,
            String txTo,
            String wrappedContract
    ) {
        String managerTopic = txTo != null ? tx.padAddressForTopic(txTo) : null;
        String walletTopic = tx.padAddressForTopic(walletAddress);
        if (managerTopic == null) {
            return null;
        }
        for (Document log : logs) {
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (address == null || !address.equalsIgnoreCase(wrappedContract)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || topics.size() >= 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (managerTopic.equalsIgnoreCase(topics.get(1))
                    && !walletTopic.equalsIgnoreCase(topics.get(2))
                    && !LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                return tx.getLogIndex(log);
            }
        }
        return null;
    }

    private Integer firstKnownLpEvidenceLogIndex(RawTransactionNormalizationView tx, List<Document> logs) {
        Integer first = null;
        for (Document log : logs) {
            Integer logIndex = tx.getLogIndex(log);
            if (logIndex == null) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            boolean interesting = lpProtocolRegistry.isKnownPositionManager(address);
            if (!interesting && !topics.isEmpty()) {
                interesting = lpProtocolRegistry.isKnownLpLifecycleTopic(topics.get(0));
            }
            if (!interesting) {
                continue;
            }
            if (first == null || logIndex < first) {
                first = logIndex;
            }
        }
        return first;
    }

    private Integer maxLogIndex(List<Document> logs) {
        Integer max = null;
        for (Document log : logs) {
            Object rawLogIndex = log.get("logIndex");
            if (rawLogIndex == null) {
                continue;
            }
            Integer parsed = null;
            try {
                if (rawLogIndex instanceof Number number) {
                    parsed = number.intValue();
                } else {
                    String text = rawLogIndex.toString().trim();
                    parsed = text.startsWith("0x") || text.startsWith("0X")
                            ? Integer.parseInt(text.substring(2), 16)
                            : Integer.parseInt(text);
                }
            } catch (NumberFormatException ignored) {
                parsed = null;
            }
            if (parsed != null && (max == null || parsed > max)) {
                max = parsed;
            }
        }
        return max;
    }

    private Map<String, TokenMeta> tokenMetaByContract(RawTransactionNormalizationView tx) {
        Map<String, TokenMeta> out = new LinkedHashMap<>();
        for (Document transfer : tx.explorerTokenTransfers()) {
            String contract = tx.tokenTransferContract(transfer);
            if (contract == null) {
                continue;
            }
            String symbol = tx.normalizeTextValue(tx.tokenTransferSymbol(transfer));
            String name = tx.normalizeTextValue(tx.tokenTransferName(transfer));
            if (symbol != null || name != null) {
                out.putIfAbsent(contract, new TokenMeta(symbol, name));
            }
        }
        return out;
    }

    private String resolvePositionIdFromLogs(RawTransactionNormalizationView tx, List<Document> logs) {
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Document log : logs) {
            String address = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (!lpProtocolRegistry.isKnownPositionManager(address)) {
                continue;
            }
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 4) {
                continue;
            }
            String parsed = parsePositionIdFromTopic(topics.get(3));
            if (parsed != null) {
                ids.add(parsed);
            }
        }
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    private String resolvePositionIdFromInput(RawTransactionNormalizationView tx) {
        String input = tx.readRawOrExplorerLower("input");
        if (input == null || input.length() < 10) {
            return null;
        }
        String selector = input.substring(0, 10);
        if (LP_POSITION_ID_INPUT_SELECTORS.contains(selector)) {
            return parsePositionIdForSelector(input, selector, 10);
        }
        if (!"0xac9650d8".equals(selector)) {
            return null;
        }
        String multicallHex = strip0x(input);
        Set<String> candidateIds = new LinkedHashSet<>();
        for (String nestedSelector : LP_POSITION_ID_INPUT_SELECTORS) {
            String selectorHex = strip0x(nestedSelector);
            int fromIndex = 0;
            while (true) {
                int idx = multicallHex.indexOf(selectorHex, fromIndex);
                if (idx < 0) {
                    break;
                }
                if (idx % 2 == 0) {
                    String candidate = parsePositionIdForSelector(multicallHex, "0x" + selectorHex, idx + selectorHex.length());
                    if (candidate != null) {
                        candidateIds.add(candidate);
                    }
                }
                fromIndex = idx + selectorHex.length();
            }
        }
        return candidateIds.size() == 1 ? candidateIds.iterator().next() : null;
    }

    private static String parsePositionIdForSelector(String hexInput, String selector, int argsStart) {
        int offset = "0x42842e0e".equalsIgnoreCase(selector) ? 64 * 2 : 0;
        return parsePositionIdFromWord(hexInput, argsStart + offset);
    }

    private static String parsePositionIdFromTopic(String topic) {
        return parseHexToUnsignedDecimal(topic);
    }

    private static String parsePositionIdFromWord(String hexInput, int wordStart) {
        if (hexInput == null || wordStart < 0) {
            return null;
        }
        int adjustedWordStart = hexInput.startsWith("0x") ? wordStart - 2 : wordStart;
        if (adjustedWordStart < 0) {
            return null;
        }
        String hex = strip0x(hexInput);
        if (hex == null || adjustedWordStart + 64 > hex.length()) {
            return null;
        }
        return parseHexToUnsignedDecimal(hex.substring(adjustedWordStart, adjustedWordStart + 64));
    }

    private static String parseHexToUnsignedDecimal(String hexValue) {
        String hex = strip0x(hexValue);
        if (hex == null || hex.isBlank() || !hex.matches("[0-9a-f]+")) {
            return null;
        }
        try {
            BigInteger value = new BigInteger(hex, 16);
            return value.signum() > 0 ? value.toString() : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String strip0x(String value) {
        if (value == null) {
            return null;
        }
        return value.startsWith("0x") ? value.substring(2) : value;
    }

    private static boolean inputContainsSelector(String input, String selector) {
        if (input == null || selector == null || selector.isBlank()) {
            return false;
        }
        String normalizedSelector = selector.toLowerCase(Locale.ROOT);
        if (input.contains(normalizedSelector)) {
            return true;
        }
        if (normalizedSelector.startsWith("0x")) {
            return input.contains(normalizedSelector.substring(2));
        }
        return input.contains("0x" + normalizedSelector);
    }

    public record TokenMeta(String symbol, String name) {
    }

    public record FlowSummary(
            Map<String, BigDecimal> outflows,
            Map<String, BigDecimal> inflows,
            Map<String, Integer> outflowLogIndex,
            Map<String, Integer> inflowLogIndex,
            Set<String> mintToWallet,
            Set<String> burnFromWallet,
            Map<String, TokenMeta> metaByContract
    ) {
    }

    public record WrappedNativeEvidence(BigInteger amountWei, Integer logIndex) {
    }

    public record NativePayoutEvidence(BigInteger amountWei, Integer logIndex) {
    }
}
