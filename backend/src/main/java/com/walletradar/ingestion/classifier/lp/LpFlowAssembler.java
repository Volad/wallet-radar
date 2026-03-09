package com.walletradar.ingestion.classifier.lp;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.classifier.ProtocolRegistry;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.RawTransactionNormalizationView;
import com.walletradar.ingestion.classifier.TransferClassifier;
import org.bson.Document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LpFlowAssembler {

    private static final BigDecimal EVM_NATIVE_DECIMALS = BigDecimal.TEN.pow(18);

    private final ProtocolRegistry protocolRegistry;
    private final EvmTokenDecimalsResolver evmTokenDecimalsResolver;
    private final LpProtocolRegistry lpProtocolRegistry;
    private final LpEvidenceExtractor lpEvidenceExtractor;

    public LpFlowAssembler(
            ProtocolRegistry protocolRegistry,
            EvmTokenDecimalsResolver evmTokenDecimalsResolver,
            LpProtocolRegistry lpProtocolRegistry,
            LpEvidenceExtractor lpEvidenceExtractor
    ) {
        this.protocolRegistry = protocolRegistry;
        this.evmTokenDecimalsResolver = evmTokenDecimalsResolver;
        this.lpProtocolRegistry = lpProtocolRegistry;
        this.lpEvidenceExtractor = lpEvidenceExtractor;
    }

    public List<RawClassifiedEvent> assemblePositionEntry(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = lpEvidenceExtractor.resolvePositionId(tx, logs);

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || topics.size() >= 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(1)) || LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(topics.get(2))) {
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
            out.add(eventFromLog(EconomicEventType.LP_ENTRY, tx, walletAddress, txTo, txProtocol, positionId, log, tokenAddress, amount.negate(), false));
        }

        RawClassifiedEvent nativeLeg = inferNativeEntryOutflow(tx, walletAddress, logs, txTo, txProtocol, positionId, out);
        if (nativeLeg != null) {
            out.add(nativeLeg);
        }
        return out;
    }

    public List<RawClassifiedEvent> assemblePositionExit(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs,
            EconomicEventType exitType
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = lpEvidenceExtractor.resolvePositionId(tx, logs);

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || topics.size() >= 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(2)) || walletTopic.equalsIgnoreCase(topics.get(1))) {
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
            out.add(eventFromLog(exitType, tx, walletAddress, txTo, txProtocol, positionId, log, tokenAddress, amount, false));
        }

        if (out.isEmpty()) {
            String managerTopic = txTo != null ? tx.padAddressForTopic(txTo) : null;
            if (lpProtocolRegistry.isKnownPositionManager(txTo) && managerTopic != null) {
                for (Document log : logs) {
                    if (!lpEvidenceExtractor.isManagerInboundErc20Transfer(tx, log, managerTopic)) {
                        continue;
                    }
                    String fromTopic = tx.getLogTopics(log).get(1);
                    if (LpProtocolRegistry.ZERO_ADDRESS_TOPIC.equalsIgnoreCase(fromTopic) || walletTopic.equalsIgnoreCase(fromTopic)) {
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
                    out.add(eventFromLog(exitType, tx, walletAddress, txTo, txProtocol, positionId, log, tokenAddress, amount, false));
                }
            }
        }

        RawClassifiedEvent nativeLeg = inferNativePayout(exitType, tx, walletAddress, logs, txTo, txProtocol, positionId, out);
        if (nativeLeg != null) {
            out.add(nativeLeg);
        }
        return out;
    }

    public List<RawClassifiedEvent> assembleFeeClaim(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs
    ) {
        String walletTopic = tx.padAddressForTopic(walletAddress);
        String txTo = tx.readRawOrExplorerAddress("to");
        String txProtocol = normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
        String positionId = lpEvidenceExtractor.resolvePositionId(tx, logs);

        List<RawClassifiedEvent> out = new ArrayList<>();
        for (Document log : logs) {
            List<String> topics = tx.getLogTopics(log);
            if (topics.size() < 3 || topics.size() >= 4 || !TransferClassifier.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                continue;
            }
            if (!walletTopic.equalsIgnoreCase(topics.get(2)) || walletTopic.equalsIgnoreCase(topics.get(1))) {
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
            out.add(eventFromLog(EconomicEventType.LP_FEE_CLAIM, tx, walletAddress, txTo, txProtocol, positionId, log, tokenAddress, amount, false));
        }

        RawClassifiedEvent nativeLeg = inferNativePayout(EconomicEventType.LP_FEE_CLAIM, tx, walletAddress, logs, txTo, txProtocol, positionId, out);
        if (nativeLeg != null) {
            out.add(nativeLeg);
        }
        return out;
    }

    public String resolveSymbol(RawTransactionNormalizationView tx, String contract, LpEvidenceExtractor.TokenMeta meta) {
        String symbol = evmTokenDecimalsResolver.getSymbol(tx.networkId(), contract);
        if (symbol != null && !symbol.isBlank()) {
            return symbol;
        }
        if (meta != null && meta.symbol() != null && !meta.symbol().isBlank()) {
            return meta.symbol();
        }
        return "";
    }

    public String resolveProtocolName(String txTo, String txProtocol, String assetContract) {
        String byAsset = normalizeProtocol(protocolRegistry.getProtocolName(assetContract).orElse(null));
        if (byAsset != null) {
            return byAsset;
        }
        if (txProtocol != null) {
            return txProtocol;
        }
        return normalizeProtocol(protocolRegistry.getProtocolName(txTo).orElse(null));
    }

    public boolean isNativeOrWrappedAlreadyRepresented(List<RawClassifiedEvent> existingEvents, String networkId) {
        LpProtocolRegistry.NativeAsset nativeAsset = lpProtocolRegistry.nativeAssetOf(networkId);
        LpProtocolRegistry.WrappedNativeAsset wrappedNative = lpProtocolRegistry.wrappedNativeOf(networkId);
        String nativeContract = txNormalize(nativeAsset != null ? nativeAsset.contract() : null);
        String wrappedContract = txNormalize(wrappedNative != null ? wrappedNative.contract() : null);
        return existingEvents.stream()
                .map(RawClassifiedEvent::getAssetContract)
                .filter(Objects::nonNull)
                .map(LpFlowAssembler::txNormalize)
                .anyMatch(contract -> contract != null
                        && (contract.equalsIgnoreCase(nativeContract)
                        || contract.equalsIgnoreCase(wrappedContract)));
    }

    private RawClassifiedEvent inferNativeEntryOutflow(
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs,
            String txTo,
            String txProtocol,
            String positionId,
            List<RawClassifiedEvent> existingEvents
    ) {
        if (isNativeOrWrappedAlreadyRepresented(existingEvents, tx.networkId())) {
            return null;
        }
        LpProtocolRegistry.WrappedNativeAsset wrappedNative = lpProtocolRegistry.wrappedNativeOf(tx.networkId());
        LpProtocolRegistry.NativeAsset nativeAsset = lpProtocolRegistry.nativeAssetOf(tx.networkId());
        if (wrappedNative == null || nativeAsset == null) {
            return null;
        }
        boolean allowTxValueFallback = lpProtocolRegistry.isKnownPositionManager(txTo);
        LpEvidenceExtractor.WrappedNativeEvidence evidence = lpEvidenceExtractor.detectNativeEntryEvidence(
                tx,
                walletAddress,
                logs,
                txTo,
                tx.normalizeAddressValue(wrappedNative.contract()),
                allowTxValueFallback
        );
        if (evidence == null || evidence.amountWei() == null || evidence.amountWei().signum() <= 0) {
            return null;
        }
        BigDecimal quantity = new BigDecimal(evidence.amountWei()).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP).negate();
        if (quantity.signum() >= 0) {
            return null;
        }
        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(EconomicEventType.LP_ENTRY);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(nativeAsset.contract());
        event.setAssetSymbol(nativeAsset.symbol());
        event.setQuantityDelta(quantity);
        event.setProtocolName(resolveProtocolName(txTo, txProtocol, wrappedNative.contract()));
        event.setLogIndex(evidence.logIndex());
        event.setPositionId(positionId);
        return event;
    }

    private RawClassifiedEvent inferNativePayout(
            EconomicEventType eventType,
            RawTransactionNormalizationView tx,
            String walletAddress,
            List<Document> logs,
            String txTo,
            String txProtocol,
            String positionId,
            List<RawClassifiedEvent> existingEvents
    ) {
        if (isNativeOrWrappedAlreadyRepresented(existingEvents, tx.networkId())) {
            return null;
        }
        LpProtocolRegistry.NativeAsset nativeAsset = lpProtocolRegistry.nativeAssetOf(tx.networkId());
        if (nativeAsset == null) {
            return null;
        }
        LpEvidenceExtractor.NativePayoutEvidence evidence = lpEvidenceExtractor.detectNativePayoutEvidence(tx, walletAddress, logs);
        if (evidence == null || evidence.amountWei() == null || evidence.amountWei().signum() <= 0) {
            return null;
        }
        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(eventType);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(nativeAsset.contract());
        event.setAssetSymbol(nativeAsset.symbol());
        event.setQuantityDelta(new BigDecimal(evidence.amountWei()).divide(EVM_NATIVE_DECIMALS, 18, RoundingMode.HALF_UP));
        event.setProtocolName(resolveProtocolName(txTo, txProtocol, nativeAsset.contract()));
        event.setLogIndex(evidence.logIndex());
        event.setPositionId(positionId);
        return event;
    }

    private RawClassifiedEvent eventFromLog(
            EconomicEventType eventType,
            RawTransactionNormalizationView tx,
            String walletAddress,
            String txTo,
            String txProtocol,
            String positionId,
            Document log,
            String tokenAddress,
            BigInteger amount,
            boolean negate
    ) {
        int decimals = evmTokenDecimalsResolver.getDecimals(tx.networkId(), tokenAddress);
        BigDecimal quantity = new BigDecimal(amount.abs()).divide(BigDecimal.TEN.pow(decimals), 18, RoundingMode.HALF_UP);
        if (negate || amount.signum() < 0) {
            quantity = quantity.negate();
        }
        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(eventType);
        event.setWalletAddress(walletAddress);
        event.setAssetContract(tokenAddress);
        event.setAssetSymbol(resolveSymbol(tx, tokenAddress, null));
        event.setQuantityDelta(quantity);
        event.setProtocolName(resolveProtocolName(txTo, txProtocol, tokenAddress));
        event.setLogIndex(tx.getLogIndex(log));
        event.setPositionId(positionId);
        return event;
    }

    private static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        return protocol.trim().toLowerCase(Locale.ROOT);
    }

    private static String txNormalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String value = address.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("0x")) {
            value = "0x" + value;
        }
        return value.length() == 42 ? value : null;
    }
}
