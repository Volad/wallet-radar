package com.walletradar.application.costbasis.breakeven;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ADR-062 §1 break-even (effective-cost) calculator. Pure read-model derivation over
 * already-aggregated per-family inputs; no replay/AVCO/RPC/Mongo effect.
 *
 * <p>ADR-062 Wave 3 (2026-07-23) single-metric spec (§5 of the ETH-family plan):</p>
 * <ul>
 *   <li><b>Numerator lane discipline (ADR-062 2026-07-24 amendment).</b> {@code heldBasis} follows
 *       the configured {@link OffsetLane} via {@link BreakEvenLaneSelector#chooseLaneBasis}: the
 *       <b>Net</b> lane ({@link FamilyBreakEvenInput#netBasisUsd()}) under NET so held zero-cost
 *       income (rewards/airdrops/yield received-and-still-held) is credited as free, and the
 *       <b>Market</b> lane ({@link FamilyBreakEvenInput#marketBasisUsd()}) under MARKET
 *       (byte-identical to the pre-amendment behaviour). Under NET this reduces to
 *       {@code effectiveBasis = netBasis − netRealizedProfit} with no double-count: every unit is
 *       either HELD (in the net basis, reward = $0) or DISPOSED (in netRealized), never both. The
 *       offset that discounts the numerator is the configured-lane realized profit + banked income,
 *       computed as {@code net} when the lane is NET (= market + (net − market)) and {@code market}
 *       when MARKET; it is byte-identical across the numerator-lane change.</li>
 *   <li><b>AC-8 intra-cluster loss-floor carve-out.</b> Realized amounts attributed via a
 *       {@code CLUSTER:*_STAKING} source bypass the {@code max(offset, 0)} loss floor, so an
 *       intra-cluster staking conversion loss (e.g. cmETH→ETH −$197.74) <b>raises</b> break-even
 *       above average cost. External/self and explicit-family losses stay floored (a standalone
 *       USDT trading loss never inflates its effective cost above AVCO).</li>
 *   <li><b>AC-9 / D8 held-exposure fold.</b> A child attribution flagged {@code foldHeldExposure}
 *       contributes its held basis (as a lane-tagged Market+Net pair, selected on the same lane as
 *       the self numerator) and ETH-equivalent covered quantity into the parent target's break-even
 *       basis and denominator (GMX GM ETH/USD, Pendle PT-ETH ETH-share).</li>
 *   <li><b>AC-7 rate-adjusted denominator.</b> The denominator is the ETH-equivalent covered
 *       quantity supplied by the caller ({@link FamilyBreakEvenInput#coveredQuantity()}), already
 *       rate-adjusted (aTokens/WETH 1:1; staked derivatives divided by their live staking rate).
 *       A {@code null} covered quantity is <b>fail-closed</b>: break-even and average cost are
 *       {@code null} (never silently treated as 1:1).</li>
 *   <li><b>R2 (no cap).</b> Break-even is not capped at average cost; it may exceed it when
 *       uncovered fees/interest were paid or an intra-cluster loss was carved out.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BreakEvenCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * ADR-082 / FB-01 defense-in-depth. The primary fix is the NET-lane re-base in
     * {@code ReplayDispatcher} (no basis recycling); this read-model guard is a coarse safety net
     * that fails a target's cluster-attributed NET offset <b>closed to the Market lane</b> when the
     * credited income ({@code netRealized − marketRealized}) is implausibly large — a signature of a
     * NET-lane recycling regression. It is a strict no-op on correct post-fix inputs (staking-cluster
     * income is dust) and never touches standalone (non-cluster) reward families, so genuine
     * "rewards reduce cost for free" credit via {@code offsetLane=NET} is preserved. Precise
     * separation of genuine zero-cost income from artifact income requires dedicated ledger
     * instrumentation (future work); until then this guard only trips on runaway inflation.
     */
    private static final BigDecimal INCOME_PLAUSIBILITY_ABSOLUTE_FLOOR_USD = new BigDecimal("5000");
    private static final BigDecimal INCOME_PLAUSIBILITY_MARKET_MULTIPLE = new BigDecimal("3");

    private final BreakEvenAttributionService attributionService;

    public Map<String, BreakEvenResult> compute(Collection<FamilyBreakEvenInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Map.of();
        }
        OffsetLane lane = attributionService.offsetLane();
        Map<String, String> targetByFamily = new LinkedHashMap<>();
        // AC-8: cluster-attributed realized amounts (intra-CLUSTER:*_STAKING) bypass the loss floor;
        // self/external amounts are floored at 0. Keep the two accumulators separate per target.
        Map<String, LaneAmount> clusterAttributed = new LinkedHashMap<>();
        Map<String, LaneAmount> externalAttributed = new LinkedHashMap<>();
        // AC-9 / D8: folded held exposure (Market-lane basis + ETH-equivalent covered quantity) from
        // children flagged foldHeldExposure. A null child covered quantity fails the target closed.
        Map<String, FoldAccumulator> foldByTarget = new LinkedHashMap<>();
        Set<String> foldDenominatorFailClosed = new LinkedHashSet<>();

        for (FamilyBreakEvenInput input : inputs) {
            if (input == null || input.familyIdentity() == null || input.familyIdentity().isBlank()) {
                continue;
            }
            String family = input.familyIdentity();
            BreakEvenAttributionService.Attribution attribution =
                    attributionService.resolve(family, input.representativeSymbol());
            String target = attribution.target();
            targetByFamily.put(family, target);
            boolean redirected = target != null && !target.equals(family);

            BigDecimal market = zeroIfNull(input.marketRealizedPnlUsd());
            BigDecimal income = zeroIfNull(input.netRealizedPnlUsd()).subtract(market, MC);
            if (redirected && attribution.viaStakingCluster()) {
                clusterAttributed.computeIfAbsent(target, ignored -> new LaneAmount()).add(market, income);
            } else {
                externalAttributed.computeIfAbsent(target, ignored -> new LaneAmount()).add(market, income);
            }

            if (redirected && attribution.foldHeldExposure()) {
                FoldAccumulator fold = foldByTarget.computeIfAbsent(target, ignored -> new FoldAccumulator());
                fold.addBasis(zeroIfNull(input.marketBasisUsd()), zeroIfNull(input.netBasisUsd()));
                BigDecimal childEthEquiv = input.coveredQuantity();
                if (childEthEquiv == null) {
                    foldDenominatorFailClosed.add(target);
                } else {
                    fold.addEthEquiv(childEthEquiv);
                }
            }
        }

        Map<String, BreakEvenResult> results = new LinkedHashMap<>();
        for (FamilyBreakEvenInput input : inputs) {
            if (input == null || input.familyIdentity() == null || input.familyIdentity().isBlank()) {
                continue;
            }
            String family = input.familyIdentity();
            String target = targetByFamily.get(family);
            boolean redirected = target != null && !target.equals(family);

            LaneAmount cluster = clusterAttributed.getOrDefault(family, LaneAmount.EMPTY);
            LaneAmount external = externalAttributed.getOrDefault(family, LaneAmount.EMPTY);
            FoldAccumulator fold = foldByTarget.get(family);

            // Numerator: lane-selected held basis, plus any folded child held basis (AC-9). Both lanes
            // are assembled (market + net, each with its folded child basis) and the C0 helper picks
            // the numerator by the configured offset lane (ADR-062 2026-07-24). Under NET the Net-lane
            // held basis credits held zero-cost income (rewards/airdrops/yield) as free; under MARKET
            // the Market-lane basis is used, byte-identical to the pre-2026-07-24 behaviour.
            BigDecimal heldBasisMarket = zeroIfNull(input.marketBasisUsd());
            BigDecimal heldBasisNet = zeroIfNull(input.netBasisUsd());
            if (fold != null) {
                heldBasisMarket = heldBasisMarket.add(fold.marketBasis(), MC);
                heldBasisNet = heldBasisNet.add(fold.netBasis(), MC);
            }
            BigDecimal heldBasis = BreakEvenLaneSelector.chooseLaneBasis(lane, heldBasisMarket, heldBasisNet);

            // AC-7: ETH-equivalent (rate-adjusted) covered quantity supplied by the caller. Null →
            // fail-closed. Fold children add their own ETH-equivalent quantity; if any fold child's
            // quantity was unavailable the parent denominator is fail-closed too.
            BigDecimal ethEquivDenominator = input.coveredQuantity();
            boolean denominatorFailClosed = foldDenominatorFailClosed.contains(family);
            if (fold != null && ethEquivDenominator != null && !denominatorFailClosed) {
                ethEquivDenominator = ethEquivDenominator.add(fold.ethEquiv(), MC);
            }

            // Offset lane selection (§5). NET credits realized income (net − market) on top of
            // trading profit; MARKET credits trading profit only. Because the NET amount is
            // reconstructed as market + income, the Market lane is never double-counted.
            // ADR-082 DiD guard: fail the cluster NET income closed to the Market lane when it is
            // implausibly large (NET-lane recycling regression signature). No-op on correct inputs.
            boolean clusterIncomeImplausible = lane == OffsetLane.NET
                    && isImplausibleCreditedIncome(cluster.income(), cluster.market());
            if (clusterIncomeImplausible) {
                log.warn("BREAKEVEN_NET_OFFSET_IMPLAUSIBLE_INCOME family={} target={} "
                                + "clusterIncomeUsd={} clusterMarketUsd={} -> Market-lane fallback (ADR-082 guard)",
                        family, target, cluster.income(), cluster.market());
            }
            BigDecimal clusterOffsetSigned = (lane == OffsetLane.NET && !clusterIncomeImplausible)
                    ? cluster.market().add(cluster.income(), MC)
                    : cluster.market();
            BigDecimal externalOffsetSigned = lane == OffsetLane.NET
                    ? external.market().add(external.income(), MC)
                    : external.market();
            // AC-8: cluster component is UNFLOORED (a staking-conversion loss raises break-even);
            // self/external component keeps the max(offset, 0) loss floor.
            BigDecimal attributedOffset = clusterOffsetSigned.add(externalOffsetSigned.max(BigDecimal.ZERO), MC);

            // Effective basis, average cost, and locked surplus are ALL derived from the SAME
            // chosen-lane held basis so the metric stays internally consistent (E-2/E-5): under NET
            // the numerator is the Net-lane basis (held income credited free); under MARKET it is the
            // Market-lane basis. attributedOffset above is byte-identical in both lanes.
            BigDecimal effectiveBasis = heldBasis.subtract(attributedOffset, MC);

            boolean denominatorUsable = ethEquivDenominator != null
                    && ethEquivDenominator.signum() > 0
                    && !denominatorFailClosed;
            // R2: no average-cost cap; break-even is only floored at 0 (banked profit past cost).
            BigDecimal breakEven = denominatorUsable
                    ? effectiveBasis.max(BigDecimal.ZERO).divide(ethEquivDenominator, MC)
                    : null;
            BigDecimal averageCost = denominatorUsable
                    ? heldBasis.divide(ethEquivDenominator, MC)
                    : null;
            BigDecimal lockedSurplus = effectiveBasis.signum() < 0 ? effectiveBasis.negate() : BigDecimal.ZERO;

            BigDecimal attributedRealizedPnl = cluster.market().add(external.market(), MC);
            BigDecimal incomeReceived = cluster.income().add(external.income(), MC);

            results.put(family, new BreakEvenResult(
                    breakEven,
                    averageCost,
                    effectiveBasis,
                    attributedRealizedPnl,
                    lockedSurplus,
                    incomeReceived,
                    redirected ? target : null
            ));
        }
        return results;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * ADR-082 DiD guard: {@code true} when positive credited income is implausibly large — both
     * above an absolute sanity floor AND above a generous multiple of the tracked market realized
     * P&amp;L. Coarse by design; the NET-lane ledger re-base is the real fix.
     */
    private static boolean isImplausibleCreditedIncome(BigDecimal income, BigDecimal marketRealized) {
        BigDecimal creditedIncome = zeroIfNull(income);
        if (creditedIncome.signum() <= 0) {
            return false;
        }
        if (creditedIncome.compareTo(INCOME_PLAUSIBILITY_ABSOLUTE_FLOOR_USD) <= 0) {
            return false;
        }
        BigDecimal marketMagnitude = zeroIfNull(marketRealized).abs();
        BigDecimal multipleBound = marketMagnitude.multiply(INCOME_PLAUSIBILITY_MARKET_MULTIPLE, MC);
        return creditedIncome.compareTo(multipleBound) > 0;
    }

    /** Market-lane trading P&amp;L plus realized income (net − market) accumulated for one target. */
    private static final class LaneAmount {
        private static final LaneAmount EMPTY = new LaneAmount();
        private BigDecimal market = BigDecimal.ZERO;
        private BigDecimal income = BigDecimal.ZERO;

        private void add(BigDecimal marketDelta, BigDecimal incomeDelta) {
            market = market.add(marketDelta, MC);
            income = income.add(incomeDelta, MC);
        }

        private BigDecimal market() {
            return market;
        }

        private BigDecimal income() {
            return income;
        }
    }

    /**
     * AC-9 / D8 folded held exposure for a target: a lane-tagged basis pair (Market + Net, mirroring
     * {@link LaneAmount}) plus the ETH-equivalent covered quantity. Carrying BOTH lanes lets the
     * numerator assembly pick the folded basis through {@link BreakEvenLaneSelector} rather than a
     * separate {@code if (lane == NET)} branch that could drift from the self held-basis selection
     * (E-4). Inactive today (no {@code foldHeldExposure:true} config maps to a {@code CLUSTER:*}/
     * {@code FAMILY:*} source); plumbed pre-emptively so an activated fold can never mix lanes.
     */
    private static final class FoldAccumulator {
        private BigDecimal marketBasis = BigDecimal.ZERO;
        private BigDecimal netBasis = BigDecimal.ZERO;
        private BigDecimal ethEquiv = BigDecimal.ZERO;

        private void addBasis(BigDecimal marketDelta, BigDecimal netDelta) {
            marketBasis = marketBasis.add(marketDelta, MC);
            netBasis = netBasis.add(netDelta, MC);
        }

        private void addEthEquiv(BigDecimal delta) {
            ethEquiv = ethEquiv.add(delta, MC);
        }

        private BigDecimal marketBasis() {
            return marketBasis;
        }

        private BigDecimal netBasis() {
            return netBasis;
        }

        private BigDecimal ethEquiv() {
            return ethEquiv;
        }
    }

    /**
     * @param familyIdentity   accounting family identity ({@code FAMILY:*}).
     * @param representativeSymbol representative asset symbol used for cluster resolution.
     * @param marketBasisUsd   Market-lane cost basis of covered holdings (numerator under MARKET lane).
     * @param netBasisUsd      Net-lane (real-cash) cost basis of covered holdings (numerator under NET
     *                         lane, ADR-062 2026-07-24). Held zero-cost income (rewards/airdrops/yield
     *                         received-and-still-held) lowers this below {@code marketBasisUsd}, so the
     *                         effective-cost numerator credits it as free. Populated as
     *                         {@code netAvcoUsd × coveredQuantity} (mirror of the market basis) by the
     *                         header producers; a child rollup input passes {@code ZERO}.
     * @param coveredQuantity  <b>ETH-equivalent (rate-adjusted)</b> covered quantity denominator
     *                         (AC-7); {@code null} fails the metric closed rather than assuming 1:1.
     * @param marketRealizedPnlUsd Market-lane (trading-only) realized P&amp;L for this family.
     * @param netRealizedPnlUsd    Net-lane (trading + income) realized P&amp;L for this family.
     */
    public record FamilyBreakEvenInput(
            String familyIdentity,
            String representativeSymbol,
            BigDecimal marketBasisUsd,
            BigDecimal netBasisUsd,
            BigDecimal coveredQuantity,
            BigDecimal marketRealizedPnlUsd,
            BigDecimal netRealizedPnlUsd
    ) {
    }

    /**
     * @param breakEvenUsd              headline "Break-even price" (§5); {@code null} when the
     *                                  ETH-equivalent denominator is unusable (fail-closed / dust).
     * @param averageCostUsd            secondary "Average cost" = chosen-lane heldBasis ÷ ETH-equiv qty
     *                                  (Net under NET, Market under MARKET — same lane as break-even).
     * @param effectiveBasisUsd         chosen-lane heldBasis − attributed offset (may be negative → surplus).
     * @param attributedRealizedPnlUsd  Market-lane realized P&amp;L attributed to this family.
     * @param lockedSurplusUsd          realized profit already past break-even (−effectiveBasis when &lt;0).
     * @param incomeReceivedUsd         realized zero-basis income (net − market) attributed here.
     * @param attributionTargetFamily   parent target this family redirects into; {@code null} when self.
     */
    public record BreakEvenResult(
            BigDecimal breakEvenUsd,
            BigDecimal averageCostUsd,
            BigDecimal effectiveBasisUsd,
            BigDecimal attributedRealizedPnlUsd,
            BigDecimal lockedSurplusUsd,
            BigDecimal incomeReceivedUsd,
            String attributionTargetFamily
    ) {
    }
}
