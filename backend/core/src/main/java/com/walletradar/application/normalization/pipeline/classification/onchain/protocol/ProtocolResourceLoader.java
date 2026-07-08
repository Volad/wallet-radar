package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads protocol-local metadata resources from {@code classpath:protocols/*.json}.
 */
@Component
public class ProtocolResourceLoader implements ProtocolResourceCatalog {

    private static final String RESOURCE_GLOB = "classpath*:protocols/*.json";

    private final Map<String, ProtocolResourceDefinition> resourcesByKey;

    public ProtocolResourceLoader(ObjectMapper objectMapper) {
        this.resourcesByKey = loadResources(objectMapper);
    }

    @Override
    public Optional<ProtocolResourceDefinition> find(String protocolName, String protocolVersion) {
        if (protocolName == null || protocolName.isBlank()) {
            return Optional.empty();
        }
        String directKey = canonicalKey(protocolName, protocolVersion);
        ProtocolResourceDefinition direct = resourcesByKey.get(directKey);
        if (direct != null) {
            return Optional.of(direct);
        }
        return resourcesByKey.values().stream()
                .filter(resource -> matchesAlias(resource, protocolName, protocolVersion))
                .findFirst();
    }

    private Map<String, ProtocolResourceDefinition> loadResources(ObjectMapper objectMapper) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(RESOURCE_GLOB);
            Map<String, ProtocolResourceDefinition> loaded = new LinkedHashMap<>();
            for (Resource resource : resources) {
                try (InputStream inputStream = resource.getInputStream()) {
                    ProtocolResourceDefinition definition = objectMapper.readValue(inputStream, ProtocolResourceDefinition.class);
                    loaded.put(canonicalKey(definition.protocol(), definition.version()), definition);
                }
            }
            return Map.copyOf(loaded);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load protocol-local resources", ex);
        }
    }

    private boolean matchesAlias(
            ProtocolResourceDefinition resource,
            String protocolName,
            String protocolVersion
    ) {
        if (resource.aliases() == null || resource.aliases().isEmpty()) {
            return false;
        }
        String canonicalVersion = normalize(protocolVersion);
        return resource.aliases().stream()
                .map(this::normalize)
                .anyMatch(alias -> alias.equals(normalize(protocolName)))
                && (canonicalVersion == null || canonicalVersion.equals(normalize(resource.version())));
    }

    private String canonicalKey(String protocolName, String protocolVersion) {
        String normalizedProtocol = normalize(protocolName);
        String normalizedVersion = normalize(protocolVersion);
        return normalizedVersion == null ? normalizedProtocol : normalizedProtocol + ":" + normalizedVersion;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
