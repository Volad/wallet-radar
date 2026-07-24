package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.counterparty.CounterpartyType;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * EVM counterparty resolver (ADR-066). Holds the resolution logic that previously lived inline in
 * {@link CounterpartyEnrichmentService}; behaviour is preserved byte-for-byte so existing EVM
 * fixtures and tests remain green. Reads EVM receipt-shaped raw evidence via
 * {@link OnChainRawTransactionView} and delegates row-local resolution to
 * {@link CounterpartyResolutionService}.
 */
@Component
public class EvmCounterpartyResolver implements CounterpartyResolver {

    private final CounterpartyResolutionService resolutionService;

    public EvmCounterpartyResolver(CounterpartyResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    /** EVM is the default family: it also handles a {@code null} network for legacy rows. */
    @Override
    public boolean supports(@Nullable NetworkId networkId) {
        return networkId != NetworkId.SOLANA && networkId != NetworkId.TON;
    }

    @Override
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
}
