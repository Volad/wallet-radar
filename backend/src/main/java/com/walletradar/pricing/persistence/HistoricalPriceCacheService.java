package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceBucketResolution;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic historical price cache facade.
 */
@Service
public class HistoricalPriceCacheService {

    private static final PriceBucketResolution DEFAULT_BUCKET_RESOLUTION = PriceBucketResolution.MINUTE;

    private final HistoricalPriceRepository historicalPriceRepository;

    public HistoricalPriceCacheService(HistoricalPriceRepository historicalPriceRepository) {
        this.historicalPriceRepository = Objects.requireNonNull(historicalPriceRepository, "historicalPriceRepository");
    }

    public Optional<PriceQuote> findQuote(PriceRequest request, PriceSource source) {
        Instant bucketStart = bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION);
        return historicalPriceRepository.findByAssetKeyAndBucketStartAndSource(request.assetKey(), bucketStart, source)
                .map(document -> new PriceQuote(
                        document.getPriceUsd(),
                        document.getSource(),
                        document.getBucketStart(),
                        document.getQuoteSymbol(),
                        document.getId()
                ));
    }

    public HistoricalPriceDocument storeQuote(PriceRequest request, PriceQuote quote) {
        Instant bucketStart = bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION);
        HistoricalPriceDocument document = new HistoricalPriceDocument();
        document.setId(HistoricalPriceDocument.composeId(request.assetKey(), bucketStart, quote.source()));
        document.setAssetKey(request.assetKey());
        document.setNetworkId(request.networkId());
        document.setSymbol(request.assetSymbol());
        document.setBucketStart(bucketStart);
        document.setBucketResolution(DEFAULT_BUCKET_RESOLUTION);
        document.setSource(quote.source());
        document.setPriceUsd(quote.unitPriceUsd());
        document.setQuoteSymbol(quote.quoteSymbol());
        document.setFetchedAt(quote.pricedAt());
        return historicalPriceRepository.save(document);
    }

    private Instant bucketStart(Instant instant, PriceBucketResolution bucketResolution) {
        return switch (bucketResolution) {
            case MINUTE -> instant.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> instant.truncatedTo(ChronoUnit.HOURS);
            case DAY -> instant.truncatedTo(ChronoUnit.DAYS);
        };
    }
}
