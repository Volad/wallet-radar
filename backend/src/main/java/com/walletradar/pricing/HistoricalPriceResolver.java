package com.walletradar.pricing;

/**
 * Resolves historical USD price for an asset at a given date. Chain: Stablecoin → SwapDerived → CoinGecko → UNKNOWN.
 */
public interface HistoricalPriceResolver {

    /**
     * Resolve USD price for the request. Returns UNKNOWN when all resolvers in the chain fail.
     */
    PriceResolutionResult resolve(HistoricalPriceRequest request);
}
