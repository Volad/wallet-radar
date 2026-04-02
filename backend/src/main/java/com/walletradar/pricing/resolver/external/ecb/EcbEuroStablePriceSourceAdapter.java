package com.walletradar.pricing.resolver.external.ecb;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceSource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Official ECB EUR/USD parity path for EUR-backed stablecoins like EURC.
 */
@Component
@Order(0)
public class EcbEuroStablePriceSourceAdapter implements ExternalPriceSource {

    private final EcbFxHistoricalClient historicalClient;

    public EcbEuroStablePriceSourceAdapter(EcbFxHistoricalClient historicalClient) {
        this.historicalClient = historicalClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.ECB;
    }

    @Override
    public boolean supports(PriceRequest request) {
        return CanonicalAssetCatalog.isEuroStablecoin(request.assetSymbol());
    }

    @Override
    public Optional<PriceQuote> resolve(PriceRequest request) {
        Optional<EcbFxHistoricalClient.EcbFxQuote> quote = historicalClient.fetchEurUsd(request.occurredAt());
        if (quote.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PriceQuote(
                quote.orElseThrow().usdPerEur(),
                PriceSource.ECB,
                quote.orElseThrow().pricedAt(),
                "USD",
                "ECB:EXR/D.USD.EUR.SP00.A"
        ));
    }
}
