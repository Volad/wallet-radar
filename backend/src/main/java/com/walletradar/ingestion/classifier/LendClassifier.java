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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies lending protocol events (deposit/withdrawal vault patterns) from token transfer logs.
 */
@Component
@Order(90)
@RequiredArgsConstructor
public class LendClassifier implements TxClassifier {

    private static final String TRANSFER_TOPIC = TransferClassifier.TRANSFER_TOPIC;
    private static final String ZERO_ADDRESS_TOPIC = "0x0000000000000000000000000000000000000000000000000000000000000000";
    private static final Set<String> KNOWN_MULTICALL_METHOD_IDS = Set.of("0x374f435d");
    private static final Set<String> KNOWN_DEPOSIT_METHOD_IDS = Set.of("0x6e553f65", "0x617ba037");
    private static final Set<String> KNOWN_WITHDRAW_METHOD_IDS = Set.of("0xba087652", "0x69328dec");
    private static final Set<String> KNOWN_BORROW_METHOD_IDS = Set.of("0xa415bcad");
    private static final Set<String> KNOWN_REPAY_METHOD_IDS = Set.of("0x573ade81");

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || !tx.hasRawData()) {
            return List.of();
        }
        List<Document> logs = tx.logs();
        if (logs.isEmpty()) {
            return List.of();
        }
        if (isLikelyVaultDepositPattern(tx, walletAddress, logs)) {
            return classifyVaultDeposit(tx, walletAddress, logs);
        }
        if (isLikelyVaultWithdrawalPattern(tx, walletAddress, logs)) {
            return classifyVaultWithdrawal(tx, walletAddress, logs);
        }
        if (isLikelyBorrowPattern(tx, walletAddress, logs)) {
            return classifyBorrow(tx, walletAddress, logs);
        }
        if (isLikelyRepayPattern(tx, walletAddress, logs)) {
            return classifyRepay(tx, walletAddress, logs);
        }
        return List.of();
    }

    private List<RawClassifiedEvent> classifyVaultDeposit(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> outflowByContract = new LinkedHashMap<>();
        Map<String, Integer> outflowLogIndexByContract = new LinkedHashMap<>();
        Map<String, BigDecimal> mintInboundByContract = new LinkedHashMap<>();
        Map<String, Integer> mintInboundLogIndexByContract = new LinkedHashMap<>();

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
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
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            Integer logIndex = tx.getLogIndex(log);

            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                outflowByContract.merge(tokenAddress, quantity.negate(), BigDecimal::add);
                outflowLogIndexByContract.putIfAbsent(tokenAddress, logIndex);
            } else if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                mintInboundByContract.merge(tokenAddress, quantity, BigDecimal::add);
                mintInboundLogIndexByContract.putIfAbsent(tokenAddress, logIndex);
            }
        }

        if (outflowByContract.size() != 1 || mintInboundByContract.size() != 1) {
            return List.of();
        }

        String underlyingContract = outflowByContract.keySet().iterator().next();
        String receiptContract = mintInboundByContract.keySet().iterator().next();
        if (underlyingContract.equalsIgnoreCase(receiptContract)) {
            return List.of();
        }

        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.LEND_DEPOSIT);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(underlyingContract);
        out.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        out.setQuantityDelta(outflowByContract.get(underlyingContract));
        out.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        out.setLogIndex(outflowLogIndexByContract.get(underlyingContract));

        RawClassifiedEvent in = new RawClassifiedEvent();
        in.setEventType(EconomicEventType.LEND_DEPOSIT);
        in.setWalletAddress(walletAddress);
        in.setAssetContract(receiptContract);
        in.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), receiptContract));
        in.setQuantityDelta(mintInboundByContract.get(receiptContract));
        in.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        in.setLogIndex(mintInboundLogIndexByContract.get(receiptContract));

        return List.of(out, in);
    }

    private List<RawClassifiedEvent> classifyBorrow(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> inboundByContract = new LinkedHashMap<>();
        Map<String, Integer> inboundLogIndexByContract = new LinkedHashMap<>();
        Set<String> mintContracts = new LinkedHashSet<>();

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
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
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            Integer logIndex = tx.getLogIndex(log);

            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                mintContracts.add(tokenAddress);
            } else if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                inboundByContract.merge(tokenAddress, quantity, BigDecimal::add);
                inboundLogIndexByContract.putIfAbsent(tokenAddress, logIndex);
            }
        }

        String underlyingContract = selectBorrowUnderlyingContract(mintContracts, inboundByContract.keySet());
        if (underlyingContract == null) {
            return List.of();
        }
        BigDecimal quantity = inboundByContract.get(underlyingContract);
        if (quantity == null || quantity.signum() <= 0) {
            return List.of();
        }

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(EconomicEventType.BORROW);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(underlyingContract);
        event.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        event.setQuantityDelta(quantity);
        event.setProtocolName(protocolRegistry.getProtocolName(tx.readRawOrExplorerAddress("to")).orElse(null));
        event.setLogIndex(inboundLogIndexByContract.get(underlyingContract));
        return List.of(event);
    }

    private List<RawClassifiedEvent> classifyRepay(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> outflowByContract = new LinkedHashMap<>();
        Map<String, Integer> outflowLogIndexByContract = new LinkedHashMap<>();
        Set<String> burnContracts = new LinkedHashSet<>();

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
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
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            Integer logIndex = tx.getLogIndex(log);

            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic) && ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                burnContracts.add(tokenAddress);
            } else if (walletTopic.equalsIgnoreCase(fromTopic) && !ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                outflowByContract.merge(tokenAddress, quantity.negate(), BigDecimal::add);
                outflowLogIndexByContract.putIfAbsent(tokenAddress, logIndex);
            }
        }

        String underlyingContract = selectRepayUnderlyingContract(burnContracts, outflowByContract.keySet());
        if (underlyingContract == null) {
            return List.of();
        }
        BigDecimal quantity = outflowByContract.get(underlyingContract);
        if (quantity == null || quantity.signum() >= 0) {
            return List.of();
        }

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(EconomicEventType.REPAY);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(underlyingContract);
        event.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        event.setQuantityDelta(quantity);
        event.setProtocolName(protocolRegistry.getProtocolName(tx.readRawOrExplorerAddress("to")).orElse(null));
        event.setLogIndex(outflowLogIndexByContract.get(underlyingContract));
        return List.of(event);
    }

    private List<RawClassifiedEvent> classifyVaultWithdrawal(
            RawTransactionNormalizationView tx, String walletAddress, List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> burnOutByContract = new LinkedHashMap<>();
        Map<String, Integer> burnOutLogIndexByContract = new LinkedHashMap<>();
        Map<String, BigDecimal> underlyingInboundByContract = new LinkedHashMap<>();
        Map<String, Integer> underlyingInboundLogIndexByContract = new LinkedHashMap<>();

        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
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
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal quantity = new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
            Integer logIndex = tx.getLogIndex(log);

            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic) && ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                burnOutByContract.merge(tokenAddress, quantity.negate(), BigDecimal::add);
                burnOutLogIndexByContract.putIfAbsent(tokenAddress, logIndex);
            } else if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                underlyingInboundByContract.merge(tokenAddress, quantity, BigDecimal::add);
                underlyingInboundLogIndexByContract.putIfAbsent(tokenAddress, logIndex);
            }
        }

        if (burnOutByContract.size() != 1 || underlyingInboundByContract.size() != 1) {
            return List.of();
        }
        String receiptContract = burnOutByContract.keySet().iterator().next();
        String underlyingContract = underlyingInboundByContract.keySet().iterator().next();
        if (receiptContract.equalsIgnoreCase(underlyingContract)) {
            return List.of();
        }

        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(receiptContract);
        out.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), receiptContract));
        out.setQuantityDelta(burnOutByContract.get(receiptContract));
        out.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        out.setLogIndex(burnOutLogIndexByContract.get(receiptContract));

        RawClassifiedEvent in = new RawClassifiedEvent();
        in.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        in.setWalletAddress(walletAddress);
        in.setAssetContract(underlyingContract);
        in.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        in.setQuantityDelta(underlyingInboundByContract.get(underlyingContract));
        in.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        in.setLogIndex(underlyingInboundLogIndexByContract.get(underlyingContract));

        return List.of(out, in);
    }

    static boolean isLikelyVaultDepositPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        String txTo = tx.readRawOrExplorerAddress("to");
        String walletTopic = tx.padAddressForTopic(walletAddress);

        Set<String> outboundContracts = new java.util.LinkedHashSet<>();
        Set<String> mintInboundContracts = new java.util.LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic)) {
                outboundContracts.add(tokenAddress);
            } else if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                mintInboundContracts.add(tokenAddress);
            }
        }
        if (outboundContracts.size() != 1 || mintInboundContracts.size() != 1) {
            return false;
        }
        String underlying = outboundContracts.iterator().next();
        String receipt = mintInboundContracts.iterator().next();
        if (underlying.equalsIgnoreCase(receipt)) {
            return false;
        }
        if (txTo != null && receipt.equalsIgnoreCase(txTo)) {
            return true;
        }
        return isVaultLikeFunctionContext(tx, true);
    }

    static boolean isLikelyVaultWithdrawalPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        String txTo = tx.readRawOrExplorerAddress("to");
        String walletTopic = tx.padAddressForTopic(walletAddress);

        Set<String> burnContracts = new java.util.LinkedHashSet<>();
        Set<String> inboundContracts = new java.util.LinkedHashSet<>();
        Set<String> inboundFromContracts = new java.util.LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic) && ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                burnContracts.add(tokenAddress);
            } else if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                inboundContracts.add(tokenAddress);
                String fromAddress = tx.topicToAddress(fromTopic);
                if (fromAddress != null) {
                    inboundFromContracts.add(fromAddress);
                }
            }
        }
        if (burnContracts.size() != 1 || inboundContracts.size() != 1) {
            return false;
        }
        String receipt = burnContracts.iterator().next();
        String underlying = inboundContracts.iterator().next();
        if (receipt.equalsIgnoreCase(underlying)) {
            return false;
        }
        // Strong vault signal for batched calls: receipt/share token is burned and vault sends underlying to wallet.
        if (inboundFromContracts.contains(receipt)) {
            return true;
        }
        if (txTo != null && receipt.equalsIgnoreCase(txTo)) {
            return true;
        }
        return isVaultLikeFunctionContext(tx, false);
    }

    static boolean isLikelyBorrowPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        if (!isBorrowLikeFunctionContext(tx)) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Set<String> mintContracts = new LinkedHashSet<>();
        Set<String> inboundContracts = new LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                mintContracts.add(tokenAddress);
            } else if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) && walletTopic.equalsIgnoreCase(toTopic)) {
                inboundContracts.add(tokenAddress);
            }
        }
        return selectBorrowUnderlyingContract(mintContracts, inboundContracts) != null;
    }

    static boolean isLikelyRepayPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        if (tx == null || walletAddress == null || walletAddress.isBlank() || logs == null || logs.isEmpty()) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        if (wallet == null || txSender == null || !wallet.equals(txSender)) {
            return false;
        }
        if (!isRepayLikeFunctionContext(tx)) {
            return false;
        }
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Set<String> burnContracts = new LinkedHashSet<>();
        Set<String> outflowContracts = new LinkedHashSet<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null) {
                continue;
            }
            String fromTopic = topics.get(1);
            String toTopic = topics.get(2);
            if (walletTopic.equalsIgnoreCase(fromTopic) && ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                burnContracts.add(tokenAddress);
            } else if (walletTopic.equalsIgnoreCase(fromTopic) && !ZERO_ADDRESS_TOPIC.equalsIgnoreCase(toTopic)) {
                outflowContracts.add(tokenAddress);
            }
        }
        return selectRepayUnderlyingContract(burnContracts, outflowContracts) != null;
    }

    private static boolean isVaultLikeFunctionContext(RawTransactionNormalizationView tx, boolean depositFlow) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        String selector = tx.selector();
        if (functionName != null && functionName.contains("swap")) {
            return false;
        }
        if (selector != null && KNOWN_MULTICALL_METHOD_IDS.contains(selector)) {
            return true;
        }
        if (depositFlow && selector != null && KNOWN_DEPOSIT_METHOD_IDS.contains(selector)) {
            return true;
        }
        if (!depositFlow && selector != null && KNOWN_WITHDRAW_METHOD_IDS.contains(selector)) {
            return true;
        }
        if (functionName == null) {
            return false;
        }
        if (functionName.contains("multicall")) {
            return true;
        }
        if (depositFlow) {
            return functionName.contains("deposit") || functionName.contains("mint") || functionName.contains("supply");
        }
        return functionName.contains("redeem") || functionName.contains("withdraw");
    }

    private static boolean isBorrowLikeFunctionContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        String selector = tx.selector();
        if (selector != null && KNOWN_BORROW_METHOD_IDS.contains(selector)) {
            return true;
        }
        return functionName != null && functionName.contains("borrow");
    }

    private static boolean isRepayLikeFunctionContext(RawTransactionNormalizationView tx) {
        String functionName = tx.readRawOrExplorerLower("functionName");
        String selector = tx.selector();
        if (selector != null && KNOWN_REPAY_METHOD_IDS.contains(selector)) {
            return true;
        }
        return functionName != null && functionName.contains("repay");
    }

    private static String selectBorrowUnderlyingContract(Set<String> mintContracts, Set<String> inboundContracts) {
        if (mintContracts == null || mintContracts.isEmpty() || inboundContracts == null || inboundContracts.isEmpty()) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>(inboundContracts);
        candidates.removeAll(mintContracts);
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        if (inboundContracts.size() == 1) {
            return inboundContracts.iterator().next();
        }
        return null;
    }

    private static String selectRepayUnderlyingContract(Set<String> burnContracts, Set<String> outflowContracts) {
        if (burnContracts == null || burnContracts.isEmpty() || outflowContracts == null || outflowContracts.isEmpty()) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>(outflowContracts);
        candidates.removeAll(burnContracts);
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        if (outflowContracts.size() == 1) {
            return outflowContracts.iterator().next();
        }
        return null;
    }

}
