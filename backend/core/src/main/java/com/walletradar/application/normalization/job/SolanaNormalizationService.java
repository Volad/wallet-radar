package com.walletradar.application.normalization.job;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.normalization.pipeline.onchain.PendingRawTransactionQueryService;
import com.walletradar.application.normalization.pipeline.CanonicalMetadataEnricher;
import com.walletradar.application.normalization.pipeline.solana.SolanaRawTransactionView;
import com.walletradar.application.normalization.pipeline.solana.SolanaNormalizedTransactionBuilder;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Processes pending Solana raw transactions from the shared {@code raw_transactions} collection.
 *
 * <p>Queries only Solana PENDING rows via {@link PendingRawTransactionQueryService#loadNextSolanaBatch},
 * builds canonical normalized documents via {@link SolanaNormalizedTransactionBuilder}, and
 * marks raw rows COMPLETE on success (or sets retry on failure).</p>
 */
@Service
@RequiredArgsConstructor
public class SolanaNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(SolanaNormalizationService.class);

    private final PendingRawTransactionQueryService pendingRawTransactionQueryService;
    private final OnChainNormalizationProperties properties;
    private final SolanaNormalizedTransactionBuilder builder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final RawTransactionRepository rawTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final CanonicalMetadataEnricher canonicalMetadataEnricher;
    private final com.walletradar.application.linking.pipeline.clarification.ExternalCustodyDestinationRegistry externalCustodyDestinationRegistry;

    public int processNextBatch() {
        return processNextBatch(null);
    }

    public int processNextBatch(String sessionId) {
        bindUniverseIfPresent(sessionId);
        try {
            List<RawTransaction> batch = pendingRawTransactionQueryService.loadNextSolanaBatch(properties.getBatchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            log.debug("Solana normalization batch loaded: size={}", batch.size());
            int completed = 0;
            Instant now = Instant.now();
            for (RawTransaction rawTransaction : batch) {
                if (normalize(rawTransaction, now)) {
                    completed++;
                }
            }
            return completed;
        } finally {
            accountingUniverseService.clearUniverseBinding();
            externalCustodyDestinationRegistry.clearSessionBinding();
        }
    }

    private boolean normalize(RawTransaction rawTransaction, Instant now) {
        if (rawTransaction.getNetworkId() == null
                || !NetworkId.SOLANA.name().equals(rawTransaction.getNetworkId())) {
            log.warn("Solana normalization skipping non-Solana row: id={}, network={}", rawTransaction.getId(), rawTransaction.getNetworkId());
            return false;
        }

        SolanaRawTransactionView view = SolanaRawTransactionView.wrap(rawTransaction);
        if (!SolanaRawTransactionView.isHeliusPayload(rawTransaction)) {
            log.warn("Solana normalization skipping row without Helius payload: id={}", rawTransaction.getId());
            markComplete(rawTransaction);
            return false;
        }

        if (!view.isValid()) {
            log.warn("Solana normalization invalid view: id={}, signature={}, wallet={}", rawTransaction.getId(), view.signature(), view.walletAddress());
            markComplete(rawTransaction);
            return false;
        }

        try {
            NormalizedTransaction normalized = builder.build(rawTransaction, now);
            canonicalMetadataEnricher.enrichSolana(normalized, rawTransaction, now);
            normalizedTransactionStore.upsert(normalized);
            markComplete(rawTransaction);
            log.debug("Solana normalization complete: id={}, signature={}, type={}", rawTransaction.getId(), view.signature(), normalized.getType());
            return true;
        } catch (RuntimeException ex) {
            log.warn("Solana normalization failed: id={}, signature={}: {}", rawTransaction.getId(), view.signature(), ex.getMessage());
            markRetry(rawTransaction, ex.getMessage(), now);
            return false;
        }
    }

    private void markComplete(RawTransaction rawTransaction) {
        rawTransaction.setNormalizationStatus(NormalizationStatus.COMPLETE);
        rawTransaction.setRetryCount(0);
        rawTransaction.setLastError(null);
        rawTransaction.setNextRetryAt(null);
        rawTransactionRepository.save(rawTransaction);
    }

    private void markRetry(RawTransaction rawTransaction, String reason, Instant now) {
        rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
        rawTransaction.setRetryCount(
                (rawTransaction.getRetryCount() == null ? 0 : rawTransaction.getRetryCount()) + 1
        );
        rawTransaction.setLastError(reason == null || reason.isBlank() ? "Solana normalization failed" : reason);
        rawTransaction.setNextRetryAt(now.plusSeconds(Math.max(1L, properties.getRetryDelaySeconds())));
        rawTransactionRepository.save(rawTransaction);
    }

    private void bindUniverseIfPresent(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            accountingUniverseService.bindUniverse(sessionId.trim());
            externalCustodyDestinationRegistry.bindSession(sessionId.trim());
        }
    }
}
