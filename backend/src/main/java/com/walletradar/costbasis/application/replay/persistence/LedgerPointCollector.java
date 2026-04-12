package com.walletradar.costbasis.application.replay.persistence;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.support.AssetLedgerSupport;
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
        long sequence = replaySequence++;
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(accountingUniverseId + ":" + transaction.getId() + ":" + flowIndex + ":" + sequence);
        point.setAccountingUniverseId(accountingUniverseId);
        point.setWalletAddress(assetKey.walletAddress());
        point.setNetworkId(assetKey.networkId());
        point.setAccountingAssetIdentity(assetKey.assetIdentity());
        String familyIdentity = AssetLedgerSupport.accountingFamilyIdentity(transaction, flow);
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
        point.setQuantityShortfallAfter(after.quantityShortfall());
        point.setUncoveredQuantityAfter(after.uncoveredQuantity());
        point.setBasisBackedQuantityAfter(nonNegative(after.quantity().subtract(after.uncoveredQuantity(), MC)));
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
}
