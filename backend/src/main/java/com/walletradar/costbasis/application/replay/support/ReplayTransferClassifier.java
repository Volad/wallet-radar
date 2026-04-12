package com.walletradar.costbasis.application.replay.support;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

@Component
public class ReplayTransferClassifier {

    private final ReplayPendingTransferKeyFactory keyFactory;

    public ReplayTransferClassifier(ReplayPendingTransferKeyFactory keyFactory) {
        this.keyFactory = keyFactory;
    }

    public boolean shouldTreatAsContinuityTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && ((transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank())
                || (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()))
                && (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                || transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER);
    }

    public boolean isLinkedBridgeContinuityTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0
                && keyFactory.bridgeTransferKey(transaction, flow) != null;
    }

    public boolean isLinkedBridgeSettlementTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0
                && keyFactory.bridgeSettlementKey(transaction, flow) != null;
    }

    public boolean isFamilyEquivalentCustodyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
            return false;
        }
        return switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    STAKING_DEPOSIT,
                    STAKING_WITHDRAW,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW -> true;
            default -> false;
        };
    }

    public boolean isBucketOutbound(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() < 0
                && switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    LENDING_DEPOSIT,
                    STAKING_DEPOSIT,
                    VAULT_DEPOSIT,
                    LP_ENTRY -> true;
            default -> false;
        };
    }

    public boolean isBucketInbound(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0
                && switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_WITHDRAW,
                    STAKING_WITHDRAW,
                    VAULT_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
    }
}
