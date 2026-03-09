package com.walletradar.ingestion.job.classification;

import com.walletradar.common.RetryPolicy;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.transaction.normalized.ClassificationStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.RawTransactionNormalizationView;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.classifier.lp.LpProtocolRegistry;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.normalizer.NormalizedTransactionBuilder;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 2 (ADR-026): Read raw_transactions → classify → normalize (1 tx = 1 normalized doc with flows) → upsert.
 * Low-confidence EVM normalization performs selective receipt enrichment with retry/backoff.
 * Used by {@link com.walletradar.ingestion.job.classification.RawTxNormalizationJob}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClassificationProcessor {

    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String LOW_CONFIDENCE_UNRESOLVED = "LOW_CONFIDENCE_UNRESOLVED";
    private static final String LOW_CONFIDENCE_NO_RECEIPT_SUPPORT = "LOW_CONFIDENCE_NO_RECEIPT_SUPPORT";
    private static final String RECEIPT_UNAVAILABLE = "RECEIPT_UNAVAILABLE";
    private static final String DETAILS_UNAVAILABLE = "DETAILS_UNAVAILABLE";
    private static final String LOW_SIGNAL_NO_ENRICHMENT_BENEFIT = "LOW_SIGNAL_NO_ENRICHMENT_BENEFIT";
    private static final String CLAIM_METHOD_ID = "0x71ee95c0";
    private static final BigDecimal CLAIM_CONFIDENCE_FLOOR = new BigDecimal("0.90");
    private static final BigDecimal SYNTHETIC_FALLBACK_CONFIDENCE_CAP = new BigDecimal("0.75");
    private static final Set<String> KNOWN_NO_LOG_FASTPATH_SELECTORS = Set.of(
            "0xd0e30db0", // deposit()
            "0x2e1a7d4d", // withdraw(uint256)
            "0x3ce33bff", // bridge(...)
            "0xf9068677", // depositNativeWithId(...)
            "0xfc5f1003", // stargate/lifi bridge observed
            "0xae328590", // stargate/lifi bridge observed
            "0x322bba21", // createOrder(tuple)
            "0x42842e0e", // safeTransferFrom(address,address,uint256)
            "0xac9650d8", // multicall(bytes[])
            "0x88316456", // mint((...))
            "0x219f5d17", // increaseLiquidity((...))
            "0x00f714ce", // strategy withdraw/unstake by tokenId
            "0x18fccc76", // strategy fee claim/harvest by tokenId
            "0x07ed2379", // 1inch swap selector (observed)
            "0x90411a32", // 1inch swap selector (observed)
            "0x73fc4457", // swap selector (observed)
            "0xa9059cbb", // ERC20 transfer(address,uint256)
            "0x9ec68f0f", // token claim/redeem selector (observed)
            "0x84d61c97", // token claim selector (observed)
            "0xd7a08473", // protocol transfer/deposit selector (observed)
            "0xcfc32570", // claim selector (observed)
            "0xdeff4b24", // claim selector (observed)
            "0xe2de2a03", // claim selector (observed)
            "0x88d695b2"  // claim selector (observed)
    );
    private static final Set<EconomicEventType> FASTPATH_EVENT_TYPES = Set.of(
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
            EconomicEventType.LP_POSITION_UNSTAKE,
            EconomicEventType.WRAP,
            EconomicEventType.UNWRAP
    );
    private static final Set<String> LP_SENSITIVE_SELECTORS = Set.of(
            "0xac9650d8", // multicall(bytes[]) often wraps LP position ops
            "0x88316456", // mint((...))
            "0x219f5d17", // increaseLiquidity((...))
            "0x00f714ce", // strategy withdraw/unstake
            "0x18fccc76", // strategy claim/harvest
            "0x0c49ccbe", // decreaseLiquidity((...))
            "0xfc6f7865"  // collect((...))
    );
    private static final LpProtocolRegistry LP_PROTOCOL_REGISTRY = new LpProtocolRegistry();

    private final RawTransactionRepository rawTransactionRepository;
    private final TxClassifierDispatcher txClassifierDispatcher;
    private final NormalizedTransactionBuilder normalizedTransactionBuilder;
    private final IdempotentNormalizedTransactionStore idempotentNormalizedTransactionStore;
    private final ExplorerProvider explorerProvider;
    private final ClassifierProperties classifierProperties;
    private final ConfidenceScorer confidenceScorer;

    /**
     * Process a batch of raw transactions directly (ADR-021/026). Used by RawTxNormalizationJob.
     * Sets normalizationStatus=COMPLETE on success, keeps PENDING on exception with retry metadata.
     */
    public void processBatch(List<RawTransaction> rawList, String walletAddress, NetworkId networkId,
                             EstimatingBlockTimestampResolver estimator) {
        for (RawTransaction tx : rawList) {
            try {
                RawTransactionNormalizationView txView = RawTransactionNormalizationView.wrap(tx);
                Instant blockTs = resolveBlockTimestamp(txView, networkId, estimator);
                if (blockTs == null) {
                    markRetry(tx, "Missing block timestamp for normalization");
                    rawTransactionRepository.save(tx);
                    continue;
                }

                ClassificationOutcome outcome = classifyWithSelectiveEnrichment(txView, walletAddress, networkId);
                NormalizedTransaction normalizedTransaction = normalizedTransactionBuilder.build(
                        txView.txHash(), networkId, walletAddress, blockTs, outcome.rawEvents(), outcome.confidence());
                applyNeedsReview(normalizedTransaction, outcome.reviewReasons());
                idempotentNormalizedTransactionStore.upsert(normalizedTransaction);
                tx.setNormalizationStatus(NormalizationStatus.COMPLETE);
                tx.setLastError(null);
                tx.setNextRetryAt(null);
                rawTransactionRepository.save(tx);
            } catch (Exception e) {
                log.error("Classification failed for tx {} on {} for wallet {}: {}",
                        tx.getTxHash(), networkId, walletAddress, e.getMessage(), e);
                markRetry(tx, e.getMessage());
                rawTransactionRepository.save(tx);
            }
        }
    }

    private ClassificationOutcome classifyWithSelectiveEnrichment(
            RawTransactionNormalizationView txView, String walletAddress, NetworkId networkId
    ) {
        EnrichmentState initial = classifyAndScore(txView, walletAddress);
        List<RawClassifiedEvent> rawEvents = initial.rawEvents();
        BigDecimal confidence = initial.confidence();
        BigDecimal highThreshold = normalizeThreshold(classifierProperties.getReceiptEnrichmentThreshold());
        BigDecimal lowThreshold = normalizeThreshold(classifierProperties.getNeedsReviewThreshold());
        if (lowThreshold.compareTo(highThreshold) > 0) {
            lowThreshold = highThreshold;
        }

        if (confidence.compareTo(highThreshold) >= 0) {
            return new ClassificationOutcome(rawEvents, confidence, List.of());
        }
        if (shouldAcceptFastPathWithoutEnrichment(txView, rawEvents, confidence, lowThreshold)) {
            log.info("ENRICHMENT_SKIPPED_FASTPATH tx {} on {} selector={} confidence={}",
                    txView.txHash(), networkId, txView.selector(), confidence);
            return new ClassificationOutcome(rawEvents, confidence, List.of());
        }

        if (!canEnrich(txView, networkId)) {
            if (confidence.compareTo(lowThreshold) <= 0) {
                return new ClassificationOutcome(rawEvents, confidence, List.of(
                        LOW_CONFIDENCE_UNRESOLVED,
                        LOW_CONFIDENCE_NO_RECEIPT_SUPPORT
                ));
            }
            return new ClassificationOutcome(rawEvents, confidence, List.of());
        }

        log.info("Attempting enrichment for tx {} on {} with initial confidence {} below threshold {}",
                txView.txHash(), networkId, confidence, highThreshold);

        SyncStrategy strategy = resolveStrategy(txView);
        EnrichmentState state = new EnrichmentState(rawEvents, confidence);
        Set<String> reasons = new LinkedHashSet<>();

        if (state.confidence().compareTo(highThreshold) < 0 && shouldSkipEnrichmentForLowSignal(txView)) {
            log.info("ENRICHMENT_SKIPPED_NO_BENEFIT tx {} on {} (selector={}, value={})",
                    txView.txHash(), networkId, txView.selector(), txView.readRawOrExplorerTx("value"));
            if (state.confidence().compareTo(lowThreshold) <= 0) {
                log.info("LOW_SIGNAL_NEEDS_REVIEW tx {} on {} with confidence {}",
                        txView.txHash(), networkId, state.confidence());
                return new ClassificationOutcome(
                        state.rawEvents(),
                        state.confidence(),
                        List.of(LOW_CONFIDENCE_UNRESOLVED, LOW_SIGNAL_NO_ENRICHMENT_BENEFIT)
                );
            }
            return new ClassificationOutcome(state.rawEvents(), state.confidence(), List.of());
        }

        if (strategy == SyncStrategy.BLOCKSCOUT) {
            if (state.confidence().compareTo(highThreshold) <= 0 && !txView.hasExplorerDetails()) {
                state = enrichWithDetailsAndRescore(txView, walletAddress, networkId, state, reasons);
            } else if (state.confidence().compareTo(highThreshold) <= 0) {
                log.debug("ENRICHMENT_SKIPPED_DETAILS_ALREADY_PRESENT tx {} on {}",
                        txView.txHash(), networkId);
            }
            if (state.confidence().compareTo(highThreshold) <= 0 && !txView.hasCanonicalLogs()) {
                log.info("Attempting receipt enrichment for tx {} on {} with confidence {} below threshold {} after details enrichment",
                        txView.txHash(), networkId, state.confidence(), highThreshold);

                state = enrichWithReceiptAndRescore(txView, walletAddress, networkId, state, reasons);
            } else if (state.confidence().compareTo(highThreshold) <= 0) {
                log.debug("ENRICHMENT_SKIPPED_RECEIPT_ALREADY_PRESENT tx {} on {}",
                        txView.txHash(), networkId);
            }
        } else if (strategy == SyncStrategy.ETHERSCAN) {
            if (state.confidence().compareTo(highThreshold) < 0 && !txView.hasCanonicalLogs()) {
                state = enrichWithReceiptAndRescore(txView, walletAddress, networkId, state, reasons);
            } else if (state.confidence().compareTo(highThreshold) < 0) {
                log.debug("ENRICHMENT_SKIPPED_RECEIPT_ALREADY_PRESENT tx {} on {}",
                        txView.txHash(), networkId);
            }
        }

        List<RawClassifiedEvent> enrichedEvents = state.rawEvents();
        BigDecimal enrichedConfidence = state.confidence();

        List<String> reviewReasons = List.of();
        if (enrichedConfidence.compareTo(lowThreshold) < 0) {
            reasons.add(LOW_CONFIDENCE_UNRESOLVED);
            reviewReasons = List.copyOf(reasons);
        }
        return new ClassificationOutcome(enrichedEvents, enrichedConfidence, reviewReasons);
    }

    private boolean canEnrich(RawTransactionNormalizationView txView, NetworkId networkId) {
        return networkId != null
                && networkId != NetworkId.SOLANA
                && txView.txHash() != null
                && !txView.txHash().isBlank()
                && explorerProvider.supports(networkId);
    }

    private EnrichmentState enrichWithDetailsAndRescore(
            RawTransactionNormalizationView txView,
            String walletAddress,
            NetworkId networkId,
            EnrichmentState currentState,
            Set<String> reasons
    ) {
        if (txView.hasExplorerDetails()) {
            log.debug("ENRICHMENT_SKIPPED_DETAILS_ALREADY_PRESENT tx {} on {}",
                    txView.txHash(), networkId);
            return classifyAndScore(txView, walletAddress);
        }
        String lastError = null;
        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(1L, classifierProperties.getReceiptEnrichmentBaseDelayMs()),
                Math.max(0.0, classifierProperties.getReceiptEnrichmentJitterFactor()),
                Math.max(1, classifierProperties.getReceiptEnrichmentMaxAttempts())
        );
        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                ExplorerTransactionDetails details = explorerProvider.getTransactionDetails(txView.txHash(), networkId);
                if (details == null) {
                    lastError = "EMPTY_DETAILS";
                    continue;
                }
                txView.mergeTransactionDetails(details);
                return classifyAndScore(txView, walletAddress);
            } catch (Exception e) {
                lastError = sanitizeReason(e.getMessage());
                log.warn("Details enrichment attempt {}/{} failed for tx {} on {}: {}",
                        attempt + 1, retryPolicy.getMaxAttempts(), txView.txHash(), networkId, e.getMessage());
            }
        }
        reasons.add(DETAILS_UNAVAILABLE);
        if (lastError != null && !lastError.isBlank()) {
            reasons.add("DETAILS_ENRICHMENT_ERROR:" + lastError);
        }
        return currentState;
    }

    private EnrichmentState enrichWithReceiptAndRescore(
            RawTransactionNormalizationView txView,
            String walletAddress,
            NetworkId networkId,
            EnrichmentState currentState,
            Set<String> reasons
    ) {
        if (txView.hasCanonicalLogs()) {
            log.debug("ENRICHMENT_SKIPPED_RECEIPT_ALREADY_PRESENT tx {} on {}",
                    txView.txHash(), networkId);
            return classifyAndScore(txView, walletAddress);
        }
        if (txView.hasExplorerReceiptLogs()) {
            log.debug("ENRICHMENT_SKIPPED_FETCH_PROMOTE_STORED_RECEIPT tx {} on {}",
                    txView.txHash(), networkId);
            txView.promoteStoredExplorerReceipt();
            return classifyAndScore(txView, walletAddress);
        }
        if (txView.hasExplorerReceipt()) {
            log.debug("ENRICHMENT_SKIPPED_RECEIPT_ALREADY_PRESENT_NO_LOGS tx {} on {}",
                    txView.txHash(), networkId);
            return classifyAndScore(txView, walletAddress);
        }
        String lastError = null;

        RetryPolicy retryPolicy = new RetryPolicy(
                Math.max(1L, classifierProperties.getReceiptEnrichmentBaseDelayMs()),
                Math.max(0.0, classifierProperties.getReceiptEnrichmentJitterFactor()),
                Math.max(1, classifierProperties.getReceiptEnrichmentMaxAttempts())
        );
        for (int attempt = 0; attempt < retryPolicy.getMaxAttempts(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(retryPolicy.delayMs(attempt - 1));
            }
            try {
                ExplorerReceipt receipt = explorerProvider.getReceipt(txView.txHash(), networkId);
                if (receipt == null || !receipt.hasLogsField()) {
                    lastError = "EMPTY_RECEIPT";
                    continue;
                }
                txView.mergeReceipt(receipt);
                return classifyAndScore(txView, walletAddress);
            } catch (Exception e) {
                lastError = sanitizeReason(e.getMessage());
                log.warn("Receipt enrichment attempt {}/{} failed for tx {} on {}: {}",
                        attempt + 1, retryPolicy.getMaxAttempts(), txView.txHash(), networkId, e.getMessage());
            }
        }
        reasons.add(RECEIPT_UNAVAILABLE);
        if (lastError != null && !lastError.isBlank()) {
            reasons.add("RECEIPT_ENRICHMENT_ERROR:" + lastError);
        }
        return currentState;
    }

    private static void applyNeedsReview(NormalizedTransaction normalizedTransaction, List<String> reviewReasons) {
        if (reviewReasons == null || reviewReasons.isEmpty()) {
            return;
        }
        Set<String> reasons = new LinkedHashSet<>();
        if (normalizedTransaction.getMissingDataReasons() != null) {
            reasons.addAll(normalizedTransaction.getMissingDataReasons());
        }
        reasons.addAll(reviewReasons);
        normalizedTransaction.setMissingDataReasons(List.copyOf(reasons));
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setClassificationStatus(ClassificationStatus.NEEDS_REVIEW);
    }

    private static BigDecimal normalizeThreshold(double rawThreshold) {
        BigDecimal threshold = BigDecimal.valueOf(rawThreshold);
        if (threshold.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (threshold.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return threshold;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = reason.replaceAll("\\s+", "_").toUpperCase();
        if (normalized.length() > 96) {
            return normalized.substring(0, 96);
        }
        return normalized;
    }

    private static BigDecimal applyConfidenceBoost(
            RawTransactionNormalizationView txView, List<RawClassifiedEvent> rawEvents, BigDecimal baseConfidence
    ) {
        if (baseConfidence == null) {
            return BigDecimal.ZERO;
        }
        if (!isLikelyClaimCall(txView) || rawEvents == null || rawEvents.isEmpty()) {
            return baseConfidence;
        }
        boolean allInbound = rawEvents.stream()
                .allMatch(e -> e != null && e.getEventType() == com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        if (!allInbound) {
            return baseConfidence;
        }
        return baseConfidence.max(CLAIM_CONFIDENCE_FLOOR);
    }

    private EnrichmentState classifyAndScore(RawTransactionNormalizationView txView, String walletAddress) {
        if (txView.isSelectorFromInputFallback()) {
            log.debug("SELECTOR_INPUT_FALLBACK tx {} selector {}", txView.txHash(), txView.selector());
        }
        txView.ensureSyntheticTransferLogsFromExplorer(TRANSFER_TOPIC);
        List<RawClassifiedEvent> rawEvents = txClassifierDispatcher.classify(txView, walletAddress);
        BigDecimal confidence = confidenceScorer.score(rawEvents);
        confidence = applyConfidenceBoost(txView, rawEvents, confidence);
        confidence = capSyntheticFallbackConfidence(txView, confidence);
        return new EnrichmentState(rawEvents, confidence);
    }

    private static boolean shouldSkipEnrichmentForLowSignal(RawTransactionNormalizationView txView) {
        if (txView == null || !txView.hasRawData()) {
            return false;
        }
        if (txView.hasCanonicalLogs() || txView.hasExplorerReceiptLogs()) {
            return false;
        }
        if (!txView.explorerTokenTransfers().isEmpty() || !txView.explorerInternalTransfers().isEmpty()) {
            return false;
        }
        if (LP_PROTOCOL_REGISTRY.isKnownLpSurfaceTarget(txView.readRawOrExplorerAddress("to"))) {
            return false;
        }
        String from = txView.readRawOrExplorerAddress("from");
        String to = txView.readRawOrExplorerAddress("to");
        if (from != null && from.equals(to)) {
            return true;
        }
        BigInteger value = txView.readRawOrExplorerUnsigned("value");
        String selector = txView.selector();
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

    private static boolean shouldAcceptFastPathWithoutEnrichment(
            RawTransactionNormalizationView txView,
            List<RawClassifiedEvent> rawEvents,
            BigDecimal confidence,
            BigDecimal lowThreshold
    ) {
        if (txView == null || !txView.hasRawData()) {
            return false;
        }
        if (rawEvents == null || rawEvents.isEmpty()) {
            return false;
        }
        if (confidence == null || lowThreshold == null || confidence.compareTo(lowThreshold) < 0) {
            return false;
        }
        if (requiresLpReceiptEnrichment(txView, rawEvents)) {
            return false;
        }
        String selector = txView.selector();
        if (selector != null && LP_SENSITIVE_SELECTORS.contains(selector) && !containsLpSemantic(rawEvents)) {
            // LP-sensitive selectors are ambiguous without full receipt logs.
            // If we did not yet produce any LP semantic, force enrichment.
            return false;
        }
        for (RawClassifiedEvent event : rawEvents) {
            if (event == null || event.getEventType() == null) {
                return false;
            }
            if (!FASTPATH_EVENT_TYPES.contains(event.getEventType())) {
                return false;
            }
        }
        if (selector != null && KNOWN_NO_LOG_FASTPATH_SELECTORS.contains(selector)) {
            return true;
        }
        // For synthetic transfer evidence, receipt/details usually do not improve semantics.
        // Keep cap-based confidence gate to avoid accepting weak synthetic guesses.
        if (!txView.hasCanonicalLogs()
                && txView.hasSyntheticLogsOnly()
                && confidence.compareTo(SYNTHETIC_FALLBACK_CONFIDENCE_CAP) >= 0) {
            return true;
        }
        return false;
    }

    private static boolean requiresLpReceiptEnrichment(
            RawTransactionNormalizationView txView,
            List<RawClassifiedEvent> rawEvents
    ) {
        if (txView == null || txView.hasCanonicalLogs()) {
            return false;
        }
        if (!LP_PROTOCOL_REGISTRY.isKnownLpSurfaceTarget(txView.readRawOrExplorerAddress("to"))) {
            return false;
        }
        return !containsLpSemantic(rawEvents);
    }

    private static boolean containsLpSemantic(List<RawClassifiedEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return false;
        }
        for (RawClassifiedEvent event : rawEvents) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            EconomicEventType type = event.getEventType();
            if (type == EconomicEventType.LP_ENTRY
                    || type == EconomicEventType.LP_EXIT
                    || type == EconomicEventType.LP_EXIT_PARTIAL
                    || type == EconomicEventType.LP_EXIT_FINAL
                    || type == EconomicEventType.LP_ADJUST
                    || type == EconomicEventType.LP_POSITION_ENTRY
                    || type == EconomicEventType.LP_POSITION_EXIT
                    || type == EconomicEventType.LP_FEE_CLAIM
                    || type == EconomicEventType.LP_POSITION_STAKE
                    || type == EconomicEventType.LP_POSITION_UNSTAKE) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal capSyntheticFallbackConfidence(RawTransactionNormalizationView txView, BigDecimal confidence) {
        if (txView == null || confidence == null) {
            return confidence;
        }
        if (!txView.hasCanonicalLogs() && txView.hasSyntheticLogsOnly()) {
            return confidence.min(SYNTHETIC_FALLBACK_CONFIDENCE_CAP);
        }
        return confidence;
    }

    private static SyncStrategy resolveStrategy(RawTransactionNormalizationView txView) {
        if (txView == null || txView.rawTransaction() == null || txView.rawTransaction().getSyncMethod() == null) {
            return SyncStrategy.ETHERSCAN;
        }
        return switch (txView.rawTransaction().getSyncMethod()) {
            case BLOCKSCOUT -> SyncStrategy.BLOCKSCOUT;
            case ETHERSCAN -> SyncStrategy.ETHERSCAN;
            case RPC -> SyncStrategy.NONE;
        };
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isLikelyClaimCall(RawTransactionNormalizationView txView) {
        String selector = txView.selector();
        if (selector != null && CLAIM_METHOD_ID.equalsIgnoreCase(selector)) {
            return true;
        }
        String functionName = txView.readRawOrExplorerTx("functionName");
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        String normalized = functionName.trim().toLowerCase();
        return normalized.startsWith("claim(") || normalized.contains(" claim(");
    }

    private void markRetry(RawTransaction tx, String errorMessage) {
        tx.setNormalizationStatus(NormalizationStatus.PENDING);
        tx.setRetryCount((tx.getRetryCount() == null ? 0 : tx.getRetryCount()) + 1);
        tx.setLastError(errorMessage);
        // Keep simple retry metadata for now; scheduler controls cadence.
        tx.setNextRetryAt(Instant.now().plusSeconds(60));
    }

    private Instant resolveBlockTimestamp(RawTransactionNormalizationView txView, NetworkId networkId,
                                          EstimatingBlockTimestampResolver estimator) {
        if (networkId == NetworkId.SOLANA) {
            Object blockTime = txView.rawData() != null ? txView.rawData().get("blockTime") : null;
            if (blockTime instanceof Number n) {
                return Instant.ofEpochSecond(n.longValue());
            }
            return null;
        }
        Instant fromRaw = txView.readTimestamp();
        if (fromRaw != null) {
            return fromRaw;
        }
        Long blockNum = txView.blockNumber() != null ? txView.blockNumber()
                : txView.blockNumberFromRawHex();
        if (blockNum == null || estimator == null) return null;
        return estimator.estimate(networkId, blockNum);
    }

    public static Long getBlockNumberFromRaw(RawTransaction tx) {
        if (tx == null) {
            return null;
        }
        return RawTransactionNormalizationView.wrap(tx).blockNumberFromRawHex();
    }

    private record ClassificationOutcome(
            List<RawClassifiedEvent> rawEvents,
            BigDecimal confidence,
            List<String> reviewReasons
    ) {
    }

    private record EnrichmentState(
            List<RawClassifiedEvent> rawEvents,
            BigDecimal confidence
    ) {
    }

    private enum SyncStrategy {
        ETHERSCAN,
        BLOCKSCOUT,
        NONE
    }

}
