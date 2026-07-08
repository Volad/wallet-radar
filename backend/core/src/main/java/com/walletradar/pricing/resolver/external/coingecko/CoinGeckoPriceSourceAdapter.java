package com.walletradar.pricing.resolver.external.coingecko;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Bounded CoinGecko historical fallback.
 */
@Component
@Order(1)
public class CoinGeckoPriceSourceAdapter implements ExternalPriceSource {

    private final CoinGeckoAssetMapper assetMapper;
    private final CoinGeckoHistoricalClient historicalClient;

    public CoinGeckoPriceSourceAdapter(
            CoinGeckoAssetMapper assetMapper,
            CoinGeckoHistoricalClient historicalClient
    ) {
        this.assetMapper = assetMapper;
        this.historicalClient = historicalClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.COINGECKO;
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        Optional<String> coinId = assetMapper.coinId(request);
        if (coinId.isEmpty()) {
            return Optional.empty();
        }
        Optional<CoinGeckoHistoricalClient.CoinGeckoHistory> history = historicalClient.fetchHistory(
                coinId.orElseThrow(),
                request.occurredAt()
        );
        if (history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PriceQuote(
                history.orElseThrow().priceUsd(),
                PriceSource.COINGECKO,
                history.orElseThrow().pricedAt(),
                "USD",
                history.orElseThrow().coinId()
        ));
    }
}
