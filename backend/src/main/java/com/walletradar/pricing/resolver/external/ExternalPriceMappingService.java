package com.walletradar.pricing.resolver.external;

import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.domain.PriceRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Shared deterministic asset mapping for external pricing sources.
 */
@Component
public class ExternalPriceMappingService {

    public String canonicalMarketSymbol(PriceRequest request) {
        return CanonicalAssetCatalog.canonicalMarketSymbol(request.assetSymbol());
    }

    public Optional<String> coinGeckoId(PriceRequest request) {
        return CanonicalAssetCatalog.coinGeckoId(request.assetSymbol());
    }
}
