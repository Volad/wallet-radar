package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.PriceSource;

import java.util.Map;
import java.util.Set;

/**
 * SPI for a single bulk latest-price data source.
 * Implementations fetch all available prices in ONE HTTP call and map native tickers to canonical symbols.
 *
 * <p>Writes ONLY to {@code current_price_quotes}. Must NEVER write to {@code historical_prices}.</p>
 */
public interface LatestPriceProvider {

    /** Identifies the underlying data source. */
    PriceSource source();

    /**
     * Priority order when multiple providers cover the same symbol.
     * Lower value = higher priority (wins in selection policy).
     */
    int priority();

    /**
     * Fetch latest prices for the wanted canonical symbols.
     * The implementation fetches ALL available tickers from the venue in one call and filters to {@code wantedSymbols}.
     *
     * <p>Must not throw: any venue error must be caught and an empty map returned so that
     * the calling refresh cycle can proceed with other providers.</p>
     *
     * @param wantedSymbolsWithKind canonical symbol → asset kind; providers may use kind to restrict
     *                              matching (e.g. Dzengi equity tickers should only match EQUITY-kinded symbols)
     * @return map of canonicalSymbol → quote; never null
     */
    Map<String, NormalizedLatestQuote> fetchAll(Map<String, TrackedPriceAssetDocument.Kind> wantedSymbolsWithKind);
}
