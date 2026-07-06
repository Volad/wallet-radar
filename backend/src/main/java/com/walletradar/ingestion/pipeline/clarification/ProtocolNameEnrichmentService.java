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
                "Protocol name enriched normalizedTxId={} protocolName={} protocolVersion={}",
                normalizedTransaction.getId(),
                normalizedTransaction.getProtocolName(),
                normalizedTransaction.getProtocolVersion()
        );
        return true;
    }

    public boolean enrichInPlace(
            NormalizedTransaction normalizedTransaction,
            @Nullable RawTransaction rawTransaction,
            Instant now
    ) {
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
                ? (needsCanonicalization ? canonicalCurrentName : currentName)
                : protocolNameCanonicalizer.canonicalize(resolved.protocolName());
        String targetVersion = currentVersion;
        if ((targetVersion == null || targetVersion.isBlank())
                && resolved != null
                && resolved.protocolVersion() != null
                && !resolved.protocolVersion().isBlank()) {
            targetVersion = resolved.protocolVersion();
        }

        String targetState = targetProtocolState(normalizedTransaction, rawTransaction, resolved, targetName);
        String targetEvidence = targetProtocolEvidence(rawTransaction, resolved, targetName);

        if ((targetName == null || targetName.isBlank()) && targetState == null) {
            return false;
        }

        boolean changed = false;
        if (targetName != null && !targetName.isBlank() && !Objects.equals(currentName, targetName)) {
            normalizedTransaction.setProtocolName(targetName);
            changed = true;
        }
        if (!Objects.equals(currentVersion, targetVersion)) {
            normalizedTransaction.setProtocolVersion(targetVersion);
            changed = true;
        }
        if (targetState != null && !Objects.equals(normalizedTransaction.getProtocolResolutionState(), targetState)) {
            normalizedTransaction.setProtocolResolutionState(targetState);
            changed = true;
        }
        if (targetEvidence != null && !Objects.equals(normalizedTransaction.getProtocolResolutionEvidence(), targetEvidence)) {
            normalizedTransaction.setProtocolResolutionEvidence(targetEvidence);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        normalizedTransaction.setUpdatedAt(now == null ? Instant.now() : now);
        return true;
    }

    private String targetProtocolState(
            NormalizedTransaction normalizedTransaction,
            @Nullable RawTransaction rawTransaction,
            @Nullable ProtocolNameResolutionService.ResolvedProtocolName resolved,
            @Nullable String targetName
    ) {
        if (resolved != null) {
            return resolved.role() == null
                    ? MetadataResolutionState.RESOLVED_FAMILY
                    : MetadataResolutionState.RESOLVED_EXACT;
        }
        if (targetName != null && !targetName.isBlank()) {
            return MetadataResolutionState.RESOLVED_FAMILY;
        }
        if (rawTransaction == null) {
            return MetadataResolutionState.IRREDUCIBLE_EVIDENCE_MISSING;
        }
        return MetadataResolutionState.TERMINAL_METADATA_ONLY;
    }

    private String targetProtocolEvidence(
            @Nullable RawTransaction rawTransaction,
            @Nullable ProtocolNameResolutionService.ResolvedProtocolName resolved,
            @Nullable String targetName
    ) {
        if (resolved != null) {
            return "REGISTRY_OR_RECEIPT_PROTOCOL";
        }
        if (targetName != null && !targetName.isBlank()) {
            return "EXISTING_PROTOCOL_NAME_CANONICALIZED";
        }
        if (rawTransaction == null) {
            return "RAW_TRANSACTION_MISSING";
        }
        return "EVIDENCE_CHECKS_EXHAUSTED_METADATA_ONLY";
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
