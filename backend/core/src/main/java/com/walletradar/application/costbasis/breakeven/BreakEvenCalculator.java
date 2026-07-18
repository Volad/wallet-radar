package com.walletradar.application.costbasis.breakeven;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADR-062 §1 break-even (effective-cost) calculator. Pure read-model derivation over
 * already-aggregated per-family inputs; no replay/AVCO/RPC/Mongo effect.
 */
@Service
@RequiredArgsConstructor
public class BreakEvenCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final BreakEvenAttributionService attributionService;

    public Map<String, BreakEvenResult> compute(Collection<FamilyBreakEvenInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> targetByFamily = new LinkedHashMap<>();
        Map<String, BigDecimal> attributedMarketPnl = new LinkedHashMap<>();
        Map<String, BigDecimal> attributedIncome = new LinkedHashMap<>();
        for (FamilyBreakEvenInput input : inputs) {
            if (input == null || input.familyIdentity() == null || input.familyIdentity().isBlank()) {
                continue;
            }
            String target = attributionService.resolveTarget(input.familyIdentity(), input.representativeSymbol());
            targetByFamily.put(input.familyIdentity(), target);
            BigDecimal market = zeroIfNull(input.marketRealizedPnlUsd());
            BigDecimal net = zeroIfNull(input.netRealizedPnlUsd());
            attributedMarketPnl.merge(target, market, (left, right) -> left.add(right, MC));
            attributedIncome.merge(target, net.subtract(market, MC), (left, right) -> left.add(right, MC));
        }

        Map<String, BreakEvenResult> results = new LinkedHashMap<>();
        for (FamilyBreakEvenInput input : inputs) {
            if (input == null || input.familyIdentity() == null || input.familyIdentity().isBlank()) {
                continue;
            }
            String family = input.familyIdentity();
            String target = targetByFamily.get(family);
            boolean redirected = target != null && !target.equals(family);

            // A redirected child receives no self-offset: nothing resolves onto it in a proper
            // partition, so its accumulated offset is naturally zero and its effective basis equals
            // its market basis. The parent target absorbs the child's market P&L.
            BigDecimal attributed = zeroIfNull(attributedMarketPnl.get(family));
            BigDecimal income = zeroIfNull(attributedIncome.get(family));
            BigDecimal marketBasis = zeroIfNull(input.marketBasisUsd());
            // Only realized net PROFIT discounts effective cost (offset floored at 0). A realized net
            // loss must not inflate the effective cost of the units still held — otherwise dividing a
            // fixed lifetime dollar loss by a small remaining quantity produces an absurd per-unit
            // figure (e.g. USDT mostly spent → $17/unit). Effective cost therefore stays in [0, AVCO].
            BigDecimal attributedOffset = attributed.max(BigDecimal.ZERO);
            BigDecimal effectiveBasis = marketBasis.subtract(attributedOffset, MC);
            BigDecimal coveredQuantity = input.coveredQuantity();
            BigDecimal breakEven = coveredQuantity != null && coveredQuantity.signum() > 0
                    ? effectiveBasis.max(BigDecimal.ZERO).divide(coveredQuantity, MC)
                    : null;
            BigDecimal lockedSurplus = effectiveBasis.signum() < 0 ? effectiveBasis.negate() : BigDecimal.ZERO;
            results.put(family, new BreakEvenResult(
                    breakEven,
                    effectiveBasis,
                    attributed,
                    lockedSurplus,
                    income,
                    redirected ? target : null
            ));
        }
        return results;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record FamilyBreakEvenInput(
            String familyIdentity,
            String representativeSymbol,
            BigDecimal marketBasisUsd,
            BigDecimal coveredQuantity,
            BigDecimal marketRealizedPnlUsd,
            BigDecimal netRealizedPnlUsd
    ) {
    }

    public record BreakEvenResult(
            BigDecimal breakEvenUsd,
            BigDecimal effectiveBasisUsd,
            BigDecimal attributedRealizedPnlUsd,
            BigDecimal lockedSurplusUsd,
            BigDecimal incomeReceivedUsd,
            String attributionTargetFamily
    ) {
    }
}
