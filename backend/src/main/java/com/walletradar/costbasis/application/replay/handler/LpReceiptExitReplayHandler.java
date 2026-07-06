package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.accounting.support.LpReceiptSymbolSupport;
import com.walletradar.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

/**
 * Cycle/15 Z3: restores principal basis from {@code lp_receipt_basis_pools} on position-scoped
 * {@link NormalizedTransactionType#LP_EXIT*} events.
 */
@Component
public class LpReceiptExitReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String LP_CORR_PREFIX = "lp-position:";

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final LpReceiptBasisPoolService lpReceiptBasisPoolService;
    private final PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler;

    public LpReceiptExitReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            LpReceiptBasisPoolService lpReceiptBasisPoolService,
            PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.lpReceiptBasisPoolService = lpReceiptBasisPoolService;
        this.positionScopedLpExitReplayHandler = positionScopedLpExitReplayHandler;
    }

    public boolean isLpReceiptExit(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getCorrelationId() == null) {
            return false;
        }
        if (!transaction.getCorrelationId().startsWith(LP_CORR_PREFIX)) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    public boolean hasPoolBalance(NormalizedTransaction transaction, LpReceiptBasisPoolReplayContext poolContext) {
        if (poolContext == null || transaction == null || transaction.getCorrelationId() == null) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        for (LpReceiptBasisPool pool : poolContext.pools().values()) {
            if (corrId.equals(pool.getLpCorrelationId())
                    && pool.getQtyHeld() != null
                    && pool.getQtyHeld().signum() > 0) {
                return true;
            }
        }
        return false;
    }

    public void apply(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null || !hasPoolBalance(transaction, poolContext)) {
            positionScopedLpExitReplayHandler.apply(transaction, replayState);
            return;
        }

        String corrId = transaction.getCorrelationId();
        Instant touchedAt = transaction.getBlockTimestamp() != null
                ? transaction.getBlockTimestamp()
                : Instant.now();
        boolean restored = false;

        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFee(transaction, indexedFlow, replayState);
                continue;
            }
            if (positionScopedLpExitReplayHandler.shouldIgnoreLpReceiptMarker(transaction, flow)) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() <= 0) {
                applyUnknown(transaction, indexedFlow, replayState);
                continue;
            }
            if (restoreInboundFromPool(transaction, indexedFlow, corrId, poolContext, touchedAt, replayState)) {
                restored = true;
            } else {
                applyUnknown(transaction, indexedFlow, replayState);
            }
        }

        if (restored) {
            clearSyntheticReceiptPosition(transaction, corrId, replayState);
        } else {
            positionScopedLpExitReplayHandler.apply(transaction, replayState);
        }
        drainMaterializedReceiptMarker(transaction, corrId, replayState);
    }

    private boolean restoreInboundFromPool(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            String corrId,
            LpReceiptBasisPoolReplayContext poolContext,
            Instant touchedAt,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        String assetIdentity = assetSupport.continuityIdentity(transaction, flow);
        LpReceiptBasisPoolKey key = new LpReceiptBasisPoolKey(poolContext.universeId(), corrId, assetIdentity);
        LpReceiptBasisPool pool = poolContext.pools().get(key);
        if (pool == null || pool.getQtyHeld() == null || pool.getQtyHeld().signum() <= 0) {
            return false;
        }
        pool.setLastTouchedAt(touchedAt);
        var withdraw = lpReceiptBasisPoolService.withdraw(pool, flow.getQuantityDelta().abs());
        poolContext.dirtyKeys().add(key);

        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);

        if (withdraw.withdrawnQty().signum() > 0) {
            // U-3: a single-asset receipt pool restore is a same-asset continuity carry. Cap a
            // USD-stablecoin underlying (e.g. FUSDC/GTUSDCC/MCUSDC/EUSDC vault-share withdraw) at the
            // $1 peg so ERC4626 share-rate contamination cannot inflate the disposed stablecoin basis.
            BigDecimal coveredQty = withdraw.withdrawnQty().subtract(withdraw.withdrawnUncoveredQty(), MC);
            BigDecimal cappedBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    assetKey, coveredQty, withdraw.withdrawnBasisUsd());
            // ADR-040 Change 2: cap net lane with same stablecoin peg guard
            BigDecimal cappedNetBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    assetKey, coveredQty, withdraw.withdrawnNetBasisUsd());
            BigDecimal avco = cappedBasis.signum() > 0
                    ? cappedBasis.divide(withdraw.withdrawnQty(), MC)
                    : null;
            flowSupport.restoreToPosition(
                    withdraw.withdrawnQty(),
                    position,
                    cappedBasis,
                    cappedNetBasis,
                    withdraw.withdrawnUncoveredQty(),
                    avco
            );
        }
        if (withdraw.residualQty() != null && withdraw.residualQty().signum() > 0) {
            flowSupport.applyUnknownTransfer(
                    flowSupport.copyFlowWithQuantity(flow, withdraw.residualQty()),
                    position
            );
        }

        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
        return withdraw.withdrawnQty().signum() > 0;
    }

    private void clearSyntheticReceiptPosition(
            NormalizedTransaction transaction,
            String corrId,
            ReplayExecutionState replayState
    ) {
        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(transaction, corrId);
        if (receiptKey == null) {
            return;
        }
        PositionState receiptPosition = replayState.position(receiptKey);
        if (receiptPosition.quantity().signum() > 0) {
            receiptPosition.setQuantity(BigDecimal.ZERO);
            receiptPosition.setTotalCostBasisUsd(BigDecimal.ZERO);
            receiptPosition.setNetTotalCostBasisUsd(BigDecimal.ZERO);
            receiptPosition.setUncoveredQuantity(BigDecimal.ZERO);
            receiptPosition.setPerWalletAvco(null);
            receiptPosition.setPerWalletNetAvco(null);
        }
    }

    private void drainMaterializedReceiptMarker(
            NormalizedTransaction transaction,
            String corrId,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || transaction.getFlows() == null || corrId == null) {
            return;
        }
        String receiptSymbol = LpReceiptSymbolSupport.fromLpPositionCorrelation(corrId);
        if (receiptSymbol == null) {
            return;
        }
        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(transaction, corrId);
        if (receiptKey == null) {
            return;
        }
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() >= 0
                    || !receiptSymbol.equalsIgnoreCase(flow.getAssetSymbol())) {
                continue;
            }
            PositionState receiptPosition = replayState.position(receiptKey);
            if (receiptPosition.quantity().signum() <= 0) {
                continue;
            }
            PositionSnapshot before = flowSupport.snapshot(receiptPosition);
            flowSupport.removeFromPosition(flow, receiptPosition);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    receiptPosition.assetKey(),
                    before,
                    receiptPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
        }
    }

    private void applyFee(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        flowSupport.applyFee(flow, position);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.GAS_ONLY
        );
    }

    private void applyUnknown(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        flowSupport.applyUnknownTransfer(flow, position);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.UNKNOWN
        );
    }
}
