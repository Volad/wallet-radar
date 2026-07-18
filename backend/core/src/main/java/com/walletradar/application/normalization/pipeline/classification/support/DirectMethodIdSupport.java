package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared direct selector-to-type mapping used by the selector fallback stage.
 * Selectors that collide across protocol families belong to verified family classifiers,
 * not this global fallback.
 *
 * <p>The mapping is the single source of truth in {@code classpath:direct-method-types.json}
 * (loaded once, fail-fast on missing/malformed config or unknown enum values) so it can be
 * inspected and edited without a code change. The class loads eagerly and works identically under
 * Spring and in plain unit tests.</p>
 */
public final class DirectMethodIdSupport {

    private static final String RESOURCE = "direct-method-types.json";

    private static final Map<String, NormalizedTransactionType> METHOD_ID_TYPES = load();

    private DirectMethodIdSupport() {
    }

    public static NormalizedTransactionType resolveType(String methodId) {
        if (methodId == null) {
            return null;
        }
        // Exact lookup (behaviour-preserving): keys are already lowercase and callers pass
        // lowercase selectors, matching the pre-config Map.get(methodId) semantics.
        return METHOD_ID_TYPES.get(methodId);
    }

    private static Map<String, NormalizedTransactionType> load() {
        try (InputStream inputStream =
                     DirectMethodIdSupport.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            Definition definition = new ObjectMapper().readValue(inputStream, Definition.class);
            if (definition == null || definition.methodTypes() == null || definition.methodTypes().isEmpty()) {
                throw new IllegalStateException("Malformed or empty " + RESOURCE);
            }
            Map<String, NormalizedTransactionType> resolved = new LinkedHashMap<>();
            definition.methodTypes().forEach((selector, typeName) -> {
                if (selector == null || selector.isBlank() || typeName == null || typeName.isBlank()) {
                    throw new IllegalStateException("Blank selector/type in " + RESOURCE);
                }
                resolved.put(selector.trim().toLowerCase(Locale.ROOT), parseType(typeName));
            });
            return Map.copyOf(resolved);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    private static NormalizedTransactionType parseType(String typeName) {
        try {
            return NormalizedTransactionType.valueOf(typeName.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Unknown NormalizedTransactionType '" + typeName + "' in " + RESOURCE, ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(Map<String, String> methodTypes) {
    }
}
