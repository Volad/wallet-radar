package com.walletradar.application.pricing.latest;

import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.domain.common.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Selects the single best USD price for a canonical symbol from multiple stored quotes.
 *
 * <p>Selection strategy:
 * <ol>
 *   <li>Freshness gate — stale quotes (pricedAt older than threshold) are deprioritised but never
 *       discarded if they are the only available quote.</li>
 *   <li>Priority — lower {@link #sourcePriority(PriceSource)} wins. Bybit (1) beats Dzengi (2).</li>
 *   <li>Freshness tiebreak — among candidates with equal priority, the most recently priced quote wins.</li>
 *   <li>Divergence guard — if two providers each have a fresh quote and their prices diverge by more
 *       than {@code tolerancePct}, a WARNING is logged (the winner is still the higher-priority source).</li>
 * </ol>
 */
@Component
public class LatestPriceSelectionPolicy {

    private static final Logger log = LoggerFactory.getLogger(LatestPriceSelectionPolicy.class);

    private final LatestPriceProperties properties;

    public LatestPriceSelectionPolicy(LatestPriceProperties properties) {
        this.properties = properties;
    }

    /**
     * Selects the best price from a list of stored quotes for the same canonical symbol.
     *
     * @param quotes         all stored quotes for the symbol (may be from multiple sources)
     * @param staleThreshold quotes with pricedAt before this are considered stale
     * @return selected price, or empty if no usable quote exists
     */
    public Optional<ResolvedPrice> select(
            List<CurrentPriceQuoteDocument> quotes,
            Instant staleThreshold
    ) {
        if (quotes == null || quotes.isEmpty()) {
            return Optional.empty();
        }

        List<CurrentPriceQuoteDocument> valid = quotes.stream()
                .filter(q -> q.getPriceUsd() != null && q.getPriceUsd().signum() > 0)
                .toList();

        if (valid.isEmpty()) {
            return Optional.empty();
        }

        List<CurrentPriceQuoteDocument> fresh = valid.stream()
                .filter(q -> isFresh(q, staleThreshold))
                .toList();

        List<CurrentPriceQuoteDocument> candidates = fresh.isEmpty() ? valid : fresh;

        CurrentPriceQuoteDocument best = candidates.stream()
                .min(Comparator
                        .comparingInt((CurrentPriceQuoteDocument q) -> sourcePriority(q.getSource()))
                        .thenComparing(q -> q.getPricedAt() == null ? Instant.EPOCH : q.getPricedAt(),
                                Comparator.reverseOrder()))
                .orElse(null);

        if (best == null) {
            return Optional.empty();
        }

        checkDivergence(fresh, best);

        boolean stale = fresh.isEmpty() || !isFresh(best, staleThreshold);
        return Optional.of(new ResolvedPrice(
                best.getPriceUsd(),
                best.getSource() != null ? best.getSource() : PriceSource.UNKNOWN,
                best.getPricedAt() != null ? best.getPricedAt() : Instant.EPOCH,
                stale
        ));
    }

    private boolean isFresh(CurrentPriceQuoteDocument q, Instant staleThreshold) {
        return q.getPricedAt() != null && q.getPricedAt().isAfter(staleThreshold);
    }

    /**
     * Emits a warning when two fresh quotes for the same symbol diverge beyond the configured tolerance.
     */
    private void checkDivergence(List<CurrentPriceQuoteDocument> fresh, CurrentPriceQuoteDocument winner) {
        if (fresh.size() < 2) return;
        BigDecimal winnerPrice = winner.getPriceUsd();
        for (CurrentPriceQuoteDocument q : fresh) {
            if (q == winner) continue;
            BigDecimal other = q.getPriceUsd();
            if (other == null || other.signum() <= 0 || winnerPrice == null || winnerPrice.signum() <= 0) continue;
            BigDecimal diff = winnerPrice.subtract(other).abs();
            BigDecimal min = winnerPrice.min(other);
            double divergence = diff.divide(min, MathContext_DECIMAL64).doubleValue();
            if (divergence > properties.getDivergenceTolerancePct()) {
                log.warn(
                        "Price divergence detected for symbol={}: source1={} price1={} vs source2={} price2={}, divergence={}%",
                        winner.getSymbol(),
                        winner.getSource(),
                        winnerPrice,
                        q.getSource(),
                        other,
                        String.format("%.1f", divergence * 100)
                );
            }
        }
    }

    private static final java.math.MathContext MathContext_DECIMAL64 = java.math.MathContext.DECIMAL64;

    private static int sourcePriority(PriceSource source) {
        if (source == null) return 99;
        return switch (source) {
            case STABLECOIN -> 0;
            case BYBIT -> 1;
            case DZENGI -> 2;
            case PROTOCOL_SNAPSHOT -> 3;
            default -> 10;
        };
    }
}
