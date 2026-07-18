package com.walletradar.application.pricing.latest;

import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.domain.common.PriceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single deterministic read path for latest USD prices.
 *
 * <p>All query services (dashboard, LP, lending) MUST go through this service instead of querying
 * {@code current_price_quotes} directly. This guarantees that every consumer sees the same price
 * for the same symbol within a single request.
 *
 * <p>The service applies {@link LatestPriceSelectionPolicy} when multiple providers have stored
 * quotes for the same symbol.
 *
 * <p><strong>This service NEVER writes to any collection and NEVER touches historical_prices.</strong>
 */
@Service
@RequiredArgsConstructor
public class CurrentPriceReadService {

    private final MongoOperations mongoOperations;
    private final LatestPriceSelectionPolicy selectionPolicy;
    private final LatestPriceProperties latestPriceProperties;

    /**
     * Resolves the latest USD prices for a set of canonical symbols.
     *
     * @param canonicalSymbols symbols to look up (will be upper-cased internally)
     * @return map of canonicalSymbol (upper-cased) → resolved price; missing symbols are absent
     */
    public Map<String, ResolvedPrice> resolveLatest(Collection<String> canonicalSymbols) {
        if (canonicalSymbols == null || canonicalSymbols.isEmpty()) {
            return Map.of();
        }

        List<String> normalized = canonicalSymbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();

        if (normalized.isEmpty()) {
            return Map.of();
        }

        Map<String, ResolvedPrice> result = new LinkedHashMap<>();

        // Stablecoin pins: always $1, never stale
        for (String sym : normalized) {
            if (CanonicalAssetCatalog.isUsdStablecoin(null, null, sym, null)) {
                result.put(sym, new ResolvedPrice(BigDecimal.ONE, PriceSource.STABLECOIN, Instant.now(), false));
            }
        }

        Set<String> remaining = normalized.stream()
                .filter(s -> !result.containsKey(s))
                .collect(Collectors.toSet());

        if (remaining.isEmpty()) {
            return result;
        }

        // Read current_price_quotes for all remaining symbols
        Query query = Query.query(Criteria.where("symbol").in(remaining));
        List<CurrentPriceQuoteDocument> allQuotes = mongoOperations.find(query, CurrentPriceQuoteDocument.class);

        // Group by canonical symbol
        Map<String, List<CurrentPriceQuoteDocument>> bySymbol = new LinkedHashMap<>();
        for (CurrentPriceQuoteDocument quote : allQuotes) {
            if (quote.getSymbol() == null) continue;
            String sym = quote.getSymbol().trim().toUpperCase(Locale.ROOT);
            bySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(quote);
        }

        Instant staleThreshold = Instant.now().minusMillis(latestPriceProperties.getStaleAfterMs());

        for (Map.Entry<String, List<CurrentPriceQuoteDocument>> entry : bySymbol.entrySet()) {
            String sym = entry.getKey();
            if (result.containsKey(sym)) continue;
            selectionPolicy.select(entry.getValue(), staleThreshold)
                    .ifPresent(price -> result.put(sym, price));
        }

        return result;
    }

    /**
     * Convenience method for a single canonical symbol.
     */
    public Optional<ResolvedPrice> resolveOne(String canonicalSymbol) {
        if (canonicalSymbol == null || canonicalSymbol.isBlank()) {
            return Optional.empty();
        }
        String upper = canonicalSymbol.trim().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(resolveLatest(List.of(upper)).get(upper));
    }
}
