package com.walletradar.application.costbasis.breakeven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator.BreakEvenResult;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator.FamilyBreakEvenInput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BreakEvenCalculatorTest {

    private static final MathContext MC = MathContext.DECIMAL128;

    // Default classpath lane is NET (ADR-062 2026-07-21 amendment).
    private final BreakEvenCalculator calculator = new BreakEvenCalculator(
            new BreakEvenAttributionService(new BreakEvenAttributionLoader(new ObjectMapper())));
    private final BreakEvenCalculator netCalculator = new BreakEvenCalculator(attributionServiceWithLane("NET"));
    private final BreakEvenCalculator marketCalculator = new BreakEvenCalculator(attributionServiceWithLane("MARKET"));

    private static BreakEvenAttributionService attributionServiceWithLane(String offsetLane) {
        String json = "{\"version\":1,\"offsetLane\":\"" + offsetLane + "\",\"attributions\":["
                + "{\"source\":\"CLUSTER:ETH_STAKING\",\"target\":\"FAMILY:ETH\"},"
                + "{\"source\":\"CLUSTER:SOL_STAKING\",\"target\":\"FAMILY:SOL\"},"
                + "{\"source\":\"CLUSTER:AVAX_STAKING\",\"target\":\"FAMILY:AVAX\"}]}";
        return attributionServiceFrom(json, "inline-" + offsetLane);
    }

    private static BreakEvenAttributionService attributionServiceFrom(String json, String sourceName) {
        BreakEvenAttributionLoader.LoadedBreakEvenAttribution loaded =
                new BreakEvenAttributionLoader(new ObjectMapper())
                        .load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), sourceName);
        return new BreakEvenAttributionService(new BreakEvenAttributionLoader(new ObjectMapper()) {
            @Override
            public LoadedBreakEvenAttribution loadFromClasspath() {
                return loaded;
            }
        });
    }

    @Test
    void selfOnlyFamilyCreditsOwnRealizedPnl() {
        FamilyBreakEvenInput usdc = new FamilyBreakEvenInput(
                "FAMILY:BTC", "BTC",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("2"),
                new BigDecimal("1500"), new BigDecimal("1500"));

        BreakEvenResult result = calculator.compute(List.of(usdc)).get("FAMILY:BTC");

        assertThat(result.attributedRealizedPnlUsd()).isEqualByComparingTo("1500");
        assertThat(result.effectiveBasisUsd()).isEqualByComparingTo("8500");
        assertThat(result.breakEvenUsd()).isEqualByComparingTo("4250");
        assertThat(result.lockedSurplusUsd()).isEqualByComparingTo("0");
        assertThat(result.attributionTargetFamily()).isNull();
    }

    @Test
    void parentReceivesChildRealizedPnlAndChildKeepsMarketAvco() {
        BigDecimal parentBasis = new BigDecimal("3029").multiply(new BigDecimal("3.82"), MC);
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                parentBasis, parentBasis, new BigDecimal("3.82"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput methChild = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH",
                new BigDecimal("5000"), new BigDecimal("5000"), new BigDecimal("2"),
                new BigDecimal("2540"), new BigDecimal("2540"));

        Map<String, BreakEvenResult> results = calculator.compute(List.of(ethParent, methChild));

        BreakEvenResult parent = results.get("FAMILY:ETH");
        assertThat(parent.attributedRealizedPnlUsd()).isEqualByComparingTo("2540");
        assertThat(parent.breakEvenUsd()).isCloseTo(new BigDecimal("2364"), org.assertj.core.data.Offset.offset(new BigDecimal("1")));
        assertThat(parent.attributionTargetFamily()).isNull();

        BreakEvenResult child = results.get("FAMILY:METH");
        assertThat(child.attributedRealizedPnlUsd()).isEqualByComparingTo("0");
        assertThat(child.effectiveBasisUsd()).isEqualByComparingTo("5000");
        assertThat(child.breakEvenUsd()).isEqualByComparingTo("2500");
        assertThat(child.attributionTargetFamily()).isEqualTo("FAMILY:ETH");
    }

    @Test
    void lockedSurplusWhenAttributedPnlExceedsBasis() {
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("0.5"),
                new BigDecimal("1500"), new BigDecimal("1500"));

        BreakEvenResult result = calculator.compute(List.of(eth)).get("FAMILY:ETH");

        assertThat(result.effectiveBasisUsd()).isEqualByComparingTo("-500");
        assertThat(result.breakEvenUsd()).isEqualByComparingTo("0");
        assertThat(result.lockedSurplusUsd()).isEqualByComparingTo("500");
    }

    @Test
    void realizedNetLossDoesNotInflateEffectiveCostAboveAvcoInEitherLane() {
        // USDT-like: mostly spent, small covered qty, with BOTH a Market-lane trading loss (-$116.38)
        // and a Net-lane realized loss (-$1292.60). Without the loss floor NET would compute a wildly
        // negative offset that flips into a huge per-unit figure; with it, effective cost stays at AVCO
        // ($1) in BOTH lanes because a realized loss never raises the cost of the units still held.
        FamilyBreakEvenInput usdt = new FamilyBreakEvenInput(
                "FAMILY:USDT", "USDT",
                new BigDecimal("7.00"), new BigDecimal("7.00"), new BigDecimal("7.00"),
                new BigDecimal("-116.38"), new BigDecimal("-1292.60"));

        for (BreakEvenCalculator laneCalculator : List.of(netCalculator, marketCalculator)) {
            BreakEvenResult result = laneCalculator.compute(List.of(usdt)).get("FAMILY:USDT");

            assertThat(result.attributedRealizedPnlUsd()).isEqualByComparingTo("-116.38");
            assertThat(result.incomeReceivedUsd()).isEqualByComparingTo("-1176.22");
            assertThat(result.effectiveBasisUsd()).isEqualByComparingTo("7.00");
            assertThat(result.breakEvenUsd()).isEqualByComparingTo("1");
            assertThat(result.lockedSurplusUsd()).isEqualByComparingTo("0");
        }
    }

    @Test
    void netLaneIncomeReducesEffectiveCostBelowMarketOnlyResult() {
        // FAMILY:ETH basis $10000 / 3 ETH, Market-lane trading profit $500, realized income $700
        // (net = $1200). NET lane offsets by $1200 → effective basis $8800; MARKET lane offsets by
        // $500 → effective basis $9500. Income component is surfaced separately in both lanes.
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("3"),
                new BigDecimal("500"), new BigDecimal("1200"));

        BreakEvenResult net = netCalculator.compute(List.of(eth)).get("FAMILY:ETH");
        BreakEvenResult market = marketCalculator.compute(List.of(eth)).get("FAMILY:ETH");

        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo("8800");
        assertThat(market.effectiveBasisUsd()).isEqualByComparingTo("9500");
        assertThat(net.effectiveBasisUsd()).isLessThan(market.effectiveBasisUsd());
        assertThat(net.breakEvenUsd()).isLessThan(market.breakEvenUsd());

        // Both components stay reported separately regardless of lane.
        assertThat(net.attributedRealizedPnlUsd()).isEqualByComparingTo("500");
        assertThat(net.incomeReceivedUsd()).isEqualByComparingTo("700");
        assertThat(market.attributedRealizedPnlUsd()).isEqualByComparingTo("500");
        assertThat(market.incomeReceivedUsd()).isEqualByComparingTo("700");
    }

    @Test
    void netLaneLargeIncomeDrivesLockedSurplusAndZeroBreakEven() {
        // FAMILY:ETH basis $1000 / 0.5 ETH, small Market-lane profit $100, large realized income
        // $1500 (net = $1600). NET offset $1600 exceeds basis → effective basis −$600 → break-even $0,
        // locked surplus $600. MARKET offset (only $100) leaves a positive break-even.
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("0.5"),
                new BigDecimal("100"), new BigDecimal("1600"));

        BreakEvenResult net = netCalculator.compute(List.of(eth)).get("FAMILY:ETH");
        BreakEvenResult market = marketCalculator.compute(List.of(eth)).get("FAMILY:ETH");

        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo("-600");
        assertThat(net.lockedSurplusUsd()).isEqualByComparingTo("600");
        assertThat(net.breakEvenUsd()).isEqualByComparingTo("0");

        assertThat(market.effectiveBasisUsd()).isEqualByComparingTo("900");
        assertThat(market.lockedSurplusUsd()).isEqualByComparingTo("0");
        assertThat(market.breakEvenUsd()).isEqualByComparingTo("1800");
    }

    @Test
    void marketLaneIgnoresIncomeMatchingPreAmendmentBehaviour() {
        // MARKET lane discounts effective cost by trading profit only; realized income is surfaced but
        // never subtracted, reproducing the pre-amendment behaviour. The Net-lane basis ($3,000, held
        // reward income lowering it) is deliberately DIFFERENT from the Market-lane basis ($10,000):
        // under MARKET the numerator must ignore it and stay on the Market basis (byte-identical).
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("2"),
                new BigDecimal("1500"), new BigDecimal("4000"));

        BreakEvenResult result = marketCalculator.compute(List.of(eth)).get("FAMILY:ETH");

        assertThat(result.attributedRealizedPnlUsd()).isEqualByComparingTo("1500");
        assertThat(result.incomeReceivedUsd()).isEqualByComparingTo("2500");
        assertThat(result.effectiveBasisUsd()).isEqualByComparingTo("8500");
        assertThat(result.breakEvenUsd()).isEqualByComparingTo("4250");
        assertThat(result.lockedSurplusUsd()).isEqualByComparingTo("0");
    }

    @Test
    void nullOrZeroCoveredQuantityYieldsNullBreakEven() {
        FamilyBreakEvenInput nullQty = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("1000"), new BigDecimal("1000"), null,
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput zeroQty = new FamilyBreakEvenInput(
                "FAMILY:BTC", "BTC", new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        Map<String, BreakEvenResult> results = calculator.compute(List.of(nullQty, zeroQty));

        assertThat(results.get("FAMILY:ETH").breakEvenUsd()).isNull();
        assertThat(results.get("FAMILY:BTC").breakEvenUsd()).isNull();
    }

    @Test
    void incomeIsNetMinusMarketAttributedToTarget() {
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("3"),
                new BigDecimal("500"), new BigDecimal("500"));
        FamilyBreakEvenInput meth = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH",
                new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1"),
                new BigDecimal("200"), new BigDecimal("900"));

        Map<String, BreakEvenResult> results = calculator.compute(List.of(eth, meth));

        // ETH income = (500-500) + (900-200) = 700 (child income rolls into parent target).
        assertThat(results.get("FAMILY:ETH").incomeReceivedUsd()).isEqualByComparingTo("700");
        assertThat(results.get("FAMILY:METH").incomeReceivedUsd()).isEqualByComparingTo("0");
    }

    @Test
    void partitionConservesTotalMarketAndNetRealizedPnlAcrossLane() {
        // Each family resolves to exactly one target (partition, no double count). The Market-lane
        // partition conserves Σ market realized P&L via attributedRealizedPnlUsd; the NET offset lane
        // (attributed + income) conserves Σ net realized P&L. Both invariants hold under NET.
        List<FamilyBreakEvenInput> inputs = List.of(
                new FamilyBreakEvenInput("FAMILY:ETH", "ETH", new BigDecimal("10000"), new BigDecimal("10000"),
                        new BigDecimal("3"), new BigDecimal("500"), new BigDecimal("650")),
                new FamilyBreakEvenInput("FAMILY:METH", "CMETH", new BigDecimal("1000"), new BigDecimal("1000"),
                        new BigDecimal("1"), new BigDecimal("2540"), new BigDecimal("3100")),
                new FamilyBreakEvenInput("FAMILY:STETH", "STETH", new BigDecimal("2000"), new BigDecimal("2000"),
                        new BigDecimal("1"), new BigDecimal("300"), new BigDecimal("420")),
                new FamilyBreakEvenInput("FAMILY:USDC", "USDC", new BigDecimal("5000"), new BigDecimal("5000"),
                        new BigDecimal("5000"), new BigDecimal("120"), new BigDecimal("120")));

        Map<String, BreakEvenResult> results = netCalculator.compute(inputs);

        BigDecimal totalAttributed = results.values().stream()
                .map(BreakEvenResult::attributedRealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalMarketPnl = inputs.stream()
                .map(FamilyBreakEvenInput::marketRealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        assertThat(totalAttributed).isEqualByComparingTo(totalMarketPnl);

        BigDecimal totalOffset = results.values().stream()
                .map(result -> result.attributedRealizedPnlUsd().add(result.incomeReceivedUsd(), MC))
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalNetPnl = inputs.stream()
                .map(FamilyBreakEvenInput::netRealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        assertThat(totalOffset).isEqualByComparingTo(totalNetPnl);
    }

    // ---- AC-8: intra-CLUSTER:*_STAKING loss-floor carve-out --------------------------------------

    @Test
    void ac8IntraClusterLossRaisesBreakEvenAboveAverageCost() {
        // FAMILY:ETH holds $10,000 basis / 3 ETH-eq (average cost $3,333.33). A cmETH→ETH intra-cluster
        // staking-conversion loss of −$197.74 (FAMILY:METH via CLUSTER:ETH_STAKING) is UNFLOORED, so it
        // RAISES the ETH break-even above average cost. A same-magnitude external/self loss stays floored.
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("3"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput methChild = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("-197.74"), new BigDecimal("-197.74"));
        // Self/external control: standalone USDT trading loss must NOT inflate its own effective cost.
        FamilyBreakEvenInput usdtSelfLoss = new FamilyBreakEvenInput(
                "FAMILY:USDT", "USDT", new BigDecimal("17"), new BigDecimal("17"), new BigDecimal("17"),
                new BigDecimal("-50"), new BigDecimal("-50"));

        Map<String, BreakEvenResult> results = netCalculator.compute(List.of(ethParent, methChild, usdtSelfLoss));

        BreakEvenResult eth = results.get("FAMILY:ETH");
        // effectiveBasis = 10000 − (−197.74) = 10197.74; break-even = 10197.74 / 3 = 3399.25 > 3333.33.
        assertThat(eth.effectiveBasisUsd()).isEqualByComparingTo("10197.74");
        assertThat(eth.averageCostUsd()).isCloseTo(new BigDecimal("3333.33"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(eth.breakEvenUsd()).isGreaterThan(eth.averageCostUsd());

        // External/self loss stays floored at AVCO ($1/unit); effective cost never rises above it.
        BreakEvenResult usdt = results.get("FAMILY:USDT");
        assertThat(usdt.effectiveBasisUsd()).isEqualByComparingTo("17");
        assertThat(usdt.breakEvenUsd()).isEqualByComparingTo("1");
    }

    // ---- AC-9 / D8: held-exposure fold -----------------------------------------------------------

    private static final String FOLD_CONFIG =
            "{\"version\":1,\"offsetLane\":\"NET\",\"attributions\":["
                    + "{\"source\":\"FAMILY:GMXGMETHUSD\",\"target\":\"FAMILY:ETH\",\"foldHeldExposure\":true}]}";

    @Test
    void ac9FoldChildAddsBasisAndEthEquivToParentDenominator() {
        BreakEvenCalculator foldCalculator =
                new BreakEvenCalculator(attributionServiceFrom(FOLD_CONFIG, "inline-fold"));
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("11567.39"), new BigDecimal("11567.39"), new BigDecimal("3.822640"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        // GMX GM ETH/USD ETH-share fold: +$411 basis, +0.11 ETH-equivalent covered quantity.
        FamilyBreakEvenInput gmChild = new FamilyBreakEvenInput(
                "FAMILY:GMXGMETHUSD", "GMXGMETHUSD", new BigDecimal("411"), new BigDecimal("411"), new BigDecimal("0.11"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        Map<String, BreakEvenResult> results = foldCalculator.compute(List.of(ethParent, gmChild));
        BreakEvenResult eth = results.get("FAMILY:ETH");

        // Denominator folds to 3.822640 + 0.11 = 3.932640; basis folds to 11567.39 + 411 = 11978.39.
        BigDecimal expectedAvg = new BigDecimal("11978.39").divide(new BigDecimal("3.932640"), MC);
        assertThat(eth.averageCostUsd()).isCloseTo(expectedAvg,
                org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        // The GM ETH-share is priced above the current ETH average (411/0.11 ≈ $3,736/ETH-eq), so folding
        // it in RAISES the blended average cost above the un-folded self-only figure (~$3,026 → ~$3,046).
        BigDecimal selfOnlyAvg = new BigDecimal("11567.39").divide(new BigDecimal("3.822640"), MC);
        assertThat(eth.averageCostUsd()).isGreaterThan(selfOnlyAvg);
    }

    @Test
    void ac9FoldChildWithNullEthEquivFailsParentClosed() {
        BreakEvenCalculator foldCalculator =
                new BreakEvenCalculator(attributionServiceFrom(FOLD_CONFIG, "inline-fold-failclosed"));
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("11567.39"), new BigDecimal("11567.39"), new BigDecimal("3.822640"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        // Fold child with a MISSING ETH-equivalent quantity (e.g. staking rate unavailable): the whole
        // parent metric fails closed rather than silently under-counting the denominator.
        FamilyBreakEvenInput gmChild = new FamilyBreakEvenInput(
                "FAMILY:GMXGMETHUSD", "GMXGMETHUSD", new BigDecimal("411"), new BigDecimal("411"), null,
                BigDecimal.ZERO, BigDecimal.ZERO);

        Map<String, BreakEvenResult> results = foldCalculator.compute(List.of(ethParent, gmChild));
        BreakEvenResult eth = results.get("FAMILY:ETH");

        assertThat(eth.breakEvenUsd()).isNull();
        assertThat(eth.averageCostUsd()).isNull();
    }

    // ---- ADR-082 defense-in-depth: implausible cluster NET income guard -------------------------

    @Test
    void ad82GuardFailsImplausibleClusterIncomeClosedToMarketLane() {
        // FAMILY:ETH parent basis $11,543 / 3.8154 ETH-eq. A cluster child (FAMILY:METH via
        // CLUSTER:ETH_STAKING) reports an EGREGIOUS net realized ($22,000) vs market ($2,000) →
        // credited income $20,000 (> $5000 floor AND > 3× market). The guard fails the cluster NET
        // offset closed to the Market lane so fabricated recycling income cannot distort break-even.
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("11543.19"), new BigDecimal("11543.19"), new BigDecimal("3.8154"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput methChild = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("2000"), new BigDecimal("22000"));

        BreakEvenResult eth = netCalculator.compute(List.of(ethParent, methChild)).get("FAMILY:ETH");

        // Offset falls back to Market-lane cluster realized ($2000); income ($20000) is NOT credited.
        assertThat(eth.effectiveBasisUsd()).isEqualByComparingTo("9543.19");
        // Income is still surfaced for transparency (only the OFFSET credit is clamped).
        assertThat(eth.incomeReceivedUsd()).isEqualByComparingTo("20000");
    }

    @Test
    void ad82GuardIsNoOpForPlausibleClusterIncome() {
        // Corrected post-fix METH: market $2356, net $2361 → income $5 (dust). Guard does NOT fire;
        // the full NET offset ($2361) is credited so "rewards reduce cost for free" is preserved.
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("11543.19"), new BigDecimal("11543.19"), new BigDecimal("3.8154"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput methChild = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("2356"), new BigDecimal("2361"));

        BreakEvenResult eth = netCalculator.compute(List.of(ethParent, methChild)).get("FAMILY:ETH");

        assertThat(eth.effectiveBasisUsd()).isEqualByComparingTo("9182.19");
    }

    // ---- ADR-062 (2026-07-24): Net-lane held-basis numerator -------------------------------------

    @Test
    void netLaneRewardDominatedFamilyBreaksEvenAtNetAvco() {
        // sAVAX exemplar (audit §1.4): market basis $28.93 (LST re-credited on every wrap/reward),
        // net (real-cash) basis $1.28, 2.4184 held units, zero realized. Under NET the numerator is the
        // Net-lane held basis, so break-even = net AVCO ≈ $0.53 (NOT $11.96 market AVCO). ARB used as a
        // self-mapping family so no staking-cluster offset participates.
        FamilyBreakEvenInput savax = new FamilyBreakEvenInput(
                "FAMILY:ARB", "ARB",
                new BigDecimal("28.9311"), new BigDecimal("1.28269"), new BigDecimal("2.4184"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        BreakEvenResult net = netCalculator.compute(List.of(savax)).get("FAMILY:ARB");
        BreakEvenResult market = marketCalculator.compute(List.of(savax)).get("FAMILY:ARB");

        // NET: effective basis = net held basis ($1.28269, income received-and-held credited free).
        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo("1.28269");
        assertThat(net.breakEvenUsd()).isCloseTo(new BigDecimal("0.5304"),
                org.assertj.core.data.Offset.offset(new BigDecimal("1e-4")));
        // Average cost moves to the SAME (net) lane as break-even (E-2).
        assertThat(net.averageCostUsd()).isCloseTo(new BigDecimal("0.5304"),
                org.assertj.core.data.Offset.offset(new BigDecimal("1e-4")));
        // MARKET lane is byte-identical to pre-amendment (numerator still the Market basis $28.93).
        assertThat(market.effectiveBasisUsd()).isEqualByComparingTo("28.9311");
        assertThat(market.breakEvenUsd()).isCloseTo(new BigDecimal("11.9629"),
                org.assertj.core.data.Offset.offset(new BigDecimal("1e-3")));
    }

    @Test
    void netLaneMixedHeldAndDisposedRewardHasNoDoubleCount() {
        // The no-double-count proof: a family holds units with $10 net cash cost (part bought, part
        // free reward still held) and has ALREADY realized +$4 net (selling some reward units banks
        // full proceeds as profit). Under NET effective basis = netBasis − netRealized = 10 − 4 = 6,
        // over 50 held ⇒ $0.12. The held reward's value is credited once (in the $10 net basis at $0)
        // and the sold reward's proceeds once (in the $4 offset) — never both.
        FamilyBreakEvenInput arb = new FamilyBreakEvenInput(
                "FAMILY:ARB", "ARB",
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("50"),
                new BigDecimal("1"), new BigDecimal("4"));

        BreakEvenResult net = netCalculator.compute(List.of(arb)).get("FAMILY:ARB");

        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo("6");
        assertThat(net.breakEvenUsd()).isEqualByComparingTo("0.12");
        assertThat(net.lockedSurplusUsd()).isEqualByComparingTo("0");
    }

    @Test
    void netLanePastBreakEvenFloorsToZeroWithSurplusFromNetBasis() {
        // Net basis $10 held over 50 units, but $15 net realized already banked → effective basis
        // 10 − 15 = −5 → break-even floored to $0, locked surplus $5 derived from the NET basis.
        FamilyBreakEvenInput arb = new FamilyBreakEvenInput(
                "FAMILY:ARB", "ARB",
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("50"),
                new BigDecimal("15"), new BigDecimal("15"));

        BreakEvenResult net = netCalculator.compute(List.of(arb)).get("FAMILY:ARB");

        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo("-5");
        assertThat(net.breakEvenUsd()).isEqualByComparingTo("0");
        assertThat(net.lockedSurplusUsd()).isEqualByComparingTo("5");
    }

    @Test
    void netLaneRewardOnlyFamilyBreaksEvenAtZeroNotSuppressed() {
        // 100% free reward/airdrop units, never bought, never sold: net basis $0, market basis $200,
        // 50 held units, zero realized. Under NET break-even = $0 (genuinely free), and it is a real
        // $0 (not null / not suppressed): the denominator is healthy.
        FamilyBreakEvenInput arb = new FamilyBreakEvenInput(
                "FAMILY:ARB", "ARB",
                new BigDecimal("200"), BigDecimal.ZERO, new BigDecimal("50"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        BreakEvenResult net = netCalculator.compute(List.of(arb)).get("FAMILY:ARB");

        assertThat(net.breakEvenUsd()).isNotNull();
        assertThat(net.breakEvenUsd()).isEqualByComparingTo("0");
        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo("0");
    }

    @Test
    void netLaneBorrowedInflowWithNetEqualMarketIsUnchanged() {
        // Borrowed / liability-backed inflow (BORROW ACQUIRE): net basis == market basis (repayment
        // obligation is real cash owed), so the net numerator leaves it unchanged — NO spurious ~$0
        // effective cost. Break-even is identical in both lanes.
        FamilyBreakEvenInput borrowed = new FamilyBreakEvenInput(
                "FAMILY:USDC", "USDC",
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("50"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        BreakEvenResult net = netCalculator.compute(List.of(borrowed)).get("FAMILY:USDC");
        BreakEvenResult market = marketCalculator.compute(List.of(borrowed)).get("FAMILY:USDC");

        assertThat(net.breakEvenUsd()).isEqualByComparingTo("2");
        assertThat(net.breakEvenUsd()).isEqualByComparingTo(market.breakEvenUsd());
        assertThat(net.effectiveBasisUsd()).isEqualByComparingTo(market.effectiveBasisUsd());
    }

    @Test
    void foldChildFoldsNetBasisUnderNetAndMarketBasisUnderMarket() {
        // E-4 lane-consistency: an ACTIVATED fold (synthetic config) must fold the child's NET basis
        // into the parent numerator under NET and the child's MARKET basis under MARKET — never mix.
        // Parent: market $10,000 / net $6,000 (held yield). Child fold: market $1,000 / net $400.
        // 4.0 ETH-eq parent + 0.0 child qty (denominator irrelevant here) → compare effective basis.
        BreakEvenCalculator netFold = new BreakEvenCalculator(attributionServiceFrom(FOLD_CONFIG, "fold-net"));
        BreakEvenCalculator marketFold = new BreakEvenCalculator(attributionServiceFrom(
                FOLD_CONFIG.replace("\"NET\"", "\"MARKET\""), "fold-market"));
        FamilyBreakEvenInput ethParent = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("10000"), new BigDecimal("6000"), new BigDecimal("4"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput gmChild = new FamilyBreakEvenInput(
                "FAMILY:GMXGMETHUSD", "GMXGMETHUSD", new BigDecimal("1000"), new BigDecimal("400"), new BigDecimal("1"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        BreakEvenResult netParent = netFold.compute(List.of(ethParent, gmChild)).get("FAMILY:ETH");
        BreakEvenResult marketParent = marketFold.compute(List.of(ethParent, gmChild)).get("FAMILY:ETH");

        // NET: numerator = net parent ($6,000) + net fold ($400) = $6,400.
        assertThat(netParent.effectiveBasisUsd()).isEqualByComparingTo("6400");
        // MARKET: numerator = market parent ($10,000) + market fold ($1,000) = $11,000.
        assertThat(marketParent.effectiveBasisUsd()).isEqualByComparingTo("11000");
    }

    @Test
    void ac7AverageCostUsesRateAdjustedDenominatorSuppliedByCaller() {
        // AC-7: the caller supplies the ETH-equivalent (rate-adjusted) covered quantity. Here 2.0 staked
        // units at a ~1.06 staking rate ⇒ ~1.8868 ETH-eq; break-even/average divide by the ETH-eq value.
        BigDecimal ethEquiv = new BigDecimal("2").divide(new BigDecimal("1.06"), MC);
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("5000"), new BigDecimal("5000"), ethEquiv,
                BigDecimal.ZERO, BigDecimal.ZERO);

        BreakEvenResult result = calculator.compute(List.of(eth)).get("FAMILY:ETH");

        assertThat(result.averageCostUsd()).isCloseTo(new BigDecimal("2650"),
                org.assertj.core.data.Offset.offset(new BigDecimal("1")));
    }
}
