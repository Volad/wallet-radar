package com.walletradar.pricing.resolver.external.binance;

import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceMappingService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Deterministic canonical-to-Binance symbol mapping.
 */
@Component
public class BinanceSymbolMapper {

    private static final Set<String> QUOTE_ASSETS = Set.of("USDT", "FDUSD", "USDC", "BUSD");
    private static final Set<String> NON_BINANCE_SYMBOLS = Set.of("XPL");

    private final ExternalPriceMappingService mappingService;

    public BinanceSymbolMapper(ExternalPriceMappingService mappingService) {
        this.mappingService = mappingService;
    }

    public List<String> candidateSymbols(PriceRequest request) {
        String baseSymbol = mappingService.canonicalMarketSymbol(request);
        if (baseSymbol.isBlank() || NON_BINANCE_SYMBOLS.contains(baseSymbol) || QUOTE_ASSETS.contains(baseSymbol)) {
            return List.of();
        }
        return List.of(
                baseSymbol + "USDT",
                baseSymbol + "FDUSD",
                baseSymbol + "USDC",
                baseSymbol + "BUSD"
        );
    }
}
