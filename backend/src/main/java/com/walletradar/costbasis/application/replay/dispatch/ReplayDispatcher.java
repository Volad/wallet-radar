package com.walletradar.costbasis.application.replay.dispatch;

import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.ingestion.pipeline.bybit.BybitInternalTransferPairer;
import com.walletradar.ingestion.pipeline.bybit.BybitStreamAuthorityCollapser;
import com.walletradar.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.costbasis.application.replay.handler.EulerLoopReplayHandler;
import com.walletradar.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
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
    private final TransferReplayHandler transferReplayHandler;
    private final BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler;
    private final LiquidStakingReplayHandler liquidStakingReplayHandler;
    private final FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler;
    private final GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler;
    private final GmxLpEntryReplayHandler gmxLpEntryReplayHandler;
    private final LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler;
    private final PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler;
    private final AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler;
    private final EulerLoopReplayHandler eulerLoopReplayHandler;
    private final CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook;
    private final BorrowReplayHandler borrowReplayHandler;
    private final RepayReplayHandler repayReplayHandler;

    public ReplayDispatcher(
            ReplayTransactionRouter replayTransactionRouter,
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplayTransferClassifier transferClassifier,
            ReplayPendingTransferKeyFactory pendingTransferKeyFactory,
            TransferReplayHandler transferReplayHandler,
            BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler,
            LiquidStakingReplayHandler liquidStakingReplayHandler,
            FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler,
            GenericAsyncLifecycleReplayHandler genericAsyncLifecycleReplayHandler,
            GmxLpEntryReplayHandler gmxLpEntryReplayHandler,
            LpReceiptEntryReplayHandler lpReceiptEntryReplayHandler,
            PositionScopedLpExitReplayHandler positionScopedLpExitReplayHandler,
            AsyncSpotOrderReplayHandler asyncSpotOrderReplayHandler,
            EulerLoopReplayHandler eulerLoopReplayHandler,
            CounterpartyBasisPoolReplayHook counterpartyBasisPoolReplayHook,
            BorrowReplayHandler borrowReplayHandler,
            RepayReplayHandler repayReplayHandler
    ) {
        this.replayTransactionRouter = replayTransactionRouter;
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.transferClassifier = transferClassifier;
        this.pendingTransferKeyFactory = pendingTransferKeyFactory;
        this.transferReplayHandler = transferReplayHandler;
        this.bybitVenueInternalReplayHandler = bybitVenueInternalReplayHandler;
        this.liquidStakingReplayHandler = liquidStakingReplayHandler;
        this.familyEquivalentCustodyReplayHandler = familyEquivalentCustodyReplayHandler;
        this.genericAsyncLifecycleReplayHandler = genericAsyncLifecycleReplayHandler;
        this.gmxLpEntryReplayHandler = gmxLpEntryReplayHandler;
        this.lpReceiptEntryReplayHandler = lpReceiptEntryReplayHandler;
        this.positionScopedLpExitReplayHandler = positionScopedLpExitReplayHandler;
        this.asyncSpotOrderReplayHandler = asyncSpotOrderReplayHandler;
        this.eulerLoopReplayHandler = eulerLoopReplayHandler;
        this.counterpartyBasisPoolReplayHook = counterpartyBasisPoolReplayHook;
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
        ReplayRoutingDecision routingDecision = replayTransactionRouter.route(
                transaction,
                gmxLpEntryReplayHandler::isGmxLpEntryRequest,
                gmxLpEntryReplayHandler::isGmxLpEntrySettlement,
                lpReceiptEntryReplayHandler::isLpReceiptEntry,
                positionScopedLpExitReplayHandler::isPositionScopedLpExit,
                liquidStakingReplayHandler::selectPrincipalFlows,
                familyEquivalentCustodyReplayHandler::selectFlows
        );
        switch (routingDecision.route()) {
            case EULER_LOOP -> eulerLoopReplayHandler.apply(transaction, replayState);
            case GMX_LP_ENTRY_REQUEST -> gmxLpEntryReplayHandler.applyRequest(transaction, replayState);
            case GMX_LP_ENTRY_SETTLEMENT -> gmxLpEntryReplayHandler.applySettlement(transaction, replayState);
            case LP_RECEIPT_ENTRY -> lpReceiptEntryReplayHandler.apply(transaction, replayState);
            case ASYNC_LP_EXIT_SETTLEMENT -> genericAsyncLifecycleReplayHandler.applyAsyncLpExitSettlement(transaction, replayState);
            case POSITION_SCOPED_LP_EXIT -> positionScopedLpExitReplayHandler.apply(transaction, replayState);
            case LIQUID_STAKING -> {
                liquidStakingReplayHandler.applySelected(transaction, routingDecision.liquidStakingSelection(), replayState);
                java.util.Set<Integer> selectedIndexes = new java.util.LinkedHashSet<>();
                selectedIndexes.addAll(routingDecision.liquidStakingSelection().outbound().stream()
                        .map(flow -> flow.index())
                        .toList());
                selectedIndexes.addAll(routingDecision.liquidStakingSelection().inbound().stream()
                        .mapToInt(flow -> flow.index())
                        .boxed()
                        .toList());
                replayGenericFlowsSkipping(transaction, replayState, selectedIndexes);
            }
            case FAMILY_EQUIVALENT_CUSTODY -> {
                familyEquivalentCustodyReplayHandler.applySelected(transaction, routingDecision.familyCustodySelection(), replayState);
                replayGenericFlowsSkipping(
                        transaction,
                        replayState,
                        routingDecision.familyCustodySelection().selectedByIndex().keySet()
                );
            }
            case GENERIC -> replayGenericFlows(transaction, replayState);
        }
    }

    private void replayGenericFlows(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        replayGenericFlowsSkipping(transaction, replayState, java.util.Set.of());
    }

    private void replayGenericFlowsSkipping(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            java.util.Set<Integer> skippedIndexes
    ) {
        for (int flowIndex : compositeAwareFlowOrder(transaction, skippedIndexes)) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            applyFlow(transaction, flow, flowIndex, replayState);
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
            flowSupport.applyInboundShortfallSpotFallback(flow, position, before);
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
            flowSupport.applyInboundShortfallSpotFallback(flow, position, before);
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
            case BUY -> applyBuyWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath);
            case SELL -> applySellWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath);
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
                flowSupport.applyInboundShortfallSpotFallback(flow, position, before);
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
        java.math.BigDecimal poolAcquisitionCost = counterpartyBasisPoolReplayHook.acquisitionCostUsdForBuy(
                transaction,
                flow,
                replayState.counterpartyBasisPoolContext(),
                continuityTransferPath
        );
        if (poolAcquisitionCost != null) {
            flowSupport.applyBuyWithAcquisitionCost(flow, position, poolAcquisitionCost);
            return;
        }
        flowSupport.applyBuy(flow, position);
    }

    private void applySellWithOptionalPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            boolean continuityTransferPath
    ) {
        PositionSnapshot before = flowSupport.snapshot(position);
        flowSupport.applySell(flow, position);
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
        int sign = flow.getQuantityDelta().signum();
        if (sign == 0) {
            return null;
        }
        return corrId + "|" + wallet + "|" + family + "|" + sign;
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
        // Cross-UID transfers must never be treated as self-transfers — they move basis
        // between different Bybit UIDs and require carry propagation.
        String corrId = transaction.getCorrelationId();
        if (corrId != null && corrId.startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)) {
            return false;
        }
        String normalizedWallet = AccountingAssetIdentitySupport.positionWalletAddress(transaction);
        String counterparty = transaction.getMatchedCounterparty();
        if (counterparty == null || counterparty.isBlank()) {
            counterparty = transaction.getCounterpartyAddress();
        }
        if (counterparty == null || counterparty.isBlank()) {
            return false;
        }
        // bybit-collapsed-v1: pairs a UTA CARRY_OUT with a FUND CARRY_IN.
        // The FUND sub-account is a real economic entity that corridors basis to on-chain wallets.
        // Only the UTA side is a no-op; FUND must receive the basis.
        // Exclude from self-transfer detection whenever one wallet endpoint is :FUND.
        if (corrId != null && corrId.startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)) {
            String wallet = transaction.getWalletAddress();
            boolean fundInvolved = (wallet != null && wallet.toUpperCase(java.util.Locale.ROOT).endsWith(":FUND"))
                    || counterparty.toUpperCase(java.util.Locale.ROOT).endsWith(":FUND");
            if (fundInvolved) {
                return false;
            }
        }
        String normalizedCounterparty = AccountingAssetIdentitySupport.positionWalletAddress(counterparty);
        return normalizedWallet != null && normalizedWallet.equals(normalizedCounterparty);
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
