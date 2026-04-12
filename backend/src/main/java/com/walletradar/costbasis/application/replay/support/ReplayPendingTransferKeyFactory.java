package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

@Component
public class ReplayPendingTransferKeyFactory {

    private final ReplayAssetSupport assetSupport;

    public ReplayPendingTransferKeyFactory(ReplayAssetSupport assetSupport) {
        this.assetSupport = assetSupport;
    }

    public TransferPendingKey transferKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
            String assetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
            if (Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
                return new TransferPendingKey("corr-family:" + transaction.getCorrelationId() + ":" + assetKey);
            }
        }

        String quantityKey = flow.getQuantityDelta().abs().stripTrailingZeros().toPlainString();
        String assetKey = assetSupport.continuityIdentity(transaction, flow);
        if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()) {
            return new TransferPendingKey(
                    "corr:" + transaction.getCorrelationId() + ":" + assetSupport.correlatedTransferIdentity(transaction, flow) + ":" + quantityKey
            );
        }
        if (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()) {
            return new TransferPendingKey("tx:" + transaction.getTxHash() + ":" + assetKey + ":" + quantityKey);
        }
        return null;
    }

    public BridgePendingKey bridgeTransferKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        String bridgeFamilyIdentity = assetSupport.bridgeFamilyIdentity(transaction, flow);
        if (bridgeFamilyIdentity == null
                || transaction == null
                || transaction.getCorrelationId() == null
                || transaction.getCorrelationId().isBlank()) {
            return null;
        }
        return new BridgePendingKey("bridge:" + transaction.getCorrelationId() + ":" + bridgeFamilyIdentity);
    }

    public BridgeSettlementPendingKey bridgeSettlementKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || transaction.getType() == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0
                || transaction.getCorrelationId() == null
                || transaction.getCorrelationId().isBlank()
                || transaction.getMatchedCounterparty() == null
                || transaction.getMatchedCounterparty().isBlank()
                || Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || !hasSinglePrincipalTransferFlow(transaction)) {
            return null;
        }
        if (transaction.getType() != NormalizedTransactionType.BRIDGE_OUT
                && transaction.getType() != NormalizedTransactionType.BRIDGE_IN) {
            return null;
        }
        return new BridgeSettlementPendingKey("bridge-settlement:" + transaction.getCorrelationId());
    }

    public ContinuityKey continuityKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return new ContinuityKey(
                transaction.getWalletAddress(),
                transaction.getNetworkId(),
                assetSupport.continuityIdentity(transaction, flow)
        );
    }

    private boolean hasSinglePrincipalTransferFlow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        long principalTransfers = transaction.getFlows().stream()
                .filter(flow -> flow != null
                        && flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0)
                .count();
        return principalTransfers == 1;
    }
}
