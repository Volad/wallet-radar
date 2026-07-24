package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.LiquidStakingCarry;
import com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.AccountRefPositionResolver;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.application.costbasis.application.replay.support.ReplayToleranceSupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ADR-083 cluster-carry engine (the {@code CLUSTER_CARRY} route). Carries basis on both lanes
 * (Market and Net) with realized PnL = 0 for an <b>intra-cluster cross-canonical conversion</b> —
 * any conversion whose every principal leg resolves to the same non-null staking cluster
 * (ETH/SOL/AVAX). The old same-{@code FAMILY:*} staking carry (e.g. METH↔CMETH) is the degenerate
 * single-cluster case; cross-family same-cluster conversions (ETH↔mETH, ETH↔wstETH, SOL↔mSOL,
 * AVAX↔sAVAX) and intra-cluster {@code SWAP}/{@code VAULT_*} conversions now carry through the same
 * REALLOCATE_OUT/IN both-lane proportional engine. Selection is gated by
 * {@link AccountingAssetClassificationSupport#isIntraClusterConversion(NormalizedTransaction)};
 * cluster↔non-cluster and cross-cluster moves fall through to the generic realize path.
 */
@Component
@Slf4j
public class LiquidStakingReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;
    /** Same dust-safe coverage threshold as {@link ReplayToleranceSupport#carrySourceCoverageRatio()}. */

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplaySettlementAllocator settlementAllocator;

    public LiquidStakingReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplaySettlementAllocator settlementAllocator
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.settlementAllocator = settlementAllocator;
    }

    /**
     * ADR-083: select the principal legs of an intra-cluster cross-canonical conversion for
     * both-lane carry. Selection is authoritatively gated by
     * {@link AccountingAssetClassificationSupport#isIntraClusterConversion(NormalizedTransaction)} —
     * type-gated (STAKING/VAULT/SWAP), every principal in the same non-null staking cluster, no
     * non-cluster principal, and for SWAP genuinely cross-canonical. The old same-{@code FAMILY:*}
     * carry (METH↔CMETH) is the degenerate single-cluster case; cross-family same-cluster
     * conversions (ETH↔mETH, SOL↔mSOL, AVAX↔sAVAX) and multi-leg pooled conversions
     * (wstETH+ETH→weETH) are handled by the same engine.
     */
    public LiquidStakingFlowSelection selectPrincipalFlows(NormalizedTransaction transaction) {
        if (!AccountingAssetClassificationSupport.isIntraClusterConversion(transaction)) {
            return LiquidStakingFlowSelection.empty();
        }

        List<IndexedFlow> outbound = new ArrayList<>();
        List<IndexedFlow> inbound = new ArrayList<>();
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (!isPrincipalCandidate(flow)) {
                continue;
            }
            // Fail-safe: the predicate already proved every principal shares one non-null cluster,
            // but re-resolve per leg so a non-cluster leg can never be swept into a carry.
            if (AccountingAssetClassificationSupport.stakingClusterForFlow(
                    flow.getAssetSymbol(), flow.getAssetContract()) == null) {
                continue;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                outbound.add(indexedFlow);
            } else {
                inbound.add(indexedFlow);
            }
        }
        if (outbound.isEmpty() || inbound.isEmpty()) {
            return LiquidStakingFlowSelection.empty();
        }
        outbound.sort(Comparator.comparingInt(IndexedFlow::index));
        inbound.sort(Comparator.comparingInt(IndexedFlow::index));
        return new LiquidStakingFlowSelection(outbound, inbound);
    }

    public void applySelected(
            NormalizedTransaction transaction,
            LiquidStakingFlowSelection selection,
            ReplayExecutionState replayState
    ) {
        // ADR-083 §4.5: cluster-carry runs at route level and bypasses the generic continuity-transfer
        // dedup. A Bybit mirror document (same correlationId re-emitting the same economic conversion)
        // must not carry twice. Fingerprint on a namespace distinct from the continuity-transfer keys
        // so the two dedup domains never collide; if this conversion was already carried, drop the
        // mirror's principal legs entirely (they are in the selected set, so the generic path will not
        // re-apply them either).
        String dedupKey = clusterCarryDedupKey(transaction, selection);
        if (dedupKey != null && !replayState.markContinuityFlowSeen(dedupKey)) {
            log.warn("REPLAY_DEDUP_CLUSTER_CARRY_MIRROR_SKIPPED txId={} corrId={} wallet={} network={}",
                    transaction.getId(),
                    transaction.getCorrelationId(),
                    transaction.getWalletAddress(),
                    transaction.getNetworkId() == null ? "" : transaction.getNetworkId().name());
            return;
        }

        LiquidStakingCarry carry = new LiquidStakingCarry();
        for (IndexedFlow indexedFlow : selection.outbound()) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);

            // ADR-083 §4.3: a SWAP/VAULT SELL principal must drain the pool that actually holds the
            // disposed inventory. resolveSellAssetKey applies the ADR-042 Bybit accountRef →
            // coverage → max-quantity sub-wallet scan (a plain assetKey would strand fills on the
            // umbrella). TRANSFER staking legs keep the plain umbrella key + :FUND/accountRef redirect.
            AssetKey assetKey = flow.getRole() == NormalizedLegRole.SELL
                    ? assetSupport.resolveSellAssetKey(transaction, flow, replayState.positions().asMap())
                    : assetSupport.assetKey(transaction, flow);
            PositionState position = resolveOutboundDrainPosition(
                    transaction, flow, assetKey, replayState, flow.getQuantityDelta().abs());
            position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot before = flowSupport.snapshot(position);
            CarryTransfer removedCarry = flowSupport.removeFromPosition(flow, position);
            carry.add(removedCarry);
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

        allocateInbound(transaction, selection.inbound(), replayState, carry);
    }

    private void allocateInbound(
            NormalizedTransaction transaction,
            List<IndexedFlow> inboundFlows,
            ReplayExecutionState replayState,
            LiquidStakingCarry carry
    ) {
        if (carry.totalSourceQuantity().signum() <= 0) {
            for (IndexedFlow indexedFlow : inboundFlows) {
                settlementAllocator.applyFallbackSettlementFlow(
                        transaction,
                        indexedFlow.flow(),
                        replayState.positions(),
                        replayState.ledgerPointCollector()
                );
            }
            return;
        }

        BigDecimal remainingCost = carry.totalCostBasisUsd();
        // ADR-040 Change 2: track net lane separately through allocation
        BigDecimal remainingNetCost = carry.totalNetCostBasisUsd();
        BigDecimal remainingCoveredQuantity = carry.totalCoveredQuantity();
        BigDecimal totalCoveredDestinationQuantity = totalInboundQuantity(inboundFlows).min(carry.totalCoveredQuantity());
        for (int index = 0; index < inboundFlows.size(); index++) {
            IndexedFlow indexedFlow = inboundFlows.get(index);
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);

            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot before = flowSupport.snapshot(position);

            BigDecimal quantity = flow.getQuantityDelta().abs();
            BigDecimal coveredBefore = remainingCoveredQuantity;
            BigDecimal coveredQuantity = quantity.min(remainingCoveredQuantity);
            boolean consumesFinalCoveredPrincipal = nonNegative(coveredBefore.subtract(coveredQuantity, MC)).signum() == 0;
            BigDecimal allocatedCost = coveredQuantity.signum() == 0
                    ? BigDecimal.ZERO
                    : consumesFinalCoveredPrincipal
                    ? remainingCost
                    : allocatedCost(carry.totalCostBasisUsd(), coveredQuantity, totalCoveredDestinationQuantity);
            // ADR-040 Change 2: proportionally allocate net cost in the same way as tax cost
            BigDecimal allocatedNetCost = coveredQuantity.signum() == 0
                    ? BigDecimal.ZERO
                    : consumesFinalCoveredPrincipal
                    ? remainingNetCost
                    : allocatedCost(carry.totalNetCostBasisUsd(), coveredQuantity, totalCoveredDestinationQuantity);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            remainingNetCost = remainingNetCost.subtract(allocatedNetCost, MC);
            remainingCoveredQuantity = nonNegative(remainingCoveredQuantity.subtract(coveredQuantity, MC));
            BigDecimal uncoveredQuantity = nonNegative(quantity.subtract(coveredQuantity, MC));
            BigDecimal avco = coveredQuantity.signum() > 0
                    ? safeDivide(allocatedCost, coveredQuantity)
                    : null;
            flowSupport.restoreToPosition(quantity, position, allocatedCost, allocatedNetCost, uncoveredQuantity, avco);
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
    }

    /**
     * R-1 (Fix A.1): a Bybit {@code STAKING_DEPOSIT} whose principal genuinely sits on {@code :FUND}
     * (corridor-funded liquid staking, e.g. the 2025-03-12 "ETH 2.0" cycle) must drain the
     * {@code :FUND} sub-account, not the umbrella. The outbound flow keys to the umbrella (because
     * {@code positionWalletAddress} strips {@code :FUND}), so when the umbrella cannot cover the
     * moved quantity (its inventory is dust / the corridor duplicate has been collapsed away) but
     * the {@code :FUND} position can, drain {@code :FUND}. This mirrors the established, tested
     * {@code TransferReplayHandler.resolveCarrySourcePosition} R-1 redirect — which is unreachable
     * for {@code STAKING_DEPOSIT} because staking routes through this handler, not the transfer
     * handler. Spot-funded staking (inventory on the umbrella) keeps its umbrella key because the
     * umbrella covers; the ETH→METH control and Part-1 METH→CMETH (umbrella-booked) are unaffected.
     *
     * <p><b>ADR-042.</b> The Fix A.1 guard below keys on the <em>transaction</em>
     * {@code walletAddress} suffix ending {@code :FUND}. A collapsed {@code STAKING_DEPOSIT} (e.g.
     * the 2025-04-18 ETH cycle) instead carries the plain umbrella as {@code walletAddress}, so that
     * guard never fires — but {@code flow.accountRef} still names the {@code :FUND} sub-account that
     * funded the principal. The shared, coverage-and-existence-gated {@code accountRef} redirect runs
     * first: when the named sub-position exists and can cover the leg, drain it so the staked
     * principal basis carries into the receipt and the sub-account keeps no phantom.
     */
    private PositionState resolveOutboundDrainPosition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            AssetKey umbrellaKey,
            ReplayExecutionState replayState,
            BigDecimal outboundQuantity
    ) {
        PositionState umbrellaPosition = replayState.position(umbrellaKey);
        AssetKey accountRefKey = AccountRefPositionResolver.resolveInventoryBearingAccountRefKey(
                umbrellaKey,
                flow == null ? null : flow.getAccountRef(),
                replayState.positions().asMap(),
                outboundQuantity
        );
        if (!accountRefKey.equals(umbrellaKey)) {
            return replayState.position(accountRefKey);
        }
        String wallet = transaction == null ? null : transaction.getWalletAddress();
        if (wallet == null) {
            return umbrellaPosition;
        }
        WalletRef walletRef = WalletRef.parse(wallet.trim());
        if (walletRef.domain() != WalletDomainKind.CEX || !"FUND".equalsIgnoreCase(walletRef.subAccount())) {
            return umbrellaPosition;
        }
        if (positionCoversQuantity(umbrellaPosition, outboundQuantity)) {
            return umbrellaPosition;
        }
        AssetKey fundKey = new AssetKey(
                wallet.trim(),
                umbrellaKey.networkId(),
                umbrellaKey.assetContract(),
                umbrellaKey.assetSymbol(),
                umbrellaKey.assetIdentity()
        );
        if (fundKey.equals(umbrellaKey)) {
            return umbrellaPosition;
        }
        PositionState fundPosition = replayState.position(fundKey);
        return positionCoversQuantity(fundPosition, outboundQuantity) ? fundPosition : umbrellaPosition;
    }

    /**
     * True when {@code position} holds at least {@link ReplayToleranceSupport#carrySourceCoverageRatio()} of the moved
     * quantity — i.e. it can supply the outbound staking leg from real inventory rather than a dust
     * residue. Mirrors {@code TransferReplayHandler.positionCoversQuantity}.
     */
    private static boolean positionCoversQuantity(PositionState position, BigDecimal outboundQuantity) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        if (qty == null || qty.signum() <= 0) {
            return false;
        }
        if (outboundQuantity == null || outboundQuantity.signum() <= 0) {
            return true;
        }
        return qty.compareTo(outboundQuantity.multiply(ReplayToleranceSupport.carrySourceCoverageRatio(), MC)) >= 0;
    }

    /**
     * ADR-083 §4.5 mirror-dedup fingerprint. Namespaced ({@code CC:}) distinctly from the
     * continuity-transfer dedup keys so cluster-carry never shadows legs owned by the
     * continuity/custody-round-trip path (and vice versa). Composed of correlationId + wallet +
     * network + resolved cluster + the sorted outbound/inbound principal quantities so that two
     * mirror emissions of the same conversion collide while distinct conversions do not.
     */
    private String clusterCarryDedupKey(NormalizedTransaction transaction, LiquidStakingFlowSelection selection) {
        if (transaction == null || selection == null) {
            return null;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId == null || corrId.isBlank()) {
            return null;
        }
        String cluster = null;
        List<String> outQty = new ArrayList<>();
        for (IndexedFlow indexedFlow : selection.outbound()) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (cluster == null) {
                cluster = AccountingAssetClassificationSupport.stakingClusterForFlow(
                        flow.getAssetSymbol(), flow.getAssetContract());
            }
            outQty.add(flow.getQuantityDelta().abs().stripTrailingZeros().toPlainString());
        }
        List<String> inQty = new ArrayList<>();
        for (IndexedFlow indexedFlow : selection.inbound()) {
            inQty.add(indexedFlow.flow().getQuantityDelta().abs().stripTrailingZeros().toPlainString());
        }
        Collections.sort(outQty);
        Collections.sort(inQty);
        String wallet = transaction.getWalletAddress() == null ? "" : transaction.getWalletAddress();
        String network = transaction.getNetworkId() == null ? "" : transaction.getNetworkId().name();
        return "CC:" + corrId + "|" + wallet + "|" + network + "|" + cluster + "|OUT" + outQty + "|IN" + inQty;
    }

    private boolean isPrincipalCandidate(NormalizedTransaction.Flow flow) {
        if (flow == null
                || flow.getRole() == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        if (flow.getRole() == NormalizedLegRole.TRANSFER) {
            return true;
        }
        return (flow.getRole() == NormalizedLegRole.SELL && flow.getQuantityDelta().signum() < 0)
                || (flow.getRole() == NormalizedLegRole.BUY && flow.getQuantityDelta().signum() > 0);
    }

    private BigDecimal allocatedCost(
            BigDecimal totalCostBasisUsd,
            BigDecimal coveredQuantity,
            BigDecimal totalCoveredDestinationQuantity
    ) {
        if (totalCostBasisUsd == null
                || coveredQuantity == null
                || coveredQuantity.signum() <= 0
                || totalCoveredDestinationQuantity == null
                || totalCoveredDestinationQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return totalCostBasisUsd.multiply(coveredQuantity.divide(totalCoveredDestinationQuantity, MC), MC);
    }

    private BigDecimal totalInboundQuantity(List<IndexedFlow> inboundFlows) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow inboundFlow : inboundFlows) {
            totalQuantity = totalQuantity.add(inboundFlow.flow().getQuantityDelta().abs(), MC);
        }
        return totalQuantity.signum() > 0 ? totalQuantity : BigDecimal.ZERO;
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
