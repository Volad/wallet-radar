package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.ingestion.adapter.TransactionClarificationResolver;
import com.walletradar.ingestion.normalizer.NormalizedTransactionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Clarifies incomplete normalized transactions. Extra RPC calls are executed only for PENDING_CLARIFICATION.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClarificationJob {

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final List<TransactionClarificationResolver> clarificationResolvers;

    @Value("${walletradar.ingestion.clarification.max-retries:3}")
    private int maxRetries;

//    @Scheduled(fixedDelayString = "${walletradar.ingestion.clarification.schedule-interval-ms:120000}")
    public void runScheduled() {
        long startedAt = System.currentTimeMillis();
        log.info("ClarificationJob started");
        try {
            List<NormalizedTransaction> pending = normalizedTransactionRepository
                    .findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            int processed = 0;
            for (NormalizedTransaction tx : pending) {
                clarifyOne(tx);
                processed++;
            }
            log.info("ClarificationJob finished: pending={}, processed={}, durationMs={}",
                    pending.size(), processed, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("ClarificationJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }

    void clarifyOne(NormalizedTransaction tx) {
        int attempts = tx.getClarificationAttempts() == null ? 0 : tx.getClarificationAttempts();
        Optional<TransactionClarificationResolver.ClarificationResult> resultOpt = clarificationResolvers.stream()
                .filter(r -> r.supports(tx.getNetworkId()))
                .map(r -> r.clarify(tx))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        if (resultOpt.isPresent()) {
            TransactionClarificationResolver.ClarificationResult result = resultOpt.get();
            List<NormalizedTransaction.Flow> mergedFlows = new ArrayList<>();
            if (tx.getFlows() != null) {
                mergedFlows.addAll(tx.getFlows());
            }
            for (NormalizedTransaction.Flow inferred : result.inferredFlows()) {
                if (inferred.getInferenceReason() == null || inferred.getInferenceReason().isBlank()) {
                    inferred.setInferenceReason(result.inferenceReason());
                }
                if (inferred.getConfidence() == null) {
                    inferred.setConfidence(result.confidence());
                }
                inferred.setInferred(true);
                mergedFlows.add(inferred);
            }
            tx.setFlows(mergedFlows);
            tx.setConfidence(confidenceScore(result.confidence()));
        }

        List<String> recalculatedReasons = NormalizedTransactionValidator.missingDataReasons(tx.getType(), tx.getFlows());
        tx.setMissingDataReasons(recalculatedReasons);
        if (recalculatedReasons.isEmpty()) {
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            tx.setClassificationStatus(ClassificationStatus.CONFIRMED);
        } else {
            attempts++;
            tx.setClarificationAttempts(attempts);
            if (attempts >= Math.max(1, maxRetries)) {
                Set<String> reasons = new LinkedHashSet<>(recalculatedReasons);
                reasons.add("CLARIFICATION_UNRESOLVED");
                tx.setMissingDataReasons(List.copyOf(reasons));
                tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
                tx.setClassificationStatus(ClassificationStatus.NEEDS_REVIEW);
            } else {
                tx.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            }
        }
        tx.setUpdatedAt(Instant.now());
        normalizedTransactionRepository.save(tx);
    }

    private static BigDecimal confidenceScore(ConfidenceLevel confidenceLevel) {
        if (confidenceLevel == null) {
            return BigDecimal.ZERO;
        }
        return switch (confidenceLevel) {
            case HIGH -> new BigDecimal("0.90");
            case MEDIUM -> new BigDecimal("0.70");
            case LOW -> new BigDecimal("0.40");
        };
    }
}
