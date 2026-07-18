package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for contract → token metadata (symbol / decimals) overrides, loaded once
 * from {@code classpath:token-metadata.json} (fail-fast on missing/malformed config).
 *
 * <p>Two intentionally separate groups preserve the exact pre-config coverage of two independent
 * lookup paths — merging them would change normalization output:</p>
 * <ul>
 *   <li>{@code fallbackTokens} — backs {@link TokenSymbolFallbackSupport}: fallback symbol/decimals
 *       applied only when the explorer omits the field, plus an authoritative {@code decimalOverride}
 *       that always replaces a wrong explorer-provided decimal.</li>
 *   <li>{@code builderTokens} — backs {@code OnChainNormalizedTransactionBuilder} Fluid/ERC-20
 *       evidence decoding (its own defaults: symbol = raw contract, decimals = 18).</li>
 * </ul>
 *
 * <p>Loaded eagerly and works identically under Spring and in plain unit tests.</p>
 */
public final class TokenMetadataRegistry {

    private static final String RESOURCE = "token-metadata.json";

    private static final Definition DEFINITION = load();

    private TokenMetadataRegistry() {
    }

    public static String fallbackSymbol(String contract) {
        Entry entry = lookup(DEFINITION.fallbackTokens(), contract);
        return entry == null ? null : entry.symbol();
    }

    public static Integer fallbackDecimals(String contract) {
        Entry entry = lookup(DEFINITION.fallbackTokens(), contract);
        return entry == null ? null : entry.decimals();
    }

    public static Integer decimalOverride(String contract) {
        Entry entry = lookup(DEFINITION.fallbackTokens(), contract);
        return entry == null ? null : entry.decimalOverride();
    }

    public static String builderSymbol(String contract) {
        Entry entry = lookup(DEFINITION.builderTokens(), contract);
        return entry == null ? null : entry.symbol();
    }

    public static Integer builderDecimals(String contract) {
        Entry entry = lookup(DEFINITION.builderTokens(), contract);
        return entry == null ? null : entry.decimals();
    }

    private static Entry lookup(Map<String, Entry> group, String contract) {
        if (group == null || contract == null || contract.isBlank()) {
            return null;
        }
        return group.get(contract.trim().toLowerCase(Locale.ROOT));
    }

    private static Definition load() {
        try (InputStream inputStream =
                     TokenMetadataRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            Definition definition = new ObjectMapper().readValue(inputStream, Definition.class);
            if (definition == null) {
                throw new IllegalStateException("Malformed " + RESOURCE);
            }
            return new Definition(
                    normalizeKeys(definition.fallbackTokens()),
                    normalizeKeys(definition.builderTokens()));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    private static Map<String, Entry> normalizeKeys(Map<String, Entry> group) {
        if (group == null || group.isEmpty()) {
            return Map.of();
        }
        Map<String, Entry> normalized = new LinkedHashMap<>();
        group.forEach((contract, entry) -> {
            if (contract != null && !contract.isBlank() && entry != null) {
                normalized.put(contract.trim().toLowerCase(Locale.ROOT), entry);
            }
        });
        return Map.copyOf(normalized);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(
            Map<String, Entry> fallbackTokens,
            Map<String, Entry> builderTokens
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(
            String symbol,
            Integer decimals,
            Integer decimalOverride
    ) {
    }
}
