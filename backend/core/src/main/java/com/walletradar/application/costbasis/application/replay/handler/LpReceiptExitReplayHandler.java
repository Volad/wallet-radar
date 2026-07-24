package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.LpReceiptSymbolSupport;
import com.walletradar.application.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Locale;

/**
 * Cycle/15 Z3: restores principal basis from {@code lp_receipt_basis_pools} on position-scoped
 * {@link NormalizedTransactionType#LP_EXIT*} events.
 */
@Component
public class LpReceiptExitReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String LP_CORR_PREFIX = "lp-position:";
    // ADR-047: Pendle fungible LP receipt pools use the pendle-lp: prefix; accept it alongside
    // the position-scoped lp-position: prefix so Equilibria combined exits that carry
    // correlationId=pendle-lp:mantle:pendle-lpt trigger LP basis restore, not market-price acquire.
    private static final String PENDLE_LP_CORR_PREFIX = "pendle-lp:";

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
        String corrId = transaction.getCorrelationId();
        if (!corrId.startsWith(LP_CORR_PREFIX) && !corrId.startsWith(PENDLE_LP_CORR_PREFIX)) {
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
            // RC-1 Scenario B: for pool-backed exits, also skip negative TRANSFER flows whose
            // symbol matches the LP receipt for this correlation. shouldIgnoreLpReceiptMarker
            // uses the composite receipt identity; for Balancer V3 / LFJ BPT burns the
            // lpCompositeReceiptIdentity may be null, causing the burn to fall through to
            // applyUnknown. drainMaterializedReceiptMarker already handles these burns via
            // symbol matching, so double-recording is prevented by skipping here.
            if (isLpReceiptBurnBySymbol(corrId, flow)) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() <= 0) {
                applyUnknown(transaction, indexedFlow, replayState);
                continue;
            }
            if (restoreInboundFromPool(transaction, indexedFlow, corrId, poolContext, touchedAt, replayState)) {
                restored = true;
            } else {
                // BLOCKER-C: inbound asset (e.g. WETH from Optimism LP_EXIT) has no basis pool
                // entry. Leave as UNKNOWN rather than crediting at market price — market-price
                // crediting would inflate the ETH family basis pool and cascade into downstream
                // AVCO spikes when WETH is later disposed in a SWAP with a tiny ETH dust leg.
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
        AssetKey corrIdReceiptKey = assetSupport.lpReceiptPositionKey(transaction, corrId);
        if (corrIdReceiptKey == null) {
            return;
        }
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            // ADR-081 (C1): match the burn leg either by the correlation's receipt symbol (EVM /
            // Balancer BPT) or by the durable LP-receipt flag (Meteora DAMM MLP — confusable symbol).
            boolean isReceiptBurn = Boolean.TRUE.equals(flow.getLpReceipt())
                    || receiptSymbol.equalsIgnoreCase(flow.getAssetSymbol());
            if (!isReceiptBurn) {
                continue;
            }
            // Drain the corrId-keyed synthetic position (NFT-style LP receipts — qty=1, full basis).
            PositionState corrIdReceiptPosition = replayState.position(corrIdReceiptKey);
            if (corrIdReceiptPosition.quantity().signum() > 0) {
                PositionSnapshot before = flowSupport.snapshot(corrIdReceiptPosition);
                flowSupport.removeFromPosition(flow, corrIdReceiptPosition);
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        corrIdReceiptPosition.assetKey(),
                        before,
                        corrIdReceiptPosition,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                );
            }
            // Cycle/Z3 fix: Also drain the contract-based LP-RECEIPT position that was
            // synthesized with the actual BPT quantity (Balancer V3 / Curve style). The
            // underlying asset basis was already restored from lp_receipt_basis_pools, so this
            // drain cleans up the position without double-counting basis.
            AssetKey contractReceiptKey = assetSupport.assetKey(transaction, flow);
            if (!contractReceiptKey.equals(corrIdReceiptKey)) {
                PositionState contractReceiptPosition = replayState.position(contractReceiptKey);
                if (contractReceiptPosition.quantity().signum() > 0) {
                    PositionSnapshot before = flowSupport.snapshot(contractReceiptPosition);
                    flowSupport.removeFromPosition(flow, contractReceiptPosition);
                    replayState.ledgerPointCollector().record(
                            transaction,
                            flow,
                            indexedFlow.index(),
                            contractReceiptPosition.assetKey(),
                            before,
                            contractReceiptPosition,
                            AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                    );
                }
            }
        }
    }

    /**
     * RC-1 Scenario B: Returns {@code true} when a negative TRANSFER flow's symbol matches the
     * LP receipt symbol derived from the pool correlation ID. Such flows represent LP-RECEIPT token
     * burns (e.g. Balancer V3 BPT, LFJ LP token) whose basis drain is already handled by
     * {@link #drainMaterializedReceiptMarker}. Skipping them in the main loop prevents
     * double-recording (UNKNOWN + REALLOCATE_OUT) on the same flow.
     */
    private boolean isLpReceiptBurnBySymbol(String corrId, NormalizedTransaction.Flow flow) {
        if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() >= 0) {
            return false;
        }
        // ADR-081 (C1): durable flag route — a Meteora DAMM MLP burn leg is flagged at normalization
        // (the fungible MLP symbol is confusable across pools and does not match the correlation's
        // receipt symbol grammar). Skipping it here defers the burn to drainMaterializedReceiptMarker,
        // preventing a double UNKNOWN + REALLOCATE_OUT record on the same flow.
        if (Boolean.TRUE.equals(flow.getLpReceipt())) {
            return true;
        }
        String receiptSymbol = LpReceiptSymbolSupport.fromLpPositionCorrelation(corrId);
        if (receiptSymbol == null || flow.getAssetSymbol() == null) {
            return false;
        }
        return receiptSymbol.equalsIgnoreCase(flow.getAssetSymbol());
    }

    /**
     * RC-1 Scenario C: Attempts to acquire an LP_EXIT inbound flow at market price when the
     * asset has no corresponding entry in the LP receipt basis pool. This covers cases such as
     * WETH returned from an Optimism single-asset exit where only the stablecoin side was
     * deposited into the pool at entry time.
     *
     * <p>Returns {@code true} if a market price was available and the flow was recorded as
     * {@link AssetLedgerPoint.BasisEffect#ACQUIRE}; returns {@code false} otherwise (caller
     * falls back to {@link #applyUnknown}).</p>
     */
    private boolean applyMarketPriceAcquire(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        BigDecimal replayUnitPrice = assetSupport.replayUnitPriceUsd(transaction, flow);
        if (replayUnitPrice == null || replayUnitPrice.signum() <= 0) {
            return false;
        }
        BigDecimal qty = flow.getQuantityDelta().abs();
        BigDecimal acquisitionCost = qty.multiply(replayUnitPrice, MC);
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);
        NormalizedTransaction.Flow pricedFlow = flowSupport.copyFlowWithQuantity(flow, qty);
        pricedFlow.setUnitPriceUsd(replayUnitPrice);
        if (flow.getPriceSource() != null) {
            pricedFlow.setPriceSource(flow.getPriceSource());
        }
        flowSupport.applyBuyWithAcquisitionCost(pricedFlow, position, acquisitionCost);
        replayState.ledgerPointCollector().record(
                transaction,
                pricedFlow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );
        return true;
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
