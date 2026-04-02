package com.walletradar.pricing.resolver.external.bybit;

import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.resolver.external.ExternalPriceMappingService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Deterministic canonical-to-Bybit spot symbol mapping.
 */
@Component
public class BybitSymbolMapper {

    private static final Set<String> QUOTE_ASSETS = Set.of("USDT", "USDC");
    private static final Set<String> NON_BYBIT_SYMBOLS = Set.of("XPL");

    private final ExternalPriceMappingService mappingService;

    public BybitSymbolMapper(ExternalPriceMappingService mappingService) {
        this.mappingService = mappingService;
    }

    public List<String> candidateSymbols(PriceRequest request) {
        String baseSymbol = mappingService.canonicalMarketSymbol(request);
        if (baseSymbol.isBlank() || NON_BYBIT_SYMBOLS.contains(baseSymbol) || QUOTE_ASSETS.contains(baseSymbol)) {
            return List.of();
        }
        return List.of(
                baseSymbol + "USDT",
                baseSymbol + "USDC"
        );
    }
}
