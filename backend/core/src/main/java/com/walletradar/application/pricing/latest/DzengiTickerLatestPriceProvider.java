package com.walletradar.application.pricing.latest;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.cex.acquisition.venue.dzengi.DzengiApiClient;
import com.walletradar.domain.common.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bulk latest-price provider backed by the Dzengi 24h ticker endpoint.
 * Issues a single GET /api/v2/ticker/24hr call that returns ALL symbols.
 *
 * <p>Symbol mapping rules (Dzengi native → canonical):
 * <ul>
 *   <li>{@code ETH/USD} → canonical {@code ETH}, quoteCcy {@code USD}</li>
 *   <li>{@code TSLA.} → canonical {@code TSLA}, quoteCcy {@code USD} (equity instruments use dot suffix)</li>
 *   <li>{@code USD/BYN} → skip (FX pair, not an asset we price)</li>
 *   <li>{@code ETH/USDT} → canonical {@code ETH}, quoteCcy {@code USDT}</li>
 * </ul>
 *
 * <p>Quote currency normalization:
 * <ul>
 *   <li>USD → pass through (price IS in USD)</li>
 *   <li>USDT / USDC → $1 parity (price ≈ USD)</li>
 *   <li>BYN → need inverse FX rate; not supported here, symbol skipped</li>
 * </ul>
 */
@Component
public class DzengiTickerLatestPriceProvider implements LatestPriceProvider {

    private static final Logger log = LoggerFactory.getLogger(DzengiTickerLatestPriceProvider.class);

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final Set<String> USD_STABLE_QUOTES = Set.of("USD", "USDT", "USDC");
    private static final Set<String> SKIP_BASE_SYMBOLS = Set.of("USD", "USDT", "USDC", "EUR", "GBP", "RUB");

    private final DzengiApiClient dzengiApiClient;

    public DzengiTickerLatestPriceProvider(DzengiApiClient dzengiApiClient) {
        this.dzengiApiClient = dzengiApiClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.DZENGI;
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public Map<String, NormalizedLatestQuote> fetchAll(Map<String, TrackedPriceAssetDocument.Kind> wantedSymbolsWithKind) {
        if (wantedSymbolsWithKind == null || wantedSymbolsWithKind.isEmpty()) {
            return Map.of();
        }

        JsonNode tickers;
        try {
            tickers = dzengiApiClient.fetchTicker24hr();
        } catch (Exception ex) {
            log.error("DzengiTickerLatestPriceProvider: fetchTicker24hr threw unexpectedly", ex);
            return Map.of();
        }

        if (tickers == null || !tickers.isArray()) {
            log.warn("DzengiTickerLatestPriceProvider: received null or non-array tickers response");
            return Map.of();
        }

        Map<String, NormalizedLatestQuote> result = new LinkedHashMap<>();

        for (JsonNode node : tickers) {
            String nativeSymbol = node.path("symbol").asText(null);
            String lastPriceStr = node.path("lastPrice").asText(null);

            if (nativeSymbol == null || nativeSymbol.isBlank() || lastPriceStr == null || lastPriceStr.isBlank()) {
                continue;
            }

            BigDecimal lastPrice;
            try {
                lastPrice = new BigDecimal(lastPriceStr);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (lastPrice.signum() <= 0) continue;

            DzengiParsedTicker parsed = parseNativeSymbol(nativeSymbol);
            if (parsed == null) continue;
            if (!USD_STABLE_QUOTES.contains(parsed.quoteCcy())) continue;
            if (SKIP_BASE_SYMBOLS.contains(parsed.base())) continue;
            if (!wantedSymbolsWithKind.containsKey(parsed.base())) continue;

            // Equity tickers (dot-suffix in native form) must only match EQUITY-kinded symbols.
            // This prevents false divergence between Dzengi equity (e.g. "U." = Unity Software)
            // and Bybit crypto (e.g. "UUSDT" = some crypto token with the same canonical symbol "U").
            boolean isEquityTicker = nativeSymbol.trim().endsWith(".");
            if (isEquityTicker) {
                TrackedPriceAssetDocument.Kind kind = wantedSymbolsWithKind.get(parsed.base());
                if (kind != TrackedPriceAssetDocument.Kind.EQUITY) continue;
            }

            long closeTimeMs = node.path("closeTime").asLong(0L);
            Instant pricedAt = closeTimeMs > 0 ? Instant.ofEpochMilli(closeTimeMs) : Instant.now();

            BigDecimal priceUsd = lastPrice; // USD/USDT/USDC all treated as ~$1

            NormalizedLatestQuote quote = new NormalizedLatestQuote(
                    parsed.base(),
                    priceUsd,
                    parsed.quoteCcy(),
                    PriceSource.DZENGI,
                    nativeSymbol,
                    pricedAt
            );

            // Prefer USD-quoted over USDT/USDC for the same base (USD is exact, stables ≈ parity)
            result.merge(parsed.base(), quote, (existing, candidate) -> {
                int existingPrio = "USD".equals(existing.quoteCurrency()) ? 0 : 1;
                int candidatePrio = "USD".equals(candidate.quoteCurrency()) ? 0 : 1;
                return candidatePrio < existingPrio ? candidate : existing;
            });
        }

        log.debug("DzengiTickerLatestPriceProvider: resolved {} / {} wanted symbols from {} tickers",
                result.size(), wantedSymbolsWithKind.size(), tickers.size());
        return result;
    }

    /**
     * Parses a Dzengi native symbol into (base, quoteCcy).
     *
     * <p>Dzengi formats:
     * <ul>
     *   <li>{@code TSLA.} — equity/commodity with trailing dot; quote currency always USD on Dzengi</li>
     *   <li>{@code ETH/USD} — slash-delimited pair</li>
     *   <li>{@code ETHUSD} — no separator (legacy); not used by Dzengi but handled for safety</li>
     * </ul>
     */
    private static DzengiParsedTicker parseNativeSymbol(String symbol) {
        String upper = symbol.trim().toUpperCase(Locale.ROOT);

        // Equity/commodity: trailing dot (e.g. "TSLA.", "GOOGL.")
        if (upper.endsWith(".")) {
            String base = upper.substring(0, upper.length() - 1);
            if (base.isBlank()) return null;
            return new DzengiParsedTicker(base, "USD");
        }

        // Slash pair (e.g. "ETH/USD", "ETH/USDT", "BTC/USDT")
        int slash = upper.indexOf('/');
        if (slash > 0 && slash < upper.length() - 1) {
            String base = upper.substring(0, slash);
            String quote = upper.substring(slash + 1);
            if (base.isBlank() || quote.isBlank()) return null;
            return new DzengiParsedTicker(base, quote);
        }

        return null;
    }

    private record DzengiParsedTicker(String base, String quoteCcy) {
    }
}
