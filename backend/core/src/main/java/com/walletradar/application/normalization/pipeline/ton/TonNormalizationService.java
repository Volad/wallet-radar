package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.normalization.pipeline.CanonicalMetadataEnricher;
import com.walletradar.application.normalization.pipeline.onchain.PendingRawTransactionQueryService;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic shell processor for pending TON raw transactions.
 *
 * <p>Queries only TON PENDING rows via {@link PendingRawTransactionQueryService#loadNextTonBatch},
 * builds canonical normalized documents via {@link TonNormalizedTransactionBuilder}, and
 * marks raw rows COMPLETE on success (or sets retry on failure).</p>
 *
 * <p>TON transactions are excluded from the generic EVM normalization pipeline. This service
 * processes them independently using the TON-specific builder.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TonNormalizationService {

    private final PendingRawTransactionQueryService pendingRawTransactionQueryService;
    private final OnChainNormalizationProperties properties;
    private final TonNormalizedTransactionBuilder builder;
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
            List<RawTransaction> batch = pendingRawTransactionQueryService.loadNextTonBatch(properties.getBatchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            log.debug("TON normalization batch loaded: size={}", batch.size());
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
                || !NetworkId.TON.name().equals(rawTransaction.getNetworkId())) {
            log.warn("TON normalization skipping non-TON row: id={}, network={}",
                    rawTransaction.getId(), rawTransaction.getNetworkId());
            return false;
        }

        try {
            NormalizedTransaction normalized = builder.build(rawTransaction, now, jettonFanoutClaim());
            canonicalMetadataEnricher.enrichTon(normalized, rawTransaction, now);
            normalizedTransactionStore.upsert(normalized);
            markComplete(rawTransaction);
            log.debug("TON normalization complete: id={}, hash={}, type={}",
                    rawTransaction.getId(), rawTransaction.getTxHash(), normalized.getType());
            return true;
        } catch (RuntimeException ex) {
            log.warn("TON normalization failed: id={}, hash={}: {}",
                    rawTransaction.getId(), rawTransaction.getTxHash(), ex.getMessage());
            markRetry(rawTransaction, ex.getMessage(), now);
            return false;
        }
    }

    private void markComplete(RawTransaction raw) {
        raw.setNormalizationStatus(NormalizationStatus.COMPLETE);
        raw.setRetryCount(0);
        raw.setLastError(null);
        raw.setNextRetryAt(null);
        rawTransactionRepository.save(raw);
    }

    private void markRetry(RawTransaction raw, String reason, Instant now) {
        raw.setNormalizationStatus(NormalizationStatus.PENDING);
        raw.setRetryCount((raw.getRetryCount() == null ? 0 : raw.getRetryCount()) + 1);
        raw.setLastError(reason == null || reason.isBlank() ? "TON normalization failed" : reason);
        raw.setNextRetryAt(now.plusSeconds(Math.max(1L, properties.getRetryDelaySeconds())));
        rawTransactionRepository.save(raw);
    }

    /**
     * Cross-row jetton fan-out claim (RULE 2): a raw is the canonical owner of a jetton transfer
     * identity when no sibling of the same wallet carrying that {@code transaction_hash} sorts
     * before it. The transfer is booked only on that one raw, collapsing the TON message fan-out.
     */
    private TonNormalizedTransactionBuilder.JettonFanoutClaim jettonFanoutClaim() {
        return (walletAddress, rawTxHash, jettonTransactionHash) -> {
            if (walletAddress == null || rawTxHash == null || jettonTransactionHash == null) {
                return true;
            }
            long earlierSiblings = rawTransactionRepository.countTonJettonFanoutSiblingsBefore(
                    NetworkId.TON.name(), walletAddress, jettonTransactionHash, rawTxHash);
            return earlierSiblings == 0;
        };
    }

    private void bindUniverseIfPresent(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            accountingUniverseService.bindUniverse(sessionId.trim());
            externalCustodyDestinationRegistry.bindSession(sessionId.trim());
        }
    }
}
