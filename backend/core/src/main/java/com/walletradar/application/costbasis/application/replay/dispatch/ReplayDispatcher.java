package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy;
import com.walletradar.application.costbasis.support.LpReceiptStakeWrapSupport;
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
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

@Component
@Slf4j
public class ReplayDispatcher {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * FB-01 / ADR-082: index of the "net-realized-kept" magnitude accumulated alongside the
     * ADR-040 swap net/market basis released ({@code swapNetRef[0]}=net released,
     * {@code swapNetRef[1]}=market released). A positive value means a paired SELL leg realized NET
     * PnL that was NOT reversed by the counterparty-basis-pool undo path — i.e. the pre-loop
     * discount has already been banked once, so the acquired lot must re-base its NET basis to the
     * market acquisition cost instead of recycling the released net basis.
     */
    private static final int SWAP_NET_REF_KEPT_INDEX = 2;

    /** Dust threshold (USD) below which a kept NET realized magnitude is treated as none. */
    private static final BigDecimal NET_REALISED_KEPT_EPSILON_USD = new BigDecimal("0.01");

    /**
     * FB-01 / ADR-082: a disposed lot carries a genuine reward/yield discount only when its released
     * NET basis is materially below its released Market basis. A tiny relative gap (rounding / AVCO
     * dust, e.g. the observed ~0.34% cmETH↔PT-cmETH residual) is NOT a reward discount and must
     * re-base; a material gap (a real zero-cost reward carry) must be preserved. 1% sits ~3x above
     * the artifact residual and far below genuine reward discounts (typically ≫10%, often 100%).
     */
    private static final BigDecimal NET_DISCOUNT_RELATIVE_THRESHOLD = new BigDecimal("0.01");

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
    private final ReplayMarketAuthority replayMarketAuthority;

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
            RepayReplayHandler repayReplayHandler,
            ReplayMarketAuthority replayMarketAuthority
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
        this.replayMarketAuthority = replayMarketAuthority;
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
        // ADR-040 Bug B: for SWAP transactions (and ADR-054 cross-canonical C1→C2 staking/vault
        // conversions), track net basis released by SELL/outbound flows so that BUY/inbound flows
        // can inherit the correct net cost instead of using fresh market price.
        // swapNetRef[0] accumulates net basis released; swapNetRef[1] accumulates market basis released.
        // FB-01 / ADR-082: swapNetRef[2] accumulates the KEPT NET realized magnitude (not undone by
        // the counterparty-basis-pool path) so a realizing distinct-canonical swap re-bases the
        // acquired NET basis to market instead of recycling the released net basis.
        // null means neither type — BUY flows fall back to standard market-price net cost.
        // ADR-083 §4.4: an intra-cluster cross-canonical conversion (ETH↔mETH, SOL↔mSOL,
        // AVAX↔sAVAX, …) is carried at the CLUSTER_CARRY route with PnL=0 and must NOT also enter
        // the realize path. Routing alone does not stop this: replayGenericFlowsSkipping iterates
        // ALL flows (ignoring the carried skip set) for the swapNetRef re-base / D1 applyUnknownTransfer
        // / stampCrossCanonicalRedemptionProceedsFromInbound machinery. Gate those OFF for carried
        // txs while keeping them live for cluster↔non-cluster and cross-cluster realizing conversions.
        boolean isCrossCanonicalStaking = (transaction.getType() == NormalizedTransactionType.STAKING_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.STAKING_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.VAULT_DEPOSIT
                || transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW)
                && AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction)
                && !AccountingAssetClassificationSupport.isIntraClusterConversion(transaction);
        // ADR-054 addendum: for an atomic cross-canonical redemption, the outbound (disposed,
        // illiquid receipt) proceeds must equal the resolved FMV of the inbound (acquired,
        // reliably-priced underlying) so value-out == value-in. Stamp the outbound leg price
        // BEFORE the disposal leg is applied (outbound is processed first, see
        // compositeAwareFlowOrder). Self-gated to WITHDRAW-direction redemptions.
        if (isCrossCanonicalStaking) {
            stampCrossCanonicalRedemptionProceedsFromInbound(transaction);
        }
        BigDecimal[] swapNetRef = (transaction.getType() == NormalizedTransactionType.SWAP || isCrossCanonicalStaking)
                ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}
                : null;
        for (int flowIndex : compositeAwareFlowOrder(transaction, skippedIndexes, isCrossCanonicalStaking)) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            // BLOCKER-9 / ADR-057: within a LENDING_LOOP_REBALANCE, skip inbound flows whose
            // counterpartyAddress matches the within-transaction sentinel pattern
            // "UNKNOWN:<txHash>:...". These are transient flash-loan legs that are self-cancelling
            // within the same block and must not credit user inventory.
            if (isLendingLoopRebalanceFlashLoanInflow(transaction, flow)) {
                log.debug("REPLAY_SKIP_FLASH_LOAN_INFLOW txId={} flowIndex={} asset={}",
                        transaction.getId(), flowIndex,
                        flow == null ? null : flow.getAssetSymbol());
                continue;
            }
            applyFlow(transaction, flow, flowIndex, replayState, swapNetRef);
        }
    }

    /**
     * BLOCKER-9 / ADR-057: Returns {@code true} when a flow is a within-transaction flash-loan
     * inflow inside a {@code LENDING_LOOP_REBALANCE} transaction.
     *
     * <p>Euler Finance v2 EVK rebalance transactions flash-loan the user's existing share tokens
     * (e.g. eUSDC-2) back into the same transaction. The loan is repaid within the same block;
     * the inbound leg is transient and carries no real economic acquisition for the user. The
     * sentinel counterparty pattern {@code UNKNOWN:<txHash>:…} is stamped by the on-chain flow
     * builder to mark these ephemeral within-transaction counterparties.</p>
     */
    static boolean isLendingLoopRebalanceFlashLoanInflow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction.getType() != NormalizedTransactionType.LENDING_LOOP_REBALANCE) {
            return false;
        }
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
            return false;
        }
        String counterparty = flow.getCounterpartyAddress();
        if (counterparty == null || counterparty.isBlank()) {
            return false;
        }
        String txHash = transaction.getTxHash();
        if (txHash == null || txHash.isBlank()) {
            return false;
        }
        // Sentinel pattern: UNKNOWN:<txHash>:NETWORK:WALLET:TRANSFER:ASSET:INDEX
        String counterpartyUpper = counterparty.toUpperCase(java.util.Locale.ROOT);
        if (!counterpartyUpper.startsWith("UNKNOWN:")) {
            return false;
        }
        return counterparty.toLowerCase(java.util.Locale.ROOT)
                .contains(txHash.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Composite {@code lp:}/{@code wrapper:} buckets must receive outbound deposits before inbound
     * restores in the same transaction. Production Curve mint txs list the LP receipt leg first.
     *
     * <p>ADR-054: cross-canonical staking/vault transactions also require the disposal (outbound) leg
     * to be processed before the acquisition (inbound) leg so the {@code swapNetRef} accumulator is
     * populated before the BUY leg consumes it. Ordering uses quantity-sign: negative = outbound.
     */
    private java.util.List<Integer> compositeAwareFlowOrder(
            NormalizedTransaction transaction,
            java.util.Set<Integer> skippedIndexes,
            boolean isCrossCanonicalStaking
    ) {
        int flowCount = transaction.getFlows() == null ? 0 : transaction.getFlows().size();
        java.util.List<Integer> naturalOrder = new java.util.ArrayList<>();
        for (int flowIndex = 0; flowIndex < flowCount; flowIndex++) {
            if (!skippedIndexes.contains(flowIndex)) {
                naturalOrder.add(flowIndex);
            }
        }
        if (!pendingTransferKeyFactory.usesCompositeContinuityBucket(transaction) && !isCrossCanonicalStaking) {
            return naturalOrder;
        }
        java.util.List<Integer> outbound = new java.util.ArrayList<>();
        java.util.List<Integer> inbound = new java.util.ArrayList<>();
        java.util.List<Integer> other = new java.util.ArrayList<>();
        for (int flowIndex : naturalOrder) {
            NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
            boolean isOutbound;
            boolean isInbound;
            if (isCrossCanonicalStaking) {
                // Use quantity sign to identify disposal-before-acquisition ordering.
                // This handles both TRANSFER-role (shouldApplyCrossCanonicalMarketLeg) and
                // BUY/SELL-role flows that may arrive inbound-first in production data.
                isOutbound = flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0;
                isInbound = flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0;
            } else {
                isOutbound = transferClassifier.isBucketOutbound(transaction, flow);
                isInbound = transferClassifier.isBucketInbound(transaction, flow);
            }
            if (isOutbound) {
                outbound.add(flowIndex);
            } else if (isInbound) {
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

        if (LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(transaction, flow)) {
            applyLpReceiptStakeWrap(transaction, flow, flowIndex, position, before, replayState);
            return;
        }

        if (isLpPositionUnstakeRewardInflow(transaction, flow)) {
            // R6a: BAL/AURA/WAVAX (and any non-principal reward token) received on an
            // LP_POSITION_UNSTAKE are zero-cost income legs, not carried LP principal. Book them
            // like the LP-exit reward sideflow: tax lane = FMV (income realized on later sale),
            // net lane = $0 (REWARD_CLAIM zero-cost). The LP-RECEIPT principal leg is excluded (it
            // rides the continuity carry) and the wrapper receipt is outbound, so only genuine
            // reward tokens reach this branch.
            applyZeroCostRewardInflow(transaction, flow, flowIndex, position, before, replayState);
            return;
        }

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
            //
            // Finding 2: NEVER run the spot fallback on a custody-roundtrip return leg. The pooled
            // envelope already redistributes EXACTLY the carried-out basis onto the returned assets
            // (Σ carried-in == Σ carried-out); any returned quantity whose value exceeds the pool is
            // deliberately left uncovered. Market-pricing that surplus here fabricated the observed
            // +$204.58 ETH-family basis. The envelope path is fully authoritative for these legs.
            if (!transferClassifier.isCustodyRoundTripContinuityTransfer(transaction, flow)) {
                PositionSnapshot afterTransfer = flowSupport.snapshot(position);
                flowSupport.applyInboundShortfallSpotFallback(transaction, flow, position, before);
                accumulateInboundSpotFallbackProvisional(transaction, flow, position, afterTransfer, replayState);
            }
            // NEW-02: after the spot fallback prices an unpaired external-capital INFLOW at market
            // value it is economically an acquisition — relabel UNKNOWN → ACQUIRE (label only).
            basisEffect = resolveExternalCapitalInflowAcquisition(transaction, flow, position, basisEffect);
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
                if (shouldApplyCrossCanonicalMarketLeg(transaction, flow)) {
                    if (!stampCrossCanonicalMarketPrice(transaction, flow)) {
                        // ADR-054: when the cross-canonical inbound leg has no market price but the
                        // paired outbound disposal released basis into swapNetRef, inherit that basis
                        // as the acquisition cost. This preserves cost continuity for unpriced receipt
                        // tokens (e.g. METH acquired via ETH staking, GTUSDCC via wstETH vault deposit).
                        //
                        // D1 / ADR-054 §9 fail-closed: take the inheritance path ONLY when a market
                        // cost is resolvable for this acquisition leg. The ADR-040 Net≤Market cap needs
                        // a market anchor; without one, applyBuyWithOptionalPool would collapse to
                        // flowSupport.applyBuy(...) and silently book $0 in BOTH lanes — stripping the
                        // disposed family's cost basis. When no market cost resolves, fall through to
                        // the UNKNOWN path below so AVCO is left UNDEFINED and the point is flagged.
                        if (swapNetRef != null
                                && swapNetRef[1].signum() > 0
                                && flow.getQuantityDelta() != null
                                && flow.getQuantityDelta().signum() > 0
                                && computeMarketCost(transaction, flow) != null) {
                            applyBuyWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, swapNetRef);
                            replayState.ledgerPointCollector().record(
                                    transaction,
                                    flow,
                                    flowIndex,
                                    position.assetKey(),
                                    before,
                                    position,
                                    AssetLedgerPoint.BasisEffect.ACQUIRE
                            );
                            return;
                        }
                        flowSupport.applyUnknownTransfer(flow, position);
                        replayState.ledgerPointCollector().record(
                                transaction,
                                flow,
                                flowIndex,
                                position.assetKey(),
                                before,
                                position,
                                AssetLedgerPoint.BasisEffect.UNKNOWN
                        );
                        return;
                    }
                    if (flow.getQuantityDelta().signum() < 0) {
                        applySellWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, swapNetRef);
                    } else {
                        applyBuyWithOptionalPool(transaction, flow, position, replayState, continuityTransferPath, swapNetRef);
                    }
                    replayState.ledgerPointCollector().record(
                            transaction,
                            flow,
                            flowIndex,
                            position.assetKey(),
                            before,
                            position,
                            flow.getQuantityDelta().signum() < 0
                                    ? AssetLedgerPoint.BasisEffect.DISPOSE
                                    : AssetLedgerPoint.BasisEffect.ACQUIRE
                    );
                    return;
                }
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
                // NEW-02: external-capital INFLOW priced via the spot fallback is an acquisition,
                // not an UNKNOWN transfer — relabel UNKNOWN → ACQUIRE (label only).
                basisEffect = resolveExternalCapitalInflowAcquisition(transaction, flow, position, basisEffect);
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

    /**
     * RC-6 (ADR-047 addendum): books a non-realizing LP-receipt stake/unstake wrap leg.
     *
     * <p>Staking a Pendle LP receipt into an Equilibria/Penpie booster (or the symmetric unstake)
     * is a continuation of the same LP position, not a sale. The authoritative basis lives in the
     * {@code pendle-lp:*} receipt basis pool (deposited at LP entry, restored at the zap-out
     * {@code LP_EXIT} by {@code PositionScopedLpExitReplayHandler}), so this handler must NOT touch
     * that pool. It simply drains the synthetic receipt holding with no realized P&amp;L (outbound
     * {@code CARRY_OUT}) — or receives the wrapper receipt without pricing it (inbound
     * {@code CARRY_IN}). Draining here (rather than parking basis into a continuity bucket that no
     * downstream leg consumes) keeps the ledger clean and leaves the pool-backed exit path intact.</p>
     */
    private void applyLpReceiptStakeWrap(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            PositionSnapshot before,
            ReplayExecutionState replayState
    ) {
        flow.setAvcoAtTimeOfSale(null);
        flow.setRealisedPnlUsd(null);
        AssetLedgerPoint.BasisEffect basisEffect;
        if (flow.getQuantityDelta().signum() < 0) {
            flowSupport.removeFromPosition(flow, position);
            basisEffect = AssetLedgerPoint.BasisEffect.CARRY_OUT;
        } else {
            flowSupport.applyUnknownTransfer(flow, position);
            basisEffect = AssetLedgerPoint.BasisEffect.CARRY_IN;
        }
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

    /**
     * R6a: {@code true} when {@code flow} is a zero-cost reward token received on an
     * {@code LP_POSITION_UNSTAKE}. Excludes the LP-RECEIPT principal leg (which carries the position
     * basis through the wrapper via the continuity path). The wrapper receipt token itself is
     * outbound (burned) on an unstake, so only genuine reward tokens (BAL/AURA/WAVAX, etc.) reach
     * this branch. Generalized: no protocol/address/token hardcoding.
     */
    private boolean isLpPositionUnstakeRewardInflow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || transaction.getType() != NormalizedTransactionType.LP_POSITION_UNSTAKE
                || flow == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0) {
            return false;
        }
        // Scope guard (plan R6a): only Balancer/Aura unstakes, whose LP principal is canonicalized to
        // an LP-RECEIPT leg. On these txs the only inbound non-LP-RECEIPT TRANSFER legs are genuine
        // reward tokens (BAL/AURA/WAVAX). Fungible v2-gauge unstakes (Velodrome/Aerodrome) return the
        // raw staked LP token as principal (NOT an LP-RECEIPT), so they must keep the continuity-carry
        // path and are intentionally excluded here.
        String corrId = transaction.getCorrelationId();
        if (corrId == null || !corrId.toLowerCase(java.util.Locale.ROOT).contains(":balancerv3:")) {
            return false;
        }
        // Exclude the LP principal itself (the LP-RECEIPT leg rides the continuity carry).
        if (Boolean.TRUE.equals(flow.getLpReceipt())
                || AccountingAssetFamilySupport.isLpReceiptSymbol(flow.getAssetSymbol())) {
            return false;
        }
        return true;
    }

    /**
     * R6a: books a zero-cost reward inflow — tax lane at FMV (income realized on later sale), net
     * lane at $0 ({@code REWARD_CLAIM} zero-cost). Mirrors the LP-exit reward sideflow booking so
     * gauge/Aura unstake rewards are consistent with fee/reward legs on LP exits.
     */
    private void applyZeroCostRewardInflow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            PositionSnapshot before,
            ReplayExecutionState replayState
    ) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal unitPriceUsd = assetSupport.replayUnitPriceUsd(transaction, flow);
        BigDecimal acquisitionCostUsd = unitPriceUsd != null && unitPriceUsd.signum() > 0
                ? quantity.multiply(unitPriceUsd, MC)
                : BigDecimal.ZERO;
        flowSupport.applyBuyWithAcquisitionCost(
                flow,
                position,
                acquisitionCostUsd,
                NormalizedTransactionType.REWARD_CLAIM
        );
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.ACQUIRE
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
        // for the BUY leg. Market cost is computed normally.
        // ADR-040 invariant: Net AVCO ≤ Market AVCO — cap inherited net basis at market basis.
        if (swapNetRef != null && swapNetRef[1].signum() > 0) {
            BigDecimal netBasisReleased = swapNetRef[0];
            // FB-01 / ADR-082 ("re-base on realize"): when the paired disposal REALIZED-AND-KEPT its
            // NET PnL on a lot that carried no reward discount (net released ≈ market released), the
            // pre-loop discount has already been banked once at the SELL. Recycling the released net
            // basis onto the freshly-priced acquisition would re-plant that discount and realize the
            // same appreciation again on the next disposal (the cmETH↔PT-cmETH recycling defect).
            // Re-base the NET lane to the market acquisition cost instead. The existing
            // min(released, market) carry is PRESERVED for genuine reward discounts (net ≪ market)
            // and for deferred/pool disposals whose NET realized was undone (kept ≈ 0). Market lane
            // is never touched (byte-identical); realized PnL on the disposal legs is unchanged.
            boolean netRealisedKept = swapNetRef.length > SWAP_NET_REF_KEPT_INDEX
                    && swapNetRef[SWAP_NET_REF_KEPT_INDEX].compareTo(NET_REALISED_KEPT_EPSILON_USD) > 0;
            boolean rewardDiscountPresent = hasRewardDiscount(netBasisReleased, swapNetRef[1]);
            boolean reBaseNetToMarket = netRealisedKept && !rewardDiscountPresent;
            if (poolAcquisitionCost != null) {
                BigDecimal effectiveNetBasis = reBaseNetToMarket
                        ? poolAcquisitionCost
                        : netBasisReleased.min(poolAcquisitionCost);
                flowSupport.applyBuyWithExplicitNetCost(flow, position, poolAcquisitionCost, effectiveNetBasis);
            } else {
                BigDecimal taxCost = computeMarketCost(transaction, flow);
                if (taxCost != null) {
                    BigDecimal effectiveNetBasis = reBaseNetToMarket
                            ? taxCost
                            : netBasisReleased.min(taxCost);
                    flowSupport.applyBuyWithExplicitNetCost(flow, position, taxCost, effectiveNetBasis);
                } else if (isCrossCanonicalStakingPrincipalAcquisition(transaction, flow)) {
                    // D1 / ADR-054 §9 fail-closed: never silently book $0 for an unpriced
                    // cross-canonical staking/vault acquisition leg. Route to the UNKNOWN transfer
                    // mechanism so AVCO is left UNDEFINED (and the ledger point is flagged) instead of
                    // fabricating a $0-basis lot that strips the disposed family's cost basis. The
                    // priced branch (taxCost != null) is unchanged, so net-inheritance conversions
                    // such as AVAX → sAVAX are unaffected.
                    flowSupport.applyUnknownTransfer(flow, position);
                } else {
                    flowSupport.applyBuy(transaction, flow, position);
                }
            }
            // Consume the swap net context so subsequent BUY flows use standard pricing
            swapNetRef[0] = BigDecimal.ZERO;
            swapNetRef[1] = BigDecimal.ZERO;
            if (swapNetRef.length > SWAP_NET_REF_KEPT_INDEX) {
                swapNetRef[SWAP_NET_REF_KEPT_INDEX] = BigDecimal.ZERO;
            }
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
        // FB-01 / ADR-082: record whether this SELL KEPT its NET realized PnL. The counterparty
        // basis-pool path above reverses it (undoSellRealisedPnl) → nothing was banked → the paired
        // acquisition must keep inheriting the released net basis (deferred carry). A kept realized
        // delta means the pre-loop discount has already been banked once, so the acquisition must
        // re-base its NET basis to market. Measured AFTER the pool branch so an undo yields zero.
        if (swapNetRef != null && swapNetRef.length > SWAP_NET_REF_KEPT_INDEX) {
            BigDecimal netRealisedBefore = zeroIfNull(before.totalNetRealisedPnlUsd());
            BigDecimal netRealisedAfter = zeroIfNull(position.totalNetRealisedPnlUsd());
            BigDecimal keptNetRealised = netRealisedAfter.subtract(netRealisedBefore, MC).abs();
            swapNetRef[SWAP_NET_REF_KEPT_INDEX] = swapNetRef[SWAP_NET_REF_KEPT_INDEX].add(keptNetRealised, MC);
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal computeMarketCost(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0) {
            return quantity.multiply(flow.getUnitPriceUsd(), MC);
        }
        if (replayMarketAuthority != null && transaction != null) {
            return replayMarketAuthority.resolve(transaction, flow)
                    .filter(resolved -> resolved.unitPriceUsd() != null && resolved.unitPriceUsd().signum() > 0)
                    .map(resolved -> quantity.multiply(resolved.unitPriceUsd(), MC))
                    .orElse(null);
        }
        return null;
    }

    /**
     * FB-01 / ADR-082: {@code true} when the disposed lot carried a genuine reward/yield discount,
     * i.e. its released NET basis is materially below its released Market basis. Rounding / AVCO dust
     * (a sub-threshold relative gap) is not a discount, so the acquired lot must re-base its NET lane
     * to the market acquisition cost rather than recycle the released net basis.
     */
    private static boolean hasRewardDiscount(BigDecimal netReleased, BigDecimal marketReleased) {
        if (marketReleased == null || marketReleased.signum() <= 0) {
            return false;
        }
        BigDecimal net = zeroIfNull(netReleased);
        BigDecimal discount = marketReleased.subtract(net, MC);
        if (discount.signum() <= 0) {
            return false;
        }
        BigDecimal threshold = marketReleased.multiply(NET_DISCOUNT_RELATIVE_THRESHOLD, MC);
        return discount.compareTo(threshold) > 0;
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
     * ADR-054: C1↔C2 / C2↔C2 identity changes on staking/vault txs dispose+acquire at market when priced.
     */
    private static boolean shouldApplyCrossCanonicalMarketLeg(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return false;
        }
        if (!AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction)) {
            return false;
        }
        return switch (transaction.getType()) {
            case STAKING_DEPOSIT, STAKING_WITHDRAW, VAULT_DEPOSIT, VAULT_WITHDRAW -> true;
            default -> false;
        };
    }

    /**
     * D1 / ADR-054 §9: {@code true} when {@code flow} is the inbound (acquisition) principal leg of a
     * cross-canonical staking/vault identity change. Used as the fail-closed guard so an unpriced C2
     * acquisition leg is never booked at $0.
     */
    private static boolean isCrossCanonicalStakingPrincipalAcquisition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0) {
            return false;
        }
        return shouldApplyCrossCanonicalMarketLeg(transaction, flow);
    }

    /**
     * ADR-054 addendum — cross-canonical redemption proceeds conservation.
     *
     * <p>An atomic cross-canonical redemption ({@code VAULT_WITHDRAW} / {@code STAKING_WITHDRAW}
     * that burns a receipt/vault token — C2, frequently illiquid — and returns the underlying —
     * C1, reliably priced) has no external cash leg, so value-out MUST equal value-in. The generic
     * cross-canonical path (ADR-054) otherwise DISPOSEs the outbound receipt at its own stale
     * FLOW/cache price (an illiquid {@code yv*}/receipt token has no live source), understating the
     * realized loss, while the inbound underlying ACQUIREs at its correct live FMV.
     *
     * <p>Mirroring {@code LinkedBridgeTransferReplaySupport.applyAssetConvertingSettlementOutbound}
     * (proceeds = destination FMV) and the {@code SwapDerivedPriceResolver} counterpart-derivation
     * semantics, this stamps the outbound DISPOSE leg's {@code unitPriceUsd} so its proceeds equal
     * the resolved total FMV of the inbound leg(s):
     * {@code unitPriceUsd = Σ(inbound resolved FMV) / |outbound qty|}. Unlike the swap-derived
     * resolver, it OVERRIDES the already-present (stale) outbound price.
     *
     * <p>Determinism / generality: gated purely on the structural condition — a WITHDRAW-direction
     * cross-canonical redemption ({@link AccountingAssetClassificationSupport#hasCrossCanonicalIdentityPrincipalPair})
     * with exactly one outbound principal DISPOSE leg paired with inbound principal ACQUIRE leg(s).
     * No tx-hash / symbol / wallet keying; generalizes to any {@code yv*}/receipt → underlying
     * atomic redemption on any network.
     *
     * <p>Direction &amp; fail-safe: only the illiquid outbound is derived FROM the reliably-priced
     * inbound. When the inbound leg has no resolved FMV, behaviour is unchanged.
     */
    private void stampCrossCanonicalRedemptionProceedsFromInbound(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return;
        }
        boolean isRedemption = transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.STAKING_WITHDRAW;
        if (!isRedemption
                || !AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction)) {
            return;
        }
        NormalizedTransaction.Flow outboundLeg = null;
        int outboundPrincipalCount = 0;
        BigDecimal inboundResolvedFmvUsd = BigDecimal.ZERO;
        boolean inboundFmvResolved = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!isPrincipalReplayFlow(flow)) {
                continue;
            }
            int sign = flow.getQuantityDelta().signum();
            if (sign < 0) {
                outboundLeg = flow;
                outboundPrincipalCount++;
            } else if (sign > 0) {
                BigDecimal legFmvUsd = computeMarketCost(transaction, flow);
                if (legFmvUsd != null && legFmvUsd.signum() > 0) {
                    inboundResolvedFmvUsd = inboundResolvedFmvUsd.add(legFmvUsd, MC);
                    inboundFmvResolved = true;
                }
            }
        }
        // Structural gate: exactly one outbound DISPOSE leg + at least one resolved inbound FMV.
        // Fail-safe (constraint 2): unresolved inbound FMV → leave outbound proceeds unchanged.
        if (outboundPrincipalCount != 1 || outboundLeg == null || !inboundFmvResolved) {
            return;
        }
        BigDecimal outboundQuantity = outboundLeg.getQuantityDelta().abs();
        if (outboundQuantity.signum() <= 0) {
            return;
        }
        BigDecimal derivedUnitPriceUsd = inboundResolvedFmvUsd.divide(outboundQuantity, MC);
        outboundLeg.setUnitPriceUsd(derivedUnitPriceUsd);
        outboundLeg.setPriceSource(PriceSource.SWAP_DERIVED);
        log.debug(
                "REPLAY_CROSS_CANONICAL_REDEMPTION_PROCEEDS txId={} type={} outboundAsset={} "
                        + "inboundFmvUsd={} outboundQty={} derivedUnitPriceUsd={}",
                transaction.getId(),
                transaction.getType(),
                outboundLeg.getAssetSymbol(),
                inboundResolvedFmvUsd,
                outboundQuantity,
                derivedUnitPriceUsd
        );
    }

    /**
     * Principal (non-fee, economically significant) flow test mirroring
     * {@link AccountingAssetClassificationSupport}'s internal principal-flow definition so the
     * redemption-proceeds gate scans the same legs as {@code hasCrossCanonicalIdentityPrincipalPair}.
     */
    private static boolean isPrincipalReplayFlow(NormalizedTransaction.Flow flow) {
        if (flow == null
                || flow.getRole() == null
                || flow.getRole() == NormalizedLegRole.FEE
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

    /**
     * Stamps an unpriced cross-canonical leg with replay-time market authority so BUY/SELL handlers
     * can realise P&L. Bybit {@code STAKING_DEPOSIT} rows often arrive without flow prices.
     */
    private boolean stampCrossCanonicalMarketPrice(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (assetSupport.hasKnownPrice(flow)) {
            return true;
        }
        if (replayMarketAuthority == null) {
            return false;
        }
        Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolved =
                replayMarketAuthority.resolve(transaction, flow);
        if (resolved.isEmpty()
                || resolved.get().unitPriceUsd() == null
                || resolved.get().unitPriceUsd().signum() <= 0) {
            return false;
        }
        flow.setUnitPriceUsd(resolved.get().unitPriceUsd());
        if (resolved.get().priceSource() != null) {
            flow.setPriceSource(resolved.get().priceSource());
        } else {
            flow.setPriceSource(PriceSource.UNKNOWN);
        }
        return flow.getPriceSource() != PriceSource.UNKNOWN;
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

    /**
     * NEW-02: relabels a fully-priced, unpaired external-capital {@code INFLOW} deposit from
     * {@code UNKNOWN} to {@code ACQUIRE}.
     *
     * <p>Dzengi fiat {@code EXTERNAL_TRANSFER_IN} legs have no pending/continuity match, so
     * {@link TransferReplayHandler#applyTransfer} returns {@code UNKNOWN}. The dispatcher then runs
     * {@link ReplayFlowSupport#applyInboundShortfallSpotFallback} which books the correct market/spot
     * basis (so AVCO is already right). Semantically such an inflow booked at market value is an
     * acquisition, so the emitted label should be {@code ACQUIRE}. This is a label-only resolution:
     * it never mutates quantity, cost basis, net lane, or AVCO.</p>
     *
     * <p>Returns {@code ACQUIRE} only when the effect is still {@code UNKNOWN} (never downgrades a
     * resolved carry/dispose), the transaction is an {@code EXTERNAL_TRANSFER_IN} with
     * {@code ExternalCapitalBoundary.INFLOW}, the flow is inbound, and the position is fully
     * basis-backed after the fallback ({@code uncoveredQuantity} is null/zero). If the fallback
     * could not price the capital (uncovered remains), the label stays {@code UNKNOWN}.</p>
     */
    private AssetLedgerPoint.BasisEffect resolveExternalCapitalInflowAcquisition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            AssetLedgerPoint.BasisEffect currentEffect
    ) {
        if (currentEffect != AssetLedgerPoint.BasisEffect.UNKNOWN
                || transaction == null
                || transaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getExternalCapitalBoundary()
                        != com.walletradar.domain.transaction.normalized.ExternalCapitalBoundary.INFLOW
                || flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0
                || position == null) {
            return currentEffect;
        }
        BigDecimal uncovered = position.uncoveredQuantity();
        if (uncovered != null && uncovered.signum() != 0) {
            return currentEffect;
        }
        return AssetLedgerPoint.BasisEffect.ACQUIRE;
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
