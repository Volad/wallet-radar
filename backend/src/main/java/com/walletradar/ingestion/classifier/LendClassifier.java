package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
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
import java.util.Locale;
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
    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);
    private static final Set<String> AAVE_NATIVE_GATEWAY_DEPOSIT_SELECTORS = Set.of("0x474cf53d");
    private static final Set<String> AAVE_NATIVE_GATEWAY_WITHDRAW_SELECTORS = Set.of("0x80500d20");
    private static final Set<String> KNOWN_MULTICALL_METHOD_IDS = Set.of(
            "0x374f435d",
            "0x5ae401dc",
            "0x1f0464d1"
    );
    private static final Set<String> KNOWN_DEPOSIT_METHOD_IDS = Set.of(
            "0x6e553f65",
            "0x617ba037",
            "0x02c205f0",
            "0x474cf53d"
    );
    private static final Set<String> KNOWN_WITHDRAW_METHOD_IDS = Set.of(
            "0xba087652",
            "0x69328dec",
            "0x80500d20"
    );
    private static final Set<String> KNOWN_ONE_LEG_WITHDRAW_METHOD_IDS = Set.of(
            "0x80500d20",
            "0xba087652",
            "0xb460af94",
            "0x69328dec"
    );
    private static final Set<String> KNOWN_BORROW_METHOD_IDS = Set.of(
            "0xa415bcad",
            "0xe74f7b85"
    );
    private static final Set<String> KNOWN_REPAY_METHOD_IDS = Set.of(
            "0x573ade81",
            "0x2dad97d4",
            "0xee3e210b",
            "0x02c5fcf8"
    );

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;
    private final IngestionNetworkProperties ingestionNetworkProperties;

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || !tx.hasRawData()) {
            return List.of();
        }
        List<Document> logs = tx.logs();
        if (logs.isEmpty() && tx.explorerTokenTransfers().isEmpty()) {
            return List.of();
        }
        if (isLikelyNativeGatewayDepositPattern(tx, walletAddress, logs)) {
            return classifyNativeGatewayDeposit(tx, walletAddress, logs);
        }
        if (isLikelyNativeGatewayWithdrawalPattern(tx, walletAddress, logs)) {
            return classifyNativeGatewayWithdrawal(tx, walletAddress, logs);
        }
        if (isLikelyOneLegLendDepositPattern(tx, walletAddress, logs)) {
            return classifyOneLegLendDeposit(tx, walletAddress, logs);
        }
        if (isLikelyVaultDepositPattern(tx, walletAddress, logs)) {
            return classifyVaultDeposit(tx, walletAddress, logs);
        }
        if (isLikelyVaultWithdrawalPattern(tx, walletAddress, logs)) {
            return classifyVaultWithdrawal(tx, walletAddress, logs);
        }
        if (isLikelyOneLegLendWithdrawalPattern(tx, walletAddress, logs)) {
            return classifyOneLegLendWithdrawal(tx, walletAddress, logs);
        }
        if (isLikelyBorrowPattern(tx, walletAddress, logs)) {
            return classifyBorrow(tx, walletAddress, logs);
        }
        if (isLikelyRepayPattern(tx, walletAddress, logs)) {
            return classifyRepay(tx, walletAddress, logs);
        }
        return List.of();
    }

    public boolean isLikelyLendPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
        return isLikelyNativeGatewayDepositPattern(tx, walletAddress, logs)
                || isLikelyNativeGatewayWithdrawalPattern(tx, walletAddress, logs)
                || isLikelyOneLegLendDepositPattern(tx, walletAddress, logs)
                || isLikelyVaultDepositPattern(tx, walletAddress, logs)
                || isLikelyVaultWithdrawalPattern(tx, walletAddress, logs)
                || isLikelyOneLegLendWithdrawalPattern(tx, walletAddress, logs)
                || isLikelyBorrowPattern(tx, walletAddress, logs)
                || isLikelyRepayPattern(tx, walletAddress, logs);
    }

    private boolean isLikelyNativeGatewayDepositPattern(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (!isWalletSender(tx, walletAddress)) {
            return false;
        }
        String selector = tx.selector();
        if (selector == null || !AAVE_NATIVE_GATEWAY_DEPOSIT_SELECTORS.contains(selector)) {
            return false;
        }
        BigInteger value = tx.readRawOrExplorerUnsigned("value");
        if (value == null || value.signum() <= 0) {
            return false;
        }
        if (hasWalletOutboundTokenTransfer(tx, walletAddress, logs)) {
            return false;
        }
        return !collectMintInboundByContract(tx, walletAddress, logs).isEmpty();
    }

    private List<RawClassifiedEvent> classifyNativeGatewayDeposit(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        Map<String, BigDecimal> mintInboundByContract = collectMintInboundByContract(tx, walletAddress, logs);
        Map<String, Integer> mintLogIndexByContract = collectMintInboundLogIndexByContract(tx, walletAddress, logs);
        String receiptContract = pickSingleContract(mintInboundByContract);
        if (receiptContract == null) {
            return List.of();
        }
        BigDecimal receiptIn = mintInboundByContract.get(receiptContract);
        if (receiptIn == null || receiptIn.signum() <= 0) {
            return List.of();
        }
        BigInteger value = tx.readRawOrExplorerUnsigned("value");
        if (value == null || value.signum() <= 0) {
            return List.of();
        }
        BigDecimal nativeOut = new BigDecimal(value).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP).negate();
        if (nativeOut.signum() >= 0) {
            return List.of();
        }
        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());
        String txTo = tx.readRawOrExplorerAddress("to");
        String protocol = protocolRegistry.getProtocolName(txTo).orElse(protocolRegistry.getProtocolName(receiptContract).orElse(null));

        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.LEND_DEPOSIT);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(nativeAsset.contract());
        out.setAssetSymbol(nativeAsset.symbol());
        out.setQuantityDelta(nativeOut);
        out.setProtocolName(protocol);

        RawClassifiedEvent in = new RawClassifiedEvent();
        in.setEventType(EconomicEventType.LEND_DEPOSIT);
        in.setWalletAddress(walletAddress);
        in.setAssetContract(receiptContract);
        in.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), receiptContract));
        in.setQuantityDelta(receiptIn);
        in.setProtocolName(protocol);
        in.setLogIndex(mintLogIndexByContract.get(receiptContract));
        return List.of(out, in);
    }

    private boolean isLikelyNativeGatewayWithdrawalPattern(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (!isWalletSender(tx, walletAddress)) {
            return false;
        }
        String selector = tx.selector();
        if (selector == null || !AAVE_NATIVE_GATEWAY_WITHDRAW_SELECTORS.contains(selector)) {
            return false;
        }
        BigInteger value = tx.readRawOrExplorerUnsigned("value");
        if (value != null && value.signum() > 0) {
            return false;
        }
        Map<String, BigDecimal> burnOutByContract = collectBurnOutByContract(tx, walletAddress, logs);
        if (burnOutByContract.isEmpty()) {
            return false;
        }
        BigDecimal nativeIn = nativeInboundFromInternalTransfers(tx, walletAddress);
        return nativeIn.signum() > 0;
    }

    private List<RawClassifiedEvent> classifyNativeGatewayWithdrawal(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        Map<String, BigDecimal> burnOutByContract = collectBurnOutByContract(tx, walletAddress, logs);
        Map<String, Integer> burnLogIndexByContract = collectBurnLogIndexByContract(tx, walletAddress, logs);
        String receiptContract = pickSingleContract(burnOutByContract);
        if (receiptContract == null) {
            return List.of();
        }
        BigDecimal receiptOut = burnOutByContract.get(receiptContract);
        if (receiptOut == null || receiptOut.signum() >= 0) {
            return List.of();
        }
        BigDecimal nativeIn = nativeInboundFromInternalTransfers(tx, walletAddress);
        if (nativeIn.signum() <= 0) {
            return List.of();
        }
        NativeAsset nativeAsset = nativeAssetOf(tx.networkId());
        String txTo = tx.readRawOrExplorerAddress("to");
        String protocol = protocolRegistry.getProtocolName(txTo).orElse(protocolRegistry.getProtocolName(receiptContract).orElse(null));

        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(receiptContract);
        out.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), receiptContract));
        out.setQuantityDelta(receiptOut);
        out.setProtocolName(protocol);
        out.setLogIndex(burnLogIndexByContract.get(receiptContract));

        RawClassifiedEvent in = new RawClassifiedEvent();
        in.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        in.setWalletAddress(walletAddress);
        in.setAssetContract(nativeAsset.contract());
        in.setAssetSymbol(nativeAsset.symbol());
        in.setQuantityDelta(nativeIn);
        in.setProtocolName(protocol);
        return List.of(out, in);
    }

    boolean isLikelyOneLegLendDepositPattern(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (!isWalletSender(tx, walletAddress) || logs == null || logs.isEmpty()) {
            return false;
        }
        IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule rule = findOneLegLendRule(tx);
        if (rule == null) {
            return false;
        }
        BigInteger txValue = tx.readRawOrExplorerUnsigned("value");
        if (txValue != null && txValue.signum() > 0) {
            return false;
        }
        Map<String, BigDecimal> outflowByContract = collectOutboundByContract(tx, walletAddress, logs);
        Map<String, BigDecimal> inboundByContract = collectInboundByContract(tx, walletAddress, logs);
        if (!inboundByContract.isEmpty()) {
            return false;
        }
        return outflowByContract.size() == 1;
    }

    private boolean isLikelyOneLegLendWithdrawalPattern(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (!isWalletSender(tx, walletAddress)) {
            return false;
        }
        String selector = tx.selector();
        if (selector == null || !KNOWN_ONE_LEG_WITHDRAW_METHOD_IDS.contains(selector)) {
            return false;
        }
        BigInteger txValue = tx.readRawOrExplorerUnsigned("value");
        if (txValue != null && txValue.signum() > 0) {
            return false;
        }

        Map<String, BigDecimal> outflowByContract = collectOutboundByContractForOneLeg(tx, walletAddress, logs);
        Map<String, BigDecimal> inboundByContract = collectInboundByContractForOneLeg(tx, walletAddress, logs);
        Map<String, BigDecimal> nonMintInboundByContract = collectNonMintInboundByContractForOneLeg(tx, walletAddress, logs);
        String nonMintInboundContract = pickSinglePositiveContract(nonMintInboundByContract);
        if (nonMintInboundContract != null && outflowByContract.isEmpty()) {
            return true;
        }
        Map<String, BigDecimal> netByContract = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : outflowByContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : inboundByContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }

        int positive = 0;
        int negative = 0;
        for (BigDecimal qty : netByContract.values()) {
            if (qty == null || qty.signum() == 0) {
                continue;
            }
            if (qty.signum() > 0) {
                positive++;
            } else {
                negative++;
            }
        }
        return (positive == 1 && negative == 0) || (positive == 0 && negative == 1);
    }

    private List<RawClassifiedEvent> classifyOneLegLendDeposit(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule rule = findOneLegLendRule(tx);
        if (rule == null) {
            return List.of();
        }
        Map<String, BigDecimal> outflowByContract = collectOutboundByContract(tx, walletAddress, logs);
        Map<String, Integer> outflowLogIndexByContract = collectOutboundLogIndexByContract(tx, walletAddress, logs);
        String underlyingContract = pickSingleContract(outflowByContract);
        if (underlyingContract == null) {
            return List.of();
        }
        BigDecimal outflow = outflowByContract.get(underlyingContract);
        if (outflow == null || outflow.signum() >= 0) {
            return List.of();
        }
        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(EconomicEventType.LEND_DEPOSIT);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(underlyingContract);
        event.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        event.setQuantityDelta(outflow);
        event.setProtocolName(protocolRegistry.getProtocolName(rule.getContract()).orElse(null));
        event.setLogIndex(outflowLogIndexByContract.get(underlyingContract));
        return List.of(event);
    }

    private List<RawClassifiedEvent> classifyOneLegLendWithdrawal(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        Map<String, BigDecimal> outflowByContract = collectOutboundByContractForOneLeg(tx, walletAddress, logs);
        Map<String, Integer> outflowLogIndexByContract = collectOutboundLogIndexByContractForOneLeg(tx, walletAddress, logs);
        Map<String, BigDecimal> inboundByContract = collectInboundByContractForOneLeg(tx, walletAddress, logs);
        Map<String, Integer> inboundLogIndexByContract = collectInboundLogIndexByContractForOneLeg(tx, walletAddress, logs);
        Map<String, BigDecimal> nonMintInboundByContract = collectNonMintInboundByContractForOneLeg(tx, walletAddress, logs);
        Map<String, Integer> nonMintInboundLogIndexByContract = collectNonMintInboundLogIndexByContractForOneLeg(tx, walletAddress, logs);

        String nonMintInboundContract = pickSinglePositiveContract(nonMintInboundByContract);
        if (nonMintInboundContract != null && outflowByContract.isEmpty()) {
            BigDecimal nonMintInbound = nonMintInboundByContract.get(nonMintInboundContract);
            if (nonMintInbound != null && nonMintInbound.signum() > 0) {
                RawClassifiedEvent event = new RawClassifiedEvent();
                event.setEventType(EconomicEventType.LEND_WITHDRAWAL);
                event.setWalletAddress(walletAddress);
                event.setAssetContract(nonMintInboundContract);
                event.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), nonMintInboundContract));
                event.setQuantityDelta(nonMintInbound);
                event.setProtocolName(protocolRegistry.getProtocolName(tx.readRawOrExplorerAddress("to")).orElse(null));
                event.setLogIndex(nonMintInboundLogIndexByContract.get(nonMintInboundContract));
                return List.of(event);
            }
        }

        Map<String, BigDecimal> netByContract = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : outflowByContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : inboundByContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            netByContract.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }

        String contract = null;
        BigDecimal net = null;
        for (Map.Entry<String, BigDecimal> e : netByContract.entrySet()) {
            BigDecimal qty = e.getValue();
            if (qty == null || qty.signum() == 0) {
                continue;
            }
            if (contract != null) {
                return List.of();
            }
            contract = e.getKey();
            net = qty;
        }
        if (contract == null || net == null) {
            return List.of();
        }

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(contract);
        event.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), contract));
        event.setQuantityDelta(net);
        event.setProtocolName(protocolRegistry.getProtocolName(tx.readRawOrExplorerAddress("to")).orElse(null));
        if (net.signum() > 0) {
            event.setLogIndex(inboundLogIndexByContract.get(contract));
        } else {
            event.setLogIndex(outflowLogIndexByContract.get(contract));
        }
        return List.of(event);
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
            if (isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
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

        VaultPair pair = resolveVaultDepositPair(
                tx.networkId(),
                outflowByContract.keySet(),
                mintInboundByContract.keySet()
        );
        if (pair == null) {
            return List.of();
        }
        String underlyingContract = pair.underlyingContract();
        String receiptContract = pair.receiptContract();
        BigDecimal underlyingOut = outflowByContract.get(underlyingContract);
        BigDecimal receiptIn = mintInboundByContract.get(receiptContract);
        if (underlyingOut == null || receiptIn == null || underlyingOut.signum() >= 0 || receiptIn.signum() <= 0) {
            return List.of();
        }

        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.LEND_DEPOSIT);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(underlyingContract);
        out.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        out.setQuantityDelta(underlyingOut);
        out.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        out.setLogIndex(outflowLogIndexByContract.get(underlyingContract));

        RawClassifiedEvent in = new RawClassifiedEvent();
        in.setEventType(EconomicEventType.LEND_DEPOSIT);
        in.setWalletAddress(walletAddress);
        in.setAssetContract(receiptContract);
        in.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), receiptContract));
        in.setQuantityDelta(receiptIn);
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

        String underlyingContract = selectBorrowUnderlyingContract(
                withoutSyntheticNativeContracts(tx.networkId(), mintContracts),
                withoutSyntheticNativeContracts(tx.networkId(), inboundByContract.keySet())
        );
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
            if (isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
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

        String underlyingContract = selectRepayUnderlyingContract(
                withoutSyntheticNativeContracts(tx.networkId(), burnContracts),
                withoutSyntheticNativeContracts(tx.networkId(), outflowByContract.keySet())
        );
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

        VaultPair pair = resolveVaultWithdrawalPair(
                tx.networkId(),
                burnOutByContract.keySet(),
                underlyingInboundByContract.keySet()
        );
        if (pair == null) {
            return List.of();
        }
        String receiptContract = pair.receiptContract();
        String underlyingContract = pair.underlyingContract();
        BigDecimal receiptOut = burnOutByContract.get(receiptContract);
        BigDecimal underlyingIn = underlyingInboundByContract.get(underlyingContract);
        if (receiptOut == null || underlyingIn == null || receiptOut.signum() >= 0 || underlyingIn.signum() <= 0) {
            return List.of();
        }

        RawClassifiedEvent out = new RawClassifiedEvent();
        out.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        out.setWalletAddress(walletAddress);
        out.setAssetContract(receiptContract);
        out.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), receiptContract));
        out.setQuantityDelta(receiptOut);
        out.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        out.setLogIndex(burnOutLogIndexByContract.get(receiptContract));

        RawClassifiedEvent in = new RawClassifiedEvent();
        in.setEventType(EconomicEventType.LEND_WITHDRAWAL);
        in.setWalletAddress(walletAddress);
        in.setAssetContract(underlyingContract);
        in.setAssetSymbol(evmTokenDecimalsResolver.getSymbol(tx.networkId(), underlyingContract));
        in.setQuantityDelta(underlyingIn);
        in.setProtocolName(protocolRegistry.getProtocolName(receiptContract).orElse(null));
        in.setLogIndex(underlyingInboundLogIndexByContract.get(underlyingContract));

        return List.of(out, in);
    }

    boolean isLikelyVaultDepositPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
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
        VaultPair pair = resolveVaultDepositPair(tx.networkId(), outboundContracts, mintInboundContracts);
        if (pair == null) {
            return false;
        }
        if (txTo != null && pair.receiptContract().equalsIgnoreCase(txTo)) {
            return true;
        }
        return isVaultLikeFunctionContext(tx, true);
    }

    boolean isLikelyVaultWithdrawalPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
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
        Map<String, Set<String>> inboundFromContractsByToken = new LinkedHashMap<>();
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
                    inboundFromContractsByToken
                            .computeIfAbsent(tokenAddress, __ -> new LinkedHashSet<>())
                            .add(fromAddress);
                }
            }
        }
        VaultPair pair = resolveVaultWithdrawalPair(tx.networkId(), burnContracts, inboundContracts);
        if (pair == null) {
            return false;
        }
        String receipt = pair.receiptContract();
        String underlying = pair.underlyingContract();
        // Strong vault signal for batched calls: receipt/share token is burned and vault sends underlying to wallet.
        Set<String> inboundFromContracts = inboundFromContractsByToken.getOrDefault(underlying, Set.of());
        if (inboundFromContracts.stream().anyMatch(from -> from != null && from.equalsIgnoreCase(receipt))) {
            return true;
        }
        if (txTo != null && receipt.equalsIgnoreCase(txTo)) {
            return true;
        }
        return isVaultLikeFunctionContext(tx, false);
    }

    private VaultPair resolveVaultDepositPair(
            String networkId,
            Set<String> outboundContracts,
            Set<String> mintInboundContracts
    ) {
        if (outboundContracts == null || outboundContracts.isEmpty()
                || mintInboundContracts == null || mintInboundContracts.isEmpty()) {
            return null;
        }

        for (String receipt : mintInboundContracts) {
            Set<String> candidates = new LinkedHashSet<>(outboundContracts);
            candidates.removeIf(contract -> equalsIgnoreCase(contract, receipt) || isSyntheticNativeContract(networkId, contract));
            if (candidates.size() == 1) {
                return new VaultPair(candidates.iterator().next(), receipt);
            }
        }

        if (outboundContracts.size() == 1 && mintInboundContracts.size() == 1) {
            String underlying = outboundContracts.iterator().next();
            String receipt = mintInboundContracts.iterator().next();
            if (!equalsIgnoreCase(underlying, receipt)) {
                return new VaultPair(underlying, receipt);
            }
        }
        return null;
    }

    private VaultPair resolveVaultWithdrawalPair(
            String networkId,
            Set<String> burnContracts,
            Set<String> inboundContracts
    ) {
        if (burnContracts == null || burnContracts.isEmpty()
                || inboundContracts == null || inboundContracts.isEmpty()) {
            return null;
        }

        for (String receipt : burnContracts) {
            Set<String> candidates = new LinkedHashSet<>(inboundContracts);
            candidates.removeIf(contract -> equalsIgnoreCase(contract, receipt) || isSyntheticNativeContract(networkId, contract));
            if (candidates.size() == 1) {
                return new VaultPair(candidates.iterator().next(), receipt);
            }
        }

        if (burnContracts.size() == 1 && inboundContracts.size() == 1) {
            String receipt = burnContracts.iterator().next();
            String underlying = inboundContracts.iterator().next();
            if (!equalsIgnoreCase(receipt, underlying)) {
                return new VaultPair(underlying, receipt);
            }
        }
        return null;
    }

    boolean isLikelyBorrowPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
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
            if (isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
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
        return selectBorrowUnderlyingContract(
                withoutSyntheticNativeContracts(tx.networkId(), mintContracts),
                withoutSyntheticNativeContracts(tx.networkId(), inboundContracts)
        ) != null;
    }

    boolean isLikelyRepayPattern(RawTransactionNormalizationView tx, String walletAddress, List<Document> logs) {
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
            if (isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
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
        return selectRepayUnderlyingContract(
                withoutSyntheticNativeContracts(tx.networkId(), burnContracts),
                withoutSyntheticNativeContracts(tx.networkId(), outflowContracts)
        ) != null;
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

    boolean hasKnownLendSelector(RawTransactionNormalizationView tx) {
        if (tx == null) {
            return false;
        }
        String selector = tx.selector();
        if (selector != null && (KNOWN_DEPOSIT_METHOD_IDS.contains(selector)
                || KNOWN_WITHDRAW_METHOD_IDS.contains(selector)
                || KNOWN_BORROW_METHOD_IDS.contains(selector)
                || KNOWN_REPAY_METHOD_IDS.contains(selector)
                || KNOWN_MULTICALL_METHOD_IDS.contains(selector))) {
            return true;
        }
        return findOneLegLendRule(tx) != null;
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

    private Map<String, BigDecimal> collectMintInboundByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1)) || !walletTopic.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(tokenAddress, quantity, BigDecimal::add);
        }
        return byContract;
    }

    private Map<String, Integer> collectMintInboundLogIndexByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, Integer> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1)) || !walletTopic.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.putIfAbsent(tokenAddress, tx.getLogIndex(log));
        }
        return byContract;
    }

    private Map<String, BigDecimal> collectBurnOutByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1)) || !ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(tokenAddress, quantity.negate(), BigDecimal::add);
        }
        return byContract;
    }

    private Map<String, Integer> collectBurnLogIndexByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, Integer> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1)) || !ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.putIfAbsent(tokenAddress, tx.getLogIndex(log));
        }
        return byContract;
    }

    private Map<String, BigDecimal> collectOutboundByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(tokenAddress, quantity.negate(), BigDecimal::add);
        }
        return byContract;
    }

    private Map<String, Integer> collectOutboundLogIndexByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, Integer> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.putIfAbsent(tokenAddress, tx.getLogIndex(log));
        }
        return byContract;
    }

    private Map<String, BigDecimal> collectInboundByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(tokenAddress, quantity, BigDecimal::add);
        }
        return byContract;
    }

    private Map<String, Integer> collectInboundLogIndexByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, Integer> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.putIfAbsent(tokenAddress, tx.getLogIndex(log));
        }
        return byContract;
    }

    private Map<String, BigDecimal> collectOutboundByContractForOneLeg(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (logs != null && !logs.isEmpty()) {
            return collectOutboundByContract(tx, walletAddress, logs);
        }
        return collectExplorerOutboundByContract(tx, walletAddress);
    }

    private Map<String, Integer> collectOutboundLogIndexByContractForOneLeg(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (logs != null && !logs.isEmpty()) {
            return collectOutboundLogIndexByContract(tx, walletAddress, logs);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, BigDecimal> collectInboundByContractForOneLeg(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (logs != null && !logs.isEmpty()) {
            return collectInboundByContract(tx, walletAddress, logs);
        }
        return collectExplorerInboundByContract(tx, walletAddress, false);
    }

    private Map<String, Integer> collectInboundLogIndexByContractForOneLeg(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (logs != null && !logs.isEmpty()) {
            return collectInboundLogIndexByContract(tx, walletAddress, logs);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, BigDecimal> collectNonMintInboundByContractForOneLeg(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (logs != null && !logs.isEmpty()) {
            return collectInboundByContractExcludingMintLogs(tx, walletAddress, logs);
        }
        return collectExplorerInboundByContract(tx, walletAddress, true);
    }

    private Map<String, Integer> collectNonMintInboundLogIndexByContractForOneLeg(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        if (logs != null && !logs.isEmpty()) {
            return collectInboundLogIndexByContractExcludingMintLogs(tx, walletAddress, logs);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, BigDecimal> collectInboundByContractExcludingMintLogs(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(tokenAddress, quantity, BigDecimal::add);
        }
        return byContract;
    }

    private Map<String, Integer> collectInboundLogIndexByContractExcludingMintLogs(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        Map<String, Integer> byContract = new LinkedHashMap<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            if (ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(2))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.putIfAbsent(tokenAddress, tx.getLogIndex(log));
        }
        return byContract;
    }

    private Map<String, BigDecimal> collectExplorerOutboundByContract(
            RawTransactionNormalizationView tx,
            String walletAddress
    ) {
        String wallet = tx.normalizeAddressValue(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        if (wallet == null) {
            return byContract;
        }
        for (Document transfer : tx.explorerTokenTransfers()) {
            String contract = tx.tokenTransferContract(transfer);
            if (contract == null || isSyntheticNativeContract(tx.networkId(), contract)) {
                continue;
            }
            String from = tx.tokenTransferFrom(transfer);
            String to = tx.tokenTransferTo(transfer);
            if (from == null || !wallet.equals(from) || wallet.equals(to)) {
                continue;
            }
            BigInteger value = tx.tokenTransferValue(transfer);
            BigDecimal quantity = toQuantity(tx, contract, value);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(contract, quantity.negate(), BigDecimal::add);
        }
        return byContract;
    }

    private Map<String, BigDecimal> collectExplorerInboundByContract(
            RawTransactionNormalizationView tx,
            String walletAddress,
            boolean excludeMintFromZeroAddress
    ) {
        String wallet = tx.normalizeAddressValue(walletAddress);
        Map<String, BigDecimal> byContract = new LinkedHashMap<>();
        if (wallet == null) {
            return byContract;
        }
        for (Document transfer : tx.explorerTokenTransfers()) {
            String contract = tx.tokenTransferContract(transfer);
            if (contract == null || isSyntheticNativeContract(tx.networkId(), contract)) {
                continue;
            }
            String from = tx.tokenTransferFrom(transfer);
            String to = tx.tokenTransferTo(transfer);
            if (to == null || !wallet.equals(to)) {
                continue;
            }
            if (excludeMintFromZeroAddress && isZeroAddress(from)) {
                continue;
            }
            BigInteger value = tx.tokenTransferValue(transfer);
            BigDecimal quantity = toQuantity(tx, contract, value);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            byContract.merge(contract, quantity, BigDecimal::add);
        }
        return byContract;
    }

    private boolean hasWalletOutboundTokenTransfer(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.getFirst())) {
                continue;
            }
            String tokenAddress = tx.normalizeAddressValue(tx.getLogAddress(log));
            if (tokenAddress == null || isSyntheticNativeContract(tx.networkId(), tokenAddress)) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1))) {
                continue;
            }
            BigDecimal quantity = toQuantity(tx, tokenAddress, tx.getLogAmount(log));
            if (quantity != null && quantity.signum() > 0) {
                return true;
            }
        }
        return false;
    }

    private IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule findOneLegLendRule(
            RawTransactionNormalizationView tx
    ) {
        if (tx == null || ingestionNetworkProperties == null) {
            return null;
        }
        String selector = tx.selector();
        String txTo = tx.readRawOrExplorerAddress("to");
        if (selector == null || txTo == null) {
            return null;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = entryOfNetwork(tx.networkId());
        if (entry == null || entry.getOneLegLendRules() == null || entry.getOneLegLendRules().isEmpty()) {
            return null;
        }
        for (IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule rule : entry.getOneLegLendRules()) {
            if (rule == null || rule.getContract() == null || rule.getSelectors() == null) {
                continue;
            }
            if (!rule.getContract().equalsIgnoreCase(txTo)) {
                continue;
            }
            if (rule.getSelectors().stream().anyMatch(selector::equalsIgnoreCase)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean isWalletSender(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || walletAddress == null || walletAddress.isBlank()) {
            return false;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        String txSender = tx.readRawOrExplorerAddress("from");
        return wallet != null && txSender != null && wallet.equals(txSender);
    }

    private static BigDecimal nativeInboundFromInternalTransfers(RawTransactionNormalizationView tx, String walletAddress) {
        if (tx == null || walletAddress == null || walletAddress.isBlank()) {
            return BigDecimal.ZERO;
        }
        String wallet = tx.normalizeAddressValue(walletAddress);
        if (wallet == null) {
            return BigDecimal.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (Document transfer : tx.explorerInternalTransfers()) {
            String isError = tx.internalTransferIsError(transfer);
            if ("1".equals(isError)) {
                continue;
            }
            String to = tx.internalTransferTo(transfer);
            String from = tx.internalTransferFrom(transfer);
            if (to == null || from == null || !wallet.equals(to) || wallet.equals(from)) {
                continue;
            }
            BigInteger value = tx.internalTransferValue(transfer);
            if (value == null || value.signum() <= 0) {
                continue;
            }
            total = total.add(value);
        }
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(total).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP);
    }

    private BigDecimal toQuantity(RawTransactionNormalizationView tx, String tokenAddress, BigInteger amount) {
        if (tx == null || tokenAddress == null || amount == null || amount.signum() <= 0) {
            return null;
        }
        int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
        BigDecimal divisor = BigDecimal.TEN.pow(decimals);
        return new BigDecimal(amount).divide(divisor, 18, RoundingMode.HALF_UP);
    }

    private static String pickSingleContract(Map<String, BigDecimal> byContract) {
        if (byContract == null || byContract.size() != 1) {
            return null;
        }
        return byContract.keySet().iterator().next();
    }

    private static String pickSinglePositiveContract(Map<String, BigDecimal> byContract) {
        if (byContract == null || byContract.isEmpty()) {
            return null;
        }
        String contract = null;
        for (Map.Entry<String, BigDecimal> e : byContract.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().signum() <= 0) {
                continue;
            }
            if (contract != null) {
                return null;
            }
            contract = e.getKey();
        }
        return contract;
    }

    private static boolean isZeroAddress(String address) {
        return address != null
                && "0x0000000000000000000000000000000000000000".equalsIgnoreCase(address);
    }

    private IngestionNetworkProperties.NetworkIngestionEntry entryOfNetwork(String networkId) {
        if (networkId == null || ingestionNetworkProperties == null || ingestionNetworkProperties.getNetwork() == null) {
            return null;
        }
        return ingestionNetworkProperties.getNetwork().get(networkId.toUpperCase(Locale.ROOT));
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

    private Set<String> withoutSyntheticNativeContracts(String networkId, Set<String> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return Set.of();
        }
        Set<String> filtered = new LinkedHashSet<>();
        for (String contract : contracts) {
            if (contract == null || isSyntheticNativeContract(networkId, contract)) {
                continue;
            }
            filtered.add(contract);
        }
        return filtered;
    }

    private boolean isSyntheticNativeContract(String networkId, String contract) {
        if (networkId == null || contract == null) {
            return false;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = entryOfNetwork(networkId);
        if (entry == null || entry.getSyntheticNativeContracts() == null || entry.getSyntheticNativeContracts().isEmpty()) {
            return false;
        }
        for (String configuredContract : entry.getSyntheticNativeContracts()) {
            if (configuredContract != null && configuredContract.equalsIgnoreCase(contract)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private record VaultPair(String underlyingContract, String receiptContract) {
    }

    private record NativeAsset(String contract, String symbol) {
    }

}
