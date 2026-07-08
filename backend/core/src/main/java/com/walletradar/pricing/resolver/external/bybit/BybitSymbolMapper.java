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
        List<String> baseSymbols = mappingService.exchangeMarketSymbols(request);
        if (baseSymbols.isEmpty()) {
            return List.of();
        }
        return baseSymbols.stream()
                .filter(baseSymbol -> !baseSymbol.isBlank())
                .filter(baseSymbol -> !NON_BYBIT_SYMBOLS.contains(baseSymbol))
                .filter(baseSymbol -> !QUOTE_ASSETS.contains(baseSymbol))
                .flatMap(baseSymbol -> List.of(
                        baseSymbol + "USDT",
                        baseSymbol + "USDC"
                ).stream())
                .distinct()
                .toList();
    }
}
