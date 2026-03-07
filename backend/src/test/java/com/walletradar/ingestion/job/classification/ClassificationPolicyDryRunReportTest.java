package com.walletradar.ingestion.job.classification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.evm.rpc.EvmTokenDecimalsResolver;
import com.walletradar.ingestion.classifier.BridgeCallClassifier;
import com.walletradar.ingestion.classifier.LendClassifier;
import com.walletradar.ingestion.classifier.LpClassifier;
import com.walletradar.ingestion.classifier.NativeTransferClassifier;
import com.walletradar.ingestion.classifier.PerpOrderClassifier;
import com.walletradar.ingestion.classifier.ProtocolRegistry;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.RawTransactionNormalizationView;
import com.walletradar.ingestion.classifier.StakeClassifier;
import com.walletradar.ingestion.classifier.SwapClassifier;
import com.walletradar.ingestion.classifier.TransferClassifier;
import com.walletradar.ingestion.classifier.TxClassifier;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassificationPolicyDryRunReportTest {

    private static final Path RAW_DUMP_PATH = Path.of("walletradar.raw_transactions_debug_v5.json");
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final BigDecimal HIGH_THRESHOLD = new BigDecimal("0.85");
    private static final BigDecimal LOW_THRESHOLD = new BigDecimal("0.60");
    private static final BigDecimal CLAIM_CONFIDENCE_FLOOR = new BigDecimal("0.90");
    private static final BigDecimal SYNTHETIC_FALLBACK_CONFIDENCE_CAP = new BigDecimal("0.75");
    private static final Set<String> KNOWN_NO_LOG_FASTPATH_SELECTORS = Set.of(
            "0xd0e30db0", "0x2e1a7d4d", "0x3ce33bff", "0xf9068677", "0xfc5f1003", "0xae328590",
            "0x322bba21", "0x42842e0e", "0xac9650d8", "0x88316456", "0x219f5d17", "0x00f714ce",
            "0x18fccc76", "0x07ed2379", "0x90411a32", "0x73fc4457", "0xa9059cbb", "0x9ec68f0f",
            "0x84d61c97", "0xd7a08473", "0xcfc32570", "0xdeff4b24", "0xe2de2a03", "0x88d695b2"
    );
    private static final Set<String> LP_SENSITIVE_SELECTORS = Set.of(
            "0xac9650d8", "0x88316456", "0x219f5d17", "0x00f714ce", "0x18fccc76", "0x0c49ccbe", "0xfc6f7865"
    );
    private static final Set<EconomicEventType> OLD_FASTPATH_EVENT_TYPES = Set.of(
            EconomicEventType.SWAP_BUY,
            EconomicEventType.SWAP_SELL,
            EconomicEventType.WRAP,
            EconomicEventType.UNWRAP,
            EconomicEventType.EXTERNAL_TRANSFER_OUT,
            EconomicEventType.EXTERNAL_INBOUND,
            EconomicEventType.LP_ENTRY,
            EconomicEventType.LP_EXIT,
            EconomicEventType.LP_EXIT_PARTIAL,
            EconomicEventType.LP_EXIT_FINAL,
            EconomicEventType.LP_FEE_CLAIM,
            EconomicEventType.LP_POSITION_STAKE,
            EconomicEventType.LP_POSITION_UNSTAKE
    );
    private static final Set<EconomicEventType> NEW_FASTPATH_EVENT_TYPES = Set.of(
            EconomicEventType.SWAP_BUY,
            EconomicEventType.SWAP_SELL,
            EconomicEventType.EXTERNAL_TRANSFER_OUT,
            EconomicEventType.EXTERNAL_INBOUND,
            EconomicEventType.LEND_DEPOSIT,
            EconomicEventType.LEND_WITHDRAWAL,
            EconomicEventType.BORROW,
            EconomicEventType.REPAY,
            EconomicEventType.LP_ENTRY,
            EconomicEventType.LP_EXIT,
            EconomicEventType.LP_EXIT_PARTIAL,
            EconomicEventType.LP_EXIT_FINAL,
            EconomicEventType.LP_FEE_CLAIM,
            EconomicEventType.LP_ADJUST,
            EconomicEventType.LP_POSITION_ENTRY,
            EconomicEventType.LP_POSITION_EXIT,
            EconomicEventType.LP_POSITION_STAKE,
            EconomicEventType.LP_POSITION_UNSTAKE
    );

    @Test
    void dryRunEnrichmentPolicy_beforeVsAfter() throws IOException {
        assertThat(Files.exists(RAW_DUMP_PATH))
                .withFailMessage("Missing dump file: %s", RAW_DUMP_PATH)
                .isTrue();

        List<RawTransaction> txs = readRawTransactions(RAW_DUMP_PATH);
        assertThat(txs).isNotEmpty();

        TxClassifierDispatcher dispatcher = buildDispatcher();
        ConfidenceScorer scorer = new ConfidenceScorer();
        Metrics before = new Metrics();
        Metrics after = new Metrics();

        for (RawTransaction tx : txs) {
            RawTransactionNormalizationView view = RawTransactionNormalizationView.wrap(tx);
            String wallet = tx.getWalletAddress();
            if (wallet == null || wallet.isBlank()) {
                continue;
            }
            view.ensureSyntheticTransferLogsFromExplorer(TRANSFER_TOPIC);
            List<RawClassifiedEvent> events = dispatcher.classify(view, wallet);
            BigDecimal confidence = scorer.score(events);
            confidence = applyConfidenceBoost(view, events, confidence);
            confidence = capSyntheticFallbackConfidence(view, confidence);

            PolicyDecision oldDecision = decide(view, events, confidence, false);
            PolicyDecision newDecision = decide(view, events, confidence, true);
            before.add(oldDecision, view.selector());
            after.add(newDecision, view.selector());
        }

        printReport(txs.size(), before, after);
    }

    private static TxClassifierDispatcher buildDispatcher() {
        ProtocolRegistry protocolRegistry = address -> Optional.empty();
        EvmTokenDecimalsResolver decimalsResolver = mock(EvmTokenDecimalsResolver.class);
        when(decimalsResolver.getDecimals(anyString(), anyString())).thenReturn(EvmTokenDecimalsResolver.DEFAULT_DECIMALS);
        when(decimalsResolver.getSymbol(anyString(), anyString())).thenReturn("");
        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry zksyncEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        zksyncEntry.setSyntheticNativeContracts(List.of("0x000000000000000000000000000000000000800a"));
        ingestionNetworkProperties.setNetwork(Map.of("ZKSYNC", zksyncEntry));
        LendClassifier lendClassifier = new LendClassifier(protocolRegistry, decimalsResolver, ingestionNetworkProperties);

        List<TxClassifier> classifiers = List.of(
                new NativeTransferClassifier(),
                new PerpOrderClassifier(),
                new BridgeCallClassifier(),
                new LpClassifier(protocolRegistry, decimalsResolver, lendClassifier),
                lendClassifier,
                new SwapClassifier(protocolRegistry, decimalsResolver),
                new TransferClassifier(protocolRegistry, decimalsResolver, lendClassifier),
                new StakeClassifier()
        );
        return new TxClassifierDispatcher(classifiers);
    }

    private static PolicyDecision decide(
            RawTransactionNormalizationView view,
            List<RawClassifiedEvent> events,
            BigDecimal confidence,
            boolean newPolicy
    ) {
        if (confidence.compareTo(HIGH_THRESHOLD) >= 0) {
            return PolicyDecision.none();
        }

        if (newPolicy
                ? shouldAcceptFastPathNew(view, events, confidence)
                : shouldAcceptFastPathOld(view, events, confidence)) {
            return PolicyDecision.none();
        }

        SyncStrategy strategy = resolveStrategy(view.rawTransaction() != null ? view.rawTransaction().getSyncMethod() : null);
        if (strategy == SyncStrategy.NONE) {
            return PolicyDecision.none();
        }

        if (newPolicy
                ? shouldSkipEnrichmentForLowSignalNew(view)
                : shouldSkipEnrichmentForLowSignalOld(view)) {
            return PolicyDecision.none();
        }

        boolean callDetails = false;
        boolean callReceipt = false;
        if (strategy == SyncStrategy.BLOCKSCOUT) {
            if (!view.hasExplorerDetails()) {
                callDetails = true;
            }
            if (!view.hasCanonicalLogs() && !view.hasExplorerReceipt() && !view.hasExplorerReceiptLogs()) {
                callReceipt = true;
            }
        } else if (strategy == SyncStrategy.ETHERSCAN) {
            if (!view.hasCanonicalLogs() && !view.hasExplorerReceipt() && !view.hasExplorerReceiptLogs()) {
                callReceipt = true;
            }
        }
        return new PolicyDecision(callDetails, callReceipt);
    }

    private static boolean shouldAcceptFastPathOld(
            RawTransactionNormalizationView view,
            List<RawClassifiedEvent> events,
            BigDecimal confidence
    ) {
        if (events == null || events.isEmpty() || confidence.compareTo(LOW_THRESHOLD) < 0) {
            return false;
        }
        if (view.hasCanonicalLogs()) {
            return false;
        }
        String selector = view.selector();
        if (selector == null || !KNOWN_NO_LOG_FASTPATH_SELECTORS.contains(selector)) {
            return false;
        }
        if (LP_SENSITIVE_SELECTORS.contains(selector) && !containsLpSemantic(events)) {
            return false;
        }
        for (RawClassifiedEvent event : events) {
            if (event == null || event.getEventType() == null || !OLD_FASTPATH_EVENT_TYPES.contains(event.getEventType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldAcceptFastPathNew(
            RawTransactionNormalizationView view,
            List<RawClassifiedEvent> events,
            BigDecimal confidence
    ) {
        if (events == null || events.isEmpty() || confidence.compareTo(LOW_THRESHOLD) < 0) {
            return false;
        }
        String selector = view.selector();
        if (selector != null && LP_SENSITIVE_SELECTORS.contains(selector) && !containsLpSemantic(events)) {
            return false;
        }
        for (RawClassifiedEvent event : events) {
            if (event == null || event.getEventType() == null || !NEW_FASTPATH_EVENT_TYPES.contains(event.getEventType())) {
                return false;
            }
        }
        if (selector != null && KNOWN_NO_LOG_FASTPATH_SELECTORS.contains(selector)) {
            return true;
        }
        return !view.hasCanonicalLogs()
                && view.hasSyntheticLogsOnly()
                && confidence.compareTo(SYNTHETIC_FALLBACK_CONFIDENCE_CAP) >= 0;
    }

    private static boolean shouldSkipEnrichmentForLowSignalOld(RawTransactionNormalizationView view) {
        if (view.hasCanonicalLogs() || view.hasExplorerReceiptLogs()) {
            return false;
        }
        if (!view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty()) {
            return false;
        }
        BigInteger value = view.readRawOrExplorerUnsigned("value");
        if (value != null && value.signum() > 0) {
            return false;
        }
        String selector = view.selector();
        if (selector == null) {
            return true;
        }
        return !KNOWN_NO_LOG_FASTPATH_SELECTORS.contains(selector);
    }

    private static boolean shouldSkipEnrichmentForLowSignalNew(RawTransactionNormalizationView view) {
        if (view.hasCanonicalLogs() || view.hasExplorerReceiptLogs()) {
            return false;
        }
        if (!view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty()) {
            return false;
        }
        String from = view.readRawOrExplorerAddress("from");
        String to = view.readRawOrExplorerAddress("to");
        if (from != null && from.equals(to)) {
            return true;
        }
        BigInteger value = view.readRawOrExplorerUnsigned("value");
        String selector = view.selector();
        if (selector == null && value != null && value.signum() > 0) {
            return false;
        }
        if (selector == null) {
            return true;
        }
        if (value != null && value.signum() > 0 && KNOWN_NO_LOG_FASTPATH_SELECTORS.contains(selector)) {
            return false;
        }
        if (value != null && value.signum() > 0) {
            return false;
        }
        return !KNOWN_NO_LOG_FASTPATH_SELECTORS.contains(selector);
    }

    private static BigDecimal applyConfidenceBoost(
            RawTransactionNormalizationView view,
            List<RawClassifiedEvent> events,
            BigDecimal baseConfidence
    ) {
        if (baseConfidence == null) {
            return BigDecimal.ZERO;
        }
        String selector = view.selector();
        String functionName = view.readRawOrExplorerTx("functionName");
        boolean claim = (selector != null && "0x71ee95c0".equalsIgnoreCase(selector))
                || (functionName != null && !functionName.isBlank() && functionName.trim().toLowerCase(Locale.ROOT).startsWith("claim("));
        if (!claim || events == null || events.isEmpty()) {
            return baseConfidence;
        }
        boolean allInbound = events.stream()
                .allMatch(e -> e != null && e.getEventType() == EconomicEventType.EXTERNAL_INBOUND);
        return allInbound ? baseConfidence.max(CLAIM_CONFIDENCE_FLOOR) : baseConfidence;
    }

    private static BigDecimal capSyntheticFallbackConfidence(RawTransactionNormalizationView view, BigDecimal confidence) {
        if (confidence == null) {
            return null;
        }
        if (!view.hasCanonicalLogs() && view.hasSyntheticLogsOnly()) {
            return confidence.min(SYNTHETIC_FALLBACK_CONFIDENCE_CAP);
        }
        return confidence;
    }

    private static boolean containsLpSemantic(List<RawClassifiedEvent> events) {
        for (RawClassifiedEvent event : events) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            EconomicEventType t = event.getEventType();
            if (EnumSet.of(
                    EconomicEventType.LP_ENTRY,
                    EconomicEventType.LP_EXIT,
                    EconomicEventType.LP_EXIT_PARTIAL,
                    EconomicEventType.LP_EXIT_FINAL,
                    EconomicEventType.LP_ADJUST,
                    EconomicEventType.LP_POSITION_ENTRY,
                    EconomicEventType.LP_POSITION_EXIT,
                    EconomicEventType.LP_POSITION_STAKE,
                    EconomicEventType.LP_POSITION_UNSTAKE,
                    EconomicEventType.LP_FEE_CLAIM
            ).contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static SyncStrategy resolveStrategy(RawSyncMethod syncMethod) {
        if (syncMethod == null) {
            return SyncStrategy.ETHERSCAN;
        }
        return switch (syncMethod) {
            case ETHERSCAN -> SyncStrategy.ETHERSCAN;
            case BLOCKSCOUT -> SyncStrategy.BLOCKSCOUT;
            case RPC -> SyncStrategy.NONE;
        };
    }

    private static List<RawTransaction> readRawTransactions(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(path.toFile());
        JsonNode rows;
        if (root.isArray()) {
            rows = root;
        } else if (root.has("documents")) {
            rows = root.get("documents");
        } else if (root.has("data")) {
            rows = root.get("data");
        } else {
            rows = mapper.createArrayNode();
        }
        List<RawTransaction> out = new ArrayList<>();
        for (JsonNode node : rows) {
            RawTransaction tx = new RawTransaction();
            tx.setTxHash(asText(node, "txHash"));
            tx.setNetworkId(asText(node, "networkId"));
            tx.setWalletAddress(asText(node, "walletAddress"));
            String sync = asText(node, "syncMethod");
            if (sync != null && !sync.isBlank()) {
                try {
                    tx.setSyncMethod(RawSyncMethod.valueOf(sync.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    tx.setSyncMethod(null);
                }
            }
            JsonNode rawDataNode = node.get("rawData");
            if (rawDataNode != null && !rawDataNode.isNull()) {
                tx.setRawData(Document.parse(rawDataNode.toString()));
            }
            out.add(tx);
        }
        return out;
    }

    private static String asText(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isObject() && value.has("$numberLong")) {
            return value.get("$numberLong").asText(null);
        }
        return value.asText(null);
    }

    private static void printReport(int total, Metrics before, Metrics after) {
        System.out.println("=== CLASSIFICATION ENRICHMENT DRY-RUN (v5 dump) ===");
        System.out.println("Total raw tx: " + total);
        System.out.println();
        System.out.println("[Before]");
        System.out.println("  tx with any enrichment: " + before.anyEnrichmentTx);
        System.out.println("  details calls:          " + before.detailsCalls);
        System.out.println("  receipt calls:          " + before.receiptCalls);
        System.out.println();
        System.out.println("[After]");
        System.out.println("  tx with any enrichment: " + after.anyEnrichmentTx);
        System.out.println("  details calls:          " + after.detailsCalls);
        System.out.println("  receipt calls:          " + after.receiptCalls);
        System.out.println();
        System.out.println("[Delta]");
        System.out.println("  tx enrichment reduced:  " + (before.anyEnrichmentTx - after.anyEnrichmentTx));
        System.out.println("  details reduced:        " + (before.detailsCalls - after.detailsCalls));
        System.out.println("  receipt reduced:        " + (before.receiptCalls - after.receiptCalls));
        System.out.println();
        System.out.println("[Top selectors still triggering enrichment]");
        after.enrichmentBySelector.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(e -> System.out.println("  " + e.getKey() + " -> " + e.getValue()));
    }

    private enum SyncStrategy {
        ETHERSCAN, BLOCKSCOUT, NONE
    }

    private record PolicyDecision(boolean callDetails, boolean callReceipt) {
        static PolicyDecision none() {
            return new PolicyDecision(false, false);
        }
    }

    private static final class Metrics {
        int detailsCalls;
        int receiptCalls;
        int anyEnrichmentTx;
        final Map<String, Integer> enrichmentBySelector = new LinkedHashMap<>();

        void add(PolicyDecision decision, String selector) {
            if (decision.callDetails()) {
                detailsCalls++;
            }
            if (decision.callReceipt()) {
                receiptCalls++;
            }
            if (decision.callDetails() || decision.callReceipt()) {
                anyEnrichmentTx++;
                String key = selector != null ? selector : "null";
                enrichmentBySelector.merge(key, 1, Integer::sum);
            }
        }
    }
}
