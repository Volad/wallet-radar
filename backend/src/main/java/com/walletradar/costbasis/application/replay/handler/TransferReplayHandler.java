package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.costbasis.application.replay.model.BridgeSettlementPendingKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.PositionStore;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.AccountRefPositionResolver;
import com.walletradar.costbasis.application.replay.support.BybitCarrySourceResolver;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.bybit.BybitEarnPrincipalTransferPairer;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
public class TransferReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * P0-A: Earn-principal carry is considered "dust" when the total cost basis is below this
     * threshold. Dust indicates that the EARN position was populated with an incorrect synthetic
     * AVCO from mis-priced normalization (e.g., CMETH at $4 instead of ~$2280). Market authority
     * is used to replace the dust carry with the authoritative lot basis.
     */
    private static final java.math.BigDecimal EARN_PRINCIPAL_DUST_BASIS_THRESHOLD = new java.math.BigDecimal("100");
    private static final BigDecimal EARN_CORRIDOR_INFLATED_BASIS_MULTIPLIER = new BigDecimal("10");

    /**
     * B-EARN-BUNDLE: Minimum carry quantity for a partial-leg detection in
     * {@link #applyBybitMultiLegBundleTransfer}. Carries below this threshold are
     * treated as dust and fall through to the full-consume path.
     */
    private static final BigDecimal EARN_BUNDLE_PARTIAL_LEG_THRESHOLD = new BigDecimal("0.001");
    private static final BigDecimal EARN_PRINCIPAL_PARTIAL_MATCH_THRESHOLD = new BigDecimal("0.00000001");

    /**
     * R-1*: Coverage ratio used by {@link #resolveCarrySourcePosition} to decide whether a
     * carry-source position can supply an outbound transfer. The stripped UID umbrella often
     * retains only a dust residue (e.g. 0.00007969 cmETH) after prior collapsed legs, while the
     * real inventory sits on the {@code :FUND} sub-account. A qty-only "non-empty" test treats
     * that dust as inventory and drains it (carrying ~$0), so the inbound leg restores almost
     * entirely uncovered and FAMILY:ETH AVCO collapses. A position is considered to cover the
     * transfer only when it holds at least this fraction of the moved quantity, which tolerates
     * sub-unit fee rounding while rejecting dust residues.
     */
    private static final BigDecimal CARRY_SOURCE_COVERAGE_RATIO = new BigDecimal("0.999");

    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory keyFactory;
    private final ReplayTransferClassifier classifier;
    private final ReplayPendingTransferMatcher matcher;
    private final ReplayMarketAuthority replayMarketAuthority;

    public TransferReplayHandler(
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory keyFactory,
            ReplayTransferClassifier classifier,
            ReplayPendingTransferMatcher matcher,
            ReplayMarketAuthority replayMarketAuthority
    ) {
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.keyFactory = keyFactory;
        this.classifier = classifier;
        this.matcher = matcher;
        this.replayMarketAuthority = replayMarketAuthority;
    }

    public AssetLedgerPoint.BasisEffect applyTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        if (classifier.isLinkedBridgeContinuityTransfer(transaction, flow)) {
            return applyLinkedBridgeTransfer(transaction, flow, flowIndex, position, replayState);
        }
        if (classifier.isLinkedBridgeSettlementTransfer(transaction, flow)) {
            return applyLinkedBridgeSettlementTransfer(transaction, flow, flowIndex, position, replayState);
        }
        if (classifier.isFamilyEquivalentCustodyTransfer(transaction, flow)) {
            ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
            if (flow.getQuantityDelta().signum() < 0) {
                // R-1: Bybit collapsed FUND-side staking / EARN custody deposits (e.g. ETH→METH
                // STAKING_DEPOSIT, FUND↔EARN reallocations) resolve their position key to the UID
                // umbrella, but the ETH-family principal inventory frequently sits on the :FUND
                // sub-account (credited by UTA→FUND collapsed inbound and EARN→FUND redemption).
                // Draining the empty umbrella mints the staking receipt at $0; instead drain the
                // sub-account that actually holds the inventory so the staked-principal AVCO is
                // carried into the receipt (conserved: basis leaves the source, the receipt-in
                // leg restores it). Mirrors the generic-transfer carry-source fallback used for
                // the corridor/venue-internal path below.
                PositionState carrySource = resolveCarrySourcePosition(
                        transaction, flow, position, replayState, false, flow.getQuantityDelta().abs());
                boolean redirectedToInventory = carrySource != position;
                continuityCarryService.moveToBucket(
                        continuityCarryService.removeTransferCarry(
                                transaction,
                                flow,
                                flowIndex,
                                carrySource,
                                replayState.passThroughCorridorPlan(),
                                replayState.reservedPassThroughCarries(),
                                redirectedToInventory
                        ),
                        bucket
                );
                flowSupport.purgeOrphanBasisWhenEmpty(carrySource);
                if (redirectedToInventory) {
                    flowSupport.purgeOrphanBasisWhenEmpty(position);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            // PROTOCOL_CUSTODY_WITHDRAW with no matching deposit (empty bucket): the deposit
            // predates the backfill window or the custody contract does not issue on-chain
            // receipt tokens (e.g., Paradex L1 Core). Carrying $0 basis via REALLOCATE_IN
            // permanently destroys cost basis. Treat the principal return as a fresh ACQUIRE
            // so stablecoin $1/unit logic (or market price) fills the gap.
            if (transaction.getType() == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                    && bucket.quantity().signum() == 0) {
                flowSupport.applyBuy(flow, position);
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            if (transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT) {
                // RC-2: distribute bucket carry by USD value (not raw token quantity).
                // An unpriced leg with billions of raw units must not absorb all outgoing
                // basis. Compute USD weight for each inbound leg; give proportion to priced
                // legs only. If all legs are unpriced, leave basis in the source bucket (WARN).
                restoreFromBucketLendingDepositUsdWeighted(transaction, flow, position, bucket);
            } else {
                restoreFromContinuityBucket(flow, position, bucket);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBucketOutbound(transaction, flow)) {
            continuityCarryService.moveToBucket(
                    continuityCarryService.removeTransferCarry(
                            transaction,
                            flow,
                            flowIndex,
                            position,
                            replayState.passThroughCorridorPlan(),
                            replayState.reservedPassThroughCarries(),
                            preserveBucketOutboundCoverage(transaction)
                    ),
                    replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
            );
            flowSupport.purgeOrphanBasisWhenEmpty(position);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBucketInbound(transaction, flow)) {
            ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
            if (keyFactory.usesWrapperCompositeBucket(transaction)) {
                restoreFullBucket(transaction, flow, position, bucket);
            } else if (transaction.getType() == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                    && bucket.quantity().signum() == 0) {
                // PROTOCOL_CUSTODY_WITHDRAW with no matching deposit (empty bucket): the deposit
                // predates the backfill window or was never issued as an on-chain receipt token
                // (e.g., Paradex L1 Core). Carrying $0 basis via REALLOCATE_IN permanently
                // destroys cost basis — treat the principal return as a fresh ACQUIRE so
                // stablecoin $1/unit logic (or market price) fills the gap.
                flowSupport.applyBuy(flow, position);
            } else if (transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT) {
                // RC-2: distribute bucket carry by USD value (not raw token quantity).
                // An unpriced leg with billions of raw units must not absorb all outgoing eToken
                // basis. Compute USD weight for each inbound leg; give proportion to priced legs
                // only. If all legs are unpriced, leave basis in the source bucket (WARN).
                restoreFromBucketLendingDepositUsdWeighted(transaction, flow, position, bucket);
            } else {
                restoreFromContinuityBucket(flow, position, bucket);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }
        if (classifier.isBybitMultiLegBundleTransfer(transaction)) {
            return applyBybitMultiLegBundleTransfer(transaction, flow, flowIndex, position, replayState);
        }

        TransferPendingKey transferKey = keyFactory.transferKey(transaction, flow);
        if (transferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        boolean corridorTransfer = classifier.isCorridorTransfer(transaction);

        if (flow.getQuantityDelta().signum() < 0) {
            boolean venueInternal = classifier.usesBybitVenueInternalCarryQueue(transaction);
            BigDecimal outboundQty = flow.getQuantityDelta().abs();
            // P0-C: Resolve the primary carry source (encodes the :EARN earn-principal redirect,
            // corridor sub-pattern A, and the R-1 venue-internal :FUND drain contracts). The drain
            // plan then layers an umbrella-remainder slice on top when the suffix slice under-covers
            // the outbound and a distinct umbrella sibling holds the inventory, so the umbrella lot
            // is both drained AND recorded (the ETH/USDT umbrella phantom fix). Single-slice plans
            // are behaviour-preserving.
            PositionState carrySource = resolveCarrySourcePosition(
                    transaction, flow, position, replayState, corridorTransfer, outboundQty);
            BybitCarrySourceResolver.BybitDrainPlan plan = BybitCarrySourceResolver.plan(
                    position, carrySource, replayState.positions(), outboundQty);
            CarryTransfer carry = null;
            for (BybitCarrySourceResolver.DrainSlice slice : plan.slices()) {
                CarryTransfer sliceCarry = drainCarrySlice(
                        transaction,
                        flow,
                        flowIndex,
                        slice.position(),
                        slice.quantity(),
                        position,
                        corridorTransfer,
                        venueInternal,
                        replayState
                );
                carry = carry == null
                        ? sliceCarry
                        : continuityCarryService.mergeCarryTransfers(position.assetKey(), carry, sliceCarry);
            }
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = corridorTransfer
                    ? matcher.findUniqueBridgeQueueIndex(queue, true)
                    : multiSourceEarnPrincipalBundle(transaction)
                    ? matcher.findUniqueBridgeQueueIndex(queue, true)
                    : matcher.findUniqueCompatibleQueueIndex(queue, true, carry.quantity());
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                if (multiSourceEarnPrincipalBundle(transaction)
                        && pendingInbound.quantity() != null
                        && carry.quantity() != null
                        && pendingInbound.quantity().subtract(carry.quantity(), MC)
                        .compareTo(EARN_PRINCIPAL_PARTIAL_MATCH_THRESHOLD) > 0) {
                    BigDecimal fullProvisional = pendingInbound.provisionalBasisUsd() != null
                            ? pendingInbound.provisionalBasisUsd() : BigDecimal.ZERO;
                    BigDecimal scaledProvisional = pendingInbound.quantity().signum() > 0
                            ? fullProvisional.multiply(
                            carry.quantity().divide(pendingInbound.quantity(), MC), MC)
                            : BigDecimal.ZERO;
                    BigDecimal remainingProvisional = fullProvisional.subtract(scaledProvisional, MC);
                    CarryTransfer slicedForAttach = new CarryTransfer(
                            carry.quantity(),
                            BigDecimal.ZERO,
                            carry.quantity(),
                            BigDecimal.ZERO,
                            null,
                            BigDecimal.ZERO,
                            null,
                            true,
                            pendingInbound.assetKey(),
                            scaledProvisional,
                            pendingInbound.sourceFlowRef(),
                            pendingInbound.materialized()
                    );
                    attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            slicedForAttach,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                    BigDecimal remainingQty = pendingInbound.quantity().subtract(carry.quantity(), MC);
                    queue.addFirst(pendingInbound.withReducedQuantityAndProvisional(remainingQty, remainingProvisional));
                } else {
                    attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            pendingInbound,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                }
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(transferKey);
        int carryIndex = corridorTransfer
                ? matcher.findUniqueBridgeQueueIndex(queue, false)
                : multiSourceEarnPrincipalBundle(transaction)
                ? matcher.findUniqueBridgeQueueIndex(queue, false)
                : matcher.findUniqueCompatibleQueueIndex(queue, false, flow.getQuantityDelta().abs());
        if (carryIndex >= 0) {
            if (multiSourceEarnPrincipalBundle(transaction) && !corridorTransfer) {
                BigDecimal remaining = flow.getQuantityDelta().abs();
                while (remaining.signum() > 0) {
                    int nextCarryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
                    if (nextCarryIndex < 0) {
                        break;
                    }
                    CarryTransfer carry = matcher.removeQueueElement(queue, nextCarryIndex);
                    BigDecimal inboundQuantity = remaining.min(carry.quantity());
                    CarryTransfer effectiveCarry = continuityCarryService.internalAccountInboundCarry(
                            carry,
                            inboundQuantity,
                            position.assetKey()
                    );
                    flowSupport.restoreToPosition(
                            inboundQuantity,
                            position,
                            flowSupport.pegCappedStablecoinCarryBasis(
                                    position.assetKey(),
                                    inboundCoveredQuantity(inboundQuantity, effectiveCarry),
                                    effectiveCarry.costBasisUsd()),
                            flowSupport.pegCappedStablecoinCarryBasis(
                                    position.assetKey(),
                                    inboundCoveredQuantity(inboundQuantity, effectiveCarry),
                                    effectiveCarry.netCostBasisUsd()),
                            effectiveCarry.uncoveredQuantity(),
                            effectiveCarry.avco()
                    );
                    continuityCarryService.reservePassThroughCarryIfPlanned(
                            transaction,
                            flowIndex,
                            effectiveCarry,
                            replayState.passThroughCorridorPlan(),
                            replayState.reservedPassThroughCarries()
                    );
                    remaining = remaining.subtract(inboundQuantity, MC);
                }
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                if (remaining.signum() <= 0) {
                    return flowSupport.continuityBasisEffect(transaction, flow);
                }
                NormalizedTransaction.Flow remainderFlow = flowWithQuantity(flow, remaining);
                enqueuePendingInbound(transaction, remainderFlow, flowIndex, position, replayState, transferKey);
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            // RC-A (ADR-043): a matched earn-principal leg's queued paired carry is the SOLE
            // authoritative basis source. The AVCO-re-derivation fallbacks
            // (normalizeBybitEarnProductCarry / backfillEarnPrincipalInboundCarry) are demoted to
            // fire ONLY when NO paired carry proves the basis (open/unredeemed position or a genuine
            // unpaired boundary — those never reach this matched branch). Never inject $0-cost or
            // market-derived quantity on a redeem when the paired subscribe carry is present.
            boolean pairedCarryAuthoritative = isBybitEarnPrincipalPaired(transaction);
            if (!pairedCarryAuthoritative) {
                carry = normalizeBybitEarnProductCarry(
                        transaction,
                        flow,
                        carry,
                        replayState,
                        position,
                        position.assetKey(),
                        derivePositionAvco(position)
                );
            }
            BigDecimal inboundQuantity = flow.getQuantityDelta().abs();
            boolean venueStyleInbound = classifier.usesBybitVenueInternalCarryQueue(transaction)
                    || pairedCarryAuthoritative;
            CarryTransfer effectiveCarry = venueStyleInbound
                    ? continuityCarryService.internalAccountInboundCarry(carry, inboundQuantity, position.assetKey())
                    : continuityCarryService.sliceCarryTransfer(carry, inboundQuantity, position.assetKey());
            if (!pairedCarryAuthoritative) {
                effectiveCarry = backfillEarnPrincipalInboundCarry(
                        transaction,
                        flow,
                        inboundQuantity,
                        position,
                        effectiveCarry,
                        replayState
                );
            }
            // U-3: a matched same-asset continuity inbound (CARRY_IN / REALLOCATE_IN / cross-denomination
            // vault-share→underlying restore such as a VAULT_WITHDRAW / LENDING_WITHDRAW) must not let a
            // USD stablecoin inherit per-unit basis above the $1 peg. ERC4626 / lending share-rate
            // contamination (e.g. 500 vault shares carrying $1,000 onto 500 USDC → $2/unit) is clamped
            // to peg so the withdrawn stablecoin disposes at ≈$0 realised.
            BigDecimal cappedInboundBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    position.assetKey(),
                    inboundCoveredQuantity(flow.getQuantityDelta().abs(), effectiveCarry),
                    effectiveCarry.costBasisUsd());
            // ADR-040 Change 2: apply same stablecoin cap to net lane
            BigDecimal cappedNetBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    position.assetKey(),
                    inboundCoveredQuantity(flow.getQuantityDelta().abs(), effectiveCarry),
                    effectiveCarry.netCostBasisUsd());
            flowSupport.restoreToPosition(
                    flow.getQuantityDelta().abs(),
                    position,
                    cappedInboundBasis,
                    cappedNetBasis,
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(transferKey);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        // RC-9 D2: spot fallback is legal ONLY for the withdrawal direction (CEX → wallet), where
        // the released basis sits on the untracked CEX spot ledger. A deposit-direction credit
        // (wallet → CEX) MUST inherit the on-chain CARRY_OUT; a missing carry there is a
        // determinism/linking defect, so it routes to the pending queue where the end-of-replay
        // CorridorBasisConservationGuard can detect the orphaned carry-out instead of fabricating
        // a spot basis that masks the orphan.
        if (classifier.isCexWithdrawalCorridorInbound(transaction, flow)) {
            BigDecimal qty = flow.getQuantityDelta();
            BigDecimal acquisitionCost = replayMarketAuthority.resolve(transaction, flow)
                    .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                    .map(price -> qty.multiply(price, MC))
                    .orElse(null);
            if (acquisitionCost != null && acquisitionCost.signum() > 0) {
                flowSupport.applyBuyWithAcquisitionCost(flow, position, acquisitionCost);
            } else {
                flowSupport.applyUnknownTransfer(flow, position);
            }
            return AssetLedgerPoint.BasisEffect.ACQUIRE;
        }

        enqueuePendingInbound(transaction, flow, flowIndex, position, replayState, transferKey);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    /**
     * Drains {@code sliceQty} from a single planned position and returns the carry it released,
     * applying ADR-019 Rule 1 proportional/explicit basis against THAT position. Reproduces the
     * legacy single-source carry-building (venue/earn-principal coverage preservation, earn-principal
     * lot-basis override, corridor proportional slice) but parameterised by the drained position and
     * its slice quantity, so a multi-slice waterfall computes each slice's basis from its own pool.
     *
     * <p><b>Drain-emission completeness.</b> When the drained position is NOT the dispatcher's flow
     * position (an umbrella remainder slice, or a redirect such as corridor sub-pattern A), this
     * method records the {@code CARRY_OUT}/{@code REALLOCATE_OUT} ledger point at the drain site via
     * {@link ReplayExecutionState#ledgerPointCollector()} — generalising the blessed
     * {@code attachLateCarryToPendingInbound} pattern of recording against a non-flow position.
     * Otherwise it emits nothing: the dispatcher's outer {@code record(flowPosition,...)} captures
     * the flow-position slice (and is suppressed by {@code before.sameAs(after)} when the flow
     * position is untouched). Σ|quantityDelta| over the emitted CARRY_OUT points therefore equals the
     * outbound quantity (modulo a genuine shortfall on the last slice).
     */
    private CarryTransfer drainCarrySlice(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState drainedPosition,
            BigDecimal sliceQty,
            PositionState flowPosition,
            boolean corridorTransfer,
            boolean venueInternal,
            ReplayExecutionState replayState
    ) {
        NormalizedTransaction.Flow sliceFlow = sliceQty != null
                && sliceQty.compareTo(flow.getQuantityDelta().abs()) == 0
                ? flow
                : flowSupport.copyFlowWithQuantity(flow, sliceQty);
        BigDecimal movedQty = sliceFlow.getQuantityDelta().abs();
        PositionSnapshot before = flowSupport.snapshot(drainedPosition);

        BigDecimal corridorOutboundSliceAvco = corridorTransfer ? derivePositionAvco(drainedPosition) : null;
        // B-3: capture pre-drain totals for proportional carry basis (ADR-019 Rule 1)
        BigDecimal preDrainTotalBasis = drainedPosition.totalCostBasisUsd();
        BigDecimal preDrainNetTotalBasis = drainedPosition.netTotalCostBasisUsd();
        BigDecimal preDrainTotalQty = drainedPosition.quantity();
        BigDecimal preDrainAvco = derivePositionAvco(drainedPosition);
        CarryTransfer carry = continuityCarryService.removeTransferCarry(
                transaction,
                sliceFlow,
                flowIndex,
                drainedPosition,
                replayState.passThroughCorridorPlan(),
                replayState.reservedPassThroughCarries(),
                venueInternal || isBybitEarnPrincipalPaired(transaction)
        );
        carry = normalizeBybitEarnProductCarry(
                transaction,
                sliceFlow,
                carry,
                replayState,
                drainedPosition,
                drainedPosition.assetKey(),
                preDrainAvco
        );
        // P0-A: For earn-principal outbound, override dust carry with authoritative lot basis.
        carry = applyEarnPrincipalLotCarryOverride(transaction, sliceFlow, carry, drainedPosition, replayState);
        // P0-C: For corridor outbound, compute carry basis as a proportional slice of total position
        // cost (ADR-019 Rule 1 amended). Do NOT use perWalletAvco × movedQty — that divides by
        // covered-qty only and inflates basis when uncoveredQuantity > 0.
        if (corridorOutboundSliceAvco != null && corridorOutboundSliceAvco.signum() > 0) {
            BigDecimal corridorCarryBasis;
            BigDecimal corridorNetCarryBasis;
            if (preDrainTotalQty != null && preDrainTotalQty.signum() > 0
                    && preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
                corridorCarryBasis = preDrainTotalBasis
                        .multiply(movedQty, MC)
                        .divide(preDrainTotalQty, MC);
                // ADR-040 Change 2: proportional net slice mirrors tax computation
                BigDecimal effectiveNetBasis = preDrainNetTotalBasis != null
                        ? preDrainNetTotalBasis : preDrainTotalBasis;
                corridorNetCarryBasis = effectiveNetBasis
                        .multiply(movedQty, MC)
                        .divide(preDrainTotalQty, MC);
            } else {
                corridorCarryBasis = movedQty.multiply(corridorOutboundSliceAvco, MC);
                corridorNetCarryBasis = corridorCarryBasis;
            }
            // Cap: guard against movedQty > preDrainTotalQty edge case (shortfall scenario)
            if (preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
                corridorCarryBasis = corridorCarryBasis.min(preDrainTotalBasis);
                corridorNetCarryBasis = corridorNetCarryBasis.min(corridorCarryBasis);
            }
            carry = continuityCarryService.buildExplicitCarryTransfer(
                    movedQty, corridorCarryBasis, corridorNetCarryBasis, drainedPosition.assetKey()
            );
        }
        if (drainedPosition != flowPosition
                && !drainedPosition.assetKey().equals(flowPosition.assetKey())) {
            replayState.ledgerPointCollector().record(
                    transaction,
                    sliceFlow,
                    flowIndex,
                    drainedPosition.assetKey(),
                    before,
                    drainedPosition,
                    flowSupport.continuityBasisEffect(transaction, sliceFlow)
            );
        }
        return carry;
    }

    /**
     * U-3: the covered (basis-backed) destination quantity of a continuity inbound = inbound quantity
     * minus the uncovered portion carried in. Used to size the USD-stablecoin peg cap so only the
     * basis-backed units are clamped to peg.
     */
    private static BigDecimal inboundCoveredQuantity(BigDecimal inboundQuantity, CarryTransfer carry) {
        BigDecimal uncovered = carry == null || carry.uncoveredQuantity() == null
                ? BigDecimal.ZERO
                : carry.uncoveredQuantity();
        BigDecimal covered = inboundQuantity.subtract(uncovered, MC);
        return covered.signum() < 0 ? BigDecimal.ZERO : covered;
    }

    /**
     * Cycle/18 R9: after inbound shortfall spot fallback promotes provisional basis on a queued
     * pending inbound, record the exact USD added so late carry can replace it.
     */
    public void accumulateInboundSpotFallbackProvisional(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            BigDecimal spotFallbackBasisUsd,
            ReplayExecutionState replayState
    ) {
        if (transaction == null
                || flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0
                || spotFallbackBasisUsd == null
                || spotFallbackBasisUsd.signum() <= 0) {
            return;
        }
        NormalizedTransaction.Flow transferFlow = flowSupport.asTransferFlow(flow);
        BridgePendingKey bridgeKey = keyFactory.bridgeTransferKey(transaction, transferFlow);
        if (bridgeKey != null) {
            replayState.pendingTransfers().addProvisionalBasisToPendingInbounds(bridgeKey, spotFallbackBasisUsd);
        }
        BridgeSettlementPendingKey settlementKey = keyFactory.bridgeSettlementKey(transaction, transferFlow);
        if (settlementKey != null) {
            replayState.pendingTransfers().addProvisionalBasisToPendingInbounds(settlementKey, spotFallbackBasisUsd);
        }
        TransferPendingKey transferKey = keyFactory.transferKey(transaction, transferFlow);
        if (transferKey != null) {
            replayState.pendingTransfers().addProvisionalBasisToPendingInbounds(transferKey, spotFallbackBasisUsd);
        }
    }

    private void enqueuePendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState,
            com.walletradar.costbasis.application.replay.model.PendingTransferKey key
    ) {
        FlowRef sourceFlowRef = flowSupport.flowRef(transaction, flowIndex);
        // FIX B (ADR-043 amendment): materialize-then-refine. For a paired earn-principal inbound we
        // still materialize covered quantity + basis at market first (permitUncovered=false), then
        // let the later authoritative paired CARRY_OUT REFINE the basis in attachLateCarryToPendingInbound
        // (no double-credit of quantity). Only when materialization genuinely cannot price the leg
        // (unpriced boundary → Optional.empty) do we defer a zero-quantity pending inbound whose
        // quantity is restored by the matched-carry branch when the FUND/UTA out arrives first.
        // This conserves quantity for the OPEN-subscribe path while keeping the RC-A basis demotion
        // authoritative, and unblocks the QUANTITY-CONSERVATION regression that the #11 unconditional
        // zero-defer introduced.
        boolean permitUncovered = !classifier.usesBybitVenueInternalCarryQueue(transaction)
                || isBybitEarnPrincipalPaired(transaction);
        Optional<BigDecimal> provisionalBasis = flowSupport.materializePendingInbound(
                transaction,
                flow,
                position,
                permitUncovered
        );
        if (provisionalBasis.isEmpty()) {
            if (isBybitEarnPrincipalPaired(transaction)) {
                // Issue 2 (ADR-043): the leg could not be priced at enqueue time, so its quantity was
                // NOT added to the position. Queue an UNMATERIALIZED deferred pending inbound so the
                // later authoritative paired CARRY_OUT materializes the quantity (LTC :EARN 0.75 @
                // $41.54) instead of the refine-only path dropping it.
                replayState.pendingTransfers().queue(key)
                        .addLast(CarryTransfer.pendingInboundUnmaterialized(
                                flow.getQuantityDelta().abs(),
                                position.assetKey(),
                                sourceFlowRef
                        ));
            }
            return;
        }
        replayState.pendingTransfers().queue(key)
                .addLast(CarryTransfer.pendingInbound(
                        flow.getQuantityDelta().abs(),
                        position.assetKey(),
                        provisionalBasis.orElse(BigDecimal.ZERO),
                        sourceFlowRef
                ));
    }

    private AssetLedgerPoint.BasisEffect applyLinkedBridgeTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        BridgePendingKey bridgeTransferKey = keyFactory.bridgeTransferKey(transaction, flow);
        if (bridgeTransferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(bridgeTransferKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                attachLateBridgeCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        replayState.positions(),
                        pendingInbound,
                        carry,
                        replayState.ledgerPointCollector(),
                        replayState.passThroughCorridorPlan(),
                        replayState.reservedPassThroughCarries()
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(bridgeTransferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(bridgeTransferKey);
        int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
        if (carryIndex >= 0) {
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(carry, flow.getQuantityDelta().abs(), position.assetKey());
            flowSupport.restoreToPosition(effectiveCarry, position);
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(bridgeTransferKey);
            }
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        enqueuePendingInbound(transaction, flow, flowIndex, position, replayState, bridgeTransferKey);
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private AssetLedgerPoint.BasisEffect applyLinkedBridgeSettlementTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        BridgeSettlementPendingKey settlementKey = keyFactory.bridgeSettlementKey(transaction, flow);
        if (settlementKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(settlementKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                attachLateBridgeSettlementCarryToPendingInbound(
                        transaction,
                        flow,
                        flowIndex,
                        replayState.positions(),
                        pendingInbound,
                        carry,
                        replayState.ledgerPointCollector()
                );
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(settlementKey);
                }
                return flowSupport.routeSettlementBasisEffect(flow);
            }
            queue.addLast(carry);
            return flowSupport.routeSettlementBasisEffect(flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(settlementKey);
        int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
        if (carryIndex >= 0) {
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            CarryTransfer effectiveCarry = continuityCarryService.bridgeSettlementInboundCarry(
                    carry,
                    flow.getQuantityDelta().abs(),
                    position.assetKey()
            );
            flowSupport.restoreToPosition(effectiveCarry, position);
            if (queue.isEmpty()) {
                replayState.pendingTransfers().remove(settlementKey);
            }
            return flowSupport.routeSettlementBasisEffect(flow);
        }

        enqueuePendingInbound(transaction, flow, flowIndex, position, replayState, settlementKey);
        return flowSupport.routeSettlementBasisEffect(flow);
    }

    /**
     * Returns the USD value of a single flow: {@code valueUsd} if set, otherwise
     * {@code |quantityDelta| × unitPriceUsd}. Returns {@code null} when the flow is unpriced.
     */
    private static BigDecimal computeFlowUsdValue(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getQuantityDelta() == null) {
            return null;
        }
        if (flow.getValueUsd() != null && flow.getValueUsd().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
        }
        return null;
    }

    /**
     * Asset identity key for same-asset matching: contract address if available, else symbol.
     */
    private static String flowAssetMatchKey(NormalizedTransaction.Flow f) {
        if (f == null) {
            return null;
        }
        String contract = f.getAssetContract();
        return (contract != null && !contract.isBlank()) ? contract : f.getAssetSymbol();
    }

    /**
     * Sums the USD values of all inbound (positive-qty, non-FEE) flows in the transaction that
     * share the same asset as {@code targetFlow}. Returns {@code null} when no priced inbound
     * same-asset flow exists.
     */
    private static BigDecimal computeTotalSameAssetInboundUsd(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow targetFlow
    ) {
        if (transaction.getFlows() == null) {
            return computeFlowUsdValue(targetFlow);
        }
        String targetKey = flowAssetMatchKey(targetFlow);
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow f : transaction.getFlows()) {
            if (f == null || f.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (f.getQuantityDelta() == null || f.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!Objects.equals(targetKey, flowAssetMatchKey(f))) {
                continue;
            }
            BigDecimal usdVal = computeFlowUsdValue(f);
            if (usdVal != null && usdVal.signum() > 0) {
                total = total.add(usdVal, MC);
            }
        }
        return total.signum() > 0 ? total : null;
    }

    /**
     * Cycle/12 / B-EARN-BUNDLE: Handles {@code bybit-it-bundle-v1:*} multi-leg Bybit bundle
     * transfers. Only {@code bybit-it-bundle-v1:} correlationId prefixes enter this path;
     * {@code bybit-collapsed-v1:} and {@code bybit-cross-uid-v1:} bundles do NOT.
     *
     * <p><b>Fan-in (N outbound → 1 inbound):</b> A Bybit Earn subscription produces three legs in
     * time order: EARN inbound, FUND outbound (tiny housekeeping qty), UTA outbound (principal qty).
     * All three share the same {@code corr-family} queue key. When the EARN inbound arrives first it
     * materialises provisionally and parks a {@code pendingInbound} entry. Each outbound leg checks
     * whether its carry qty is less than the parked pending-inbound qty (partial-leg detection):
     * <ul>
     *   <li>If yes (partial leg): the outbound's carry is applied for its own qty slice via a
     *       proportionally-scaled provisional so that {@code applyAuthoritativeLateInboundCarryBasis}
     *       does not double-subtract the full provisional. The remaining pending inbound is
     *       re-enqueued at the front of the queue for the next outbound leg to consume.</li>
     *   <li>If no (epsilon guard): carry quantities below {@value #EARN_BUNDLE_PARTIAL_LEG_THRESHOLD}
     *       fall through to the full-consume path (treated as dust, not partial legs).</li>
     * </ul>
     * CARRY_IN ledger points are emitted once per outbound leg (one from FUND, one from UTA).
     *
     * <p><b>Inbound path:</b> drains all parked outbound carries (qty-agnostic bridge match) so
     * UTA + FUND outflows jointly restore basis on the EARN inbound even when timestamps are skewed.
     */
    private AssetLedgerPoint.BasisEffect applyBybitMultiLegBundleTransfer(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        TransferPendingKey transferKey = keyFactory.transferKey(transaction, flow);
        if (transferKey == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return AssetLedgerPoint.BasisEffect.UNKNOWN;
        }

        if (flow.getQuantityDelta().signum() < 0) {
            // Cycle/19: Bybit bundle transfers use proportional basis to prevent the
            // shortfall spiral that erodes coverage on each UTA↔FUND↔EARN round-trip.
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    flow,
                    flowIndex,
                    position,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries(),
                    true
            );
            Deque<CarryTransfer> queue = replayState.pendingTransfers().queue(transferKey);
            int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
            if (pendingInboundIndex >= 0) {
                CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
                boolean isPartialLeg = carry.quantity() != null
                        && pendingInbound.quantity() != null
                        && carry.quantity().compareTo(pendingInbound.quantity()) < 0
                        && carry.quantity().compareTo(EARN_BUNDLE_PARTIAL_LEG_THRESHOLD) > 0;
                if (isPartialLeg) {
                    // Partial outbound leg (e.g. FUND in a UTA+FUND+EARN bundle): attach this leg's
                    // carry for its own qty slice and re-enqueue the remaining pending inbound so the
                    // next (larger) outbound leg can also attach. Implements N-outbound-to-1-inbound
                    // fan-in without losing UTA's basis to premature pending-inbound consumption.
                    //
                    // provisionalBasisUsd is partitioned proportionally so that
                    // applyAuthoritativeLateInboundCarryBasis does not double-subtract the full
                    // provisional when called for each leg separately.
                    BigDecimal fullProvisional = pendingInbound.provisionalBasisUsd() != null
                            ? pendingInbound.provisionalBasisUsd() : BigDecimal.ZERO;
                    BigDecimal scaledProvisional = pendingInbound.quantity().signum() > 0
                            ? fullProvisional.multiply(
                                    carry.quantity().divide(pendingInbound.quantity(), MC), MC)
                            : BigDecimal.ZERO;
                    BigDecimal remainingProvisional = fullProvisional.subtract(scaledProvisional, MC);
                    CarryTransfer slicedForAttach = new CarryTransfer(
                            carry.quantity(), BigDecimal.ZERO, carry.quantity(),
                            BigDecimal.ZERO, null, null, null,
                            true, pendingInbound.assetKey(), scaledProvisional,
                            pendingInbound.sourceFlowRef()
                    );
                    attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            slicedForAttach,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                    BigDecimal remainingQty = pendingInbound.quantity().subtract(carry.quantity(), MC);
                    queue.addFirst(
                            pendingInbound.withReducedQuantityAndProvisional(remainingQty, remainingProvisional));
                } else {
                    attachLateCarryToPendingInbound(
                            transaction,
                            flow,
                            flowIndex,
                            replayState.positions(),
                            pendingInbound,
                            carry,
                            replayState.ledgerPointCollector()
                    );
                }
                if (queue.isEmpty()) {
                    replayState.pendingTransfers().remove(transferKey);
                }
                return flowSupport.continuityBasisEffect(transaction, flow);
            }
            queue.addLast(carry);
            return flowSupport.continuityBasisEffect(transaction, flow);
        }

        Deque<CarryTransfer> queue = replayState.pendingTransfers().find(transferKey);
        BigDecimal remaining = flow.getQuantityDelta().abs();
        boolean restoredAny = false;
        while (remaining.signum() > 0) {
            int carryIndex = matcher.findUniqueBridgeQueueIndex(queue, false);
            if (carryIndex < 0) {
                break;
            }
            CarryTransfer carry = matcher.removeQueueElement(queue, carryIndex);
            BigDecimal takeQty = remaining.min(carry.quantity());
            CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(
                    carry,
                    takeQty,
                    position.assetKey()
            );
            flowSupport.restoreToPosition(effectiveCarry, position);
            continuityCarryService.reservePassThroughCarryIfPlanned(
                    transaction,
                    flowIndex,
                    effectiveCarry,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries()
            );
            remaining = remaining.subtract(takeQty, MC);
            restoredAny = true;
        }
        if (queue != null && queue.isEmpty()) {
            replayState.pendingTransfers().remove(transferKey);
        }
        if (remaining.signum() > 0) {
            TransferPendingKey fifoKey = keyFactory.earnCarryFifoKey(transaction, flow);
            Deque<CarryTransfer> fifoQueue = fifoKey != null
                    ? replayState.pendingTransfers().find(fifoKey) : null;
            while (fifoQueue != null && remaining.signum() > 0) {
                int carryIndex = matcher.findUniqueBridgeQueueIndex(fifoQueue, false);
                if (carryIndex < 0) break;
                CarryTransfer carry = matcher.removeQueueElement(fifoQueue, carryIndex);
                BigDecimal takeQty = remaining.min(carry.quantity());
                CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(
                        carry, takeQty, position.assetKey());
                flowSupport.restoreToPosition(effectiveCarry, position);
                continuityCarryService.reservePassThroughCarryIfPlanned(
                        transaction,
                        flowIndex,
                        effectiveCarry,
                        replayState.passThroughCorridorPlan(),
                        replayState.reservedPassThroughCarries()
                );
                remaining = remaining.subtract(takeQty, MC);
                restoredAny = true;
            }
            if (fifoKey != null && fifoQueue != null && fifoQueue.isEmpty()) {
                replayState.pendingTransfers().remove(fifoKey);
            }
        }
        if (remaining.signum() > 0) {
            NormalizedTransaction.Flow remainderFlow = flow;
            if (restoredAny) {
                remainderFlow = flowWithQuantity(flow, remaining);
            }
            BigDecimal provisionalBasis = flowSupport.materializePendingInbound(remainderFlow, position);
            replayState.pendingTransfers().queue(transferKey)
                    .addLast(CarryTransfer.pendingInbound(remaining, position.assetKey(), provisionalBasis));
        }
        return flowSupport.continuityBasisEffect(transaction, flow);
    }

    private static NormalizedTransaction.Flow flowWithQuantity(
            NormalizedTransaction.Flow source,
            BigDecimal quantity
    ) {
        NormalizedTransaction.Flow copy = new NormalizedTransaction.Flow();
        copy.setRole(source.getRole());
        copy.setAssetSymbol(source.getAssetSymbol());
        copy.setAssetContract(source.getAssetContract());
        copy.setQuantityDelta(quantity);
        copy.setCounterpartyAddress(source.getCounterpartyAddress());
        copy.setCounterpartyType(source.getCounterpartyType());
        copy.setAccountRef(source.getAccountRef());
        return copy;
    }

    /**
     * Rounding tolerance for VAULT_WITHDRAW proportional carry: if
     * |qty_returned − qty_deposited| / qty_deposited is below this threshold the
     * difference is treated as dust (same-day round-trip) and the full carry basis is
     * used without proportional scaling.
     */
    private static final BigDecimal VAULT_WITHDRAW_ROUND_TRIP_TOLERANCE = new BigDecimal("0.0001");

    /**
     * For wrapper-composite buckets (VAULT_WITHDRAW, STAKING_WITHDRAW returning a
     * different-denomination receipt), drain the ENTIRE bucket carry instead of a
     * proportional slice. The receipt token (e.g., mevUSDC shares) and the returned asset
     * (USDC) have incompatible quantity scales — proportional slicing yields ~$0 basis.
     *
     * <p>VAULT_WITHDRAW exception (RC-3): when the vault returns slightly fewer tokens than
     * deposited (vault fee / rounding), carrying back the full basis inflates AVCO on each
     * cycle. Apply a proportional carry: basis = depositedBasis × (returned / deposited),
     * capped at 1.0 to handle yield (excess quantity enters via the BUY/income path).
     * If the difference is within {@link #VAULT_WITHDRAW_ROUND_TRIP_TOLERANCE} treat as a
     * same-day round-trip and use full carry to avoid dust drift.
     */
    private void restoreFullBucket(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = continuityCarryService.drainFullBucket(bucket, position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        BigDecimal costBasisToRestore = carry.costBasisUsd();

        // RC-3 / RC-vault-yield: proportional carry for vault/lending withdrawals.
        //
        // USD-first path: when the inbound leg carries a USD value (priced token), allocate
        // the deposited basis across all same-asset inbound flows proportionally by USD value.
        // This handles the mixed TRANSFER+BUY case (principal return + yield on the same asset)
        // and the cross-denomination case (share scale differs from underlying scale).
        //
        // Quantity-based fallback: for VAULT_WITHDRAW with unpriced tokens, fall back to the
        // original quantity-ratio approach (RC-3). Preserves existing test contracts.
        boolean isWithdraw = transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                || transaction.getType() == NormalizedTransactionType.LENDING_WITHDRAW;
        if (isWithdraw && carry.costBasisUsd() != null && carry.costBasisUsd().signum() > 0) {
            BigDecimal thisFlowUsd = computeFlowUsdValue(flow);
            BigDecimal totalReturnedUsd = computeTotalSameAssetInboundUsd(transaction, flow);
            if (thisFlowUsd != null && totalReturnedUsd != null && totalReturnedUsd.signum() > 0) {
                // ratio = min(total_returned_usd / deposited_basis_usd, 1.0)
                BigDecimal ratio = totalReturnedUsd
                        .divide(carry.costBasisUsd(), MathContext.DECIMAL64)
                        .min(BigDecimal.ONE);
                BigDecimal flowFraction = thisFlowUsd.divide(totalReturnedUsd, MathContext.DECIMAL64);
                costBasisToRestore = carry.costBasisUsd()
                        .multiply(ratio, MathContext.DECIMAL64)
                        .multiply(flowFraction, MathContext.DECIMAL64);
            } else if (transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                    && carry.quantity() != null && carry.quantity().signum() > 0) {
                costBasisToRestore = vaultWithdrawQuantityRatioBasis(carry, flow);
            }
        } else if (transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                && carry.quantity() != null && carry.quantity().signum() > 0) {
            costBasisToRestore = vaultWithdrawQuantityRatioBasis(carry, flow);
        }

        // ADR-040 Change 2: apply same scaling ratio to net lane
        BigDecimal netCostBasisToRestore;
        if (carry.costBasisUsd() != null && carry.costBasisUsd().signum() > 0
                && costBasisToRestore.compareTo(carry.costBasisUsd()) != 0) {
            BigDecimal netSrc = carry.netCostBasisUsd() != null ? carry.netCostBasisUsd() : carry.costBasisUsd();
            BigDecimal ratio = costBasisToRestore.divide(carry.costBasisUsd(), MC);
            netCostBasisToRestore = netSrc.multiply(ratio, MC);
        } else {
            netCostBasisToRestore = carry.netCostBasisUsd() != null ? carry.netCostBasisUsd() : costBasisToRestore;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                costBasisToRestore,
                netCostBasisToRestore,
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    /**
     * RC-3 quantity-based fallback: proportional carry for VAULT_WITHDRAW when the returned
     * quantity differs from the deposited quantity (vault fee or yield). Caps the ratio at 1.0
     * so excess yield enters at zero cost. Dust differences (within
     * {@link #VAULT_WITHDRAW_ROUND_TRIP_TOLERANCE}) are treated as same-day round-trips and
     * the full deposited basis is returned unchanged.
     */
    private BigDecimal vaultWithdrawQuantityRatioBasis(CarryTransfer carry, NormalizedTransaction.Flow flow) {
        BigDecimal qtyReturned = flow.getQuantityDelta().abs();
        BigDecimal qtyDeposited = carry.quantity();
        BigDecimal diff = qtyReturned.subtract(qtyDeposited, MC).abs();
        BigDecimal tolerance = qtyDeposited.multiply(VAULT_WITHDRAW_ROUND_TRIP_TOLERANCE, MC);
        if (diff.compareTo(tolerance) >= 0) {
            BigDecimal ratio = qtyReturned.divide(qtyDeposited, MathContext.DECIMAL64)
                    .min(BigDecimal.ONE);
            return carry.costBasisUsd().multiply(ratio, MathContext.DECIMAL64);
        }
        return carry.costBasisUsd();
    }

    private void restoreFromContinuityBucket(
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, flow.getQuantityDelta().abs(), position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                carry.costBasisUsd(),
                carry.netCostBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    /**
     * RC-vault-yield: USD-value-weighted bucket restoration for VAULT_WITHDRAW and
     * LENDING_WITHDRAW with mixed inbound flows (principal TRANSFER + yield BUY).
     *
     * <p>When a vault/lending withdrawal returns both a principal TRANSFER leg (e.g., 926 USDC)
     * and a yield BUY leg (e.g., 73 USDC interest), raw-quantity restoration from the carry
     * bucket would route the entire deposited basis to whichever leg calls first — typically
     * inflating AVCO for the TRANSFER leg to an absurd level ($1.072 instead of $1.00).
     *
     * <p>Instead, distribute the carry proportionally by USD value so each inbound leg gets only
     * its fair share of the original deposit basis. Unpriced legs fall through to
     * {@code applyUnknownTransfer}; if ALL legs are unpriced, fall back to standard quantity
     * restore to avoid losing carry silently.
     */
    private void restoreFromContinuityBucketVaultProportional(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            restoreFromContinuityBucket(flow, position, bucket);
            return;
        }

        BigDecimal totalUsdWeight = BigDecimal.ZERO;
        BigDecimal currentFlowUsdWeight = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow f : transaction.getFlows()) {
            if (f == null
                    || f.getQuantityDelta() == null
                    || f.getQuantityDelta().signum() <= 0
                    || f.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            BigDecimal weight = BigDecimal.ZERO;
            if (f.getUnitPriceUsd() != null && f.getUnitPriceUsd().signum() > 0) {
                weight = f.getQuantityDelta().multiply(f.getUnitPriceUsd(), MC);
            }
            totalUsdWeight = totalUsdWeight.add(weight, MC);
            if (f == flow) {
                currentFlowUsdWeight = weight;
            }
        }

        if (totalUsdWeight.signum() == 0) {
            restoreFromContinuityBucket(flow, position, bucket);
            return;
        }

        if (currentFlowUsdWeight.signum() == 0) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }

        BigDecimal weightFraction = currentFlowUsdWeight.divide(totalUsdWeight, MC);
        BigDecimal virtualQty = bucket.quantity().multiply(weightFraction, MC);
        CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, virtualQty, position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                carry.costBasisUsd(),
                carry.netCostBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    /**
     * RC-2: USD-value-weighted bucket restoration for LENDING_DEPOSIT inbound legs.
     *
     * <p>When a composite bucket holds carry from a LENDING_DEPOSIT principal outbound, and
     * multiple REALLOCATE_IN legs (e.g., eUSDC-2 at $1/unit + an unpriced internal ERC-20
     * at 4.5 billion units) compete to restore from that bucket, raw-quantity weighting
     * routes virtually all basis to the unpriced billion-unit leg. Instead, basis is
     * distributed proportionally by USD value (qty × unitPriceUsd). Unpriced legs receive
     * $0 carry. If ALL legs are unpriced, the basis is left in the source bucket and a
     * warning is emitted rather than silently destroying it.
     */
    private void restoreFromBucketLendingDepositUsdWeighted(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ContinuityBucket bucket
    ) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            restoreFromContinuityBucket(flow, position, bucket);
            return;
        }

        // Compute USD weights for all inbound (positive qty, non-fee) flows in this transaction.
        BigDecimal totalUsdWeight = BigDecimal.ZERO;
        BigDecimal currentFlowUsdWeight = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow f : transaction.getFlows()) {
            if (f == null
                    || f.getQuantityDelta() == null
                    || f.getQuantityDelta().signum() <= 0) {
                continue;
            }
            BigDecimal weight = BigDecimal.ZERO;
            if (f.getUnitPriceUsd() != null && f.getUnitPriceUsd().signum() > 0) {
                weight = f.getQuantityDelta().multiply(f.getUnitPriceUsd(), MC);
            }
            totalUsdWeight = totalUsdWeight.add(weight, MC);
            if (f == flow) {
                currentFlowUsdWeight = weight;
            }
        }

        if (totalUsdWeight.signum() == 0) {
            // All inbound legs are unpriced: leave basis in source pool to avoid silent destruction.
            log.warn(
                    "LENDING_DEPOSIT_USD_WEIGHT_ZERO txId={} all inbound legs unpriced; basis left in source bucket",
                    transaction.getId()
            );
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }

        if (currentFlowUsdWeight.signum() == 0) {
            // This leg is unpriced: it receives no carry from the bucket.
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }

        // USD-weighted virtual quantity: scale current bucket by this leg's weight fraction.
        BigDecimal weightFraction = currentFlowUsdWeight.divide(totalUsdWeight, MC);
        BigDecimal virtualQty = bucket.quantity().multiply(weightFraction, MC);
        CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, virtualQty, position.assetKey());
        if (carry == null) {
            flowSupport.applyUnknownTransfer(flow, position);
            return;
        }
        flowSupport.restoreToPosition(
                flow.getQuantityDelta().abs(),
                position,
                carry.costBasisUsd(),
                carry.netCostBasisUsd(),
                carry.uncoveredQuantity(),
                carry.avco()
        );
    }

    private void attachLateCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        // FIX B (ADR-043 amendment): HEAD "materialize-then-refine" late-attach. The pending inbound
        // was materialized at enqueue time (covered quantity + provisional basis) when priceable, so
        // this path only REFINES the basis with the authoritative paired carry via
        // applyAuthoritativeLateInboundCarryBasis — it must NOT re-add pendingInbound.quantity()
        // (that would double-credit quantity). The prior #11 paired branch that unconditionally
        // re-added quantity relied on an always-deferred zero inbound; that combined with the
        // upstream cross-event mis-pairing dropped the OPEN-subscribe :EARN inbound entirely.
        CarryTransfer effectiveCarry = classifier.usesBybitVenueInternalCarryQueue(transaction)
                || isBybitEarnPrincipalPaired(transaction)
                ? continuityCarryService.internalAccountInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey())
                : continuityCarryService.sliceCarryTransfer(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        if (!pendingInbound.materialized()) {
            // Issue 2 (ADR-043): the pending inbound was DEFERRED unmaterialized — its quantity was
            // never added to the destination (materializePendingInbound could not price the unpriced
            // boundary leg → Optional.empty). The authoritative paired carry now supplies both quantity
            // AND basis, so MATERIALIZE it onto the destination (LTC :EARN 0.75 @ $41.54) instead of
            // the refine-only path that would drop the quantity. Conservation-safe: this quantity was
            // already drained from the paired FUND/UTA source (gated on isBybitEarnPrincipalPaired via
            // the enqueue defer branch) — it cannot resurrect an inbound-only phantom.
            flowSupport.restoreToPosition(
                    effectiveCarry.quantity(),
                    destination,
                    flowSupport.pegFlooredStablecoinCarryBasis(
                            destination.assetKey(),
                            effectiveCarry.coveredQuantity(),
                            effectiveCarry.costBasisUsd()
                    ),
                    effectiveCarry.netCostBasisUsd(),
                    effectiveCarry.uncoveredQuantity(),
                    effectiveCarry.avco()
            );
            flowSupport.recomputePerWalletAvco(destination);
            if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
                flowSupport.resolveTemporaryUnresolved(destination);
            }
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowIndex,
                    destination.assetKey(),
                    before,
                    destination,
                    AssetLedgerPoint.BasisEffect.CARRY_IN
            );
            return;
        }
        // Cycle/19: resolve the full carry quantity against the pending inbound's uncovered
        // portion — the carry represents the actual transfer and its covered portion provides
        // real basis. The uncovered portion from the carry stays uncovered at the destination.
        BigDecimal resolvedQuantity = effectiveCarry.quantity().min(
                destination.uncoveredQuantity() == null ? BigDecimal.ZERO : destination.uncoveredQuantity()
        );
        destination.setUncoveredQuantity(nonNegative(
                destination.uncoveredQuantity().subtract(resolvedQuantity, MC)
                        .add(effectiveCarry.uncoveredQuantity(), MC)
        ));
        // Cycle/19: when the pending inbound was materialised with a provisional spot-basis,
        // replace it with the authoritative carry basis instead of stacking on top.
        // R-3*: floor USD-stablecoin carries to $1/covered-unit so a depressed source pool cannot
        // settle the destination sub-ledger below peg via this late-attach path.
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.costBasisUsd()
                ),
                // ADR-040 Change 2: net lane gets the floored net carry basis, not tax
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.netCostBasisUsd()
                )
        );
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey(),
                before,
                destination,
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    /**
     * ADR-020: When BRIDGE_IN fires before its paired BRIDGE_OUT (late-carry ordering), the
     * authoritative carry is applied here. If the BRIDGE_IN was part of a pre-built pass-through
     * corridor (e.g. BRIDGE_IN → LENDING_DEPOSIT on the same network), this method must activate
     * the reservation so the downstream consumer can use {@code takeReservedCarry} instead of
     * draining the depleted family pool.
     */
    private void attachLateBridgeCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(carry, pendingInbound.quantity(), pendingInbound.assetKey());
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.setUncoveredQuantity(nonNegative(destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)));
        // R-3*: floor USD-stablecoin bridge carries to $1/covered-unit (e.g. BRIDGE_IN USDC at
        // $0.8874) so a depressed origin pool cannot propagate a sub-peg basis across the corridor.
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.costBasisUsd()
                ),
                // ADR-040 Change 2: net lane
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.netCostBasisUsd()
                )
        );
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        if (pendingInbound.sourceFlowRef() != null) {
            continuityCarryService.reservePassThroughCarry(
                    passThroughCorridorPlan,
                    pendingInbound.sourceFlowRef(),
                    effectiveCarry,
                    reservedPassThroughCarries
            );
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey(),
                before,
                destination,
                AssetLedgerPoint.BasisEffect.CARRY_IN
        );
    }

    private void attachLateBridgeSettlementCarryToPendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionStore positions,
            CarryTransfer pendingInbound,
            CarryTransfer carry,
            LedgerPointCollector ledgerPointCollector
    ) {
        PositionState destination = positions.position(pendingInbound.assetKey());
        PositionSnapshot before = flowSupport.snapshot(destination);
        CarryTransfer effectiveCarry = continuityCarryService.bridgeSettlementInboundCarry(
                carry,
                pendingInbound.quantity(),
                pendingInbound.assetKey()
        );
        BigDecimal coveredResolvedQuantity = effectiveCarry.coveredQuantity();
        destination.setUncoveredQuantity(nonNegative(destination.uncoveredQuantity().subtract(coveredResolvedQuantity, MC)));
        // R-3*: floor USD-stablecoin settlement carries to $1/covered-unit (depressed-source guard).
        flowSupport.applyAuthoritativeLateInboundCarryBasis(
                destination,
                pendingInbound.provisionalBasisUsd(),
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.costBasisUsd()
                ),
                // ADR-040 Change 2: net lane
                flowSupport.pegFlooredStablecoinCarryBasis(
                        destination.assetKey(),
                        effectiveCarry.coveredQuantity(),
                        effectiveCarry.netCostBasisUsd()
                )
        );
        flowSupport.recomputePerWalletAvco(destination);
        if (effectiveCarry.avco() != null && destination.uncoveredQuantity().signum() == 0) {
            flowSupport.resolveTemporaryUnresolved(destination);
        }
        ledgerPointCollector.record(
                transaction,
                flow,
                flowIndex,
                destination.assetKey(),
                before,
                destination,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

  private CarryTransfer backfillEarnPrincipalInboundCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            BigDecimal inboundQuantity,
            PositionState position,
            CarryTransfer effectiveCarry,
            ReplayExecutionState replayState
    ) {
        if (!isBybitEarnPrincipalPaired(transaction)
                || effectiveCarry == null
                || inboundQuantity == null
                || inboundQuantity.signum() <= 0) {
            return effectiveCarry;
        }
        BigDecimal fallbackAvco = resolveEarnPrincipalFallbackAvco(transaction, flow, position, replayState);
        if (fallbackAvco == null || fallbackAvco.signum() <= 0) {
            return effectiveCarry;
        }
        BigDecimal authoritativeBasis = inboundQuantity.multiply(fallbackAvco, MC);
        BigDecimal cost = effectiveCarry.costBasisUsd() == null ? BigDecimal.ZERO : effectiveCarry.costBasisUsd();
        if (cost.signum() <= 0) {
            return continuityCarryService.syntheticBybitEarnProductCarry(
                    flow,
                    inboundQuantity,
                    position.assetKey(),
                    fallbackAvco
            );
        }
        if (flow != null
                && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())
                && cost.compareTo(authoritativeBasis.multiply(EARN_CORRIDOR_INFLATED_BASIS_MULTIPLIER, MC)) > 0) {
            return continuityCarryService.buildExplicitCarryTransfer(
                    inboundQuantity,
                    authoritativeBasis,
                    position.assetKey()
            );
        }
        return effectiveCarry;
    }

    /**
     * P0-A: For earn-principal outbound transfers, override a dust carry with the authoritative
     * lot basis = movedQty × marketAvco. This corrects cases where the EARN position was
     * populated with a stale synthetic AVCO (e.g., CMETH at $4 vs. ~$2280) from incorrect
     * spot pricing at subscription time. Only overrides when carry cost is below the dust
     * threshold ({@link #EARN_PRINCIPAL_DUST_BASIS_THRESHOLD}).
     */
    private CarryTransfer applyEarnPrincipalLotCarryOverride(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CarryTransfer carry,
            PositionState carrySource,
            ReplayExecutionState replayState
    ) {
        if (!isBybitEarnPrincipalPaired(transaction) || flow.getQuantityDelta().signum() >= 0) {
            return carry;
        }
        BigDecimal currentCost = carry == null || carry.costBasisUsd() == null
                ? BigDecimal.ZERO
                : carry.costBasisUsd();
        if (currentCost.compareTo(EARN_PRINCIPAL_DUST_BASIS_THRESHOLD) >= 0) {
            return carry;
        }
        BigDecimal movedQty = flow.getQuantityDelta().abs();
        if (movedQty == null || movedQty.signum() <= 0) {
            return carry;
        }
        // Use historical cache (not flow-embedded price) since the flow's unitPriceUsd may be
        // the mis-assigned spot price from earn subscription normalization.
        BigDecimal marketAvco = replayMarketAuthority.resolveFromCacheOrCatalog(transaction, flow)
                .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                .orElse(null);
        if (marketAvco == null || marketAvco.signum() <= 0) {
            return carry;
        }
        BigDecimal lotBasis = movedQty.multiply(marketAvco, MC);
        if (lotBasis.compareTo(currentCost) <= 0) {
            return carry;
        }
        return continuityCarryService.buildExplicitCarryTransfer(movedQty, lotBasis, carrySource.assetKey());
    }

    private static boolean isBybitEarnPrincipalPaired(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null
                || !correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)) {
            return false;
        }
        return Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || (transaction.getWalletAddress() != null
                && transaction.getWalletAddress().toUpperCase(Locale.ROOT).endsWith(":EARN"))
                || (transaction.getWalletAddress() != null
                && !transaction.getWalletAddress().toUpperCase(Locale.ROOT).endsWith(":EARN"));
    }

    private static boolean multiSourceEarnPrincipalBundle(NormalizedTransaction transaction) {
        return isBybitEarnPrincipalPaired(transaction);
    }

    /**
     * Resolves the position from which carry basis should be drained for an outbound transfer.
     *
     * <p>Two fallback paths are supported:
     * <ul>
     *   <li><b>:EARN path</b> — earn-principal outbound rows are booked on {@code :EARN}; when
     *       the deposit path landed no covered basis on the slice, drain from the umbrella instead.
     *   <li><b>:FUND corridor path (B-BYBIT-CORRIDOR-2 sub-pattern A)</b> — Bybit's API does not
     *       expose the internal UTA→FUND transfer that precedes every on-chain withdrawal. When
     *       the FUND position has zero inventory at outbound time, fall back to the umbrella
     *       {@code BYBIT:UID} position so the proportional-carry override fires correctly.
     *   <li><b>:FUND venue-internal drain (R-1 / R-1*)</b> — collapsed FUND↔UTA/EARN legs strip
     *       {@code :FUND} to the umbrella, but the real inventory sits on the {@code :FUND}
     *       sub-account. Drain {@code :FUND} when the umbrella cannot cover the moved quantity
     *       (empty or dust residue) but {@code :FUND} can, so basis is conserved instead of
     *       carrying ~$0.
     * </ul>
     *
     *   <li><b>accountRef sub-account (ADR-042)</b> — when the flow carries an explicit
     *       {@code accountRef} naming a {@code :FUND}/{@code :UTA}/{@code :EARN} sub-account that
     *       already exists with enough inventory to cover the leg, drain that named sub-position.
     *       This is the corridor carry/drain source selection: the collapsed flow-position KEY is
     *       unchanged, only the drain source is redirected. It runs first and keys purely on
     *       {@code accountRef} + inventory (never on counterparty/type/correlationId), so a
     *       self-funded corridor-out (RC-9) is structurally immune — it already drains its
     *       {@code :FUND} pool and the redirect returns that same position.
     * </ul>
     *
     * @param outboundQuantity absolute moved quantity of the outbound leg, used to decide whether
     *                         a candidate source actually covers the transfer (dust-safe); may be
     *                         {@code null} for callers that do not perform coverage-based redirect.
     */
    private static PositionState resolveCarrySourcePosition(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState flowPosition,
            ReplayExecutionState replayState,
            boolean isCorridorTransfer,
            BigDecimal outboundQuantity
    ) {
        if (transaction == null || flowPosition == null || replayState == null) {
            return flowPosition;
        }
        // ADR-042: honour flow.accountRef — prefer the existing inventory-bearing sub-account the
        // flow names when it can cover the outbound. Coverage + existence gated, so plain collapsed
        // FUND↔UTA legs whose inventory sits on the umbrella (RC-2) find no covering :FUND
        // sub-position and keep netting on the umbrella.
        AssetKey accountRefKey = AccountRefPositionResolver.resolveInventoryBearingAccountRefKey(
                flowPosition.assetKey(),
                flow == null ? null : flow.getAccountRef(),
                replayState.positions().asMap(),
                outboundQuantity
        );
        if (!accountRefKey.equals(flowPosition.assetKey())) {
            return replayState.position(accountRefKey);
        }
        String wallet = transaction.getWalletAddress();
        if (wallet == null) {
            return flowPosition;
        }
        String walletUpper = wallet.toUpperCase(Locale.ROOT);

        // :EARN path — unchanged behaviour
        String correlationId = transaction.getCorrelationId();
        if (correlationId != null
                && correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)
                && walletUpper.endsWith(":EARN")) {
            if (hasEarnPrincipalCarryBasis(flowPosition)) {
                return flowPosition;
            }
            String umbrellaWallet = wallet.substring(0, wallet.length() - ":EARN".length());
            return replayState.position(umbrellaKeyFor(flowPosition.assetKey(), umbrellaWallet));
        }

        // :FUND corridor path — sub-pattern A: FUND has zero inventory because the UTA→FUND
        // internal step is not exposed by Bybit's API. Fall back to the umbrella BYBIT:UID
        // position so the corridor proportional-carry override (ADR-019 Rule 1) fires.
        // Sub-pattern B (FUND qty>0 but AVCO=0) is intentionally NOT covered here — it requires
        // a separate fix to the UNIVERSAL_TRANSFER inbound carry path.
        if (isCorridorTransfer
                && walletUpper.endsWith(":FUND")
                && !hasFundCarryInventory(flowPosition)) {
            String umbrellaWallet = wallet.substring(0, wallet.length() - ":FUND".length());
            return replayState.position(umbrellaKeyFor(flowPosition.assetKey(), umbrellaWallet));
        }

        // :FUND venue-internal drain (R-1/R-3, RC-A FUND-drain symmetry, ADR-043).
        //
        // The redirect is now symmetric for ALL FUND↔UTA legs, gated purely on COVERAGE — not on an
        // earn-principal context whitelist. Coverage preserves RC-2: a plain collapsed FUND↔UTA leg
        // whose inventory sits on the umbrella finds the umbrella covers the moved quantity, so no
        // :FUND redirect fires and both legs net on the umbrella (no inbound-only phantom). Only when
        // the umbrella cannot cover but a named :FUND sub-position holds the inventory does the drain
        // resolve to :FUND — draining the pool that actually holds the principal instead of stranding
        // a same-quantity phantom on the umbrella (the B-REG-01 umbrella-phantom class, which
        // previously reappeared on non-earn FUND↔UTA legs that the earn-context gate excluded). RC-9
        // (self-funded :FUND corridor-out) already drains :FUND via the accountRef redirect above and
        // is unaffected. ETH is 0 post-ADR-042 and does not regress (its legs carry accountRef).
        if (!isCorridorTransfer
                && walletUpper.endsWith(":FUND")
                && !positionCoversQuantity(flowPosition, outboundQuantity)) {
            AssetKey fundKey = umbrellaKeyFor(flowPosition.assetKey(), wallet.trim());
            if (!fundKey.equals(flowPosition.assetKey())) {
                PositionState fundPosition = replayState.position(fundKey);
                if (positionCoversQuantity(fundPosition, outboundQuantity)) {
                    return fundPosition;
                }
                if (outboundQuantity == null && hasFundCarryInventory(fundPosition)) {
                    return fundPosition;
                }
            }
        }

        return flowPosition;
    }

    /**
     * R-1*: True when {@code position} holds at least {@link #CARRY_SOURCE_COVERAGE_RATIO} of the
     * moved quantity, i.e. it can supply the outbound transfer from real inventory rather than a
     * dust residue. When {@code outboundQuantity} is null/zero, falls back to a plain non-empty
     * check so legacy callers keep their previous behaviour.
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
        BigDecimal required = outboundQuantity.multiply(CARRY_SOURCE_COVERAGE_RATIO, MC);
        return qty.compareTo(required) >= 0;
    }

    /**
     * Returns {@code true} when the FUND position holds non-zero quantity, meaning it has
     * real inventory that should be drained instead of falling back to the umbrella.
     * Deliberately checks quantity only (not basis), so sub-pattern B (qty>0, AVCO=0) is
     * excluded and remains out of scope.
     */
    private static boolean hasFundCarryInventory(PositionState position) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        return qty != null && qty.signum() > 0;
    }

    private static AssetKey umbrellaKeyFor(AssetKey flowKey, String umbrellaWallet) {
        return new AssetKey(
                umbrellaWallet,
                flowKey.networkId(),
                flowKey.assetContract(),
                flowKey.assetSymbol(),
                flowKey.assetIdentity()
        );
    }

    private static boolean hasEarnPrincipalCarryBasis(PositionState position) {
        if (position == null) {
            return false;
        }
        BigDecimal quantity = position.quantity();
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        BigDecimal basis = position.totalCostBasisUsd();
        if (basis != null && basis.signum() > 0) {
            return true;
        }
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal covered = quantity.subtract(uncovered, MC);
        if (covered.signum() <= 0) {
            return false;
        }
        BigDecimal avco = position.perWalletAvco();
        return avco != null && avco.signum() > 0;
    }

    private CarryTransfer normalizeBybitEarnProductCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CarryTransfer carry,
            ReplayExecutionState replayState,
            PositionState carrySourcePosition,
            AssetKey assetKey,
            BigDecimal preResolvedAvco
    ) {
        if (!classifier.usesBybitVenueInternalCarryQueue(transaction)
                && !isBybitEarnPrincipalPaired(transaction)) {
            return carry;
        }
        BigDecimal requested = flow.getQuantityDelta().abs();
        if (carry != null && carry.quantity() != null && carry.quantity().signum() > 0) {
            BigDecimal carryCost = carry.costBasisUsd() == null ? BigDecimal.ZERO : carry.costBasisUsd();
            if (carry.coveredQuantity() != null
                    && carry.coveredQuantity().signum() > 0
                    && carryCost.signum() > 0) {
                return carry;
            }
            requested = carry.quantity();
        }
        if (requested.signum() <= 0) {
            return carry;
        }
        BigDecimal fallbackAvco = preResolvedAvco;
        if (fallbackAvco == null || fallbackAvco.signum() <= 0) {
            fallbackAvco = resolveEarnPrincipalFallbackAvco(
                    transaction,
                    flow,
                    carrySourcePosition,
                    replayState
            );
        }
        return continuityCarryService.syntheticBybitEarnProductCarry(
                flow,
                requested,
                assetKey,
                fallbackAvco
        );
    }

    private BigDecimal resolveEarnPrincipalFallbackAvco(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState primaryPosition,
            ReplayExecutionState replayState
    ) {
        BigDecimal avco = derivePositionAvco(primaryPosition);
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        if (isBybitEarnPrincipalPaired(transaction) && replayState != null && transaction != null) {
            AssetKey flowKey = primaryPosition == null ? null : primaryPosition.assetKey();
            if (flowKey != null) {
                String wallet = transaction.getWalletAddress();
                if (wallet != null) {
                    String uid = extractBybitUid(wallet);
                    if (uid != null) {
                        AssetKey umbrellaKey = new AssetKey(
                                "BYBIT:" + uid,
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(umbrellaKey));
                        if (avco != null) {
                            return avco;
                        }
                        AssetKey earnKey = new AssetKey(
                                "BYBIT:" + uid + ":EARN",
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(earnKey));
                        if (avco != null) {
                            return avco;
                        }
                        AssetKey fundKey = new AssetKey(
                                "BYBIT:" + uid + ":FUND",
                                flowKey.networkId(),
                                flowKey.assetContract(),
                                flowKey.assetSymbol(),
                                flowKey.assetIdentity()
                        );
                        avco = firstPositiveAvco(replayState.position(fundKey));
                        if (avco != null) {
                            return avco;
                        }
                    }
                }
            }
        }
        if (flow != null
                && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())
                && replayState != null) {
            BigDecimal ethFamilyAvco = resolveBybitOutboundEthFamilyAvco(transaction, replayState);
            if (ethFamilyAvco != null && ethFamilyAvco.signum() > 0) {
                return ethFamilyAvco;
            }
        }
        return replayMarketAuthority.resolve(transaction, flow)
                .map(ReplayMarketAuthority.ResolvedMarketPrice::unitPriceUsd)
                .orElse(null);
    }

    private BigDecimal resolveBybitOutboundEthFamilyAvco(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || replayState == null) {
            return null;
        }
        String uid = extractBybitUid(transaction.getWalletAddress());
        if (uid == null) {
            return null;
        }
        String[] walletSuffixes = {"", ":UTA", ":FUND"};
        String[] symbols = {"ETH", "WETH"};
        for (String suffix : walletSuffixes) {
            String wallet = "BYBIT:" + uid + suffix;
            for (String symbol : symbols) {
                AssetKey symbolKey = new AssetKey(wallet, null, null, symbol, "SYMBOL:" + symbol);
                BigDecimal avco = firstPositiveAvco(replayState.position(symbolKey));
                if (avco != null) {
                    return avco;
                }
            }
        }
        return null;
    }

    private static BigDecimal firstPositiveAvco(PositionState position) {
        BigDecimal avco = derivePositionAvco(position);
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        return null;
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = walletAddress.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        return colon > 0 ? without.substring(0, colon) : without;
    }

    private static BigDecimal derivePositionAvco(PositionState position) {
        if (position == null) {
            return null;
        }
        BigDecimal avco = position.perWalletAvco();
        if (avco != null && avco.signum() > 0) {
            return avco;
        }
        BigDecimal quantity = position.quantity();
        BigDecimal basis = position.totalCostBasisUsd();
        if (quantity == null || basis == null || quantity.signum() <= 0 || basis.signum() <= 0) {
            return avco;
        }
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal covered = quantity.subtract(uncovered, MC);
        if (covered.signum() <= 0) {
            return basis.divide(quantity, MC);
        }
        return basis.divide(covered, MC);
    }

    private static boolean preserveBucketOutboundCoverage(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        return switch (transaction.getType()) {
            case VAULT_WITHDRAW,
                    LENDING_WITHDRAW,
                    EARN_FLEXIBLE_SAVING,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    STAKING_WITHDRAW,
                    LP_EXIT,
                    LP_EXIT_PARTIAL,
                    LP_EXIT_FINAL -> true;
            default -> false;
        };
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
