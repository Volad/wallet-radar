package com.walletradar.application.costbasis.breakeven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator.BreakEvenResult;
import com.walletradar.application.costbasis.breakeven.BreakEvenCalculator.FamilyBreakEvenInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BreakEvenCalculatorTest {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final BreakEvenCalculator calculator = new BreakEvenCalculator(
            new BreakEvenAttributionService(new BreakEvenAttributionLoader(new ObjectMapper())));

    @Test
    void selfOnlyFamilyCreditsOwnRealizedPnl() {
        FamilyBreakEvenInput usdc = new FamilyBreakEvenInput(
                "FAMILY:BTC", "BTC",
                new BigDecimal("10000"), new BigDecimal("2"),
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
                parentBasis, new BigDecimal("3.82"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput methChild = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH",
                new BigDecimal("5000"), new BigDecimal("2"),
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
                new BigDecimal("1000"), new BigDecimal("0.5"),
                new BigDecimal("1500"), new BigDecimal("1500"));

        BreakEvenResult result = calculator.compute(List.of(eth)).get("FAMILY:ETH");

        assertThat(result.effectiveBasisUsd()).isEqualByComparingTo("-500");
        assertThat(result.breakEvenUsd()).isEqualByComparingTo("0");
        assertThat(result.lockedSurplusUsd()).isEqualByComparingTo("500");
    }

    @Test
    void realizedNetLossDoesNotInflateEffectiveCostAboveAvco() {
        // USDT-like: mostly spent, small covered qty, accumulated Market-lane trading loss.
        // Without the loss floor this would be ($1210 + $116) / 7 ≈ $189/unit; with it, effective
        // cost stays at AVCO ($1) because a realized loss never raises the cost of held units.
        FamilyBreakEvenInput usdt = new FamilyBreakEvenInput(
                "FAMILY:USDT", "USDT",
                new BigDecimal("7.00"), new BigDecimal("7.00"),
                new BigDecimal("-116.38"), new BigDecimal("1292.60"));

        BreakEvenResult result = calculator.compute(List.of(usdt)).get("FAMILY:USDT");

        assertThat(result.attributedRealizedPnlUsd()).isEqualByComparingTo("-116.38");
        assertThat(result.effectiveBasisUsd()).isEqualByComparingTo("7.00");
        assertThat(result.breakEvenUsd()).isEqualByComparingTo("1");
        assertThat(result.lockedSurplusUsd()).isEqualByComparingTo("0");
    }

    @Test
    void nullOrZeroCoveredQuantityYieldsNullBreakEven() {
        FamilyBreakEvenInput nullQty = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH", new BigDecimal("1000"), null,
                BigDecimal.ZERO, BigDecimal.ZERO);
        FamilyBreakEvenInput zeroQty = new FamilyBreakEvenInput(
                "FAMILY:BTC", "BTC", new BigDecimal("1000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        Map<String, BreakEvenResult> results = calculator.compute(List.of(nullQty, zeroQty));

        assertThat(results.get("FAMILY:ETH").breakEvenUsd()).isNull();
        assertThat(results.get("FAMILY:BTC").breakEvenUsd()).isNull();
    }

    @Test
    void incomeIsNetMinusMarketAttributedToTarget() {
        FamilyBreakEvenInput eth = new FamilyBreakEvenInput(
                "FAMILY:ETH", "ETH",
                new BigDecimal("10000"), new BigDecimal("3"),
                new BigDecimal("500"), new BigDecimal("500"));
        FamilyBreakEvenInput meth = new FamilyBreakEvenInput(
                "FAMILY:METH", "CMETH",
                new BigDecimal("1000"), new BigDecimal("1"),
                new BigDecimal("200"), new BigDecimal("900"));

        Map<String, BreakEvenResult> results = calculator.compute(List.of(eth, meth));

        // ETH income = (500-500) + (900-200) = 700 (child income rolls into parent target).
        assertThat(results.get("FAMILY:ETH").incomeReceivedUsd()).isEqualByComparingTo("700");
        assertThat(results.get("FAMILY:METH").incomeReceivedUsd()).isEqualByComparingTo("0");
    }

    @Test
    void partitionConservesTotalMarketRealizedPnl() {
        List<FamilyBreakEvenInput> inputs = List.of(
                new FamilyBreakEvenInput("FAMILY:ETH", "ETH", new BigDecimal("10000"), new BigDecimal("3"),
                        new BigDecimal("500"), new BigDecimal("500")),
                new FamilyBreakEvenInput("FAMILY:METH", "CMETH", new BigDecimal("1000"), new BigDecimal("1"),
                        new BigDecimal("2540"), new BigDecimal("2540")),
                new FamilyBreakEvenInput("FAMILY:STETH", "STETH", new BigDecimal("2000"), new BigDecimal("1"),
                        new BigDecimal("300"), new BigDecimal("300")),
                new FamilyBreakEvenInput("FAMILY:USDC", "USDC", new BigDecimal("5000"), new BigDecimal("5000"),
                        new BigDecimal("120"), new BigDecimal("120")));

        Map<String, BreakEvenResult> results = calculator.compute(inputs);

        BigDecimal totalAttributed = results.values().stream()
                .map(BreakEvenResult::attributedRealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal totalMarketPnl = inputs.stream()
                .map(FamilyBreakEvenInput::marketRealizedPnlUsd)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));

        assertThat(totalAttributed).isEqualByComparingTo(totalMarketPnl);
    }
}
