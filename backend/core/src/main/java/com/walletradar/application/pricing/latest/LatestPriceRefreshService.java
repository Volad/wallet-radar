package com.walletradar.application.pricing.latest;

import com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.PriceSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stateless service that executes one full latest-price refresh cycle.
 *
 * <p>Each cycle:
 * <ol>
 *   <li>Loads all symbols from {@code tracked_price_assets}.</li>
 *   <li>Calls each registered {@link LatestPriceProvider#fetchAll(Set)} in turn.</li>
 *   <li>Upserts only the symbols that were actually returned this cycle to {@code current_price_quotes}.</li>
 *   <li>Never touches {@code historical_prices}.</li>
 * </ol>
 *
 * <p>A provider that throws is logged and counted as failed; remaining providers still run.
 */
@Service
@RequiredArgsConstructor
public class LatestPriceRefreshService {

    private static final Logger log = LoggerFactory.getLogger(LatestPriceRefreshService.class);

    private final MongoOperations mongoOperations;
    private final List<LatestPriceProvider> providers;
    private final LatestPriceProperties latestPriceProperties;

    /**
     * Executes a full refresh cycle.
     *
     * @return statistics from this cycle
     */
    public LatestPriceRefreshResult refresh() {
        long startNanos = System.nanoTime();

        // Load tracked symbols (excluding stablecoins — they are pinned by consumers)
        Map<String, TrackedPriceAssetDocument.Kind> trackedWithKind = loadTrackedSymbolsWithKind();

        if (trackedWithKind.isEmpty()) {
            log.info("LatestPriceRefreshService: no tracked symbols, refresh skipped");
            return LatestPriceRefreshResult.empty();
        }

        Set<String> tracked = trackedWithKind.keySet();

        // Collect quotes from all providers (each provider receives the full kind map and decides itself)
        Map<String, Map<String, NormalizedLatestQuote>> byProvider = new LinkedHashMap<>();
        Set<PriceSource> failedSources = EnumSet.noneOf(PriceSource.class);
        Set<PriceSource> okSources = EnumSet.noneOf(PriceSource.class);

        for (LatestPriceProvider provider : providers) {
            try {
                Map<String, NormalizedLatestQuote> quotes = provider.fetchAll(trackedWithKind);
                byProvider.put(provider.source().name(), quotes);
                okSources.add(provider.source());
                log.debug("Provider {} returned {} quotes", provider.source(), quotes.size());
            } catch (Exception ex) {
                log.error("LatestPriceRefreshService: provider {} threw unexpectedly, skipping",
                        provider.source(), ex);
                failedSources.add(provider.source());
            }
        }

        // Persist each provider's quotes independently (one row per symbol+source)
        Instant fetchedAt = Instant.now();
        int upserted = 0;
        int bybit = 0;
        int dzengi = 0;
        int divergences = 0;

        // Detect and log divergences across providers for the same symbol
        for (String symbol : tracked) {
            NormalizedLatestQuote bybitQuote = getQuote(byProvider, PriceSource.BYBIT.name(), symbol);
            NormalizedLatestQuote dzengiQuote = getQuote(byProvider, PriceSource.DZENGI.name(), symbol);

            if (bybitQuote != null && dzengiQuote != null) {
                double div = relativeDivergence(bybitQuote.priceUsd(), dzengiQuote.priceUsd());
                if (div > latestPriceProperties.getDivergenceTolerancePct()) {
                    log.warn("Price divergence: symbol={} bybit={} dzengi={} div={}%",
                            symbol, bybitQuote.priceUsd(), dzengiQuote.priceUsd(),
                            String.format("%.1f", div * 100));
                    divergences++;
                }
            }
        }

        // Upsert all returned quotes to current_price_quotes
        for (Map.Entry<String, Map<String, NormalizedLatestQuote>> providerEntry : byProvider.entrySet()) {
            String providerName = providerEntry.getKey();
            for (Map.Entry<String, NormalizedLatestQuote> quoteEntry : providerEntry.getValue().entrySet()) {
                NormalizedLatestQuote quote = quoteEntry.getValue();
                upsertQuote(quote, fetchedAt);
                upserted++;
                if (PriceSource.BYBIT.name().equals(providerName)) bybit++;
                if (PriceSource.DZENGI.name().equals(providerName)) dzengi++;
            }
        }

        int pricedByNeither = (int) tracked.stream()
                .filter(sym -> byProvider.values().stream().noneMatch(m -> m.containsKey(sym)))
                .count();

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "LatestPriceRefreshService cycle complete: tracked={}, upserted={}, bybit={}, dzengi={}, divergences={}, pricedByNeither={}, providersOk={}, providersFailed={}, elapsedMs={}",
                tracked.size(), upserted, bybit, dzengi, divergences, pricedByNeither,
                okSources, failedSources, elapsedMs
        );

        return new LatestPriceRefreshResult(
                tracked.size(), bybit, dzengi, pricedByNeither, divergences,
                okSources.contains(PriceSource.BYBIT),
                okSources.contains(PriceSource.DZENGI)
        );
    }

    private Map<String, TrackedPriceAssetDocument.Kind> loadTrackedSymbolsWithKind() {
        Query query = Query.query(Criteria.where("kind").ne(TrackedPriceAssetDocument.Kind.STABLECOIN.name()));
        query.fields().include("symbol").include("kind");
        List<TrackedPriceAssetDocument> docs = mongoOperations.find(query, TrackedPriceAssetDocument.class);
        Map<String, TrackedPriceAssetDocument.Kind> result = new LinkedHashMap<>();
        for (TrackedPriceAssetDocument doc : docs) {
            if (doc.getSymbol() != null && !doc.getSymbol().isBlank()) {
                String sym = doc.getSymbol().trim().toUpperCase(Locale.ROOT);
                TrackedPriceAssetDocument.Kind kind = doc.getKind() != null
                        ? doc.getKind()
                        : TrackedPriceAssetDocument.Kind.UNKNOWN;
                result.put(sym, kind);
            }
        }
        return result;
    }

    private void upsertQuote(NormalizedLatestQuote quote, Instant fetchedAt) {
        String symbol = quote.canonicalSymbol().trim().toUpperCase(Locale.ROOT);
        String id = CurrentPriceQuoteDocument.composeId(symbol, quote.source());
        mongoOperations.upsert(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("symbol", symbol)
                        .set("source", quote.source())
                        .set("priceUsd", Decimal128Support.normalize(quote.priceUsd()))
                        .set("quoteSymbol", quote.quoteCurrency())
                        .set("pricedAt", quote.pricedAt())
                        .set("fetchedAt", fetchedAt)
                        .set("sourceReference", quote.sourceSymbol()),
                CurrentPriceQuoteDocument.class
        );
    }

    private static NormalizedLatestQuote getQuote(
            Map<String, Map<String, NormalizedLatestQuote>> byProvider,
            String providerName,
            String symbol
    ) {
        Map<String, NormalizedLatestQuote> quotes = byProvider.get(providerName);
        return quotes == null ? null : quotes.get(symbol);
    }

    private static double relativeDivergence(java.math.BigDecimal a, java.math.BigDecimal b) {
        if (a == null || b == null || a.signum() <= 0 || b.signum() <= 0) return 0;
        java.math.BigDecimal diff = a.subtract(b).abs();
        java.math.BigDecimal min = a.min(b);
        return diff.divide(min, java.math.MathContext.DECIMAL64).doubleValue();
    }
}
