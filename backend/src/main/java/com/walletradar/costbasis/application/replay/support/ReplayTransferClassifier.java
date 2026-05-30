package com.walletradar.costbasis.application.replay.support;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.ingestion.pipeline.bybit.BybitEarnPrincipalTransferPairer;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
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
        if (transaction == null || flow == null || flow.getRole() == NormalizedLegRole.FEE) {
            return false;
        }
        if (Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && ((transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank())
                || (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()))
                && (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                || transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                || transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW)) {
            return true;
        }
        return transaction.getSource() == NormalizedTransactionSource.BYBIT
                && (transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                || transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW);
    }

    private static boolean isBybitSource(NormalizedTransaction transaction) {
        return transaction != null && transaction.getSource() == NormalizedTransactionSource.BYBIT;
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
                || keyFactory.usesCompositeContinuityBucket(transaction)
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId != null
                && correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)) {
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
        if (isBybitSource(transaction)) {
            return false;
        }
        return flow != null
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() < 0
                && switch (transaction.getType()) {
            // Cycle/8 S5: LP_EXIT* is added so the burned LP receipt token carries its basis
            // INTO the composite bucket, where the inbound underlying restorations can pick it
            // up. Without this the LP token disposal would dispose against AVCO and lose
            // covered quantity instead of returning basis to the exiting assets.
            //
            // Cycle/15 R5 F1: full deposit/withdraw symmetry. Wrapper composite buckets
            // (`wrapper:<receipt>`) are populated only when both directions of a 2-leg
            // receipt-style transaction route through this classifier. Previously
            // {@code LENDING_WITHDRAW} / {@code STAKING_WITHDRAW} / {@code PROTOCOL_CUSTODY_WITHDRAW}
            // were absent here, so gauge → LP unstake outbound (gauge negative leg) fell
            // through to the generic pending-transfer path which fails for wrapper shape.
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    STAKING_DEPOSIT,
                    STAKING_WITHDRAW,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW,
                    LP_ENTRY,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL,
                    LP_POSITION_STAKE,
                    LP_POSITION_UNSTAKE -> true;
            default -> false;
        };
    }

    public boolean isBucketInbound(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (isBybitSource(transaction)) {
            return false;
        }
        return flow != null
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0
                && switch (transaction.getType()) {
            // Cycle/8 S5: LP_ENTRY inbound (the minted LP receipt) restores from the composite
            // bucket populated by the source legs above. Was missing, leaving LP tokens with
            // $0 basis throughout their holding period.
            //
            // Cycle/15 R5 F1: full deposit/withdraw symmetry — without {@code LENDING_DEPOSIT}
            // (and the staking / protocol-custody siblings) on this list, the receipt-side
            // inbound leg of a wrapper-shape stake transaction (LP → gauge, vault share mint,
            // etc.) cannot read from the {@code wrapper:<receipt>} bucket that the burned
            // outbound leg deposited into. Diagnosed via the AVAX Curve `Aave GHO/USDT/USDC`
            // → gauge stake on 2025-07-31 where the gauge inherited zero basis from a fully
            // basis-backed LP token.
            case PROTOCOL_CUSTODY_WITHDRAW,
                    PROTOCOL_CUSTODY_DEPOSIT,
                    LENDING_WITHDRAW,
                    LENDING_DEPOSIT,
                    STAKING_WITHDRAW,
                    STAKING_DEPOSIT,
                    VAULT_WITHDRAW,
                    VAULT_DEPOSIT,
                    LP_ENTRY,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL,
                    LP_POSITION_STAKE,
                    LP_POSITION_UNSTAKE -> true;
            default -> false;
        };
    }

    /**
     * Bybit corridor transfer (Bybit ↔ on-chain). The correlation ID uniquely identifies
     * the pair, so quantity-based matching is unnecessary and breaks due to withdrawal fees.
     */
    public boolean isCorridorTransfer(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        return corrId != null && corrId.startsWith("BYBIT-CORRIDOR:");
    }

    /**
     * True for BYBIT-CORRIDOR:NETWORK:txHash transfers from Bybit CEX to a user wallet.
     * These have no on-chain CARRY_OUT (Bybit is a CEX), so the pending transfer mechanism
     * would never resolve. Requires immediate spot-price acquisition treatment.
     */
    public boolean isBybitCexCorridor(NormalizedTransaction transaction) {
        return isCorridorTransfer(transaction);
    }

    public boolean usesBybitVenueInternalCarryQueue(NormalizedTransaction transaction) {
        return keyFactory.usesBybitVenueInternalCarryQueue(transaction);
    }

    public boolean isBybitMultiLegBundleTransfer(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        return correlationId != null && correlationId.startsWith("bybit-it-bundle-v1:");
    }
}
