package com.walletradar.pricing.resolver.external;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
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
        return Optional.empty();
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
