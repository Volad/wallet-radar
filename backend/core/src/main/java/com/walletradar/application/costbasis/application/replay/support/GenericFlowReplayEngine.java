package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.model.QuantityConsumption;
import com.walletradar.application.costbasis.support.UncoveredExternalInboundSupport;
import com.walletradar.application.costbasis.support.ZeroCostAcquisitionSupport;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

@Component
@Slf4j
public class GenericFlowReplayEngine {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * R-3*: peg-floor activation threshold for carried/reallocated USD-stablecoin basis.
     *
     * <p>A USD-pegged stablecoin carry whose per-covered-unit basis is at or above this value is
     * left untouched — that band covers genuine rounding / cross-asset bridge unit-conversion
     * artifacts (e.g. a USDT→USDC LiFi settlement that conserves total basis at ~$0.9998/unit) and
     * the residual sub-peg USDC shortfall (~$0.977) which the audit asked to keep sane. Only a
     * carry materially below peg (the observed depressed-source signature: USDT {@code CARRY_IN}
     * at $0.5575, {@code LENDING_WITHDRAW REALLOCATE_IN} at $0.5716, EARN carry at $0.5397) is
     * re-pegged to $1. No major USD stablecoin has sustainably carried &gt;10% below peg outside a
     * terminal depeg, which would arrive as a fresh priced {@code ACQUIRE} (honoured), not a carry.
     */
    private static final BigDecimal STABLECOIN_CARRY_PEG_FLOOR_THRESHOLD = new BigDecimal("0.90");

    /**
     * U-3: the USD-stablecoin peg unit basis ($1.00) — both the floor target and the cap ceiling.
     * A USD-pegged stablecoin carried basis is clamped to this peg in both directions.
     */
    private static final BigDecimal STABLECOIN_PEG_UNIT_USD = BigDecimal.ONE;

    private final ReplayMarketAuthority replayMarketAuthority;

    /**
     * RC-D (ADR-043, replay #13b amendment): the constructor-level {@link Autowired} is REQUIRED.
     * Spring only promotes a constructor for autowiring when the annotation is at CONSTRUCTOR level;
     * the previous {@code @Autowired} on the PARAMETER of a second constructor did not promote it, so
     * with a competing no-arg constructor present Spring silently selected the no-arg one and left
     * {@code replayMarketAuthority = null}. That made the entire BOT_LEDGER pre-coverage clamp (and the
     * {@code resolve()} paths in {@link #materializePendingInbound}/{@link #applyInboundShortfallSpotFallback})
     * dead code (DOGE never clamped across three attempts). The no-arg constructor is removed from the
     * production/bean path; tests construct with an explicit authority (or {@code null}).
     */
    @Autowired
    public GenericFlowReplayEngine(ReplayMarketAuthority replayMarketAuthority) {
        this.replayMarketAuthority = replayMarketAuthority;
    }

    public void applyBuy(NormalizedTransaction.Flow flow, PositionState position) {
        applyBuy(null, flow, position);
    }

    public void applyBuy(NormalizedTransaction transaction, NormalizedTransaction.Flow flow, PositionState position) {
        boolean zeroNetCost = isZeroNetCostAcquisition(transaction);
        BigDecimal quantity = flow.getQuantityDelta().abs();
        // RC-D (ADR-043): a BOT_LEDGER lot is priced at normalization from net stablecoin consumed
        // (before historical prices exist) and is marked CONFIRMED, so it never reaches the pricing
        // orchestrator's pre-coverage fallback. hasKnownPrice(flow) is TRUE for it, so a normally
        // priced acquire would short-circuit below and book the out-of-range stablecoin-derived
        // price. Route BOT_LEDGER flows through the replay market authority first: resolve() applies
        // clampPreCoverageBotLot for genuinely pre-coverage bot lots (returns the nearest valid
        // market bucket) and returns the FLOW price unchanged for in-coverage bot lots. Blast radius
        // is BOT_LEDGER only — normally priced ACQUIRE/BUY legs keep the hasKnownPrice short-circuit.
        if (flow.getPriceSource() == PriceSource.BOT_LEDGER
                && transaction != null
                && replayMarketAuthority != null) {
            Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolvedBotLot =
                    replayMarketAuthority.resolve(transaction, flow);
            if (resolvedBotLot.isPresent()
                    && resolvedBotLot.get().unitPriceUsd() != null
                    && resolvedBotLot.get().unitPriceUsd().signum() > 0) {
                applyBuyWithAcquisitionCost(
                        flow,
                        position,
                        quantity.multiply(resolvedBotLot.get().unitPriceUsd(), MC),
                        zeroNetCost
                );
                return;
            }
        }
        if (hasKnownPrice(flow)) {
            applyBuyWithAcquisitionCost(flow, position, quantity.multiply(flow.getUnitPriceUsd(), MC), zeroNetCost);
            return;
        }
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
            applyBuyWithAcquisitionCost(flow, position, quantity, zeroNetCost);
            return;
        }
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(quantity));
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    public void applyBuyWithAcquisitionCost(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal acquisitionCostUsd
    ) {
        applyBuyWithAcquisitionCost(flow, position, acquisitionCostUsd, false);
    }

    public void applyBuyWithAcquisitionCost(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal acquisitionCostUsd,
            boolean zeroNetCost
    ) {
        applyBuyWithAcquisitionCost(flow, position, acquisitionCostUsd, zeroNetCost ? NormalizedTransactionType.REWARD_CLAIM : null);
    }

    public void applyBuyWithAcquisitionCost(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal acquisitionCostUsd,
            NormalizedTransactionType acquisitionType
    ) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal cost = acquisitionCostUsd == null ? BigDecimal.ZERO : acquisitionCostUsd;
        BigDecimal netCost = ZeroCostAcquisitionSupport.isZeroCostAcquisition(acquisitionType) ? BigDecimal.ZERO : cost;
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(cost));
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(netCost));
        position.setQuantity(position.quantity().add(quantity));
        recomputePerWalletAvco(position);
    }

    /**
     * ADR-040 Bug B / ADR-082: SWAP net-cost propagation.
     * Tax (market) lane uses the normal acquisition cost; Net lane uses the explicit
     * {@code netAcquisitionCostUsd}.
     *
     * <p>Two caller regimes (both keep {@code net ≤ market}):</p>
     * <ul>
     *   <li><b>Deferred / reward carry (ADR-040 Bug B):</b> {@code netAcquisitionCostUsd} equals the
     *       (capped) net basis released from the disposed asset. Used when the paired disposal did
     *       NOT bank NET realized PnL (counterparty-pool undo, unpriced disposal) or when the
     *       disposed lot carried a genuine reward discount (net ≪ market). This preserves the
     *       reward/deferred write-down (swapping a reward-derived net=$0 asset never raises Net AVCO).</li>
     *   <li><b>Re-base on realize (ADR-082, FB-01):</b> for a realizing distinct-canonical swap whose
     *       NET realized PnL was KEPT on a lot with no reward discount, the caller passes
     *       {@code netAcquisitionCostUsd == taxAcquisitionCostUsd} (net = market). The discount was
     *       already banked once at the disposal, so the acquired lot carries no residual discount —
     *       preventing the NET-lane basis-recycling double-count.</li>
     * </ul>
     */
    public void applyBuyWithExplicitNetCost(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal taxAcquisitionCostUsd,
            BigDecimal netAcquisitionCostUsd
    ) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal taxCost = taxAcquisitionCostUsd == null ? BigDecimal.ZERO : taxAcquisitionCostUsd;
        BigDecimal netCost = netAcquisitionCostUsd == null ? taxCost : netAcquisitionCostUsd;
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(taxCost));
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(netCost));
        position.setQuantity(position.quantity().add(quantity));
        recomputePerWalletAvco(position);
    }

    public void applySell(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfSale = position.perWalletAvco();
        BigDecimal netAvcoAtTimeOfSale = position.perWalletNetAvco();
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal soldCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (avcoAtTimeOfSale != null && soldCoveredQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(avcoAtTimeOfSale);
            BigDecimal relievedCost = soldCoveredQuantity.multiply(avcoAtTimeOfSale, MC);
            position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(relievedCost, MC)));
            BigDecimal netAvco = netAvcoAtTimeOfSale != null ? netAvcoAtTimeOfSale : avcoAtTimeOfSale;
            BigDecimal relievedNetCost = soldCoveredQuantity.multiply(netAvco, MC);
            position.setNetTotalCostBasisUsd(nonNegative(position.netTotalCostBasisUsd().subtract(relievedNetCost, MC)));
            if (hasKnownPrice(flow)
                    && consumption.externalShortfallQuantity().signum() == 0
                    && consumption.uncoveredQuantity().signum() == 0) {
                BigDecimal realised = flow.getUnitPriceUsd().subtract(avcoAtTimeOfSale, MC).multiply(soldCoveredQuantity, MC);
                flow.setRealisedPnlUsd(realised);
                position.setTotalRealisedPnlUsd(position.totalRealisedPnlUsd().add(realised));
                BigDecimal netRealised = flow.getUnitPriceUsd().subtract(netAvco, MC).multiply(soldCoveredQuantity, MC);
                position.setTotalNetRealisedPnlUsd(position.totalNetRealisedPnlUsd().add(netRealised));
            } else {
                flow.setAvcoAtTimeOfSale(null);
                flow.setRealisedPnlUsd(null);
                markUnresolved(position);
            }
        } else if (requestedQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);
            markUnresolved(position);
        }
        recomputePerWalletAvco(position);
    }

    /**
     * ADR-051: Adds a buy-side CEX acquisition fee to the Net AVCO lane only.
     *
     * <p>Called after the standard BUY application so that the Market (tax) lane is never touched.
     * The fee is also accumulated into {@code totalGasPaidUsd} so the move-basis "gas paid"
     * header reflects real CEX commissions paid on the position.
     *
     * @param feeUsd   positive USD commission to capitalize; must be {@code > 0}
     * @param position the position being updated
     */
    public void capitalizeFeeIntoNetLane(BigDecimal feeUsd, PositionState position) {
        if (feeUsd == null || feeUsd.signum() <= 0 || position == null) {
            return;
        }
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(feeUsd));
        position.setTotalGasPaidUsd(position.totalGasPaidUsd().add(feeUsd));
        recomputePerWalletAvco(position);
    }

    public void applyFee(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfCharge = position.perWalletAvco();
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal chargedCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (hasKnownPrice(flow)) {
            BigDecimal feeCost = requestedQuantity.multiply(flow.getUnitPriceUsd(), MC);
            position.setTotalGasPaidUsd(position.totalGasPaidUsd().add(feeCost));
            if (avcoAtTimeOfCharge != null && chargedCoveredQuantity.signum() > 0) {
                position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(
                        chargedCoveredQuantity.multiply(avcoAtTimeOfCharge, MC),
                        MC
                )));
                BigDecimal netAvco = position.perWalletNetAvco() != null ? position.perWalletNetAvco() : avcoAtTimeOfCharge;
                position.setNetTotalCostBasisUsd(nonNegative(position.netTotalCostBasisUsd().subtract(
                        chargedCoveredQuantity.multiply(netAvco, MC),
                        MC
                )));
            }
        } else {
            markUnresolved(position);
        }
        if (consumption.uncoveredQuantity().signum() > 0 || consumption.externalShortfallQuantity().signum() > 0) {
            markUnresolved(position);
        }
        recomputePerWalletAvco(position);
    }

    public void applyUnknownTransfer(NormalizedTransaction.Flow flow, PositionState position) {
        if (flow.getQuantityDelta().signum() > 0) {
            position.setQuantity(position.quantity().add(flow.getQuantityDelta().abs()));
            position.setUncoveredQuantity(position.uncoveredQuantity().add(flow.getQuantityDelta().abs()));
            markUnresolved(position);
            recomputePerWalletAvco(position);
            return;
        }
        QuantityConsumption consumption = consumeQuantity(position, flow.getQuantityDelta().abs());
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    public void applySponsoredGasIn(NormalizedTransaction.Flow flow, PositionState position) {
        restoreToPosition(flow.getQuantityDelta().abs(), position, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

  /**
     * Materialises a pending inbound transfer and returns the USD basis added to the position.
     */
    public BigDecimal materializePendingInbound(NormalizedTransaction.Flow flow, PositionState position) {
        return materializePendingInbound(null, flow, position, true).orElse(BigDecimal.ZERO);
    }

    /**
     * @param permitUncoveredFallback when false, returns empty instead of creating uncovered qty
     *                                without authoritative market price (Bybit venue-internal).
     */
    public Optional<BigDecimal> materializePendingInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            boolean permitUncoveredFallback
    ) {
        BigDecimal basisBefore = position.totalCostBasisUsd() == null ? BigDecimal.ZERO : position.totalCostBasisUsd();
        BigDecimal quantity = flow.getQuantityDelta().abs();
        // B2b: a sourceless bridge inbound (reclassified to EXTERNAL_TRANSFER_IN) has no provable
        // basis, so skip every market/flow-price resolution and fall through to the uncovered /
        // incomplete-history (PENDING) route below — the leg must not fabricate a market-at-arrival
        // basis it never paid. Byte-identical for every row without the marker.
        boolean basisUnknownInbound = UncoveredExternalInboundSupport.isBasisUnknownInbound(transaction);
        if (!basisUnknownInbound && replayMarketAuthority != null && transaction != null) {
            Optional<ReplayMarketAuthority.ResolvedMarketPrice> authority =
                    replayMarketAuthority.resolve(transaction, flow);
            if (authority.isPresent()) {
                BigDecimal unitPrice = authority.get().unitPriceUsd();
                applyBuyWithAcquisitionCost(flow, position, quantity.multiply(unitPrice, MC));
                clearResolvedPositionFlags(position);
                return Optional.of(nonNegative(position.totalCostBasisUsd().subtract(basisBefore, MC)));
            }
            if (!permitUncoveredFallback) {
                log.warn(
                        "REPLAY_MATERIALIZE_DEFERRED wallet={} asset={} qty={} reason=no_market_authority",
                        position.assetKey().walletAddress(),
                        flow.getAssetSymbol(),
                        quantity
                );
                return Optional.empty();
            }
        }
        if (!basisUnknownInbound && hasKnownPrice(flow)) {
            applyBuyWithAcquisitionCost(flow, position, quantity.multiply(flow.getUnitPriceUsd(), MC));
            clearResolvedPositionFlags(position);
            return Optional.of(nonNegative(position.totalCostBasisUsd().subtract(basisBefore, MC)));
        }
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
            applyBuyWithAcquisitionCost(flow, position, quantity);
            clearResolvedPositionFlags(position);
            return Optional.of(nonNegative(position.totalCostBasisUsd().subtract(basisBefore, MC)));
        }
        if (!permitUncoveredFallback) {
            return Optional.empty();
        }
        if (transaction != null
                && CanonicalAssetCatalog.isCrossNetworkPriceResolvable(flow.getAssetSymbol())) {
            // RC-3 (B-ETH-03): a canonical, cross-network-priceable asset (e.g. ETH on an
            // ETH-native L2 such as LINEA) that still resolved no quote here is an incomplete
            // pricing-history gap, not a real $0 acquisition. It is routed to the uncovered /
            // incomplete-history (PENDING) state below — markUnresolved sets hasIncompleteHistory
            // / hasUnresolvedFlags — so the inbound is surfaced for review instead of silently
            // diluting AVCO toward $0 with fabricated covered basis.
            log.warn(
                    "REPLAY_INBOUND_UNRESOLVED_CANONICAL wallet={} network={} asset={} qty={} "
                            + "reason=no_cross_network_quote route=PENDING",
                    position.assetKey().walletAddress(),
                    transaction.getNetworkId(),
                    flow.getAssetSymbol(),
                    quantity
            );
        }
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(quantity));
        markUnresolved(position);
        recomputePerWalletAvco(position);
        return Optional.of(BigDecimal.ZERO);
    }

    public CarryTransfer removeFromPosition(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal effectiveAvco = effectiveRemovalAvco(position);
        BigDecimal effectiveNetAvco = effectiveNetRemovalAvco(position);
        QuantityConsumption consumption = consumeQuantityCoveredFirst(position, requestedQuantity);
        BigDecimal cost = effectiveAvco == null
                ? BigDecimal.ZERO
                : consumption.coveredQuantity().multiply(effectiveAvco, MC);
        BigDecimal netCost = effectiveNetAvco == null
                ? BigDecimal.ZERO
                : consumption.coveredQuantity().multiply(effectiveNetAvco, MC);
        if (position.quantity().signum() == 0 && position.totalCostBasisUsd().signum() > 0) {
            cost = position.totalCostBasisUsd();
        }
        if (position.quantity().signum() == 0 && position.netTotalCostBasisUsd().signum() > 0) {
            netCost = position.netTotalCostBasisUsd();
        }
        position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(cost, MC)));
        position.setNetTotalCostBasisUsd(nonNegative(position.netTotalCostBasisUsd().subtract(netCost, MC)));
        purgeOrphanBasisWhenEmpty(position);
        recomputePerWalletAvco(position);
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (effectiveAvco == null && consumption.coveredQuantity().signum() > 0) {
            markUnresolved(position);
        }
        BigDecimal carryAvco = effectiveAvco;
        if (carryAvco == null && consumption.coveredQuantity().signum() > 0 && cost.signum() > 0) {
            carryAvco = cost.divide(consumption.coveredQuantity(), MC);
        }
        BigDecimal carryNetAvco = effectiveNetAvco;
        if (carryNetAvco == null && consumption.coveredQuantity().signum() > 0 && netCost.signum() > 0) {
            carryNetAvco = netCost.divide(consumption.coveredQuantity(), MC);
        }
        return new CarryTransfer(
                requestedQuantity,
                consumption.coveredQuantity(),
                requestedQuantity.subtract(consumption.coveredQuantity(), MC),
                cost,
                carryAvco,
                netCost,
                carryNetAvco,
                false,
                position.assetKey()
        );
    }

    /**
     * Cycle/19: same-umbrella variant of {@link #removeFromPosition} that spreads basis
     * proportionally across the full requested quantity instead of only the covered portion.
     *
     * <p>For Bybit internal transfers (UTA↔FUND↔EARN), "uncovered" is an artifact of replay
     * ordering, not a genuine unknown. Using proportional basis prevents the compounding
     * coverage gap where each round-trip through sub-accounts erodes covered quantity.</p>
     */
    public CarryTransfer removeFromPositionPreservingCoverage(
            NormalizedTransaction.Flow flow,
            PositionState position
    ) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal availableQty = position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        BigDecimal availableUncov = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal totalCost = position.totalCostBasisUsd() == null ? BigDecimal.ZERO : position.totalCostBasisUsd();
        BigDecimal totalNetCost = position.netTotalCostBasisUsd() == null ? BigDecimal.ZERO : position.netTotalCostBasisUsd();

        if (availableQty.signum() <= 0 || totalCost.signum() <= 0) {
            return removeFromPosition(flow, position);
        }

        BigDecimal appliedQty = requestedQuantity.min(availableQty);
        BigDecimal shortfall = nonNegative(requestedQuantity.subtract(appliedQty, MC));
        BigDecimal proportion = appliedQty.divide(availableQty, MC).min(BigDecimal.ONE);
        BigDecimal proportionalCost = totalCost.multiply(proportion, MC);
        BigDecimal proportionalNetCost = totalNetCost.multiply(proportion, MC);
        BigDecimal proportionalUncov = availableUncov.multiply(proportion, MC);

        BigDecimal newQty = nonNegative(availableQty.subtract(appliedQty, MC));
        BigDecimal newUncov = nonNegative(availableUncov.subtract(proportionalUncov, MC));
        if (newQty.signum() == 0) {
            newUncov = BigDecimal.ZERO;
        }

        position.setQuantity(newQty);
        position.setUncoveredQuantity(newUncov);
        position.setTotalCostBasisUsd(nonNegative(totalCost.subtract(proportionalCost, MC)));
        position.setNetTotalCostBasisUsd(nonNegative(totalNetCost.subtract(proportionalNetCost, MC)));
        purgeOrphanBasisWhenEmpty(position);
        recomputePerWalletAvco(position);

        if (shortfall.signum() > 0) {
            recordQuantityShortfall(position, shortfall);
        }

        BigDecimal carryAvco = appliedQty.signum() > 0
                ? proportionalCost.divide(appliedQty, MC)
                : null;
        BigDecimal carryNetAvco = appliedQty.signum() > 0
                ? proportionalNetCost.divide(appliedQty, MC)
                : null;

        return new CarryTransfer(
                requestedQuantity,
                appliedQty,
                BigDecimal.ZERO,
                proportionalCost,
                carryAvco,
                proportionalNetCost,
                carryNetAvco,
                false,
                position.assetKey()
        );
    }

    private static BigDecimal effectiveNetRemovalAvco(PositionState position) {
        if (position == null) {
            return null;
        }
        BigDecimal coveredQuantity = nonNegative(position.quantity().subtract(position.uncoveredQuantity(), MC));
        BigDecimal derivedAvco = null;
        if (coveredQuantity.signum() > 0 && position.netTotalCostBasisUsd().signum() > 0) {
            derivedAvco = position.netTotalCostBasisUsd().divide(coveredQuantity, MC);
        }
        BigDecimal storedAvco = position.perWalletNetAvco();
        if (storedAvco != null && storedAvco.signum() > 0) {
            return storedAvco;
        }
        return derivedAvco;
    }

    /**
     * When {@link PositionState#perWalletAvco()} is null because the position still carries
     * uncovered quantity, derive AVCO from stored basis so composite LP / wrapper bucket deposits
     * do not drop cost on REALLOCATE_OUT.
     *
     * <p>Cycle/17 R7: never trust a stored {@code perWalletAvco} of zero when covered quantity and
     * basis are both positive — sponsored-gas or partial restores can leave a stale {@code 0} AVCO
     * while {@code totalCostBasisUsd} is non-zero, which previously caused REALLOCATE_OUT to move
     * qty into wrapper buckets without moving basis (zkSync WETH → aZksWETH zombie basis).</p>
     */
    private static BigDecimal effectiveRemovalAvco(PositionState position) {
        if (position == null) {
            return null;
        }
        BigDecimal coveredQuantity = nonNegative(position.quantity().subtract(position.uncoveredQuantity(), MC));
        BigDecimal derivedAvco = null;
        if (coveredQuantity.signum() > 0 && position.totalCostBasisUsd().signum() > 0) {
            derivedAvco = position.totalCostBasisUsd().divide(coveredQuantity, MC);
        }
        BigDecimal storedAvco = position.perWalletAvco();
        if (storedAvco != null && storedAvco.signum() > 0) {
            return storedAvco;
        }
        return derivedAvco;
    }

    public void restoreToPosition(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal avco,
            BigDecimal cost
    ) {
        restoreToPosition(flow.getQuantityDelta().abs(), position, cost, BigDecimal.ZERO, avco);
    }

    public void restoreToPosition(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco
    ) {
        restoreToPosition(quantity, position, cost, cost, uncoveredQuantity, avco);
    }

    /**
     * ADR-040 Change 2: net-conserving carry-aware restore. Routes through the 6-arg net-aware
     * overload so the net cost lane receives {@code carry.netCostBasisUsd()} instead of cloning
     * the tax basis, which would inject phantom net cost on every WRAP/UNWRAP/corridor CARRY_IN.
     */
    public void restoreToPosition(CarryTransfer carry, PositionState position) {
        restoreToPosition(
                carry.quantity(), position,
                carry.costBasisUsd(), carry.netCostBasisUsd(),
                carry.uncoveredQuantity(), carry.avco()
        );
    }

    public void restoreToPosition(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal netCost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco
    ) {
        // Cycle/15 R5 F2: math invariant — the uncovered quantity applied to a position
        // on inbound restore cannot exceed the inbound quantity itself. Diagnosed via
        // 0xf03b/ARBITRUM/ETH where a Pancakeswap LP_EXIT bucket restoration injected
        // 8.65 uncov on a 0.622 inbound, producing uncov > qty (8.0 ghost ETH that the
        // dashboard filters but reports retain). Clamp at source: surplus uncov is
        // dropped, which is the only sane choice when the bucket over-counts.
        BigDecimal clampedUncov = uncoveredQuantity == null ? BigDecimal.ZERO : uncoveredQuantity;
        if (clampedUncov.signum() < 0) {
            clampedUncov = BigDecimal.ZERO;
        }
        if (clampedUncov.compareTo(quantity) > 0) {
            clampedUncov = quantity;
        }
        BigDecimal coveredOfRestore = nonNegative(quantity.subtract(clampedUncov, MC));
        BigDecimal effectiveCost = pegFlooredStablecoinCarryBasis(
                position == null ? null : position.assetKey(), coveredOfRestore, cost);
        BigDecimal effectiveNetCost = pegFlooredStablecoinCarryBasis(
                position == null ? null : position.assetKey(), coveredOfRestore,
                netCost == null ? effectiveCost : netCost);
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(clampedUncov));
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(effectiveCost));
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(effectiveNetCost));
        recomputePerWalletAvco(position);
        if (clampedUncov.signum() > 0 || avco == null) {
            markUnresolved(position);
        } else if (effectiveCost.signum() > 0) {
            clearResolvedPositionFlags(position);
        }
    }

    /**
     * D3 (LP-exit net-lane conservation): restores authoritative {@code lp_receipt_basis_pools}
     * basis onto a returned principal position <b>without</b> applying the R-3* below-peg
     * {@link #pegFlooredStablecoinCarryBasis} to the <b>net</b> lane.
     *
     * <p>The pooled net basis is the reward-discounted lane already carried into the pool at entry
     * (net ≤ tax). Re-flooring it up to {@code qty × $1} on exit (as the generic
     * {@link #restoreToPosition(BigDecimal, PositionState, BigDecimal, BigDecimal, BigDecimal,
     * BigDecimal) 6-arg restore} does) fabricates net basis above the pooled net — the observed
     * {@code net > tax} violations (BASE {@code 450450} USDC net $2,099.45 &gt; tax $2,027.07;
     * OPTIMISM {@code 2984825}; BASE {@code 72791605}). The tax lane keeps the R-3* floor (its
     * contamination guard is unaffected here since LP pool tax basis is authoritative and sits at
     * or above peg on the anchors), while the net lane is carried exactly as pooled and clamped so
     * {@code 0 ≤ net ≤ tax}. This keeps {@code Σ carried net ≤ pool net} (no fabrication) and the
     * {@code net ≤ tax} invariant on every {@code LP_EXIT REALLOCATE_IN} leg.</p>
     */
    public void restoreLpReceiptPoolBasis(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal netCost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco
    ) {
        restoreLpReceiptPoolBasis(quantity, position, cost, netCost, uncoveredQuantity, avco, true);
    }

    /**
     * D1/D3: LP-receipt pool basis restore. {@code applyTaxPegFloor=false} skips the R-3* below-peg
     * floor on the <b>tax</b> lane too — used by the dual-token cross-asset residual carry, where the
     * tax allocation is already authoritative (stable residuals are pre-capped at the $1 peg, volatile
     * residuals absorb the exact remainder), so re-flooring would fabricate basis above the allocation.
     */
    public void restoreLpReceiptPoolBasis(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal netCost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco,
            boolean applyTaxPegFloor
    ) {
        BigDecimal clampedUncov = uncoveredQuantity == null ? BigDecimal.ZERO : uncoveredQuantity;
        if (clampedUncov.signum() < 0) {
            clampedUncov = BigDecimal.ZERO;
        }
        if (clampedUncov.compareTo(quantity) > 0) {
            clampedUncov = quantity;
        }
        BigDecimal coveredOfRestore = nonNegative(quantity.subtract(clampedUncov, MC));
        // Tax lane keeps the R-3* below-peg floor (contamination guard) unless the caller supplies an
        // authoritative allocation. Net lane is NEVER floored: the authoritative pooled net
        // (reward-discounted) must not be inflated up to face value.
        BigDecimal effectiveCost = applyTaxPegFloor
                ? pegFlooredStablecoinCarryBasis(
                        position == null ? null : position.assetKey(), coveredOfRestore, cost)
                : (cost == null ? BigDecimal.ZERO : cost);
        BigDecimal effectiveNetCost = netCost == null ? effectiveCost : netCost;
        if (effectiveNetCost.signum() < 0) {
            effectiveNetCost = BigDecimal.ZERO;
        }
        // net ≤ tax invariant.
        if (effectiveNetCost.compareTo(effectiveCost) > 0) {
            effectiveNetCost = effectiveCost;
        }
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(clampedUncov));
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(effectiveCost));
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(effectiveNetCost));
        recomputePerWalletAvco(position);
        if (clampedUncov.signum() > 0 || avco == null) {
            markUnresolved(position);
        } else if (effectiveCost.signum() > 0) {
            clearResolvedPositionFlags(position);
        }
    }

    /**
     * R-3*: USD-stablecoin peg floor on carried / reallocated basis. (Symmetric peer:
     * {@link #pegCappedStablecoinCarryBasis} adds the U-3 above-peg cap for same-asset carries.)
     *
     * <p>A USD-pegged stablecoin must never carry below $1 per covered unit when the basis arrives
     * via any continuity-carry path ({@code CARRY_IN} / {@code REALLOCATE_IN} /
     * {@code LENDING_WITHDRAW}, the Bybit FUND↔EARN↔UTA sub-ledger moves, and the bridge /
     * pending-late-attach inbound paths). A depressed source AVCO (e.g. borrow proceeds, an
     * on-chain USD corridor pool entering at $0.55, or a bridge inbound carried at $0.89) must not
     * propagate a sub-peg basis through carries — otherwise the receiving sub-ledger settles below
     * $1 (the observed {@code BYBIT:…:FUND} USDT at $0.846, or {@code BRIDGE_IN} USDC at $0.8874)
     * and disposals book a fabricated gain.
     *
     * <p>Scope discipline: this floors only carried / reallocated basis. Fresh sub-peg
     * {@code ACQUIRE} legs are NOT touched (they keep their genuine acquisition price via
     * {@link #applyBuy}). The homoglyph / confusable-symbol guard is inherited from
     * {@link CanonicalAssetCatalog#isUsdStablecoinBySymbol} (confusable lookalikes return false),
     * so a spoofed {@code UЅДС} can never be floored to $1.
     *
     * @param assetKey         destination asset key (its symbol drives the USD-stablecoin test)
     * @param coveredQuantity  the covered (basis-backed) quantity portion of the carry
     * @param carryBasisUsd    the carried cost basis about to be applied to the position
     * @return {@code carryBasisUsd}, or {@code coveredQuantity × $1} when the carry is a USD
     * stablecoin whose covered per-unit basis is materially below peg
     */
    public BigDecimal pegFlooredStablecoinCarryBasis(
            AssetKey assetKey,
            BigDecimal coveredQuantity,
            BigDecimal carryBasisUsd
    ) {
        BigDecimal effectiveCost = carryBasisUsd == null ? BigDecimal.ZERO : carryBasisUsd;
        if (assetKey == null
                || !CanonicalAssetCatalog.isUsdStablecoinBySymbol(assetKey.assetSymbol())) {
            return effectiveCost;
        }
        BigDecimal covered = coveredQuantity == null ? BigDecimal.ZERO : coveredQuantity;
        if (covered.signum() <= 0) {
            return effectiveCost;
        }
        // Activate only for carries materially below peg (covered basis < threshold × covered units),
        // so near-peg conversion / bridge artifacts and the residual USDC shortfall stay conserved.
        BigDecimal activationCeiling = covered.multiply(STABLECOIN_CARRY_PEG_FLOOR_THRESHOLD, MC);
        if (effectiveCost.compareTo(activationCeiling) >= 0) {
            return effectiveCost;
        }
        // Peg floor = covered units × $1.
        return covered.multiply(STABLECOIN_PEG_UNIT_USD, MC);
    }

    /**
     * U-3: USD-stablecoin peg <em>cap</em> on a same-asset continuity carry — the symmetric peer of
     * the R-3* floor in {@link #pegFlooredStablecoinCarryBasis}.
     *
     * <p>A USD-pegged stablecoin can never carry per-unit basis above the $1 peg when it is acquired
     * via a <b>same-asset</b> vault / lending withdrawal continuity carry ({@code VAULT_WITHDRAW} /
     * {@code LENDING_WITHDRAW} / {@code REALLOCATE_IN} / {@code CARRY_IN} that yields the canonical
     * stablecoin from a single-asset USD vault/lending position). ERC4626 / EVK vault-share-rate
     * contamination (e.g. {@code FUSDC}/{@code GTUSDCC}/{@code MCUSDC}/{@code EUSDC-2} round-trips
     * carrying USDC basis at $1.27–$3,021/unit) and yield-inflated basis are clamped down to peg, so
     * the withdrawn stablecoin disposes at ≈$0 realised (peg in ≈ peg out). Genuine yield surfaces as
     * quantity, never as above-peg per-unit basis.
     *
     * <p><b>Scope discipline (conservation):</b> the cap is applied <em>only</em> by callers that
     * have confirmed the carry is a same-asset stablecoin continuity (no cross-asset basis carried).
     * A cross-asset LP exit (e.g. a WETH/USDC pool fully exited as USDC, which legitimately carries
     * the pool's combined basis above $1/unit) must NOT be capped — clamping it would destroy the
     * cross-asset basis and fabricate a gain. The homoglyph / confusable-symbol guard and the
     * non-stablecoin exemption are inherited from
     * {@link CanonicalAssetCatalog#isUsdStablecoinBySymbol}.
     *
     * @param assetKey         destination asset key (its symbol drives the USD-stablecoin test)
     * @param coveredQuantity  the covered (basis-backed) quantity portion of the carry
     * @param carryBasisUsd    the carried cost basis about to be applied to the position
     * @return {@code carryBasisUsd}, or {@code coveredQuantity × $1} when the carry is a USD
     * stablecoin whose covered per-unit basis is above peg
     */
    public BigDecimal pegCappedStablecoinCarryBasis(
            AssetKey assetKey,
            BigDecimal coveredQuantity,
            BigDecimal carryBasisUsd
    ) {
        BigDecimal effectiveCost = carryBasisUsd == null ? BigDecimal.ZERO : carryBasisUsd;
        if (assetKey == null
                || !CanonicalAssetCatalog.isUsdStablecoinBySymbol(assetKey.assetSymbol())) {
            return effectiveCost;
        }
        BigDecimal covered = coveredQuantity == null ? BigDecimal.ZERO : coveredQuantity;
        if (covered.signum() <= 0) {
            return effectiveCost;
        }
        BigDecimal pegBasis = covered.multiply(STABLECOIN_PEG_UNIT_USD, MC);
        if (effectiveCost.compareTo(pegBasis) > 0) {
            return pegBasis;
        }
        return effectiveCost;
    }

    /**
     * Cycle/16 R6: Generic spot-basis fallback for unbacked inbound TRANSFER legs.
     *
     * <p>When continuity carry (or LP composite restore) leaves {@code uncovDelta > 0} on an
     * inbound leg that carries a resolved spot quote from normalization, promote the unbacked
     * portion to basis = qty × spot. Idempotent — acts only on uncov added by this flow.</p>
     */
    /**
     * Cycle/18 R9: When authoritative carry arrives for a pending inbound, replace the exact
     * provisional basis captured at materialisation (and any later spot-fallback promotion)
     * instead of stacking carry on top or using whole-position AVCO heuristics.
     */
    /**
     * ADR-040 Change 2: 3-arg wrapper kept for callers that don't distinguish net carry — delegates
     * to the 4-arg overload with {@code netCarryBasisUsd = carryBasisUsd} (net mirrors tax).
     */
    public void applyAuthoritativeLateInboundCarryBasis(
            PositionState destination,
            BigDecimal provisionalBasisUsd,
            BigDecimal carryBasisUsd
    ) {
        applyAuthoritativeLateInboundCarryBasis(destination, provisionalBasisUsd, carryBasisUsd, carryBasisUsd);
    }

    /**
     * ADR-040 Change 2: net-lane-aware late-attach. Subtracts the same provisional from both lanes
     * (the provisional was booked identically to tax and net at materialisation time), then adds the
     * authoritative carry basis to the tax lane and {@code netCarryBasisUsd} to the net lane.
     */
    public void applyAuthoritativeLateInboundCarryBasis(
            PositionState destination,
            BigDecimal provisionalBasisUsd,
            BigDecimal carryBasisUsd,
            BigDecimal netCarryBasisUsd
    ) {
        if (destination == null || carryBasisUsd == null || carryBasisUsd.signum() <= 0) {
            return;
        }
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        BigDecimal effectiveNetCarry = netCarryBasisUsd != null ? netCarryBasisUsd : carryBasisUsd;
        if (provisional.signum() > 0) {
            destination.setTotalCostBasisUsd(
                    destination.totalCostBasisUsd().subtract(provisional, MC).add(carryBasisUsd, MC)
            );
            destination.setNetTotalCostBasisUsd(
                    destination.netTotalCostBasisUsd().subtract(provisional, MC).add(effectiveNetCarry, MC)
            );
            recomputePerWalletAvco(destination);
            return;
        }
        destination.setTotalCostBasisUsd(destination.totalCostBasisUsd().add(carryBasisUsd, MC));
        destination.setNetTotalCostBasisUsd(destination.netTotalCostBasisUsd().add(effectiveNetCarry, MC));
        recomputePerWalletAvco(destination);
    }

    public void applyInboundShortfallSpotFallback(
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot before
    ) {
        applyInboundShortfallSpotFallback(null, flow, position, before);
    }

    /**
     * F-5(a): promotes the basisless (uncovered) portion of an inbound TRANSFER leg to a USD basis
     * so a zero-/sub-market-basis corridor inflow can never dilute the pooled AVCO and fabricate a
     * later disposal gain.
     *
     * <p>The promotion price is, in priority order: the flow's own resolved spot price (e.g. a
     * pegged-native CMETH receipt that already carries a market price); otherwise the
     * market-at-timestamp resolved by {@link ReplayMarketAuthority} for the leg's block time
     * (BRIDGE_IN / STAKING_DEPOSIT REALLOCATE_IN / INTERNAL_TRANSFER CARRY_IN / Bybit
     * collapsed-asset carry-ins with no paired OUT source). The basis added here is recorded as
     * provisional by the dispatcher, so a later authoritative paired carry still replaces it.</p>
     *
     * <p>Fail-safe: when neither a flow price nor a market-at-timestamp quote can be resolved the
     * uncovered quantity is left untouched (flagged incomplete-history) rather than fabricating a
     * basis — i.e. it is excluded from AVCO until a real source/price is found.</p>
     */
    public void applyInboundShortfallSpotFallback(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot before
    ) {
        if (flow == null
                || position == null
                || before == null
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() <= 0
                || flow.getAssetSymbol() == null) {
            return;
        }
        BigDecimal beforeUncov = before.uncoveredQuantity() == null ? BigDecimal.ZERO : before.uncoveredQuantity();
        BigDecimal afterUncov = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal uncovDelta = afterUncov.subtract(beforeUncov, MC);
        if (uncovDelta.signum() <= 0) {
            return;
        }
        BigDecimal flowQuantity = flow.getQuantityDelta().abs();
        BigDecimal coveredPromotion = uncovDelta.min(flowQuantity);
        if (coveredPromotion.signum() <= 0) {
            return;
        }
        // B2b: a sourceless external inbound must stay uncovered — never promoted at a fabricated
        // market-at-arrival price. Forcing resolved=null routes it onto the same PENDING /
        // incomplete-history path a canonical inbound takes when no quote resolves (RC-7 below).
        ResolvedSpotPrice resolved = UncoveredExternalInboundSupport.isBasisUnknownInbound(transaction)
                ? null
                : resolveInboundSpotUnitPrice(transaction, flow);
        if (resolved == null) {
            // RC-7: a bridge / corridor CARRY_IN whose source carry is empty (no covering basis)
            // must never settle silently at avco $0. The covering carry already booked this leg as
            // uncovered (markUnresolved); when the leg is a cross-network-priceable canonical asset
            // (e.g. ETH on an ETH-native L2 such as LINEA) and neither a flow price nor a
            // market-at-timestamp quote (incl. the cross-network canonical fallback in
            // ReplayMarketAuthority.resolve) could be resolved, route it explicitly to PENDING /
            // incomplete-history — symmetric with RC-3's REPLAY_INBOUND_UNRESOLVED_CANONICAL in
            // materializePendingInbound. No double application with F-5(a): the spot promotion above
            // is skipped precisely because no price resolved, so this only flags state.
            // Guard against double-flagging: an inbound-first bridge/corridor leg is already marked
            // unresolved by materializePendingInbound (RC-3) and is cleared by exactly one
            // resolveTemporaryUnresolved when its late carry attaches. Re-marking here would inflate
            // unresolvedFlagCount so the single decrement can no longer clear it. Only flag a leg that
            // is NOT already pending — i.e. a genuinely empty-source carry-in with no covering carry.
            if (CanonicalAssetCatalog.isCrossNetworkPriceResolvable(flow.getAssetSymbol())
                    && !position.hasIncompleteHistory()) {
                markUnresolved(position);
                log.warn(
                        "REPLAY_INBOUND_UNRESOLVED_CANONICAL wallet={} network={} asset={} qty={} "
                                + "reason=no_cross_network_quote route=PENDING source=carry_in",
                        position.assetKey().walletAddress(),
                        transaction == null ? null : transaction.getNetworkId(),
                        flow.getAssetSymbol(),
                        coveredPromotion
                );
            }
            return;
        }
        BigDecimal addedBasis = coveredPromotion.multiply(resolved.unitPriceUsd(), MC);
        position.setUncoveredQuantity(nonNegative(afterUncov.subtract(coveredPromotion, MC)));
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(addedBasis, MC));
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(addedBasis, MC));
        recomputePerWalletAvco(position);
        if (position.uncoveredQuantity().signum() == 0) {
            resolveTemporaryUnresolved(position);
            clearResolvedPositionFlags(position);
        }
        log.info(
                "REPLAY_INBOUND_SPOT_FALLBACK wallet={} asset={} qty={} price={} authority={} usdAdded={}",
                position.assetKey().walletAddress(),
                flow.getAssetSymbol(),
                coveredPromotion,
                resolved.unitPriceUsd(),
                resolved.authority(),
                addedBasis
        );
    }

    /**
     * Resolves the unit price for promoting an uncovered inbound TRANSFER leg: the flow's own
     * resolved spot price first (preserves the Cycle/15 pegged-native behaviour and keeps the
     * null-authority test path working), then a market-at-timestamp quote from the replay market
     * authority. Returns {@code null} when no trustworthy price exists.
     */
    private ResolvedSpotPrice resolveInboundSpotUnitPrice(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (flow.getUnitPriceUsd() != null
                && flow.getUnitPriceUsd().signum() > 0
                && flow.getPriceSource() != null
                && flow.getPriceSource() != PriceSource.UNKNOWN) {
            return new ResolvedSpotPrice(flow.getUnitPriceUsd(), "FLOW");
        }
        if (replayMarketAuthority != null && transaction != null) {
            return replayMarketAuthority.resolve(transaction, flow)
                    .filter(price -> price.unitPriceUsd() != null && price.unitPriceUsd().signum() > 0)
                    .map(price -> new ResolvedSpotPrice(price.unitPriceUsd(), price.authority().name()))
                    .orElse(null);
        }
        return null;
    }

    private record ResolvedSpotPrice(BigDecimal unitPriceUsd, String authority) {
    }

    /**
     * Cycle/15 R5 F3: Pegged-native spot-basis fallback — delegates to
     * {@link #applyInboundShortfallSpotFallback} after pegged-native guard.
     */
    public void applyPeggedNativeSpotFallback(
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot before
    ) {
        if (flow == null
                || flow.getAssetSymbol() == null
                || !CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())) {
            return;
        }
        applyInboundShortfallSpotFallback(flow, position, before);
    }

    public void markUnresolved(PositionState position) {
        position.setHasIncompleteHistory(true);
        position.setHasUnresolvedFlags(true);
        position.setUnresolvedFlagCount(position.unresolvedFlagCount() + 1);
    }

    public void recordQuantityShortfall(PositionState position, BigDecimal quantityShortfall) {
        if (quantityShortfall == null || quantityShortfall.signum() <= 0) {
            return;
        }
        position.setQuantityShortfall(position.quantityShortfall().add(quantityShortfall));
        markUnresolved(position);
    }

    public QuantityConsumption consumeQuantity(PositionState position, BigDecimal requestedQuantity) {
        BigDecimal availableQuantity = position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        BigDecimal availableUncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal appliedQuantity = requestedQuantity.min(availableQuantity);
        BigDecimal uncoveredQuantity = appliedQuantity.min(availableUncovered);
        BigDecimal coveredQuantity = nonNegative(appliedQuantity.subtract(uncoveredQuantity, MC));
        BigDecimal externalShortfallQuantity = nonNegative(requestedQuantity.subtract(appliedQuantity, MC));
        BigDecimal newQuantity = nonNegative(availableQuantity.subtract(appliedQuantity, MC));
        BigDecimal newUncovered = nonNegative(availableUncovered.subtract(uncoveredQuantity, MC));
        // Cycle/15 R5 F2: zombie-uncov clamp. A position whose physical balance dropped to
        // zero cannot retain uncovered residue from a prior invariant breach. Drop any
        // surplus uncovered quantity so reports stay sane after rebuild.
        if (newQuantity.signum() == 0 && newUncovered.signum() > 0) {
            newUncovered = BigDecimal.ZERO;
        }
        position.setQuantity(newQuantity);
        position.setUncoveredQuantity(newUncovered);
        if (newQuantity.signum() == 0 && position.totalCostBasisUsd().signum() > 0) {
            position.setTotalCostBasisUsd(BigDecimal.ZERO);
            position.setPerWalletAvco(null);
            position.setNetTotalCostBasisUsd(BigDecimal.ZERO);
            position.setPerWalletNetAvco(null);
        }
        return new QuantityConsumption(appliedQuantity, coveredQuantity, uncoveredQuantity, externalShortfallQuantity);
    }

    /**
     * Cycle/17 R7: qty=0 positions must not retain orphan basis (observed after wrapper REALLOCATE_OUT
     * when effectiveRemovalAvco returned zero).
     */
    public void purgeOrphanBasisWhenEmpty(PositionState position) {
        if (position == null) {
            return;
        }
        if (position.quantity() == null || position.quantity().signum() != 0) {
            return;
        }
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        position.setPerWalletAvco(null);
        position.setNetTotalCostBasisUsd(BigDecimal.ZERO);
        position.setPerWalletNetAvco(null);
    }

    private QuantityConsumption consumeQuantityCoveredFirst(PositionState position, BigDecimal requestedQuantity) {
        BigDecimal availableQuantity = position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        BigDecimal availableUncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal availableCovered = nonNegative(availableQuantity.subtract(availableUncovered, MC));
        BigDecimal appliedQuantity = requestedQuantity.min(availableQuantity);
        BigDecimal coveredQuantity = appliedQuantity.min(availableCovered);
        BigDecimal uncoveredQuantity = nonNegative(appliedQuantity.subtract(coveredQuantity, MC));
        BigDecimal externalShortfallQuantity = nonNegative(requestedQuantity.subtract(appliedQuantity, MC));
        BigDecimal newQuantity = nonNegative(availableQuantity.subtract(appliedQuantity, MC));
        BigDecimal newUncovered = nonNegative(availableUncovered.subtract(uncoveredQuantity, MC));
        // Cycle/15 R5 F2: zombie-uncov clamp (covered-first variant). Same invariant.
        if (newQuantity.signum() == 0 && newUncovered.signum() > 0) {
            newUncovered = BigDecimal.ZERO;
        }
        position.setQuantity(newQuantity);
        position.setUncoveredQuantity(newUncovered);
        if (newQuantity.signum() == 0 && position.totalCostBasisUsd().signum() > 0) {
            position.setTotalCostBasisUsd(BigDecimal.ZERO);
            position.setPerWalletAvco(null);
            position.setNetTotalCostBasisUsd(BigDecimal.ZERO);
            position.setPerWalletNetAvco(null);
        }
        return new QuantityConsumption(appliedQuantity, coveredQuantity, uncoveredQuantity, externalShortfallQuantity);
    }

    public void recomputePerWalletAvco(PositionState position) {
        BigDecimal coveredQuantity = nonNegative(position.quantity().subtract(position.uncoveredQuantity(), MC));
        position.setPerWalletAvco(coveredQuantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd(), coveredQuantity));
        position.setPerWalletNetAvco(coveredQuantity.signum() == 0
                ? null
                : safeDivide(position.netTotalCostBasisUsd(), coveredQuantity));
    }

    private static boolean isZeroNetCostAcquisition(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        return ZeroCostAcquisitionSupport.isZeroCostAcquisition(transaction.getType());
    }

    /**
     * Cycle/15 Cluster A: stale {@code hasUnresolvedFlags} from earlier orphan materialisations
     * must not permanently mark a fully covered position as non-authoritative for dashboard AVCO.
     *
     * <p>Secondary path: fully-sold-out positions where qty=0, uncovered=0, and shortfall=0
     * (e.g. USDT sold out with no remaining basis gap) must also have flags cleared. Without
     * this path the early-return {@code quantity <= 0} guard blocks flag clearing permanently
     * on sold-out positions.
     */
    void clearResolvedPositionFlags(PositionState position) {
        if (position == null) {
            return;
        }
        BigDecimal quantity = position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal shortfall = position.quantityShortfall() == null ? BigDecimal.ZERO : position.quantityShortfall();
        if (quantity.signum() == 0 && uncovered.signum() == 0 && shortfall.signum() == 0) {
            position.setHasIncompleteHistory(false);
            position.setHasUnresolvedFlags(false);
            position.setUnresolvedFlagCount(0);
            return;
        }
        if (quantity.signum() <= 0 || uncovered.signum() > 0) {
            return;
        }
        position.setHasIncompleteHistory(false);
        position.setHasUnresolvedFlags(false);
        position.setUnresolvedFlagCount(0);
        position.setQuantityShortfall(BigDecimal.ZERO);
    }

    public void resolveTemporaryUnresolved(PositionState position) {
        if (position.unresolvedFlagCount() > 0) {
            position.setUnresolvedFlagCount(position.unresolvedFlagCount() - 1);
        }
        if (position.unresolvedFlagCount() == 0) {
            position.setHasIncompleteHistory(false);
            position.setHasUnresolvedFlags(false);
        }
    }

    private static boolean hasKnownPrice(NormalizedTransaction.Flow flow) {
        return flow.getUnitPriceUsd() != null
                && flow.getPriceSource() != null
                && flow.getPriceSource() != PriceSource.UNKNOWN;
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
