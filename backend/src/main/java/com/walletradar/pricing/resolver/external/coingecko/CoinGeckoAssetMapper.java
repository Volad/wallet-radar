package com.walletradar.pricing.resolver.external.coingecko;

import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceMappingService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Deterministic asset-id mapping for bounded CoinGecko fallback.
 */
@Component
public class CoinGeckoAssetMapper {

    private final ExternalPriceMappingService mappingService;

    public CoinGeckoAssetMapper(ExternalPriceMappingService mappingService) {
        this.mappingService = mappingService;
    }

    public Optional<String> coinId(PriceRequest request) {
        return mappingService.coinGeckoId(request);
    }
}
