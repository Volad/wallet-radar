package com.walletradar.pricing;

import com.walletradar.pricing.resolver.CoinGeckoHistoricalResolver;
import com.walletradar.pricing.resolver.StablecoinResolver;
import com.walletradar.pricing.resolver.SwapDerivedResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Chain: Stablecoin → SwapDerived → CoinGecko → UNKNOWN per 03-accounting (Price Resolution).
 */
@Component
@RequiredArgsConstructor
public class HistoricalPriceResolverChain implements HistoricalPriceResolver {

    private final StablecoinResolver stablecoinResolver;
    private final SwapDerivedResolver swapDerivedResolver;
    private final CoinGeckoHistoricalResolver coinGeckoHistoricalResolver;

    @Override
    public PriceResolutionResult resolve(HistoricalPriceRequest request) {
        PriceResolutionResult r = stablecoinResolver.resolve(request);
        if (!r.isUnknown()) {
            return r;
        }
        r = swapDerivedResolver.resolve(request);
        if (!r.isUnknown()) {
            return r;
        }
        return coinGeckoHistoricalResolver.resolve(request);
    }
}
