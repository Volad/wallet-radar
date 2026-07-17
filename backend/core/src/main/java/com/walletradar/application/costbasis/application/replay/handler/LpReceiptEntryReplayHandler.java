package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
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

    /** Absolute USD floor below which a net-positive family (or the whole deposit) is negligible. */
    private static final BigDecimal MATERIALITY_USD_FLOOR = new BigDecimal("1.00");
    /** Relative dust fraction: a net-positive family under 1% of the outbound USD is dust. */
    private static final BigDecimal DUST_USD_RELATIVE_FRACTION = new BigDecimal("0.01");
    /** Relative quantity epsilon for unpriced dust, scaled by the largest net-outbound quantity. */
    private static final BigDecimal DUST_QTY_RELATIVE_EPSILON = new BigDecimal("0.000001");

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
     * <p>Net-by-<b>family</b> aggregation with USD dust tolerance: Uniswap/SushiSwap V3/V4
     * routers may refund excess deposited tokens in the same transaction. Single-token
     * concentrated-liquidity "zap-in" entries additionally refund <em>dust</em> of the sibling
     * pool token(s). Netting by {@link AccountingAssetFamilySupport#continuityIdentity} (rather
     * than raw symbol) collapses those siblings into the deposit's family so the family stays
     * net-outbound; a genuinely material inbound of a different asset (Curve/Balancer mixed
     * receipt) still produces a net-positive family and is rejected. A net-positive family is
     * treated as dust when it is immaterial in USD (below {@code max($1, 1% × outbound USD)}) or,
     * when unpriced, negligible in quantity relative to the largest net-outbound quantity. The
     * refund is handled inside {@link #apply} via {@code applyInboundReceipt}.
     */
    static boolean hasOnlyOutboundPrincipalFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return false;
        }
        // Aggregate net quantity AND net USD per continuity family (FEE + LP-receipt markers
        // excluded). Family netting merges sibling-token dust refunds into the deposit family.
        java.util.Map<String, FamilyNet> netByFamily = new java.util.LinkedHashMap<>();
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
            String family = AccountingAssetFamilySupport.continuityIdentity(
                    flow.getAssetSymbol(), flow.getAssetContract());
            String key = family == null ? "" : family;
            FamilyNet acc = netByFamily.computeIfAbsent(key, k -> new FamilyNet());
            BigDecimal qty = flow.getQuantityDelta();
            acc.netQty = acc.netQty.add(qty, MC);
            BigDecimal usdMagnitude = flowUsdMagnitude(flow);
            if (usdMagnitude == null) {
                acc.allPriced = false;
            } else {
                acc.netUsd = acc.netUsd.add(qty.signum() < 0 ? usdMagnitude.negate() : usdMagnitude, MC);
            }
        }
        if (netByFamily.isEmpty()) {
            return false;
        }

        // Outbound aggregates: total resolved outbound USD (materiality scale) and the largest
        // net-outbound quantity (unpriced dust epsilon scale).
        BigDecimal totalOutboundUsd = BigDecimal.ZERO;
        boolean anyOutboundUsdResolved = false;
        BigDecimal maxOutboundQty = BigDecimal.ZERO;
        boolean hasNetOutbound = false;
        for (FamilyNet acc : netByFamily.values()) {
            if (acc.netQty.signum() < 0) {
                hasNetOutbound = true;
                BigDecimal absQty = acc.netQty.abs();
                if (absQty.compareTo(maxOutboundQty) > 0) {
                    maxOutboundQty = absQty;
                }
                if (acc.allPriced) {
                    totalOutboundUsd = totalOutboundUsd.add(acc.netUsd.abs(), MC);
                    anyOutboundUsdResolved = true;
                }
            }
        }
        if (!hasNetOutbound) {
            return false;
        }

        // A real deposit must exist. When outbound USD is resolvable, require it to clear the
        // absolute floor; otherwise fall back to quantity presence (fully-unpriced all-outbound
        // entries keep their legacy behaviour).
        if (anyOutboundUsdResolved && totalOutboundUsd.compareTo(MATERIALITY_USD_FLOOR) < 0) {
            return false;
        }

        BigDecimal dustUsdThreshold = totalOutboundUsd.multiply(DUST_USD_RELATIVE_FRACTION, MC);
        if (dustUsdThreshold.compareTo(MATERIALITY_USD_FLOOR) < 0) {
            dustUsdThreshold = MATERIALITY_USD_FLOOR;
        }
        BigDecimal dustQtyThreshold = maxOutboundQty.multiply(DUST_QTY_RELATIVE_EPSILON, MC);

        // Every net-positive family must be dust; any material net-positive family means the pool
        // returned a materially different asset (Curve/Balancer) → not a clean single-side deposit.
        for (FamilyNet acc : netByFamily.values()) {
            if (acc.netQty.signum() <= 0) {
                continue;
            }
            boolean dust = acc.allPriced
                    ? acc.netUsd.compareTo(dustUsdThreshold) < 0
                    : acc.netQty.compareTo(dustQtyThreshold) < 0;
            if (!dust) {
                return false;
            }
        }
        return true;
    }

    /**
     * Per-flow USD magnitude (always non-negative): {@code valueUsd} when present and positive,
     * else {@code |quantityDelta| × unitPriceUsd} when the unit price is present and positive,
     * else {@code null} (unresolved for this flow).
     */
    private static BigDecimal flowUsdMagnitude(NormalizedTransaction.Flow flow) {
        BigDecimal valueUsd = flow.getValueUsd();
        if (valueUsd != null && valueUsd.signum() > 0) {
            return valueUsd.abs();
        }
        BigDecimal unitPriceUsd = flow.getUnitPriceUsd();
        if (unitPriceUsd != null && unitPriceUsd.signum() > 0) {
            return flow.getQuantityDelta().abs().multiply(unitPriceUsd, MC);
        }
        return null;
    }

    /** Mutable per-family accumulator for {@link #hasOnlyOutboundPrincipalFlows}. */
    private static final class FamilyNet {
        private BigDecimal netQty = BigDecimal.ZERO;
        private BigDecimal netUsd = BigDecimal.ZERO;
        private boolean allPriced = true;
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
        // ADR-040 Change 2: deposit both tax and net basis into the pool
        lpReceiptBasisPoolService.deposit(pool, carry.quantity(), carry.costBasisUsd(), carry.netCostBasisUsd(), carry.uncoveredQuantity());

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
                new com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey(
                        poolContext.universeId(),
                        corrId,
                        assetIdentity
                )
        );
        BigDecimal requested = flow.getQuantityDelta().abs();
        BigDecimal basisUsd = BigDecimal.ZERO;
        BigDecimal netBasisUsd = BigDecimal.ZERO;
        BigDecimal uncovered = BigDecimal.ZERO;
        if (pool != null) {
            var withdraw = lpReceiptBasisPoolService.withdraw(pool, requested);
            basisUsd = withdraw.withdrawnBasisUsd();
            netBasisUsd = withdraw.withdrawnNetBasisUsd();
            uncovered = withdraw.withdrawnUncoveredQty();
            requested = withdraw.withdrawnQty();
            poolContext.dirtyKeys().add(
                    new com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey(
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
            // ADR-040 Change 2: restore net basis from pool alongside tax basis
            flowSupport.restoreToPosition(requested, position, basisUsd, netBasisUsd, uncovered, avco);
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

        // Cycle/Z3 fix: When LP_ENTRY has an explicit LP-RECEIPT inbound flow (e.g., Balancer V3
        // BPT that appears as a same-tx inbound), synthesize the position on the CONTRACT-BASED
        // asset key using the actual BPT quantity. This allows LP_POSITION_STAKE to correctly drain
        // from the position. For NFT-style LP receipts (Uniswap V3, Aerodrome) there is no
        // explicit LP-RECEIPT flow, so the original corrId-keyed synthetic qty=1 path is preserved.
        NormalizedTransaction.Flow explicitReceiptFlow = findExplicitLpReceiptInboundFlow(transaction);
        AssetKey receiptKey;
        BigDecimal syntheticQty;
        if (explicitReceiptFlow != null) {
            receiptKey = assetSupport.assetKey(transaction, explicitReceiptFlow);
            syntheticQty = explicitReceiptFlow.getQuantityDelta().abs();
        } else {
            receiptKey = assetSupport.lpReceiptPositionKey(transaction, corrId);
            syntheticQty = BigDecimal.ONE;
        }
        if (receiptKey == null || syntheticQty.signum() <= 0) {
            return;
        }
        PositionState receiptPosition = replayState.position(receiptKey);
        PositionSnapshot before = flowSupport.snapshot(receiptPosition);
        BigDecimal avco = totalBasis.signum() > 0 ? totalBasis.divide(syntheticQty, MC) : null;
        receiptPosition.setQuantity(syntheticQty);
        receiptPosition.setTotalCostBasisUsd(totalBasis);
        receiptPosition.setNetTotalCostBasisUsd(totalBasis);
        receiptPosition.setUncoveredQuantity(totalUncovered);
        receiptPosition.setPerWalletAvco(avco);
        receiptPosition.setPerWalletNetAvco(avco);
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

    /**
     * Returns the first explicit LP-RECEIPT inbound TRANSFER flow in the transaction, or
     * {@code null} if none exists. Present in Balancer V3 / Curve-style pools that mint
     * fungible LP tokens in the same transaction; absent for NFT-receipt protocols (Uniswap V3).
     */
    private static NormalizedTransaction.Flow findExplicitLpReceiptInboundFlow(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (isLpReceiptMarker(flow)) {
                return flow;
            }
        }
        return null;
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
