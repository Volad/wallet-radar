package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.CustodyRoundTripBasisEnvelope;
import com.walletradar.application.costbasis.application.replay.model.CustodyRoundTripInboundAllocation;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finding 2 — conservation-preserving replay for a same-network custody/parking round-trip linked
 * by {@code SameNetworkCustodyRoundTripLinkService}.
 *
 * <p>The whole {@code bridge:custody-roundtrip:} correlation is one basis envelope:
 *
 * <ol>
 *   <li><b>OUT</b> — each deposited principal releases its carried-out basis (tax + net) from the
 *       wallet position and pools it into {@link CustodyRoundTripBasisEnvelope} (CARRY_OUT).</li>
 *   <li><b>IN</b> — the pooled total is redistributed onto the RETURNED assets by their market-value
 *       weights at return time, so {@code Σ carried-in == Σ carried-out} exactly (CARRY_IN,
 *       {@code rPnL = 0}). Any returned quantity whose value exceeds the pooled basis stays
 *       <em>uncovered</em> (zero-basis surplus) — basis is never minted above what was carried out.
 *       This is the same value-weighted, capped-at-source allocation the async-redeem allocator
 *       ({@code ReplaySettlementAllocator.allocateIndexedSettlementByKnownValue}) uses.</li>
 * </ol>
 *
 * <p>By pooling across families, a vault that rebalances its composition inside the round-trip
 * (deposit A+B, withdraw less A + more B) no longer inflates the shrunken family's avco nor
 * re-prices the grown family's surplus at market. When no return-time market price is resolvable,
 * the allocation falls back to quantity weights (still conservation-preserving).
 */
@Component
@Slf4j
public class CustodyRoundTripReplaySupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayFlowSupport flowSupport;
    private final ReplayMarketAuthority replayMarketAuthority;

    public CustodyRoundTripReplaySupport(
            ReplayFlowSupport flowSupport,
            ReplayMarketAuthority replayMarketAuthority
    ) {
        this.flowSupport = flowSupport;
        this.replayMarketAuthority = replayMarketAuthority;
    }

    public AssetLedgerPoint.BasisEffect apply(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        CustodyRoundTripBasisEnvelope envelope =
                replayState.custodyRoundTripEnvelope(transaction.getCorrelationId());
        if (flow.getQuantityDelta().signum() < 0) {
            return applyOutbound(flow, position, envelope);
        }
        return applyInbound(transaction, flow, flowIndex, position, envelope);
    }

    private AssetLedgerPoint.BasisEffect applyOutbound(
            NormalizedTransaction.Flow flow,
            PositionState position,
            CustodyRoundTripBasisEnvelope envelope
    ) {
        CarryTransfer carry = flowSupport.removeFromPosition(flow, position);
        envelope.addCarriedOut(carry.costBasisUsd(), carry.netCostBasisUsd());
        flowSupport.purgeOrphanBasisWhenEmpty(position);
        return AssetLedgerPoint.BasisEffect.CARRY_OUT;
    }

    private AssetLedgerPoint.BasisEffect applyInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            CustodyRoundTripBasisEnvelope envelope
    ) {
        String transactionId = transaction.getId();
        if (!envelope.hasInboundAllocation(transactionId)) {
            Map<Integer, CustodyRoundTripInboundAllocation> allocation = computeInboundAllocation(transaction, envelope);
            envelope.putInboundAllocation(transactionId, allocation);
            // The pooled basis is redistributed exactly once, at the first inbound leg processed.
            envelope.consumeBasis();
        }
        Map<Integer, CustodyRoundTripInboundAllocation> allocation = envelope.inboundAllocation(transactionId);
        CustodyRoundTripInboundAllocation slice = allocation == null ? null : allocation.get(flowIndex);
        if (slice == null) {
            // Non-principal inbound leg (should not happen for a demoted-principal round-trip) — leave
            // it basis-unknown rather than fabricate a market basis.
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.CARRY_IN;
        }
        flowSupport.restoreToPosition(
                slice.quantity(),
                position,
                slice.taxBasisUsd(),
                slice.netBasisUsd(),
                slice.uncoveredQuantity(),
                slice.avco()
        );
        return AssetLedgerPoint.BasisEffect.CARRY_IN;
    }

    /**
     * Redistributes the pooled envelope basis onto the returned principal flows by market-value
     * weight (quantity weight when unpriced), capping covered quantity at market so returned value
     * above the pooled basis stays uncovered. {@code Σ allocated == Σ pooled} exactly (the last
     * principal absorbs the rounding remainder).
     */
    private Map<Integer, CustodyRoundTripInboundAllocation> computeInboundAllocation(
            NormalizedTransaction transaction,
            CustodyRoundTripBasisEnvelope envelope
    ) {
        BigDecimal pooledTax = envelope.taxBasisUsd();
        BigDecimal pooledNet = envelope.netBasisUsd().min(pooledTax);

        java.util.List<Integer> principalIndexes = new java.util.ArrayList<>();
        Map<Integer, BigDecimal> quantities = new LinkedHashMap<>();
        Map<Integer, BigDecimal> values = new LinkedHashMap<>();
        boolean allPriced = true;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (int index = 0; index < transaction.getFlows().size(); index++) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(index);
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            BigDecimal quantity = flow.getQuantityDelta().abs();
            principalIndexes.add(index);
            quantities.put(index, quantity);
            totalQuantity = totalQuantity.add(quantity, MC);
            BigDecimal value = marketValueUsd(transaction, flow);
            if (value == null || value.signum() <= 0) {
                allPriced = false;
            } else {
                values.put(index, value);
                totalValue = totalValue.add(value, MC);
            }
        }

        Map<Integer, CustodyRoundTripInboundAllocation> allocation = new LinkedHashMap<>();
        if (principalIndexes.isEmpty()) {
            return allocation;
        }

        boolean valueWeighted = allPriced && totalValue.signum() > 0;
        BigDecimal weightDenominator = valueWeighted ? totalValue : totalQuantity;
        if (weightDenominator.signum() <= 0) {
            return allocation;
        }
        BigDecimal remainingTax = pooledTax;
        BigDecimal remainingNet = pooledNet;
        for (int i = 0; i < principalIndexes.size(); i++) {
            int index = principalIndexes.get(i);
            boolean last = i == principalIndexes.size() - 1;
            BigDecimal quantity = quantities.get(index);
            BigDecimal weightNumerator = valueWeighted ? values.get(index) : quantity;
            BigDecimal taxBasis = last
                    ? remainingTax
                    : pooledTax.multiply(weightNumerator.divide(weightDenominator, MC), MC);
            BigDecimal netBasis = last
                    ? remainingNet
                    : pooledNet.multiply(weightNumerator.divide(weightDenominator, MC), MC);
            taxBasis = nonNegative(taxBasis);
            netBasis = nonNegative(netBasis).min(taxBasis);
            remainingTax = nonNegative(remainingTax.subtract(taxBasis, MC));
            remainingNet = nonNegative(remainingNet.subtract(netBasis, MC));

            BigDecimal coveredQuantity;
            if (valueWeighted) {
                // covered quantity = min(qty, taxBasis / marketPrice). When the pooled basis is
                // smaller than the returned value, only value-P worth of quantity is covered at
                // market avco; the surplus stays uncovered (zero basis). When the pooled basis
                // exceeds the returned value the whole quantity is covered (basis > market: a carried
                // unrealised loss, no avco inflation beyond the value-weighted share).
                BigDecimal marketPrice = values.get(index).divide(quantity, MC);
                BigDecimal coveredByBasis = marketPrice.signum() > 0
                        ? taxBasis.divide(marketPrice, MC)
                        : quantity;
                coveredQuantity = coveredByBasis.min(quantity);
            } else {
                coveredQuantity = quantity;
            }
            BigDecimal uncoveredQuantity = nonNegative(quantity.subtract(coveredQuantity, MC));
            BigDecimal avco = coveredQuantity.signum() > 0 ? taxBasis.divide(coveredQuantity, MC) : null;
            allocation.put(index, new CustodyRoundTripInboundAllocation(
                    quantity, coveredQuantity, uncoveredQuantity, taxBasis, netBasis, avco));
        }
        log.debug(
                "REPLAY_CUSTODY_ROUNDTRIP_ALLOCATION corr={} pooledTaxUsd={} pooledNetUsd={} returnedLegs={} valueWeighted={}",
                transaction.getCorrelationId(), pooledTax, pooledNet, principalIndexes.size(), valueWeighted);
        return allocation;
    }

    private BigDecimal marketValueUsd(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        // ReplayMarketAuthority.resolve() already prefers a resolved flow price, then the historical
        // cache, then stablecoin par — never an external call at replay time.
        return replayMarketAuthority.resolve(transaction, flow)
                .filter(resolved -> resolved.unitPriceUsd() != null && resolved.unitPriceUsd().signum() > 0)
                .map(resolved -> quantity.multiply(resolved.unitPriceUsd(), MC))
                .orElse(null);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
