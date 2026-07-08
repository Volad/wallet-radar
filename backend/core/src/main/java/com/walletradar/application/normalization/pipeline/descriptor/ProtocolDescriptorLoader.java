package com.walletradar.application.normalization.pipeline.descriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ProtocolDescriptorLoader {

    static final String RESOURCE_PATTERN = "classpath:protocol-descriptors/*.json";
    private static final Logger log = LoggerFactory.getLogger(ProtocolDescriptorLoader.class);

    private final ObjectMapper objectMapper;

    public LoadedProtocolDescriptors loadFromClasspath() {
        Set<String> registryProtocols = readRegistryProtocolNames();
        Map<String, ProtocolDescriptor> descriptorsByProtocol = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(RESOURCE_PATTERN);
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    continue;
                }
                try (InputStream inputStream = resource.getInputStream()) {
                    ProtocolDescriptor descriptor = parseDescriptor(inputStream, resource.getFilename());
                    validateAgainstRegistry(descriptor, registryProtocols, resource.getFilename());
                    ProtocolDescriptor existing = descriptorsByProtocol.putIfAbsent(
                            normalizeProtocol(descriptor.protocol()),
                            descriptor
                    );
                    if (existing != null) {
                        throw new IllegalStateException(
                                "Duplicate protocol descriptor for " + descriptor.protocol() + " in " + resource.getFilename()
                        );
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load protocol descriptors", ex);
        }
        log.info("Loaded {} protocol descriptors from {}", descriptorsByProtocol.size(), RESOURCE_PATTERN);
        return new LoadedProtocolDescriptors(List.copyOf(descriptorsByProtocol.values()));
    }

    ProtocolDescriptor parseDescriptor(InputStream inputStream, String sourceName) {
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            String protocol = requiredText(root, "protocol", sourceName);
            String version = optionalText(root, "version").orElse(null);
            Set<ProtocolCapability> capabilities = readCapabilities(root.path("capabilities"), sourceName);
            return new ProtocolDescriptor(
                    protocol,
                    version,
                    capabilities,
                    readClassification(root.path("classification")),
                    readLpPresentation(root.path("lpPresentation")),
                    readLending(root.path("lending")),
                    readValuationSource(root.path("valuationSource"))
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse protocol descriptor from " + sourceName, ex);
        }
    }

    private Set<String> readRegistryProtocolNames() {
        Set<String> names = new HashSet<>();
        try (InputStream inputStream = new org.springframework.core.io.ClassPathResource(
                "protocol-registry.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            JsonNode contractsNode = root.path("contracts");
            contractsNode.fields().forEachRemaining(field -> {
                JsonNode entry = field.getValue();
                if (entry.isObject() && entry.has("protocol")) {
                    names.add(normalizeProtocol(entry.get("protocol").asText()));
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read protocol registry for descriptor validation", ex);
        }
        return names;
    }

    private static void validateAgainstRegistry(
            ProtocolDescriptor descriptor,
            Set<String> registryProtocols,
            String sourceName
    ) {
        String normalized = normalizeProtocol(descriptor.protocol());
        if (!registryProtocols.contains(normalized)) {
            throw new IllegalStateException(
                    "Protocol descriptor protocol '" + descriptor.protocol()
                            + "' is missing from protocol-registry.json (source=" + sourceName + ")"
            );
        }
    }

    private static Set<ProtocolCapability> readCapabilities(JsonNode node, String sourceName) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("Protocol descriptor capabilities must be a non-empty array in " + sourceName);
        }
        EnumSet<ProtocolCapability> capabilities = EnumSet.noneOf(ProtocolCapability.class);
        for (JsonNode item : node) {
            capabilities.add(ProtocolCapability.valueOf(item.asText().trim().toUpperCase(Locale.ROOT)));
        }
        return Set.copyOf(capabilities);
    }

    private static ProtocolDescriptor.ProtocolClassificationConfig readClassification(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String semanticClassifier = optionalText(node, "semanticClassifier").orElse(null);
        Set<String> families = new HashSet<>();
        if (node.path("supportedFamilies").isArray()) {
            node.path("supportedFamilies").forEach(item -> families.add(item.asText()));
        }
        return new ProtocolDescriptor.ProtocolClassificationConfig(
                semanticClassifier,
                families.isEmpty() ? Set.of() : Set.copyOf(families)
        );
    }

    private static ProtocolDescriptor.LpPresentationConfig readLpPresentation(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        List<String> patterns = new ArrayList<>();
        if (node.path("receiptTokenPatterns").isArray()) {
            node.path("receiptTokenPatterns").forEach(item -> patterns.add(item.asText()));
        }
        return new ProtocolDescriptor.LpPresentationConfig(
                optionalText(node, "positionIdentityStrategy").orElse(null),
                List.copyOf(patterns)
        );
    }

    private static ProtocolDescriptor.LendingConfig readLending(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new ProtocolDescriptor.LendingConfig(
                optionalText(node, "marketRateSource").orElse(null),
                node.path("supportsVariableDebt").asBoolean(false)
        );
    }

    private static ProtocolDescriptor.ValuationSourceConfig readValuationSource(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        List<String> fallbacks = new ArrayList<>();
        if (node.path("fallbackSources").isArray()) {
            node.path("fallbackSources").forEach(item -> fallbacks.add(item.asText()));
        }
        return new ProtocolDescriptor.ValuationSourceConfig(
                optionalText(node, "primarySource").orElse(null),
                List.copyOf(fallbacks)
        );
    }

    private static String requiredText(JsonNode node, String fieldName, String sourceName) {
        return optionalText(node, fieldName)
                .orElseThrow(() -> new IllegalStateException("Missing " + fieldName + " in " + sourceName));
    }

    private static java.util.Optional<String> optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return java.util.Optional.empty();
        }
        String text = value.asText().trim();
        return text.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(text);
    }

    private static String normalizeProtocol(String protocol) {
        return protocol == null ? "" : protocol.trim();
    }

    public record LoadedProtocolDescriptors(List<ProtocolDescriptor> descriptors) {
    }
}
