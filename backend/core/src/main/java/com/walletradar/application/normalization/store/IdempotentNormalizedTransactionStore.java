package com.walletradar.application.normalization.store;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Idempotent write path for canonical normalized transactions.
 *
 * <p>Before each write, all registered {@link NormalizedTransactionPostProcessor} implementations
 * are invoked on the candidate. This allows the ingestion plane (e.g.
 * {@link com.walletradar.application.cex.acquisition.venue.CexBoundaryContractStamper}) to stamp
 * additional boundary-contract fields without modifying individual normalization builders.</p>
 */
@Service
@Slf4j
public class IdempotentNormalizedTransactionStore {

    private final NormalizedTransactionRepository repository;
    private final List<NormalizedTransactionPostProcessor> postProcessors;

    public IdempotentNormalizedTransactionStore(
            NormalizedTransactionRepository repository,
            ObjectProvider<NormalizedTransactionPostProcessor> postProcessorProvider
    ) {
        this.repository = repository;
        this.postProcessors = postProcessorProvider.stream().toList();
        log.info("IdempotentNormalizedTransactionStore initialized with {} post-processors: {}",
                postProcessors.size(),
                postProcessors.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    public NormalizedTransaction upsert(NormalizedTransaction candidate) {
        Instant now = Instant.now();
        postProcessors.forEach(p -> p.process(candidate));
        return repository.findById(candidate.getId())
                .map(existing -> merge(existing, candidate, now))
                .map(repository::save)
                .orElseGet(() -> repository.save(candidate));
    }

    private NormalizedTransaction merge(
            NormalizedTransaction existing,
            NormalizedTransaction candidate,
            Instant now
    ) {
        if (existing.getStatus() == NormalizedTransactionStatus.CONFIRMED) {
            existing.setClarificationAttempts(normalizedCounter(candidate.getClarificationAttempts()));
            existing.setFullReceiptClarificationAttempts(normalizedCounter(candidate.getFullReceiptClarificationAttempts()));
            existing.setUpdatedAt(now);
            // Propagate per-flow buy-side fee signal (ADR-051): this field is newly computed at
            // normalization and must be refreshed even on already-CONFIRMED transactions so that
            // replay picks up the correct acquisitionFeeUsd after a rebuild. The field is additive
            // and does not affect pricing, stat-validation, or clarification counters.
            propagateFlowCapabilities(existing, candidate);
            // Propagate boundary-contract marker so that re-normalization refreshes the stamped field
            // on existing CONFIRMED rows without triggering a full re-process cycle.
            if (candidate.getExternalCapitalBoundary() != null) {
                existing.setExternalCapitalBoundary(candidate.getExternalCapitalBoundary());
            }
            // WS-8 (ADR-074): the network-neutral capability flags are additive and re-derived
            // deterministically from networkId/correlationId at normalization; refresh them on
            // already-CONFIRMED rows so a re-normalization sweep cannot drop them (they drive the
            // receipt-less lending and concentrated-LP read paths). Mirrors the propagation above.
            existing.setReceiptBearingCollateral(candidate.getReceiptBearingCollateral());
            existing.setLpConcentrated(candidate.getLpConcentrated());
            // ADR-072/ADR-079: the off-chain custody flag is re-derived deterministically by the
            // counterparty resolver at normalization (global TON operator registry + per-session EVM
            // destinations); refresh it on already-CONFIRMED rows so a re-normalization sweep restores
            // it if a prior copy-and-replace cycle dropped it, and never leaves the custody ledger empty.
            existing.setCustodialOffChain(candidate.getCustodialOffChain());
            // D1 (ADR-054 §9): the cross-canonical staking flag is re-derived deterministically at
            // normalization from the ADR-054 identity registry; refresh it on already-CONFIRMED rows
            // so a re-normalization sweep never drops the signal that forces pricing on the acquired
            // receipt leg.
            existing.setCrossCanonicalStakingConversion(candidate.getCrossCanonicalStakingConversion());
            return existing;
        }
        candidate.setId(existing.getId());
        candidate.setCreatedAt(existing.getCreatedAt() != null ? existing.getCreatedAt() : candidate.getCreatedAt());
        candidate.setClarificationAttempts(normalizedCounter(candidate.getClarificationAttempts()));
        candidate.setFullReceiptClarificationAttempts(normalizedCounter(candidate.getFullReceiptClarificationAttempts()));
        if (candidate.getConfirmedAt() == null) {
            candidate.setConfirmedAt(existing.getConfirmedAt());
        }
        candidate.setUpdatedAt(now);
        return candidate;
    }

    /**
     * Merges additive, deterministically re-derived per-flow capability signals from candidate flows
     * into existing CONFIRMED flows by matching on {@code role} + {@code assetSymbol}:
     * <ul>
     *   <li>ADR-051: {@code acquisitionFeeUsd} (buy-side fee) so replay picks it up after a rebuild;</li>
     *   <li>ADR-081 (C1): {@code lpReceipt} (LP-receipt flag) so a re-normalization sweep restores the
     *       FAMILY:LP_RECEIPT stamp for the confusable Meteora DAMM MLP receipt if a prior
     *       copy-and-replace cycle dropped it — the same restore contract as {@code custodialOffChain}.</li>
     * </ul>
     * Both are additive (propagated only when the candidate supplies a value), so an existing flow is
     * never wiped when the candidate does not carry the signal for that leg.
     */
    private void propagateFlowCapabilities(
            NormalizedTransaction existing,
            NormalizedTransaction candidate
    ) {
        if (existing.getFlows() == null || candidate.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow candidateFlow : candidate.getFlows()) {
            if (candidateFlow == null) {
                continue;
            }
            boolean hasFee = candidateFlow.getAcquisitionFeeUsd() != null;
            boolean hasLpReceipt = Boolean.TRUE.equals(candidateFlow.getLpReceipt());
            if (!hasFee && !hasLpReceipt) {
                continue;
            }
            for (NormalizedTransaction.Flow existingFlow : existing.getFlows()) {
                if (existingFlow == null) {
                    continue;
                }
                if (existingFlow.getRole() == candidateFlow.getRole()
                        && java.util.Objects.equals(existingFlow.getAssetSymbol(), candidateFlow.getAssetSymbol())) {
                    if (hasFee) {
                        existingFlow.setAcquisitionFeeUsd(candidateFlow.getAcquisitionFeeUsd());
                    }
                    if (hasLpReceipt) {
                        existingFlow.setLpReceipt(Boolean.TRUE);
                    }
                    break;
                }
            }
        }
    }

    private int normalizedCounter(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
