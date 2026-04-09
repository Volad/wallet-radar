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
import java.util.Objects;
import java.util.Optional;

/**
 * Clarification-adjacent protocolName enrichment that uses persisted raw and clarification evidence
 * without changing transaction economics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolNameEnrichmentService {

    private final ProtocolNameEnrichmentQueryService queryService;
    private final ProtocolNameResolutionService resolutionService;
    private final ProtocolNameCanonicalizer protocolNameCanonicalizer;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int processNextBatch(int batchSize) {
        List<NormalizedTransaction> batch = queryService.loadNextBatch(batchSize);
        int updated = 0;
        Instant now = Instant.now();
        for (NormalizedTransaction transaction : batch) {
            Optional<RawTransaction> rawTransaction = loadRaw(transaction);
            if (enrich(transaction, rawTransaction.orElse(null), now)) {
                updated++;
            }
        }
        return updated;
    }

    public int processRepairSweep(int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);
        int updated = 0;
        String afterId = null;
        while (true) {
            List<NormalizedTransaction> batch = queryService.loadRepairBatchAfter(afterId, boundedBatchSize);
            if (batch.isEmpty()) {
                return updated;
            }
            Instant now = Instant.now();
            for (NormalizedTransaction transaction : batch) {
                afterId = transaction.getId();
                Optional<RawTransaction> rawTransaction = loadRaw(transaction);
                if (enrich(transaction, rawTransaction.orElse(null), now)) {
                    updated++;
                }
            }
        }
    }

    public boolean enrich(NormalizedTransaction normalizedTransaction, @Nullable RawTransaction rawTransaction, Instant now) {
        if (normalizedTransaction == null) {
            return false;
        }

        String currentName = normalizedTransaction.getProtocolName();
        String currentVersion = normalizedTransaction.getProtocolVersion();
        String canonicalCurrentName = protocolNameCanonicalizer.canonicalize(currentName);
        boolean needsCanonicalization = protocolNameCanonicalizer.needsCanonicalization(currentName);

        ProtocolNameResolutionService.ResolvedProtocolName resolved = rawTransaction == null
                ? null
                : resolutionService.resolve(normalizedTransaction, rawTransaction).orElse(null);

        String targetName = resolved == null
                ? (needsCanonicalization ? canonicalCurrentName : null)
                : protocolNameCanonicalizer.canonicalize(resolved.protocolName());
        String targetVersion = currentVersion;
        if ((targetVersion == null || targetVersion.isBlank())
                && resolved != null
                && resolved.protocolVersion() != null
                && !resolved.protocolVersion().isBlank()) {
            targetVersion = resolved.protocolVersion();
        }

        if (targetName == null || targetName.isBlank()) {
            return false;
        }
        if (Objects.equals(currentName, targetName) && Objects.equals(currentVersion, targetVersion)) {
            return false;
        }

        normalizedTransaction.setProtocolName(targetName);
        normalizedTransaction.setProtocolVersion(targetVersion);
        normalizedTransaction.setUpdatedAt(now == null ? Instant.now() : now);
        normalizedTransactionRepository.save(normalizedTransaction);
        log.debug(
                "Protocol name enriched normalizedTxId={} protocolName={} protocolVersion={}",
                normalizedTransaction.getId(),
                normalizedTransaction.getProtocolName(),
                normalizedTransaction.getProtocolVersion()
        );
        return true;
    }

    private Optional<RawTransaction> loadRaw(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null
                || normalizedTransaction.getTxHash() == null
                || normalizedTransaction.getNetworkId() == null
                || normalizedTransaction.getWalletAddress() == null) {
            return Optional.empty();
        }
        String rawId = normalizedTransaction.getTxHash().toLowerCase()
                + ":"
                + normalizedTransaction.getNetworkId().name()
                + ":"
                + normalizedTransaction.getWalletAddress().toLowerCase();
        return rawTransactionRepository.findById(rawId);
    }
}
