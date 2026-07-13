package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bulk latest-price provider backed by Bybit spot tickers.
 * Fetches ALL spot tickers in one HTTP call, then maps to canonical symbols.
 *
 * <p>Symbol mapping rules:
 * <ul>
 *   <li>{@code ETHUSDT} → canonical {@code ETH}, quoteCcy {@code USDT}</li>
 *   <li>{@code ETHUSDC} → canonical {@code ETH}, quoteCcy {@code USDC}</li>
 *   <li>Tickers without USDT/USDC suffix are skipped (BTC pairs, token-to-token pairs)</li>
 * </ul>
 * If both USDT and USDC are present for the same base, selection policy chooses the winner.
 */
@Component
public class BybitTickerLatestPriceProvider implements LatestPriceProvider {

    private static final Logger log = LoggerFactory.getLogger(BybitTickerLatestPriceProvider.class);

    private static final String USDT = "USDT";
    private static final String USDC = "USDC";

    private final BybitTickerClient bybitTickerClient;

    public BybitTickerLatestPriceProvider(BybitTickerClient bybitTickerClient) {
        this.bybitTickerClient = bybitTickerClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.BYBIT;
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public Map<String, NormalizedLatestQuote> fetchAll(Map<String, TrackedPriceAssetDocument.Kind> wantedSymbolsWithKind) {
        if (wantedSymbolsWithKind == null || wantedSymbolsWithKind.isEmpty()) {
            return Map.of();
        }
        // Bybit only trades crypto — skip equity-kinded symbols to avoid false divergences
        Set<String> wantedCanonicalSymbols = wantedSymbolsWithKind.entrySet().stream()
                .filter(e -> e.getValue() != TrackedPriceAssetDocument.Kind.EQUITY)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        List<BybitTickerClient.BybitTicker> tickers;
        try {
            tickers = bybitTickerClient.fetchAllSpotTickers();
        } catch (Exception ex) {
            log.error("BybitTickerLatestPriceProvider: fetchAllSpotTickers threw unexpectedly", ex);
            return Map.of();
        }

        if (tickers.isEmpty()) {
            log.warn("BybitTickerLatestPriceProvider: received empty tickers list");
            return Map.of();
        }

        Map<String, List<NormalizedLatestQuote>> candidates = new LinkedHashMap<>();

        for (BybitTickerClient.BybitTicker ticker : tickers) {
            String symbol = ticker.symbol();
            if (symbol == null || symbol.isBlank()) continue;

            String upper = symbol.trim().toUpperCase(Locale.ROOT);
            String base;
            String quoteCcy;

            if (upper.endsWith(USDT)) {
                base = upper.substring(0, upper.length() - USDT.length());
                quoteCcy = USDT;
            } else if (upper.endsWith(USDC)) {
                base = upper.substring(0, upper.length() - USDC.length());
                quoteCcy = USDC;
            } else {
                continue;
            }

            if (base.isBlank() || !wantedCanonicalSymbols.contains(base)) {
                continue;
            }

            NormalizedLatestQuote quote = new NormalizedLatestQuote(
                    base,
                    ticker.lastPrice(),  // USDT/USDC ≈ $1 parity
                    quoteCcy,
                    PriceSource.BYBIT,
                    symbol,
                    ticker.fetchedAt()
            );
            candidates.computeIfAbsent(base, k -> new ArrayList<>()).add(quote);
        }

        // Prefer USDT over USDC for the same symbol (USDT is higher liquidity on Bybit)
        Map<String, NormalizedLatestQuote> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<NormalizedLatestQuote>> entry : candidates.entrySet()) {
            List<NormalizedLatestQuote> quotes = entry.getValue();
            NormalizedLatestQuote best = quotes.stream()
                    .min((a, b) -> {
                        int pa = USDT.equals(a.quoteCurrency()) ? 0 : 1;
                        int pb = USDT.equals(b.quoteCurrency()) ? 0 : 1;
                        return Integer.compare(pa, pb);
                    })
                    .orElse(null);
            if (best != null) {
                result.put(entry.getKey(), best);
            }
        }

        log.debug("BybitTickerLatestPriceProvider: resolved {} / {} wanted symbols from {} tickers",
                result.size(), wantedCanonicalSymbols.size(), tickers.size());
        return result;
    }
}
