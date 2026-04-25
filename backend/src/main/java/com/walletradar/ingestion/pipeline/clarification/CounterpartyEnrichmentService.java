package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Clarification-adjacent counterparty enrichment that fills row-local counterpartyAddress from persisted raw evidence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CounterpartyEnrichmentService {

    private final CounterpartyEnrichmentQueryService queryService;
    private final CounterpartyResolutionService resolutionService;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int processNextBatch(int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);
        int updated = 0;
        String afterId = null;
        Instant now = Instant.now();
        while (updated < boundedBatchSize) {
            List<NormalizedTransaction> batch = queryService.loadBatchAfterId(afterId, boundedBatchSize);
            if (batch.isEmpty()) {
                return updated;
            }
            for (NormalizedTransaction transaction : batch) {
                afterId = transaction.getId();
                Optional<RawTransaction> rawTransaction = loadRaw(transaction);
                if (enrich(transaction, rawTransaction.orElse(null), now)) {
                    updated++;
                    if (updated >= boundedBatchSize) {
                        return updated;
                    }
                }
            }
        }
        return updated;
    }

    public boolean enrich(NormalizedTransaction normalizedTransaction, @Nullable RawTransaction rawTransaction, Instant now) {
        if (!enrichInPlace(normalizedTransaction, rawTransaction, now)) {
            return false;
        }
        normalizedTransactionRepository.save(normalizedTransaction);
        log.debug(
                "Counterparty enriched normalizedTxId={} counterpartyAddress={}",
                normalizedTransaction.getId(),
                normalizedTransaction.getCounterpartyAddress()
        );
        return true;
    }

    public boolean enrichInPlace(
            NormalizedTransaction normalizedTransaction,
            @Nullable RawTransaction rawTransaction,
            Instant now
    ) {
        if (normalizedTransaction == null || rawTransaction == null) {
            return false;
        }

        String targetCounterparty = resolutionService.resolve(normalizedTransaction, rawTransaction).orElse(null);
        if (targetCounterparty == null || targetCounterparty.isBlank()) {
            return false;
        }
        if (Objects.equals(normalizedTransaction.getCounterpartyAddress(), targetCounterparty)) {
            return false;
        }

        normalizedTransaction.setCounterpartyAddress(targetCounterparty);
        normalizedTransaction.setUpdatedAt(now == null ? Instant.now() : now);
        return true;
    }

    private Optional<RawTransaction> loadRaw(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null
                || normalizedTransaction.getTxHash() == null
                || normalizedTransaction.getNetworkId() == null
                || normalizedTransaction.getWalletAddress() == null) {
            return Optional.empty();
        }
        String txHash = normalizedTransaction.getTxHash().trim().toLowerCase(Locale.ROOT);
        String networkId = normalizedTransaction.getNetworkId().name();
        String walletAddress = normalizedTransaction.getWalletAddress().trim().toLowerCase(Locale.ROOT);
        String rawId = txHash + ":" + networkId + ":" + walletAddress;

        Optional<RawTransaction> exact = rawTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                txHash,
                networkId,
                walletAddress
        );
        if (exact != null && exact.isPresent()) {
            return exact;
        }

        Optional<RawTransaction> byId = rawTransactionRepository.findById(rawId);
        if (byId != null && byId.isPresent()) {
            return byId;
        }

        return Optional.empty();
    }
}
