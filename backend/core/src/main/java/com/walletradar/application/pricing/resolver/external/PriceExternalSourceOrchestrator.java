package com.walletradar.application.pricing.resolver.external;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.application.pricing.domain.PriceQuote;
import com.walletradar.application.pricing.domain.PriceRequest;
import com.walletradar.application.pricing.persistence.HistoricalPriceCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs external market-data sources in fallback order with deterministic cache reuse.
 */
@Component
@Slf4j
public class PriceExternalSourceOrchestrator {

    /**
     * RC-D (ADR-043, F-7) — bound the pre-coverage nearest-valid-bucket clamp so an out-of-range
     * request only borrows a price from a bucket within roughly a year. Wide enough to bridge a
     * multi-month coverage gap (the DOGE 2025-01 lot vs 2025-09 first bucket), narrow enough that a
     * request years from any coverage still fails safe.
     */
    private static final java.time.Duration PRE_COVERAGE_NEAREST_WINDOW = java.time.Duration.ofDays(400);

    private final HistoricalPriceCacheService historicalPriceCacheService;
    private final List<ExternalPriceSource> externalSources;

    public PriceExternalSourceOrchestrator(
            HistoricalPriceCacheService historicalPriceCacheService,
            List<ExternalPriceSource> externalSources
    ) {
        this.historicalPriceCacheService = Objects.requireNonNull(
                historicalPriceCacheService,
                "historicalPriceCacheService"
        );
        this.externalSources = List.copyOf(Objects.requireNonNull(externalSources, "externalSources"));
    }

    public Optional<PriceQuote> resolve(PriceRequest request) {
        List<ExternalPriceSource> prioritizedSources = prioritizedExternalSources(request);
        for (ExternalPriceSource externalSource : prioritizedSources) {
            Optional<PriceQuote> cached = historicalPriceCacheService.findQuote(request, externalSource.source());
            if (cached.isPresent()) {
                return cached;
            }
            try {
                Optional<PriceQuote> quote = externalSource.resolve(request);
                if (quote.isPresent()) {
                    historicalPriceCacheService.storeQuote(request, quote.get());
                    return quote;
                }
            } catch (RuntimeException error) {
                log.error(
                        "External price source failed: normalizedTxId={}, networkId={}, assetKey={}, assetSymbol={}, source={}",
                        request.normalizedTransactionId(),
                        request.networkId(),
                        request.assetKey(),
                        request.assetSymbol(),
                        externalSource.source(),
                        error
                );
            }
        }
        return resolveBoundedNearestBucket(request);
    }

    public Optional<PriceQuote> resolveExternalOnly(PriceRequest request) {
        for (ExternalPriceSource externalSource : prioritizedExternalSources(request)) {
            try {
                Optional<PriceQuote> quote = externalSource.resolve(request);
                if (quote.isPresent()) {
                    return quote;
                }
            } catch (RuntimeException error) {
                log.error(
                        "External price source failed: normalizedTxId={}, networkId={}, assetKey={}, assetSymbol={}, source={}",
                        request.normalizedTransactionId(),
                        request.networkId(),
                        request.assetKey(),
                        request.assetSymbol(),
                        externalSource.source(),
                        error
                );
            }
        }
        return resolveBoundedNearestBucket(request);
    }

    /**
     * RC-D (ADR-043, F-7) — when every external source misses (e.g. a pre-coverage / out-of-range
     * request whose event date predates the asset's first cached bucket), bound the result to the
     * temporally closest valid cached bucket within {@link #PRE_COVERAGE_NEAREST_WINDOW} instead of
     * leaving the leg unpriced (which historically let a far, wrong out-of-range value stand). The
     * lookup is asset+source scoped and deterministic (cache-only), so it never fabricates a price
     * outside the window.
     */
    private Optional<PriceQuote> resolveBoundedNearestBucket(PriceRequest request) {
        for (PriceSource source : prioritizedSources(request)) {
            Optional<PriceQuote> nearest = historicalPriceCacheService.findNearestQuoteWithinWindow(
                    request, source, PRE_COVERAGE_NEAREST_WINDOW);
            if (nearest.isPresent()) {
                return nearest;
            }
        }
        return Optional.empty();
    }

    /**
     * RC-D (ADR-043) — pre-coverage-only projection of the bounded nearest-valid-bucket fallback,
     * exposed for replay so it can clamp a bot-derived ACQUIRE lot ({@code BOT_LEDGER}) whose event
     * predates the asset's first cached bucket. Such a lot is priced at normalization from net
     * stablecoin consumed and marked CONFIRMED, so it never enters {@link #resolve(PriceRequest)} where
     * the private fallback runs; this variant lets the replay market authority apply the same bounded
     * window ({@link #PRE_COVERAGE_NEAREST_WINDOW}) and source priority to genuinely pre-coverage lots
     * only, leaving in-coverage lots untouched.
     */
    public Optional<PriceQuote> resolvePreCoverageNearestBucket(PriceRequest request) {
        for (PriceSource source : prioritizedSources(request)) {
            Optional<PriceQuote> nearest = historicalPriceCacheService.findPreCoverageNearestQuote(
                    request, source, PRE_COVERAGE_NEAREST_WINDOW);
            if (nearest.isPresent()) {
                return nearest;
            }
        }
        return Optional.empty();
    }

    public List<PriceSource> prioritizedSources(PriceRequest request) {
        return prioritizedExternalSources(request).stream()
                .map(ExternalPriceSource::source)
                .toList();
    }

    private List<ExternalPriceSource> prioritizedExternalSources(PriceRequest request) {
        return externalSources.stream()
                .filter(source -> source.supports(request))
                .sorted(Comparator.comparingInt(source -> sourcePriority(request, source.source())))
                .toList();
    }

    private int sourcePriority(PriceRequest request, PriceSource source) {
        return switch (source) {
            case ECB -> 0;
            case BYBIT -> 1;
            case BINANCE -> 2;
            case DEFILLAMA -> 3;
            case COINGECKO -> 4;
            default -> 100;
        };
    }
}
