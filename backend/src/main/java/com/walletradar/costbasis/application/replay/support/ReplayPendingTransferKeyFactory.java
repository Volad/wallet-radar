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

    /**
     * True when this transaction routes bucketed flows through a shared {@code lp:} or
     * {@code wrapper:} composite continuity bucket (multi-asset LP mint/exit, gauge/vault wrap).
     */
    public boolean usesCompositeContinuityBucket(NormalizedTransaction transaction) {
        return lpCompositeBucketIdentity(transaction) != null
                || wrapperCompositeBucketIdentity(transaction) != null;
    }

    /** Receipt token continuity identity for composite {@code lp:} bucket routing, if any. */
    public String lpCompositeReceiptIdentity(NormalizedTransaction transaction) {
        return lpCompositeBucketIdentity(transaction);
    }

    public ContinuityKey continuityKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        // Cycle/8 S5: composite LP bucket. Multi-asset LP entries (e.g., AAVE GHO/USDT/USDC,
        // Balancer / Curve stable pools) deposit several distinct families and mint one LP
        // token. Each source leg parked its basis in its own FAMILY:XXX bucket previously, so
        // the LP receipt itself carried $0 basis and the ledger reported the LP position as
        // uncovered until exit. The mirror problem afflicts LP exits: the LP token burn could
        // not find the family-keyed source basis on the way back.
        //
        // The fix routes EVERY bucketed flow of an LP_ENTRY / LP_EXIT* transaction into the
        // same composite key {@code lp:<lpReceiptIdentity>}. Source-asset basis is therefore
        // aggregated in a single LP-receipt bucket on entry and reapplied on exit. The LP
        // receipt token inherits the full source basis on its inbound restore, eliminating the
        // 100% uncovered LP positions observed in Cycle/8 audit (AAVE GHO/USDT/USDC = 2144 /
        // 0 cov before this change).
        // Cycle/17 R7: gauge↔LP round-trip must reuse the same bucket key as stake. Stake deposits
        // into {@code wrapper:<gauge>} via LENDING_DEPOSIT; misclassified unstake as LP_EXIT used
        // to route through {@code lp:<lpToken>} and read an empty bucket (AAVE GHO gauge cov=0%).
        String wrapperCompositeIdentity = wrapperCompositeBucketIdentity(transaction);
        String lpCompositeIdentity = lpCompositeBucketIdentity(transaction);
        if (wrapperCompositeIdentity != null && lpCompositeIdentity != null) {
            return new ContinuityKey(
                    transaction.getWalletAddress(),
                    transaction.getNetworkId(),
                    "wrapper:" + wrapperCompositeIdentity
            );
        }
        if (lpCompositeIdentity != null) {
            return new ContinuityKey(
                    transaction.getWalletAddress(),
                    transaction.getNetworkId(),
                    "lp:" + lpCompositeIdentity
            );
        }
        if (wrapperCompositeIdentity != null) {
            return new ContinuityKey(
                    transaction.getWalletAddress(),
                    transaction.getNetworkId(),
                    "wrapper:" + wrapperCompositeIdentity
            );
        }
        return new ContinuityKey(
                transaction.getWalletAddress(),
                transaction.getNetworkId(),
                assetSupport.continuityIdentity(transaction, flow)
        );
    }

    /**
     * Returns the LP receipt token continuity identity for transactions that look like a
     * multi-asset LP entry / exit, otherwise {@code null}.
     *
     * <p>Recognises two flavors:
     * <ul>
     *   <li><b>Explicit LP type</b> — {@code LP_ENTRY} and {@code LP_EXIT*}. Always composite,
     *       regardless of source-leg asset count.</li>
     *   <li><b>Implicit multi-asset LP</b> via {@code LENDING_DEPOSIT} / {@code LENDING_WITHDRAW}.
     *       Some on-chain protocols mint a single ERC-20 receipt token for multi-asset
     *       deposits (e.g., AAVE GHO/USDT/USDC stable pool, Balancer-style boosted pools).
     *       When the receipt-side flow's identity is not a {@code FAMILY:*} alias AND the
     *       opposite side has more than one distinct asset family, we treat the transaction
     *       as a composite LP for bucketing purposes. Without this carve-out, the receipt
     *       inherits no basis and the position stays uncovered.</li>
     * </ul>
     *
     * <p>The LP receipt is the flow whose continuity identity does NOT start with
     * {@code FAMILY:}. For entry-shaped flows it's the inbound positive-qty leg; for exit
     * it's the outbound negative-qty leg.</p>
     */
    private String lpCompositeBucketIdentity(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type == null) {
            return null;
        }
        boolean isExplicitEntry = type == NormalizedTransactionType.LP_ENTRY;
        boolean isExplicitExit = type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
        boolean isImplicitEntry = type == NormalizedTransactionType.LENDING_DEPOSIT
                || type == NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT
                || type == NormalizedTransactionType.STAKING_DEPOSIT
                || type == NormalizedTransactionType.VAULT_DEPOSIT;
        boolean isImplicitExit = type == NormalizedTransactionType.LENDING_WITHDRAW
                || type == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                || type == NormalizedTransactionType.STAKING_WITHDRAW
                || type == NormalizedTransactionType.VAULT_WITHDRAW;
        boolean entryShape = isExplicitEntry || isImplicitEntry;
        boolean exitShape = isExplicitExit || isImplicitExit;
        if (!entryShape && !exitShape) {
            return null;
        }

        NonFamilyReceiptCandidate positiveReceipt = dominantNonFamilyReceipt(transaction, 1, assetSupport);
        NonFamilyReceiptCandidate negativeReceipt = dominantNonFamilyReceipt(transaction, -1, assetSupport);

        String receiptIdentity;
        if (positiveReceipt != null && negativeReceipt == null) {
            receiptIdentity = positiveReceipt.identity();
        } else if (negativeReceipt != null && positiveReceipt == null) {
            receiptIdentity = negativeReceipt.identity();
        } else if (negativeReceipt != null && positiveReceipt != null) {
            if (entryShape) {
                receiptIdentity = positiveReceipt.identity();
            } else {
                // Dominant leg by abs qty disambiguates true LP_EXIT (large negative LP burn) from
                // misclassified mint-shaped LP_EXIT (large positive LP receipt, smaller outbound legs).
                boolean mintShapedExit =
                        positiveReceipt.absQuantity().compareTo(negativeReceipt.absQuantity()) > 0;
                receiptIdentity = mintShapedExit ? positiveReceipt.identity() : negativeReceipt.identity();
            }
        } else {
            return null;
        }

        if (isExplicitEntry || isExplicitExit) {
            return receiptIdentity;
        }

        int counterpartySign = positiveReceipt != null && receiptIdentity.equals(positiveReceipt.identity()) ? -1 : 1;
        // Implicit LP detection: require >=2 distinct counterparty-side asset identities.
        // Single-asset lending / staking deposits keep the legacy family-keyed bucket so the
        // {@link FamilyEquivalentCustodyReplayHandler} fast path remains intact.
        java.util.Set<String> counterpartyIdentities = new java.util.HashSet<>();
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() == NormalizedLegRole.FEE
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() != counterpartySign) {
                continue;
            }
            String identity = assetSupport.continuityIdentity(transaction, candidate);
            if (identity != null && !identity.isBlank()) {
                counterpartyIdentities.add(identity);
            }
        }
        if (counterpartyIdentities.size() < 2) {
            return null;
        }
        return receiptIdentity;
    }

    private record NonFamilyReceiptCandidate(String identity, java.math.BigDecimal absQuantity) {
    }

    private static NonFamilyReceiptCandidate dominantNonFamilyReceipt(
            NormalizedTransaction transaction,
            int sign,
            ReplayAssetSupport assetSupport
    ) {
        if (transaction == null || transaction.getFlows() == null || assetSupport == null) {
            return null;
        }
        NonFamilyReceiptCandidate best = null;
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() == NormalizedLegRole.FEE
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() != sign) {
                continue;
            }
            String identity = assetSupport.continuityIdentity(transaction, candidate);
            if (identity == null || identity.isBlank() || identity.startsWith("FAMILY:")) {
                continue;
            }
            java.math.BigDecimal abs = candidate.getQuantityDelta().abs();
            if (best == null || abs.compareTo(best.absQuantity()) > 0) {
                best = new NonFamilyReceiptCandidate(identity, abs);
            }
        }
        return best;
    }

    /**
     * Two-leg wrapper pass-through (LP token → gauge/vault share, or reverse). Covers explicit
     * {@link NormalizedTransactionType#LP_POSITION_STAKE} / {@code VAULT_DEPOSIT} and production
     * Curve/Aura rows still classified as {@code LENDING_DEPOSIT} / {@code LENDING_WITHDRAW}.
     */
    private String wrapperCompositeBucketIdentity(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type == null) {
            return null;
        }
        boolean depositShape = switch (type) {
            case LP_POSITION_STAKE,
                    VAULT_DEPOSIT,
                    LENDING_DEPOSIT,
                    STAKING_DEPOSIT,
                    PROTOCOL_CUSTODY_DEPOSIT,
                    LP_ENTRY -> true;
            default -> false;
        };
        boolean withdrawShape = switch (type) {
            case LP_POSITION_UNSTAKE,
                    VAULT_WITHDRAW,
                    LENDING_WITHDRAW,
                    STAKING_WITHDRAW,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
        if (!depositShape && !withdrawShape) {
            return null;
        }
        int receiptSign = depositShape ? 1 : -1;
        int counterpartySign = -receiptSign;

        String receiptIdentity = null;
        int principalLegs = 0;
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() == NormalizedLegRole.FEE
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (candidate.getRole() != NormalizedLegRole.TRANSFER) {
                return null;
            }
            principalLegs++;
            int sign = candidate.getQuantityDelta().signum();
            if (sign != receiptSign) {
                continue;
            }
            String identity = assetSupport.continuityIdentity(transaction, candidate);
            if (identity == null || identity.isBlank() || identity.startsWith("FAMILY:")) {
                continue;
            }
            receiptIdentity = identity;
        }
        if (principalLegs != 2 || receiptIdentity == null) {
            return null;
        }
        return receiptIdentity;
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
