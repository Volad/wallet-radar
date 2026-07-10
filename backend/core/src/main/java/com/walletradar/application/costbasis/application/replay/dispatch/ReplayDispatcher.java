package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy;
import com.walletradar.canonical.correlation.BybitCarryContinuitySupport;
import com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

@Component
@Slf4j
public class ReplayDispatcher {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayTransactionRouter replayTransactionRouter;
    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplayTransferClassifier transferClassifier;
    private final ReplayPendingTransferKeyFactory pendingTransferKeyFactory;
    private final ReplayRouteHandlerRegistry replayRouteHandlerRegistry;
    private final AcquisitionFeeCapitalizationPolicy feeCapitalizationPolicy;
    private final TransferReplayHandler transferReplayHandler;
    private final BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler;
    private final LiquidStakingReplayHandler liquidStakingReplayHandler;
    private final FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler;
    private final GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler;
    private final GmxLpEntryReplayHandler gmxLpEntryReplayHandler;
    private final LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler;
    private final PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler;
    private final AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler;
    private final CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook;
    private final LeverageBorrowReplayHook leverageBorrowReplayHook;
    private final BorrowReplayHandler borrowReplayHandler;
    private final RepayReplayHandler repayReplayHandler;

    public ReplayDispatcher(
            ReplayTransactionRouter replayTransactionRouter,
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplayTransferClassifier transferClassifier,
            ReplayPendingTransferKeyFactory pendingTransferKeyFactory,
            ReplayRouteHandlerRegistry replayRouteHandlerRegistry,
            AcquisitionFeeCapitalizationPolicy feeCapitalizationPolicy,
            TransferReplayHandler transferReplayHandler,
            BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler,
            LiquidStakingReplayHandler liquidStakingReplayHandler,
            FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler,
            GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler,
            GmxLpEntryReplayHandler gmxLpEntryReplayHandler,
            LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler,
            PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler,
            AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler,
            CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook,
            LeverageBorrowReplayHook leverageBorrowReplayHook,
            BorrowReplayHandler borrowReplayHandler,
            RepayReplayHandler repayReplayHandler
    ) {
        this.replayTransactionRouter = replayTransactionRouter;
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.transferClassifier = transferClassifier;
        this.pendingTransferKeyFactory = pendingTransferKeyFactory;
        this.replayRouteHandlerRegistry = replayRouteHandlerRegistry;
        this.feeCapitalizationPolicy = feeCapitalizationPolicy;
        this.transferReplayHandler = transferReplayHandler;
        this.bybitVenueInternalReplayHandler = bybitVenueInternalReplayHandler;
        this.liquidStakingReplayHandler = liquidStakingReplayHandler;
        this.familyEquivalentCustodyReplayHandler = familyEquivalentCustodyReplayHandler;
        this.genericAsyncLifecycleReplayHandler = genericAsyncLifecycleReplayHandler;
        this.gmxLpEntryReplayHandler = gmxLpEntryReplayHandler;
        this.lpReceiptEntryReplayHandler = lpReceiptEntryReplayHandler;
        this.positionScopedLpExitReplayHandler = positionScopedLpExitReplayHandler;
        this.asyncSpotOrderReplayHandler = asyncSpotOrderReplayHandler;
        this.counterpartyBasisPoolReplayHook = counterpartyBasisPoolReplayHook;
        this.leverageBorrowReplayHook = leverageBorrowReplayHook;
        this.borrowReplayHandler = borrowReplayHandler;
        this.repayReplayHandler = repayReplayHandler;
    }

    public void dispatch(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            return;
        }
        if (isBybitSelfTransfer(transaction)) {
            log.debug("REPLAY_SKIP_SELF_TRANSFER txId={} wallet={}", transaction.getId(), transaction.getWalletAddress());
            return;
        }
        if (transaction.getType() == NormalizedTransactionType.CEX_DERIVATIVE_SETTLEMENT) {
            replayDerivativeSettlement(transaction, replayState);
            return;
        }
        ReplayRoutingDecision routingDecision = replayTransactionRouter.route(
                transaction,
                gmxLpEntryReplayHandler::isGmxLpEntryRequest,
                gmxLpEntryReplayHandler::isGmxLpEntrySettlement,
                lpReceiptEntryReplayHandler::isLpReceiptEntry,
                positionScopedLpExitReplayHandler::isPositionScopedLpExit,
                liquidStakingReplayHandler::selectPrincipalFlows,
                familyEquivalentCustodyReplayHandler::selectFlows
        );
        replayRouteHandlerRegistry.dispatch(
                transaction,
                routingDecision,
                replayState,
                dispatchCallbacks()
        );
    }

    private ReplayDispatchCallbacks dispatchCallbacks() {
        return new ReplayDispatchCallbacks() {
            @Override
            public void replayGenericFlows(NormalizedTransaction transaction, ReplayExecutionState replayState) {
                ReplayDispatcher.this.replayGenericFlows(transaction, replayState);
            }

            @Override
            public void replayGenericFlowsSkipping(
                    NormalizedTransaction transaction,
                    ReplayExecutionState replayState,
                    java.util.Set<Integer> skippedIndexes
            ) {
                ReplayDispatcher.this.replayGenericFlowsSkipping(transaction, replayState, skippedIndexes);
            }

            @Override
            public void applyLeverageBorrowIfNeeded(
                    NormalizedTransaction transaction,
                    ReplayExecutionState replayState
            ) {
                leverageBorrowReplayHook.applyIfLeverage(transaction, replayState);
            }
        };
    }

    /**
     * ADR-028 end-of-replay settlement: closes inferred-leverage synthetic borrows whose collateral
     * has fully drained from the leverage wallet. Must run once after the replay loop completes, when
     * all positions are final. Fails safe to OPEN for still-held collateral.
     */
    public void closeDrainedLeverageLiabilities(ReplayExecutionState replayState) {
        leverageBorrowReplayHook.closeDrainedLeverageLiabilities(replayState);
    }

    private void replayGenericFlows(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        replayGenericFlowsSkipping(transaction, replayState, java.util.Set.of());
    }

    private void replayDerivativeSettlement(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction.getFlows() == null) {
            return;
        }
        for (int flowIndex = 0; flowIndex < transaction.getFlows().size(); flowIndex++) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                applyFlowWithEffect(
                        transaction,
                        flow,
                        flowIndex,
                        replayState,
                        flowSupport.defaultBasisEffect(flow),
                        position -> flowSupport.applyFee(flow, position)
                );
                continue;
            }
            if (flow.getQuantityDelta().signum() > 0) {
                applyFlowWithEffect(
                        transaction,
                        flow,
                        flowIndex,
                        replayState,
                        AssetLedgerPoint.BasisEffect.ACQUIRE,
                        position -> {
                            BigDecimal unitPriceUsd = assetSupport.replayUnitPriceUsd(transaction, flow);
                            BigDecimal acquisitionCostUsd = flow.getQuantityDelta().multiply(unitPriceUsd, MC);
                            flowSupport.applyBuyWithAcquisitionCost(transaction, flow, position, acquisitionCostUsd);
                        }
                );
            } else {
                applyFlowWithEffect(
                        transaction,
                        flow,
                        flowIndex,
                        replayState,
                        AssetLedgerPoint.BasisEffect.DISPOSE,
                        position -> flowSupport.applySell(flow, position)
                );
            }
        }
    }

    private void replayGenericFlowsSkipping(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            java.util.Set<Integer> skippedIndexes
    ) {
        // ADR-040 Bug B: for SWAP transactions, track net basis released by SELL flows
        // so that BUY flows can inherit the correct net cost instead of using market price.
        // swapNetRef[0] accumulates net basis released; swapNetRef[1] accumulates market basis released.
        // null means "not a SWAP" — BUY flows fall back to standard market-price net cost.
        BigDecimal[] swapNetRef = transaction.getType() == NormalizedTransactionType.SWAP
                ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}
                : null;
        for (int flowIndex : compositeAwareFlowOrder(transaction, skippedIndexes)) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            applyFlow(transaction, flow, flowIndex, replayState, swapNetRef);
        }
    }

    /**
     * Composite {@code lp:}/{@code wrapper:} buckets must receive outbound deposits before inbound
     * restores in the same transaction. Production Curve mint txs list the LP receipt leg first.
     */
    private java.util.List<Integer> compositeAwareFlowOrder(
            NormalizedTransaction transaction,
            java.util.Set<Integer> skippedIndexes
    ) {
        int flowCount = transaction.getFlows() == null ? 0 : transaction.getFlows().size();
        java.util.List<Integer> naturalOrder = new java.util.ArrayList<>();
        for (int flowIndex = 0; flowIndex < flowCount; flowIndex++) {
            if (!skippedIndexes.contains(flowIndex)) {
                naturalOrder.add(flowIndex);
            }
        }
        if (!pendingTransferKeyFactory.usesCompositeContinuityBucket(transaction)) {
            return naturalOrder;
        }
        java.util.List<Integer> outbound = new java.util.ArrayList<>();
        java.util.List<Integer> inbound = new java.util.ArrayList<>();
        java.util.List<Integer> other = new java.util.ArrayList<>();
        for (int flowIndex : naturalOrder) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            if (transferClassifier.isBucketOutbound(transaction, flow)) {
                outbound.add(flowIndex);
            } else if (transferClassifier.isBucketInbound(transaction, flow)) {
                inbound.add(flowIndex);
            } else {
                other.add(flowIndex);
            }
        }
        java.util.List<Integer> ordered = new java.util.ArrayList<>(outbound.size() + inbound.size() + other.size());
        ordered.addAll(outbound);
        ordered.addAll(inbound);
        ordered.addAll(other);
        return ordered;
    }

    private void applyFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            ReplayExecutionState replayState
    ) {
        applyFlow(transaction, flow, flowIndex, replayState, null);
    }

    private void applyFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            ReplayExecutionState replayState,
            BigDecimal[] swapNetRef
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        if (asyncSpotOrderReplayHandler.isAsyncSpotOrderRequestSell(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                    position -> asyncSpotOrderReplayHandler.applyRequest(transaction, flow, position, replayState)
            );
            return;
        }
        if (asyncSpotOrderReplayHandler.isAsyncSpotOrderSettlementBuy(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.ACQUIRE,
                    position -> asyncSpotOrderReplayHandler.applySettlement(transaction, flow, position, replayState)
            );
            return;
        }
        if (genericAsyncLifecycleReplayHandler.isAsyncLifecycleRequestOutbound(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                    position -> genericAsyncLifecycleReplayHandler.applyAsyncLifecycleRequest(transaction, flow, position, replayState)
            );
            return;
        }
        if (genericAsyncLifecycleReplayHandler.isAsyncLifecycleSettlementInbound(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                    position -> genericAsyncLifecycleReplayHandler.applyAsyncLifecycleSettlement(transaction, flow, position, replayState)
            );
            return;
        }
        if (genericAsyncLifecycleReplayHandler.isPositionScopedLpEntryOutbound(transaction, flow)) {
            applyFlowWithEffect(
                    transaction,
                    flow,
                    flowIndex,
                    replayState,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                    position -> genericAsyncLifecycleReplayHandler.applyAsyncLifecycleRequest(
                            transaction,
                            flow,
                            position,
                            replayState,
                            assetSupport.continuityIdentity(transaction, flow)
                    )
            );
            return;
        }
        if (positionScopedLpExitReplayHandler.shouldIgnoreLpReceiptMarker(transaction, flow)) {
            return;
        }
        if (transaction.getType() == NormalizedTransactionType.BORROW) {
            // Non-debt outbound flows in a BORROW tx represent collateral deposited into the
            // lending protocol (e.g. INIT Capital's combined deposit+borrow execute() call).
            // Route them to the continuity bucket so the paired LENDING_WITHDRAW can restore
            // the basis via isBucketInbound. Standard AAVE-style borrows never have outbound
            // non-debt flows in the BORROW tx, so this branch does not affect them.
            if (!AccountingAssetIdentitySupport.isDebtIdentity(flow.getAssetSymbol())
                    && flow.getQuantityDelta() != null
                    && flow.getQuantityDelta().signum() < 0) {
                AssetKey assetKey = assetSupport.assetKey(transaction, flow);
                PositionState borrowCollateralPosition = replayState.position(assetKey);
                borrowCollateralPosition.setLastEventTimestamp(
                        flowSupport.laterOf(borrowCollateralPosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
                PositionSnapshot borrowCollateralBefore = flowSupport.snapshot(borrowCollateralPosition);
                AssetLedgerPoint.BasisEffect collateralEffect = transferReplayHandler.applyBorrowCollateralOut(
                        transaction, flow, flowIndex, borrowCollateralPosition, replayState);
                replayState.ledgerPointCollector().record(
                        transaction, flow, flowIndex,
                        borrowCollateralPosition.assetKey(),
                        borrowCollateralBefore, borrowCollateralPosition,
                        collateralEffect);
                return;
            }
            borrowReplayHandler.apply(transaction, flow, flowIndex, replayState);
            return;
        }
        if (transaction.getType() == NormalizedTransactionType.REPAY) {
            repayReplayHandler.apply(transaction, flow, flowIndex, replayState);
            return;
        }

        AssetKey assetKey = flow.getRole() == NormalizedLegRole.SELL
                ? assetSupport.resolveSellAssetKey(transaction, flow, replayState.positions().asMap())
                : assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);

        if (isSponsoredGasIn(transaction, flow)) {
            // ERC-4337 Paymaster gas rebates are pure dust (no material economic substance).
            // Restore the tiny qty with zero cost so position qty is consistent, but emit
            // GAS_ONLY — not ACQUIRE — so no cost-basis delta enters AVCO computation.
            flowSupport.applySponsoredGasIn(flow, position);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey(),
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.GAS_ONLY
            );
            return;
        }

        if (bybitVenueInternalReplayHandler.applies(transaction, flow)) {
            AssetLedgerPoint.BasisEffect basisEffect = bybitVenueInternalReplayHandler.apply(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState
            );
            PositionSnapshot afterTransfer = flowSupport.snapshot(position);
            flowSupport.applyInboundShortfallSpotFallback(transaction, flow, position, before);
            accumulateInboundSpotFallbackProvisional(transaction, flow, position, afterTransfer, replayState);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey(),
                    before,
                    position,
                    basisEffect
            );
            return;
        }

        if (transferClassifier.shouldTreatAsContinuityTransfer(transaction, flow)) {
            // Cycle/7 S5: dedup safety net. Upstream Bybit stream-authority collapser is the
            // primary mechanism for suppressing mirror documents, but if a mirror slips through
            // (e.g., new stream not yet covered by the policy), this guard prevents the same
            // economic flow from disposing/acquiring twice via the continuity-transfer path.
            String continuityDedupKey = continuityDedupKey(transaction, flow);
            if (continuityDedupKey != null && !replayState.markContinuityFlowSeen(continuityDedupKey)) {
                log.warn("REPLAY_DEDUP_MIRROR_SKIPPED txId={} corrId={} wallet={} family={} sign={} qty={}",
                        transaction.getId(),
                        transaction.getCorrelationId(),
                        transaction.getWalletAddress(),
                        assetSupport.continuityIdentity(transaction, flow),
                        flow.getQuantityDelta().signum(),
                        flow.getQuantityDelta());
                return;
            }
            AssetLedgerPoint.BasisEffect basisEffect = transferReplayHandler.applyTransfer(
                    transaction,
                    flowSupport.asTransferFlow(flow),
                    flowIndex,
                    position,
                    replayState
            );
            // Cycle/15 R5 F3: pegged-native spot-basis fallback for unbacked inbound transfers
            // (CMETH / METH / WEETH / BBSOL). Runs after the standard continuity carry so the
            // pairing path remains authoritative whenever it does supply basis.
            PositionSnapshot afterTransfer = flowSupport.snapshot(position);
            flowSupport.applyInboundShortfallSpotFallback(transaction, flow, position, before);
            accumulateInboundSpotFallbackProvisional(transaction, flow, position, afterTransfer, replayState);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey(),
                    before,
                    position,
                    basisEffect
            );
            return;
        }

        boolean continuityTransferPath = transferClassifier.shouldTreatAsContinuityTransfer(transaction, flow);
        switch (flow.getRole()) {
            case BUY -> applyBuyWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, swapNetRef);
            case SELL -> applySellWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, swapNetRef);
            case FEE -> flowSupport.applyFee(flow, position);
            case TRANSFER -> {
                AssetLedgerPoint.BasisEffect basisEffect = transferReplayHandler.applyTransfer(
                        transaction,
                        flow,
                        flowIndex,
                        position,
                        replayState
                );
                // Cycle/15 R5 F3: pegged-native spot-basis fallback also covers the
                // non-continuity TRANSFER path (e.g., wrapper-shape LP→gauge stake when the
                // gauge token is itself a pegged-native receipt — currently none, but the
                // hook is symmetric for safety).
                PositionSnapshot afterTransfer = flowSupport.snapshot(position);
                flowSupport.applyInboundShortfallSpotFallback(transaction, flow, position, before);
                accumulateInboundSpotFallbackProvisional(transaction, flow, position, afterTransfer, replayState);
                replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    flowIndex,
                    position.assetKey(),
                    before,
                    position,
                    basisEffect
                );
                return;
            }
        }
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                flowSupport.defaultBasisEffect(flow)
        );
    }

    private void applyBuyWithOptionalPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            boolean continuityTransferPath
    ) {
        applyBuyWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, null);
    }

    /**
     * ADR-040 Bug B: {@code swapNetRef} carries the net basis released by prior SELL flows in the
     * same SWAP transaction. When set, the inbound BUY leg inherits that net cost (tax lane still
     * uses market price), preventing reward-derived disposals from raising Net AVCO.
     *
     * <p>ADR-051: after applying the standard buy logic, any CEX acquisition fee stored in
     * {@code flow.acquisitionFeeUsd} is capitalized into the Net lane only via
     * {@link ReplayFlowSupport#capitalizeFeeIntoNetLane}. Market AVCO is never affected.
     */
    private void applyBuyWithOptionalPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            boolean continuityTransferPath,
            BigDecimal[] swapNetRef
    ) {
        BigDecimal poolAcquisitionCost = counterpartyBasisPoolReplayHook.acquisitionCostUsdForBuy(
                transaction,
                flow,
                replayState.counterpartyBasisPoolContext(),
                continuityTransferPath
        );
        // ADR-040 Bug B: when net basis from SELL is available, use it as the net acquisition cost
        // for the BUY leg. Tax (market) cost is computed normally.
        if (swapNetRef != null && swapNetRef[1].signum() > 0) {
            BigDecimal netBasisReleased = swapNetRef[0];
            if (poolAcquisitionCost != null) {
                flowSupport.applyBuyWithExplicitNetCost(flow, position, poolAcquisitionCost, netBasisReleased);
            } else {
                BigDecimal taxCost = computeMarketCost(transaction, flow);
                if (taxCost != null) {
                    flowSupport.applyBuyWithExplicitNetCost(flow, position, taxCost, netBasisReleased);
                } else {
                    flowSupport.applyBuy(transaction, flow, position);
                }
            }
            // Consume the swap net context so subsequent BUY flows use standard pricing
            swapNetRef[0] = BigDecimal.ZERO;
            swapNetRef[1] = BigDecimal.ZERO;
            capitalizeCexFeeIfApplicable(transaction, flow, position);
            return;
        }
        if (poolAcquisitionCost != null) {
            flowSupport.applyBuyWithAcquisitionCost(transaction, flow, position, poolAcquisitionCost);
            capitalizeCexFeeIfApplicable(transaction, flow, position);
            return;
        }
        flowSupport.applyBuy(transaction, flow, position);
        capitalizeCexFeeIfApplicable(transaction, flow, position);
    }

    /**
     * ADR-051: if the policy allows and the flow carries a buy-side CEX commission, adds it to the
     * Net AVCO lane (and {@code gasPaidUsd}) without touching the Market (tax) lane.
     */
    private void capitalizeCexFeeIfApplicable(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position
    ) {
        BigDecimal feeUsd = feeCapitalizationPolicy.capitalizableFeeUsd(transaction, flow);
        if (feeUsd != null) {
            flowSupport.capitalizeFeeIntoNetLane(feeUsd, position);
        }
    }

    private void applySellWithOptionalPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            boolean continuityTransferPath
    ) {
        applySellWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, null);
    }

    private void applySellWithOptionalPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            boolean continuityTransferPath,
            BigDecimal[] swapNetRef
    ) {
        PositionSnapshot before = flowSupport.snapshot(position);
        flowSupport.applySell(flow, position);
        // ADR-040 Bug B: accumulate net and market basis released by SELL flows in SWAP transactions.
        if (swapNetRef != null) {
            BigDecimal netReleased = safeDelta(before.netTotalCostBasisUsd(), position.netTotalCostBasisUsd());
            BigDecimal mktReleased = safeDelta(before.totalCostBasisUsd(), position.totalCostBasisUsd());
            swapNetRef[0] = swapNetRef[0].add(netReleased, MC);
            swapNetRef[1] = swapNetRef[1].add(mktReleased, MC);
        }
        if (counterpartyBasisPoolReplayHook.shouldApplyPool(transaction, flow, continuityTransferPath)) {
            counterpartyBasisPoolReplayHook.undoSellRealisedPnl(flow, position, before);
            counterpartyBasisPoolReplayHook.afterSell(
                    transaction,
                    flow,
                    before,
                    replayState.counterpartyBasisPoolContext(),
                    continuityTransferPath
            );
        }
    }

    private BigDecimal computeMarketCost(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0) {
            return quantity.multiply(flow.getUnitPriceUsd(), MC);
        }
        return null;
    }

    private static BigDecimal safeDelta(BigDecimal before, BigDecimal after) {
        BigDecimal b = before == null ? BigDecimal.ZERO : before;
        BigDecimal a = after == null ? BigDecimal.ZERO : after;
        BigDecimal delta = b.subtract(a);
        return delta.signum() > 0 ? delta : BigDecimal.ZERO;
    }

    private void applyFlowWithEffect(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            ReplayExecutionState replayState,
            AssetLedgerPoint.BasisEffect basisEffect,
            java.util.function.Consumer<PositionState> action
    ) {
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);
        action.accept(position);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                basisEffect
        );
    }

    private boolean isSponsoredGasIn(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && transaction.getType() == NormalizedTransactionType.SPONSORED_GAS_IN
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0;
    }

    /**
     * Cycle/7 S5: continuity-path duplicate fingerprint.
     *
     * <p>Returns {@code null} when the flow has no usable correlation id or no asset identity —
     * those cases do not participate in cross-document continuity matching so dedup is moot.</p>
     */
    private String continuityDedupKey(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId == null || corrId.isBlank()) {
            return null;
        }
        String family = assetSupport.continuityIdentity(transaction, flow);
        if (family == null) {
            return null;
        }
        String wallet = transaction.getWalletAddress() == null ? "" : transaction.getWalletAddress();
        String network = transaction.getNetworkId() == null ? "" : transaction.getNetworkId().name();
        int sign = flow.getQuantityDelta().signum();
        if (sign == 0) {
            return null;
        }
        return corrId + "|" + wallet + "|" + network + "|" + family + "|" + sign;
    }

    /**
     * Detects UTA &lt;-&gt; FUND self-transfers that map to the same unified position
     * after wallet address normalization. These are no-ops for cost basis.
     */
    private boolean isBybitSelfTransfer(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT
                || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        Boolean stamped = transaction.getSelfTransferNoop();
        if (stamped != null) {
            return stamped;
        }
        return Boolean.TRUE.equals(BybitCarryContinuitySupport.resolveSelfTransferNoop(transaction));
    }

    private void accumulateInboundSpotFallbackProvisional(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot afterTransfer,
            ReplayExecutionState replayState
    ) {
        if (flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0
                || position == null
                || afterTransfer == null) {
            return;
        }
        BigDecimal basisAfterTransfer = afterTransfer.totalCostBasisUsd() == null
                ? BigDecimal.ZERO
                : afterTransfer.totalCostBasisUsd();
        BigDecimal basisAfterSpot = position.totalCostBasisUsd() == null ? BigDecimal.ZERO : position.totalCostBasisUsd();
        BigDecimal spotAdded = basisAfterSpot.subtract(basisAfterTransfer, MC);
        if (spotAdded.signum() <= 0) {
            return;
        }
        transferReplayHandler.accumulateInboundSpotFallbackProvisional(
                transaction,
                flow,
                spotAdded,
                replayState
        );
    }
}
