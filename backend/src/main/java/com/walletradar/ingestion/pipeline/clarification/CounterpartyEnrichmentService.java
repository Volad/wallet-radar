package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
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
        if (normalizedTransaction == null) {
            return false;
        }

        CounterpartyResolutionService.ResolvedCounterparty resolved = rawTransaction == null
                ? CounterpartyResolutionService.ResolvedCounterparty.missingRaw()
                : resolutionService.resolveMetadata(normalizedTransaction, rawTransaction);
        if (resolved == null) {
            return false;
        }

        boolean changed = false;
        if (resolved.address() != null
                && !resolved.address().isBlank()
                && !Objects.equals(normalizedTransaction.getCounterpartyAddress(), resolved.address())) {
            normalizedTransaction.setCounterpartyAddress(resolved.address());
            changed = true;
        }
        if (resolved.counterpartyType() != null
                && !Objects.equals(normalizedTransaction.getCounterpartyType(), resolved.counterpartyType())) {
            normalizedTransaction.setCounterpartyType(resolved.counterpartyType());
            changed = true;
        }
        if (resolved.resolutionState() != null
                && !Objects.equals(normalizedTransaction.getCounterpartyResolutionState(), resolved.resolutionState())) {
            normalizedTransaction.setCounterpartyResolutionState(resolved.resolutionState());
            changed = true;
        }
        if (resolved.evidence() != null
                && !Objects.equals(normalizedTransaction.getCounterpartyResolutionEvidence(), resolved.evidence())) {
            normalizedTransaction.setCounterpartyResolutionEvidence(resolved.evidence());
            changed = true;
        }
        if (promoteExternalTransferToInternal(normalizedTransaction)) {
            changed = true;
        }
        if (enrichFlowCounterparty(normalizedTransaction, rawTransaction)) {
            changed = true;
        }
        FlowCounterpartySupport.applyTransactionCounterparty(normalizedTransaction);
        if (!changed) {
            return false;
        }

        normalizedTransaction.setUpdatedAt(now == null ? Instant.now() : now);
        return true;
    }

    private boolean promoteExternalTransferToInternal(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null || transaction.getCounterpartyType() == null) {
            return false;
        }
        if (CounterpartyType.CEX.equals(transaction.getCounterpartyType())) {
            return false;
        }
        if (!CounterpartyType.PERSONAL_WALLET.equals(transaction.getCounterpartyType())) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && type != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        transaction.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            transaction.setExcludedFromAccounting(false);
            transaction.setAccountingExclusionReason(null);
        }
        return true;
    }

    private boolean enrichFlowCounterparty(
            NormalizedTransaction transaction,
            @Nullable RawTransaction rawTransaction
    ) {
        if (transaction == null) {
            return false;
        }
        if (rawTransaction == null) {
            FlowCounterpartySupport.syncFlowsFromTransaction(transaction);
            return true;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        FlowCounterpartySupport.enrichOnChainFlows(
                transaction,
                view,
                (address, networkId) -> resolutionService.classifyCounterpartyType(transaction, address)
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
