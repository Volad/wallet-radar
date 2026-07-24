package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
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
        // BRIDGE_OUT/IN: only the TRANSFER-role flow carries continuity basis.
        // BUY/SELL incidental flows (e.g. dust refunds, protocol adjustments) must fall through
        // to normal BUY/SELL handling — they must NOT steal the carry queue slot meant for the
        // actual paired destination transaction, which would cause a phantom CARRY_IN on the
        // source network and leave the destination with a stale provisional/market-price basis.
        if ((transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_IN)
                && flow.getRole() != NormalizedLegRole.TRANSFER) {
            return false;
        }
        if (Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && ((transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank())
                || (transaction.getTxHash() != null && !transaction.getTxHash().isBlank()))
                && (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.FIAT_EXIT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                || transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                || transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.EARN_FLEXIBLE_SAVING)) {
            return true;
        }
        return transaction.getSource() == NormalizedTransactionSource.BYBIT
                && (transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                || transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
    }

    private static boolean isBybitSource(NormalizedTransaction transaction) {
        return transaction != null && transaction.getSource() == NormalizedTransactionSource.BYBIT;
    }

    /**
     * Finding 2 — a same-network custody/parking round-trip leg linked by
     * {@code SameNetworkCustodyRoundTripLinkService} (shared {@code bridge:custody-roundtrip:}
     * correlation, {@code continuityCandidate=true}, principals demoted to price-less TRANSFER).
     *
     * <p>These legs are pooled into one cross-family basis envelope and redistributed by return-time
     * market value, so they must NOT flow through the per-family bridge continuity queue (which
     * restores each family's carried-out basis independently and breaks conservation when the vault
     * rebalances the returned composition). This predicate routes them to the dedicated envelope path
     * instead. Deterministic; keyed only on the generalized correlation prefix, never a tx hash.
     */
    public boolean isCustodyRoundTripContinuityTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || !Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        return correlationId != null
                && correlationId.startsWith(CorrelationContract.BRIDGE_CUSTODY_ROUNDTRIP_PREFIX);
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
                && correlationId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)) {
            return false;
        }
        String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
            return false;
        }
        if (AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction)) {
            return false;
        }
        return switch (transaction.getType()) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    EARN_FLEXIBLE_SAVING,
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
        if (blocksCrossCanonicalBucketCarry(transaction)) {
            return false;
        }
        if (flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() >= 0) {
            return false;
        }
        // B-ETH-02: a linked LENDING_LOOP_OPEN parks its collateral principal outbound leg into a
        // network-agnostic continuity bucket so the later DECREASE/CLOSE restores the carried basis
        // instead of re-pricing the returned collateral at market. The borrow/debt leg is excluded
        // by dominantCollateralPrincipal so it is not double-counted.
        if (isLendingLoopCollateralPrincipal(transaction, flow, true)) {
            return true;
        }
        return switch (transaction.getType()) {
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
                    EARN_FLEXIBLE_SAVING,
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
        if (blocksCrossCanonicalBucketCarry(transaction)) {
            return false;
        }
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
            return false;
        }
        // B-ETH-02: a linked LENDING_LOOP_DECREASE/CLOSE restores its collateral principal inbound
        // leg pro-rata from the network-agnostic bucket parked on the OPEN. Any residual basis stays
        // in the bucket for the next decrease/final close.
        if (isLendingLoopCollateralPrincipal(transaction, flow, false)) {
            return true;
        }
        // For LP_POSITION_UNSTAKE: only the LP-RECEIPT positive TRANSFER (the principal return)
        // should restore from the wrapper bucket. Extra reward flows credited in the same
        // transaction (e.g. BAL gauge rewards) are standard ACQUIRE events and must not drain the
        // bucket before the LP-RECEIPT restore runs. Without this guard, a BAL reward inbound
        // would consume the wrapper:<gauge> carry placed by the GAUGE outbound, leaving the
        // LP-RECEIPT restore with an empty bucket and forcing $0 basis onto the position.
        if (transaction.getType() == NormalizedTransactionType.LP_POSITION_UNSTAKE) {
            String sym = flow.getAssetSymbol();
            if (sym == null || !sym.trim().toUpperCase(java.util.Locale.ROOT).startsWith("LP-RECEIPT:")) {
                return false;
            }
        }
        return switch (transaction.getType()) {
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
                    EARN_FLEXIBLE_SAVING,
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
        return corrId != null && corrId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX);
    }

    /**
     * RC-9 D2 — withdrawal-direction corridor inbound (CEX → user wallet). The credit lands on an
     * on-chain wallet whose corridor counterpart is the Bybit CEX. The released basis sits on the
     * CEX spot ledger, which is not always tracked as an on-chain lot, so when no matching carry
     * arrives a spot-price ACQUIRE is the legal fallback.
     *
     * <p>Restricted to the on-chain inbound leg whose {@code matchedCounterparty} is a Bybit
     * endpoint. On-chain↔on-chain corridors (both endpoints are wallets) and the deposit direction
     * are deliberately excluded so a missing carry there is treated as an error, not silently
     * back-filled at spot.
     */
    public boolean isCexWithdrawalCorridorInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (!isCorridorTransfer(transaction)
                || transaction.getSource() != NormalizedTransactionSource.ON_CHAIN
                || flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0) {
            return false;
        }
        return hasBybitEndpoint(transaction.getMatchedCounterparty())
                || hasBybitEndpoint(flow.getCounterpartyAddress());
    }

    /**
     * RC-9 D2 — deposit-direction corridor (user wallet → CEX). The credit lands on the Bybit side
     * and MUST inherit the on-chain {@code CARRY_OUT}'s released basis. A spot fallback is
     * forbidden here: a missing carry means a linking/determinism defect and must surface to the
     * conservation guard rather than be masked by a fabricated spot acquisition.
     */
    public boolean isCexDepositCorridor(NormalizedTransaction transaction) {
        return isCorridorTransfer(transaction)
                && transaction.getSource() == NormalizedTransactionSource.BYBIT;
    }

    private static boolean hasBybitEndpoint(String value) {
        return value != null && WalletRef.parse(value).domain() == WalletDomainKind.CEX;
    }

    public boolean usesBybitVenueInternalCarryQueue(NormalizedTransaction transaction) {
        return keyFactory.usesBybitVenueInternalCarryQueue(transaction);
    }

    public boolean isBybitMultiLegBundleTransfer(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        return correlationId != null && correlationId.startsWith(CorrelationContract.BYBIT_IT_BUNDLE_V1_PREFIX);
    }

    /**
     * Returns true for bybit-rekeyed-v1 FUND→UTA internal carries.
     *
     * <p>Bybit captures FUND→UTA amounts with two decimal precisions that often differ by a small
     * rounding error (e.g. 1.569276 vs 1.5692). The default qty-compatible matching rejects these
     * pairs. Bridge-style matching (qty-agnostic) is therefore required so the carry-out created by
     * the FUND debit can be consumed by the UTA credit despite the tiny quantity mismatch.
     */
    public boolean isRekeyedVenueTransfer(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        return correlationId != null && correlationId.startsWith(CorrelationContract.BYBIT_REKEYED_V1_PREFIX);
    }

    /**
     * B-ETH-02: true when {@code flow} is the collateral principal leg of a linked lending-loop
     * OPEN (outbound, {@code openSide=true}) or DECREASE/CLOSE (inbound, {@code openSide=false}).
     *
     * <p>Gating anchors: the transaction type, a {@code lending-loop:} correlation id (only linked
     * loops route through the bucket — an unpaired loop keeps today's market pricing), a
     * TRANSFER-role leg of the required sign, and dominance over any sibling same-sign leg. The
     * debt/borrow receipt (variable/stable debt token) is explicitly excluded so only the collateral
     * is parked/restored and the borrow leg is never double-counted through the continuity bucket.
     */
    private static boolean isLendingLoopCollateralPrincipal(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            boolean openSide
    ) {
        if (transaction == null || flow == null || flow.getQuantityDelta() == null) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        boolean typeMatches = openSide
                ? type == NormalizedTransactionType.LENDING_LOOP_OPEN
                : (type == NormalizedTransactionType.LENDING_LOOP_DECREASE
                        || type == NormalizedTransactionType.LENDING_LOOP_CLOSE);
        if (!typeMatches) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null || !correlationId.startsWith(CorrelationContract.LENDING_LOOP_PREFIX)) {
            return false;
        }
        if (flow.getRole() != NormalizedLegRole.TRANSFER) {
            return false;
        }
        int requiredSign = openSide ? -1 : 1;
        if (flow.getQuantityDelta().signum() != requiredSign) {
            return false;
        }
        return flow == dominantCollateralPrincipal(transaction, requiredSign);
    }

    /**
     * B-ETH-02: selects the dominant collateral principal leg among the same-sign TRANSFER flows,
     * excluding debt/borrow receipts. Ranks by resolvable USD value, falling back to absolute
     * quantity when no USD value is available (the loop OPEN/CLOSE has a single collateral principal
     * of each sign in practice, so the fallback merely provides deterministic behaviour).
     */
    private static NormalizedTransaction.Flow dominantCollateralPrincipal(
            NormalizedTransaction transaction,
            int requiredSign
    ) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        NormalizedTransaction.Flow best = null;
        java.math.BigDecimal bestValue = null;
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() != NormalizedLegRole.TRANSFER
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() != requiredSign) {
                continue;
            }
            if (AccountingAssetIdentitySupport.isDebtIdentity(candidate.getAssetSymbol())) {
                continue;
            }
            java.math.BigDecimal candidateValue = principalRankingValue(candidate);
            if (best == null || candidateValue.compareTo(bestValue) > 0) {
                best = candidate;
                bestValue = candidateValue;
            }
        }
        return best;
    }

    private static java.math.BigDecimal principalRankingValue(NormalizedTransaction.Flow flow) {
        if (flow.getValueUsd() != null && flow.getValueUsd().abs().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null
                && flow.getUnitPriceUsd().signum() > 0
                && flow.getQuantityDelta() != null) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd());
        }
        return flow.getQuantityDelta() == null
                ? java.math.BigDecimal.ZERO
                : flow.getQuantityDelta().abs();
    }

    /**
     * ADR-054: identity-changing staking/vault moves must not use continuity buckets (1:1 carry).
     */
    private static boolean blocksCrossCanonicalBucketCarry(NormalizedTransaction transaction) {
        if (!AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction)) {
            return false;
        }
        return switch (transaction.getType()) {
            case STAKING_DEPOSIT, STAKING_WITHDRAW, VAULT_DEPOSIT, VAULT_WITHDRAW -> true;
            default -> false;
        };
    }
}
