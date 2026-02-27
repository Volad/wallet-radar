package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.ingestion.adapter.TransactionClarificationResolver;
import com.walletradar.ingestion.normalizer.NormalizedTransactionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Scheduled(fixedDelayString = "${walletradar.ingestion.clarification.schedule-interval-ms:120000}")
    public void runScheduled() {
        List<NormalizedTransaction> pending = normalizedTransactionRepository
                .findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        for (NormalizedTransaction tx : pending) {
            clarifyOne(tx);
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
            List<NormalizedTransaction.Leg> mergedLegs = new ArrayList<>();
            if (tx.getLegs() != null) {
                mergedLegs.addAll(tx.getLegs());
            }
            for (NormalizedTransaction.Leg inferred : result.inferredLegs()) {
                if (inferred.getInferenceReason() == null || inferred.getInferenceReason().isBlank()) {
                    inferred.setInferenceReason(result.inferenceReason());
                }
                if (inferred.getConfidence() == null) {
                    inferred.setConfidence(result.confidence());
                }
                inferred.setInferred(true);
                mergedLegs.add(inferred);
            }
            tx.setLegs(mergedLegs);
            tx.setConfidence(result.confidence());
        }

        List<String> recalculatedReasons = NormalizedTransactionValidator.missingDataReasons(tx.getType(), tx.getLegs());
        tx.setMissingDataReasons(recalculatedReasons);
        if (recalculatedReasons.isEmpty()) {
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        } else {
            attempts++;
            tx.setClarificationAttempts(attempts);
            if (attempts >= Math.max(1, maxRetries)) {
                Set<String> reasons = new LinkedHashSet<>(recalculatedReasons);
                reasons.add("CLARIFICATION_UNRESOLVED");
                tx.setMissingDataReasons(List.copyOf(reasons));
                tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            } else {
                tx.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
            }
        }
        tx.setUpdatedAt(Instant.now());
        normalizedTransactionRepository.save(tx);
    }
}
