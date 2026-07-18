package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Cached Dzengi exchangeInfo symbol metadata for extraction routing.
 */
@Component
@RequiredArgsConstructor
public class DzengiSymbolMetadataCache {

    private final DzengiApiClient dzengiApiClient;
    private final Cache<String, SymbolMetadata> cache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    public SymbolMetadata resolve(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return SymbolMetadata.unknown(symbol);
        }
        return cache.get(symbol, this::load);
    }

    public void refreshAll() {
        cache.invalidateAll();
        JsonNode symbols = dzengiApiClient.fetchExchangeInfo().path("symbols");
        if (!symbols.isArray()) {
            return;
        }
        for (JsonNode row : symbols) {
            String symbol = row.path("symbol").asText(null);
            if (symbol != null && !symbol.isBlank()) {
                cache.put(symbol, fromNode(symbol, row));
            }
        }
    }

    private SymbolMetadata load(String symbol) {
        JsonNode symbols = dzengiApiClient.fetchExchangeInfo().path("symbols");
        if (!symbols.isArray()) {
            return SymbolMetadata.unknown(symbol);
        }
        for (JsonNode row : symbols) {
            if (symbol.equals(row.path("symbol").asText())) {
                return fromNode(symbol, row);
            }
        }
        return SymbolMetadata.unknown(symbol);
    }

    private static SymbolMetadata fromNode(String symbol, JsonNode row) {
        String base = upper(row.path("baseAsset").asText(null));
        String quote = upper(row.path("quoteAsset").asText(null));
        String marketType = upper(row.path("marketType").asText("SPOT"));
        String assetType = upper(row.path("assetType").asText("CRYPTOCURRENCY"));
        boolean leverage = "LEVERAGE".equals(marketType) || symbol.endsWith("_LEVERAGE") || symbol.endsWith(".");
        return new SymbolMetadata(symbol, base, quote, marketType, assetType, leverage);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public record SymbolMetadata(
            String symbol,
            String baseAsset,
            String quoteAsset,
            String marketType,
            String assetType,
            boolean leverageOrCfd
    ) {
        static SymbolMetadata unknown(String symbol) {
            boolean cfd = symbol != null && (symbol.endsWith("_LEVERAGE") || symbol.endsWith("."));
            return new SymbolMetadata(symbol, null, null, cfd ? "LEVERAGE" : "SPOT", "UNKNOWN", cfd);
        }
    }
}
