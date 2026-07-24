package com.walletradar.application.costbasis.application.replay.persistence;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AssetLedgerSupport;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;

public final class LedgerPointCollector {

    private static final MathContext MC = MathContext.DECIMAL128;

    private long replaySequence;
    private final String accountingUniverseId;
    private final List<AssetLedgerPoint> points;
    private final Instant createdAt;

    public LedgerPointCollector(String accountingUniverseId, List<AssetLedgerPoint> points, Instant createdAt) {
        this.accountingUniverseId = accountingUniverseId;
        this.points = points;
        this.createdAt = createdAt;
    }

    public void record(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            AssetKey assetKey,
            PositionSnapshot before,
            PositionState after,
            AssetLedgerPoint.BasisEffect basisEffect
    ) {
        if (transaction == null || flow == null || assetKey == null || before == null || after == null || before.sameAs(after)) {
            return;
        }
        // B-DOUBLE-LEDGER-POINT: suppress phantom carry/acquire points that carry zero financial
        // content. These arise when attachLateCarryToPendingInbound fires for a pending inbound
        // that was already materialised with zero provisional basis (no spot price available),
        // resulting in a second ledger point with qty delta=0 and basis delta=0. The point's
        // only visible effect is a spurious AVCO spike caused by a changed uncoveredQuantity —
        // a cosmetic artifact with no financial substance.
        if (isPhantomCarryPoint(before, after, basisEffect)) {
            return;
        }
        if (transaction.getBlockTimestamp() == null) {
            throw new IllegalStateException("Ledger point requires blockTimestamp (txId=" + transaction.getId() + ")");
        }
        long sequence = replaySequence++;
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(accountingUniverseId + ":" + transaction.getId() + ":" + flowIndex + ":" + sequence);
        point.setAccountingUniverseId(accountingUniverseId);
        point.setWalletAddress(assetKey.walletAddress());
        point.setNetworkId(assetKey.networkId());
        point.setAccountingAssetIdentity(assetKey.assetIdentity());
        // Prefer family identity derived from the position assetKey (symbol+contract) so that
        // synthesized ledger points (e.g. LP-RECEIPT written via an ETH marker flow) get the
        // correct FAMILY:LP_RECEIPT rather than the marker flow's FAMILY:ETH. Fall back to the
        // flow-based identity only when the assetKey does not resolve to a family.
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(
                assetKey.assetSymbol(), assetKey.assetContract());
        if (familyIdentity == null || familyIdentity.isBlank()) {
            familyIdentity = AssetLedgerSupport.accountingFamilyIdentity(transaction, flow);
        }
        // ADR-080/ADR-081 (C1, durable flag route): an LP-receipt leg flagged at classification
        // (e.g. the Meteora DAMM fungible MLP whose symbol is confusable across pools, so its
        // symbol/contract resolves to the pool-mint family) must carry FAMILY:LP_RECEIPT. Driven by
        // LP-correlation membership, not the symbol — so the dashboard, spot-family aggregation and
        // LP-receipt isolation all exclude it regardless of the confusable symbol.
        if (Boolean.TRUE.equals(flow.getLpReceipt())) {
            familyIdentity = AccountingAssetFamilySupport.FAMILY_LP_RECEIPT;
        }
        point.setAccountingFamilyIdentity(familyIdentity);
        point.setFamilyDisplaySymbol(AssetLedgerSupport.familyDisplaySymbol(familyIdentity, assetKey.assetSymbol()));
        point.setAssetSymbol(assetKey.assetSymbol());
        point.setAssetContract(assetKey.assetContract());
        point.setNormalizedTransactionId(transaction.getId());
        point.setTxHash(transaction.getTxHash());
        point.setCorrelationId(transaction.getCorrelationId());
        point.setLifecycleChainId(transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()
                ? transaction.getCorrelationId()
                : transaction.getTxHash());
        point.setMatchedCounterparty(transaction.getMatchedCounterparty());
        point.setContinuityCandidate(transaction.getContinuityCandidate());
        point.setBlockTimestamp(transaction.getBlockTimestamp());
        point.setTransactionIndex(transaction.getTransactionIndex());
        point.setFlowIndex(flowIndex);
        point.setReplaySequence(sequence);
        point.setNormalizedType(transaction.getType() == null ? null : transaction.getType().name());
        point.setLifecycleKind(AssetLedgerSupport.lifecycleKind(transaction.getType()));
        point.setLifecycleStage(AssetLedgerSupport.lifecycleStage(transaction.getType()));
        point.setBasisEffect(basisEffect);
        point.setProtocolName(transaction.getProtocolName());
        point.setQuantityDelta(delta(after.quantity(), before.quantity()));
        point.setCostBasisDeltaUsd(delta(after.totalCostBasisUsd(), before.totalCostBasisUsd()));
        point.setRealisedPnlDeltaUsd(delta(after.totalRealisedPnlUsd(), before.totalRealisedPnlUsd()));
        point.setGasDeltaUsd(delta(after.totalGasPaidUsd(), before.totalGasPaidUsd()));
        point.setQuantityShortfallDelta(delta(after.quantityShortfall(), before.quantityShortfall()));
        point.setUncoveredQuantityDelta(delta(after.uncoveredQuantity(), before.uncoveredQuantity()));
        point.setQuantityBefore(before.quantity());
        point.setQuantityAfter(after.quantity());
        point.setTotalCostBasisBeforeUsd(before.totalCostBasisUsd());
        point.setTotalCostBasisAfterUsd(after.totalCostBasisUsd());
        point.setAvcoBeforeUsd(before.perWalletAvco());
        point.setAvcoAfterUsd(after.perWalletAvco());
        point.setNetTotalCostBasisBeforeUsd(before.netTotalCostBasisUsd());
        point.setNetTotalCostBasisAfterUsd(after.netTotalCostBasisUsd());
        point.setNetAvcoBeforeUsd(before.perWalletNetAvco());
        point.setNetAvcoAfterUsd(after.perWalletNetAvco());
        point.setNetCostBasisDeltaUsd(delta(after.netTotalCostBasisUsd(), before.netTotalCostBasisUsd()));
        point.setNetRealisedPnlDeltaUsd(delta(after.totalNetRealisedPnlUsd(), before.totalNetRealisedPnlUsd()));
        point.setQuantityShortfallAfter(after.quantityShortfall());
        BigDecimal qtyAfter = after.quantity() == null ? BigDecimal.ZERO : after.quantity();
        BigDecimal uncovAfter = after.uncoveredQuantity() == null ? BigDecimal.ZERO : after.uncoveredQuantity();
        // Cycle/15 R5 F2: defensive report invariant — uncov must not exceed qty on snapshots.
        if (uncovAfter.compareTo(qtyAfter) > 0) {
            uncovAfter = qtyAfter;
        }
        point.setUncoveredQuantityAfter(uncovAfter);
        point.setBasisBackedQuantityAfter(nonNegative(qtyAfter.subtract(uncovAfter, MC)));
        point.setHasIncompleteHistoryAfter(after.hasIncompleteHistory());
        point.setHasUnresolvedFlagsAfter(after.hasUnresolvedFlags());
        point.setUnresolvedFlagCountAfter(after.unresolvedFlagCount());
        point.setCreatedAt(createdAt);
        points.add(point);
    }

    private static BigDecimal delta(BigDecimal after, BigDecimal before) {
        BigDecimal left = after == null ? BigDecimal.ZERO : after;
        BigDecimal right = before == null ? BigDecimal.ZERO : before;
        return left.subtract(right, MC);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    /**
     * Returns {@code true} for phantom carry/acquire points that have zero quantity delta AND
     * zero cost-basis delta. Such points arise when {@code attachLateCarryToPendingInbound}
     * resolves a pending inbound that already has zero provisional basis (no spot price
     * available), mutating only {@code uncoveredQuantity}. The resulting AVCO spike (basis /
     * newly-covered-qty) is a cosmetic artifact with no financial substance and should not be
     * persisted as a ledger point.
     */
    private static boolean isPhantomCarryPoint(
            PositionSnapshot before,
            PositionState after,
            AssetLedgerPoint.BasisEffect basisEffect
    ) {
        if (basisEffect != AssetLedgerPoint.BasisEffect.CARRY_IN
                && basisEffect != AssetLedgerPoint.BasisEffect.CARRY_OUT
                && basisEffect != AssetLedgerPoint.BasisEffect.ACQUIRE) {
            return false;
        }
        BigDecimal qtyDelta = delta(after.quantity(), before.quantity());
        BigDecimal basisDelta = delta(after.totalCostBasisUsd(), before.totalCostBasisUsd());
        return qtyDelta.signum() == 0 && basisDelta.signum() == 0;
    }
}
