package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.QuantityConsumption;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
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

    public GenericFlowReplayEngine() {
        this(null);
    }

    public GenericFlowReplayEngine(@Autowired(required = false) ReplayMarketAuthority replayMarketAuthority) {
        this.replayMarketAuthority = replayMarketAuthority;
    }

    public void applyBuy(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (hasKnownPrice(flow)) {
            applyBuyWithAcquisitionCost(flow, position, quantity.multiply(flow.getUnitPriceUsd(), MC));
            return;
        }
        // Cycle/19: USD stablecoin $1 fallback for unpriced BUY legs.
        if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
            applyBuyWithAcquisitionCost(flow, position, quantity);
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
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal cost = acquisitionCostUsd == null ? BigDecimal.ZERO : acquisitionCostUsd;
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(cost));
        position.setQuantity(position.quantity().add(quantity));
        recomputePerWalletAvco(position);
    }

    public void applySell(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfSale = position.perWalletAvco();
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal soldCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (avcoAtTimeOfSale != null && soldCoveredQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(avcoAtTimeOfSale);
            BigDecimal relievedCost = soldCoveredQuantity.multiply(avcoAtTimeOfSale, MC);
            position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(relievedCost, MC)));
            if (hasKnownPrice(flow)
                    && consumption.externalShortfallQuantity().signum() == 0
                    && consumption.uncoveredQuantity().signum() == 0) {
                BigDecimal realised = flow.getUnitPriceUsd().subtract(avcoAtTimeOfSale, MC).multiply(soldCoveredQuantity, MC);
                flow.setRealisedPnlUsd(realised);
                position.setTotalRealisedPnlUsd(position.totalRealisedPnlUsd().add(realised));
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
        if (replayMarketAuthority != null && transaction != null) {
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
        if (hasKnownPrice(flow)) {
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
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(quantity));
        markUnresolved(position);
        recomputePerWalletAvco(position);
        return Optional.of(BigDecimal.ZERO);
    }

    public CarryTransfer removeFromPosition(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal effectiveAvco = effectiveRemovalAvco(position);
        QuantityConsumption consumption = consumeQuantityCoveredFirst(position, requestedQuantity);
        BigDecimal cost = effectiveAvco == null
                ? BigDecimal.ZERO
                : consumption.coveredQuantity().multiply(effectiveAvco, MC);
        // Cycle/17 R7: a full-position removal must drain all remaining basis into the carry.
        // AVCO-derived cost can under-shoot stored basis on wrapper REALLOCATE_OUT (zkSync WETH
        // seq≈7581: basisDelta=0 while qty→0, orphan basis inflated AMANWETH AVCO).
        if (position.quantity().signum() == 0 && position.totalCostBasisUsd().signum() > 0) {
            cost = position.totalCostBasisUsd();
        }
        position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(cost, MC)));
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
        return new CarryTransfer(
                requestedQuantity,
                consumption.coveredQuantity(),
                requestedQuantity.subtract(consumption.coveredQuantity(), MC),
                cost,
                carryAvco,
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

        if (availableQty.signum() <= 0 || totalCost.signum() <= 0) {
            return removeFromPosition(flow, position);
        }

        BigDecimal appliedQty = requestedQuantity.min(availableQty);
        BigDecimal shortfall = nonNegative(requestedQuantity.subtract(appliedQty, MC));
        BigDecimal proportion = appliedQty.divide(availableQty, MC).min(BigDecimal.ONE);
        BigDecimal proportionalCost = totalCost.multiply(proportion, MC);
        BigDecimal proportionalUncov = availableUncov.multiply(proportion, MC);

        BigDecimal newQty = nonNegative(availableQty.subtract(appliedQty, MC));
        BigDecimal newUncov = nonNegative(availableUncov.subtract(proportionalUncov, MC));
        if (newQty.signum() == 0) {
            newUncov = BigDecimal.ZERO;
        }

        position.setQuantity(newQty);
        position.setUncoveredQuantity(newUncov);
        position.setTotalCostBasisUsd(nonNegative(totalCost.subtract(proportionalCost, MC)));
        purgeOrphanBasisWhenEmpty(position);
        recomputePerWalletAvco(position);

        if (shortfall.signum() > 0) {
            recordQuantityShortfall(position, shortfall);
        }

        BigDecimal carryAvco = appliedQty.signum() > 0
                ? proportionalCost.divide(appliedQty, MC)
                : null;

        return new CarryTransfer(
                requestedQuantity,
                appliedQty,
                BigDecimal.ZERO,
                proportionalCost,
                carryAvco,
                false,
                position.assetKey()
        );
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
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(clampedUncov));
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(effectiveCost));
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
    public void applyAuthoritativeLateInboundCarryBasis(
            PositionState destination,
            BigDecimal provisionalBasisUsd,
            BigDecimal carryBasisUsd
    ) {
        if (destination == null || carryBasisUsd == null || carryBasisUsd.signum() <= 0) {
            return;
        }
        BigDecimal provisional = provisionalBasisUsd == null ? BigDecimal.ZERO : provisionalBasisUsd;
        if (provisional.signum() > 0) {
            destination.setTotalCostBasisUsd(
                    destination.totalCostBasisUsd().subtract(provisional, MC).add(carryBasisUsd, MC)
            );
            return;
        }
        destination.setTotalCostBasisUsd(destination.totalCostBasisUsd().add(carryBasisUsd, MC));
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
        ResolvedSpotPrice resolved = resolveInboundSpotUnitPrice(transaction, flow);
        if (resolved == null) {
            return;
        }
        BigDecimal addedBasis = coveredPromotion.multiply(resolved.unitPriceUsd(), MC);
        position.setUncoveredQuantity(nonNegative(afterUncov.subtract(coveredPromotion, MC)));
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(addedBasis, MC));
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
        }
        return new QuantityConsumption(appliedQuantity, coveredQuantity, uncoveredQuantity, externalShortfallQuantity);
    }

    public void recomputePerWalletAvco(PositionState position) {
        BigDecimal coveredQuantity = nonNegative(position.quantity().subtract(position.uncoveredQuantity(), MC));
        position.setPerWalletAvco(coveredQuantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd(), coveredQuantity));
    }

    /**
     * Cycle/15 Cluster A: stale {@code hasUnresolvedFlags} from earlier orphan materialisations
     * must not permanently mark a fully covered position as non-authoritative for dashboard AVCO.
     */
    void clearResolvedPositionFlags(PositionState position) {
        if (position == null) {
            return;
        }
        BigDecimal quantity = position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        if (quantity.signum() <= 0 || uncovered.signum() > 0) {
            return;
        }
        position.setHasIncompleteHistory(false);
        position.setHasUnresolvedFlags(false);
        position.setUnresolvedFlagCount(0);
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
