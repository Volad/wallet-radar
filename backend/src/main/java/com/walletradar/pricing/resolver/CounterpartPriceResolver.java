package com.walletradar.pricing.resolver;

import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolver used by SwapDerivedResolver to resolve the counterpart token price (Stablecoin + CoinGecko only, no SwapDerived to avoid recursion).
 */
@Component("counterpartPriceResolver")
@RequiredArgsConstructor
public class CounterpartPriceResolver {

    private final StablecoinResolver stablecoinResolver;
    private final CoinGeckoHistoricalResolver coinGeckoHistoricalResolver;

    public PriceResolutionResult resolve(HistoricalPriceRequest request) {
        PriceResolutionResult r = stablecoinResolver.resolve(request);
        if (!r.isUnknown()) {
            return r;
        }
        return coinGeckoHistoricalResolver.resolve(request);
    }
}
