package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.RegistryBridgeInboundTypeCorrectionService;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.PendingReclassificationQueryService;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the normal on-chain classifier over rows whose clarification evidence is now persisted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnChainReclassificationService {

    private final PendingReclassificationQueryService pendingReclassificationQueryService;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final OnChainNormalizationProperties properties;
    private final OnChainClarificationProperties clarificationProperties;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder builder;
    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;
    private final RegistryBridgeInboundTypeCorrectionService registryBridgeInboundTypeCorrectionService;
    private final CounterpartyEnrichmentService counterpartyEnrichmentService;
    private final AccountingUniverseService accountingUniverseService;

    public int processNextBatch() {
        return processNextBatch(null);
    }

    public int processNextBatch(String sessionId) {
        bindUniverseIfPresent(sessionId);
        try {
            List<NormalizedTransaction> batch = pendingReclassificationQueryService.loadNextBatch(properties.getBatchSize());
            int completed = 0;
            for (NormalizedTransaction normalizedTransaction : batch) {
                if (reclassify(normalizedTransaction)) {
                    completed++;
                }
            }
            return completed;
        } finally {
            accountingUniverseService.clearUniverseBinding();
        }
    }

    private void bindUniverseIfPresent(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            accountingUniverseService.bindUniverse(sessionId.trim());
        }
    }

    public boolean reclassify(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null || normalizedTransaction.getId() == null) {
            return false;
        }
        Instant now = Instant.now();
        return rawTransactionRepository.findById(normalizedTransaction.getId())
                .map(rawTransaction -> reclassify(normalizedTransaction, rawTransaction, now))
                .orElseGet(() -> markMissingRaw(normalizedTransaction, now));
    }

    private boolean reclassify(
            NormalizedTransaction existing,
            RawTransaction rawTransaction,
            Instant now
    ) {
        try {
            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            NormalizedTransaction reclassified = builder.rebuildAfterReclassification(
                    existing,
                    rawTransaction,
                    classificationResult,
                    now
            );
            terminalizeExhaustedClarification(reclassified);
            enrichCanonicalMetadata(reclassified, rawTransaction, now);
            NormalizedTransaction saved = normalizedTransactionRepository.save(reclassified);
            log.debug(
                    "On-chain reclassification complete: normalizedTxId={}, status={}, type={}",
                    saved.getId(),
                    saved.getStatus(),
                    saved.getType()
            );
            return true;
        } catch (RuntimeException error) {
            log.warn(
                    "On-chain reclassification failed for normalizedTxId={}: {}",
                    existing.getId(),
                    error.getMessage(),
                    error
            );
            return false;
        }
    }

    private void enrichCanonicalMetadata(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now
    ) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            return;
        }
        protocolNameEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        registryBridgeInboundTypeCorrectionService.correctIfApplicable(normalizedTransaction, rawTransaction, now);
        counterpartyEnrichmentService.enrichInPlace(normalizedTransaction, rawTransaction, now);
        builder.enrichFluidEvidence(normalizedTransaction, rawTransaction);
    }

    private void terminalizeExhaustedClarification(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() != NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            return;
        }
        int maxAttempts = Math.max(1, clarificationProperties.getMaxAttempts());
        if (safeCounter(normalizedTransaction.getClarificationAttempts()) < maxAttempts
                && !shouldTerminalizeAfterReceiptOnlyClarification(normalizedTransaction)) {
            return;
        }
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(normalizedTransaction.getMissingDataReasons());
        if (!reasons.contains(ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code())) {
            reasons.add(ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code());
        }
        normalizedTransaction.setStatus(hasReplayableFlows(normalizedTransaction)
                ? NormalizedTransactionStatus.PENDING_PRICE
                : NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setMissingDataReasons(List.copyOf(reasons));
        normalizedTransaction.setClarificationLeaseUntil(null);
        normalizedTransaction.setClarificationWorkerId(null);
    }

    private boolean shouldTerminalizeAfterReceiptOnlyClarification(NormalizedTransaction normalizedTransaction) {
        if (safeCounter(normalizedTransaction.getClarificationAttempts()) <= 0) {
            return false;
        }
        List<String> reasons = normalizedTransaction.getMissingDataReasons();
        if (reasons == null || reasons.isEmpty()) {
            return false;
        }
        return reasons.contains(ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code())
                || reasons.contains(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code())
                || reasons.contains(ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code())
                || reasons.contains(ClassificationReasonCode.GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED.code())
                || reasons.contains(ClassificationReasonCode.GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED.code())
                || reasons.contains(ClassificationReasonCode.GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED.code())
                || reasons.contains(ClassificationReasonCode.GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED.code());
    }

    private boolean hasReplayableFlows(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null
                || normalizedTransaction.getFlows() == null
                || normalizedTransaction.getFlows().isEmpty()) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : normalizedTransaction.getFlows()) {
            if (flow != null && flow.getRole() != null && flow.getQuantityDelta() != null) {
                return true;
            }
        }
        return false;
    }

    private boolean markMissingRaw(NormalizedTransaction normalizedTransaction, Instant now) {
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? new ArrayList<>()
                : new ArrayList<>(normalizedTransaction.getMissingDataReasons());
        if (!reasons.contains(ClassificationReasonCode.RAW_TRANSACTION_MISSING.code())) {
            reasons.add(ClassificationReasonCode.RAW_TRANSACTION_MISSING.code());
        }
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setMissingDataReasons(List.copyOf(reasons));
        normalizedTransaction.setUpdatedAt(now);
        normalizedTransactionRepository.save(normalizedTransaction);
        return true;
    }

    private int safeCounter(Integer attempts) {
        return attempts == null ? 0 : Math.max(0, attempts);
    }
}
