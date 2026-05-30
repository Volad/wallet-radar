package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cycle/15 Z3: moves principal basis from wallet assets into {@code lp_receipt_basis_pools}
 * on {@link NormalizedTransactionType#LP_ENTRY} with {@code lp-position:} correlation ids.
 */
@Component
public class LpReceiptEntryReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String LP_CORR_PREFIX = "lp-position:";
    private static final String PENDLE_LP_CORR_PREFIX = "pendle-lp:";

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final LpReceiptBasisPoolService lpReceiptBasisPoolService;

    public LpReceiptEntryReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            LpReceiptBasisPoolService lpReceiptBasisPoolService
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.lpReceiptBasisPoolService = lpReceiptBasisPoolService;
    }

    public boolean isLpReceiptEntry(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY
                && transaction.getCorrelationId() != null
                && (transaction.getCorrelationId().startsWith(LP_CORR_PREFIX)
                        || transaction.getCorrelationId().startsWith(PENDLE_LP_CORR_PREFIX))
                && hasOnlyOutboundPrincipalFlows(transaction);
    }

    /**
     * Multi-asset LP entries with a same-tx inbound receipt/principal leg (Curve/Balancer pools
     * that mint LP tokens visible as a flow) stay on the async-lifecycle bucket path so the
     * existing async exit logic still sees the receipt token. Receipt-pool routing targets
     * outbound-only LP_ENTRY shapes (Pancake V3, Aerodrome, Uniswap V3/V4 NFTs — the LP receipt is
     * an NFT/marker that does not appear as a same-tx inbound flow) where basis would otherwise
     * be reallocated incorrectly. Multi-family outbound is supported in round 2: one
     * {@link LpReceiptBasisPool} per outbound asset family, all sharing the same
     * {@code lpCorrelationId}.
     *
     * <p>Net-by-asset aggregation: Uniswap V3/V4 routers may refund excess deposited tokens
     * in the same transaction. A small positive inbound of a deposited asset does not make
     * this a Curve/Balancer-style mixed receipt — as long as the NET flow for every principal
     * asset remains negative (net outbound), this LP_ENTRY is routed to the receipt-pool path.
     * The refund is handled inside {@link #apply} via {@code applyInboundReceipt}.
     */
    static boolean hasOnlyOutboundPrincipalFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return false;
        }
        // Aggregate net quantity per asset symbol (FEE and LP-receipt markers excluded).
        // A Uniswap router may return excess tokens (refund) in the same tx; the net per
        // asset is still negative. Curve/Balancer shapes where the pool returns a different
        // asset produce net positive flows for that asset and are correctly rejected.
        java.util.Map<String, java.math.BigDecimal> netByAsset = new java.util.LinkedHashMap<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE || isLpReceiptMarker(flow)) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                continue;
            }
            String key = flow.getAssetSymbol() == null
                    ? ""
                    : flow.getAssetSymbol().trim().toLowerCase(java.util.Locale.ROOT);
            netByAsset.merge(key, flow.getQuantityDelta(), java.math.BigDecimal::add);
        }
        if (netByAsset.isEmpty()) {
            return false;
        }
        boolean hasNetOutbound = false;
        for (java.math.BigDecimal net : netByAsset.values()) {
            if (net.signum() > 0) {
                return false;  // net inbound for some asset → Curve/Balancer or unexpected shape
            }
            if (net.signum() < 0) {
                hasNetOutbound = true;
            }
        }
        return hasNetOutbound;
    }

    public void apply(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null) {
            return;
        }
        String corrId = transaction.getCorrelationId();
        Instant touchedAt = transaction.getBlockTimestamp() != null
                ? transaction.getBlockTimestamp()
                : Instant.now();

        List<IndexedFlow> outboundPrincipal = new ArrayList<>();
        List<IndexedFlow> inboundReceipt = new ArrayList<>();

        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                continue;
            }
            if (isLpReceiptMarker(flow)) {
                if (flow.getQuantityDelta().signum() > 0) {
                    inboundReceipt.add(indexedFlow);
                }
                continue;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                outboundPrincipal.add(indexedFlow);
            } else {
                inboundReceipt.add(indexedFlow);
            }
        }

        for (IndexedFlow indexedFlow : outboundPrincipal) {
            applyOutboundPrincipal(transaction, indexedFlow, corrId, poolContext, touchedAt, replayState);
        }

        for (IndexedFlow indexedFlow : inboundReceipt) {
            if (isLpReceiptMarker(indexedFlow.flow())) {
                continue;
            }
            applyInboundReceipt(transaction, indexedFlow, corrId, poolContext, touchedAt, replayState);
        }

        // P2: Skip receipt synthesis if the LP lifecycle has already been fully closed
        // (exits ≥ entries AND exits > 0). A re-add after close is an orphan — do not
        // mint a new synthetic receipt for it (ETH-C7 guard, ADR-018 §3.1).
        if (!outboundPrincipal.isEmpty() && !replayState.lpReceiptLifecycleClosed(corrId)) {
            synthesizeReceiptFromOutbound(transaction, outboundPrincipal, corrId, poolContext, touchedAt, replayState);
        }
    }

    private void applyOutboundPrincipal(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            String corrId,
            LpReceiptBasisPoolReplayContext poolContext,
            Instant touchedAt,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);

        CarryTransfer carry = flowSupport.removeFromPosition(flow, position);
        String assetIdentity = assetSupport.continuityIdentity(transaction, flow);
        LpReceiptBasisPool pool = lpReceiptBasisPoolService.lookupOrCreate(
                poolContext.universeId(),
                corrId,
                transaction.getWalletAddress(),
                transaction.getNetworkId(),
                assetIdentity,
                assetKey.assetSymbol(),
                assetKey.assetContract(),
                poolContext.pools(),
                poolContext.dirtyKeys(),
                touchedAt
        );
        lpReceiptBasisPoolService.deposit(pool, carry.quantity(), carry.costBasisUsd(), carry.uncoveredQuantity());

        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
        );
    }

    private void applyInboundReceipt(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            String corrId,
            LpReceiptBasisPoolReplayContext poolContext,
            Instant touchedAt,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);

        String assetIdentity = assetSupport.continuityIdentity(transaction, flow);
        LpReceiptBasisPool pool = poolContext.pools().get(
                new com.walletradar.costbasis.domain.LpReceiptBasisPoolKey(
                        poolContext.universeId(),
                        corrId,
                        assetIdentity
                )
        );
        BigDecimal requested = flow.getQuantityDelta().abs();
        BigDecimal basisUsd = BigDecimal.ZERO;
        BigDecimal uncovered = BigDecimal.ZERO;
        if (pool != null) {
            var withdraw = lpReceiptBasisPoolService.withdraw(pool, requested);
            basisUsd = withdraw.withdrawnBasisUsd();
            uncovered = withdraw.withdrawnUncoveredQty();
            requested = withdraw.withdrawnQty();
            poolContext.dirtyKeys().add(
                    new com.walletradar.costbasis.domain.LpReceiptBasisPoolKey(
                            poolContext.universeId(),
                            corrId,
                            assetIdentity
                    )
            );
        }
        if (requested.signum() > 0) {
            BigDecimal avco = basisUsd.signum() > 0 && requested.signum() > 0
                    ? basisUsd.divide(requested, MC)
                    : null;
            flowSupport.restoreToPosition(requested, position, basisUsd, uncovered, avco);
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
    }

    private void synthesizeReceiptFromOutbound(
            NormalizedTransaction transaction,
            List<IndexedFlow> outboundPrincipal,
            String corrId,
            LpReceiptBasisPoolReplayContext poolContext,
            Instant touchedAt,
            ReplayExecutionState replayState
    ) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalBasis = BigDecimal.ZERO;
        BigDecimal totalUncovered = BigDecimal.ZERO;
        for (LpReceiptBasisPool pool : poolContext.pools().values()) {
            if (!corrId.equals(pool.getLpCorrelationId())) {
                continue;
            }
            totalQty = totalQty.add(zeroIfNull(pool.getQtyHeld()), MC);
            totalBasis = totalBasis.add(zeroIfNull(pool.getBasisHeldUsd()), MC);
            totalUncovered = totalUncovered.add(zeroIfNull(pool.getUncoveredQtyHeld()), MC);
        }
        if (totalQty.signum() <= 0 || outboundPrincipal.isEmpty()) {
            return;
        }
        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(transaction, corrId);
        if (receiptKey == null) {
            return;
        }
        PositionState receiptPosition = replayState.position(receiptKey);
        PositionSnapshot before = flowSupport.snapshot(receiptPosition);
        BigDecimal avco = totalBasis.signum() > 0 ? totalBasis.divide(BigDecimal.ONE, MC) : null;
        receiptPosition.setQuantity(BigDecimal.ONE);
        receiptPosition.setTotalCostBasisUsd(totalBasis);
        receiptPosition.setUncoveredQuantity(totalUncovered);
        receiptPosition.setPerWalletAvco(avco);
        IndexedFlow marker = outboundPrincipal.getFirst();
        replayState.recordLpReceiptEntryEvent(corrId);
        replayState.ledgerPointCollector().record(
                transaction,
                marker.flow(),
                marker.index(),
                receiptPosition.assetKey(),
                before,
                receiptPosition,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    private static boolean isLpReceiptMarker(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getAssetSymbol() == null) {
            return false;
        }
        String sym = flow.getAssetSymbol().trim().toUpperCase();
        if (sym.startsWith("LP-RECEIPT:") || sym.contains("-LP-") || sym.endsWith("-LP")) {
            return true;
        }
        // Pendle-family LP receipt tokens (PENDLE-LPT, eqbPENDLE-LPT) are position tokens, not principal assets.
        return sym.endsWith("-LPT") && (sym.contains("PENDLE") || sym.startsWith("EQB"));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
