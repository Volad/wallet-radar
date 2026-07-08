package com.walletradar.application.normalization.pipeline.classification.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ProtocolRegistryLoader {

    static final String RESOURCE_PATH = "protocol-registry.json";
    private static final Logger log = LoggerFactory.getLogger(ProtocolRegistryLoader.class);

    private final ObjectMapper objectMapper;

    public LoadedProtocolRegistry loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return load(inputStream, RESOURCE_PATH);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load protocol registry from classpath", ex);
        }
    }

    LoadedProtocolRegistry load(InputStream inputStream, String sourceName) {
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            Set<ProtocolRegistryFamily> declaredFamilies = readDeclaredFamilies(root.path("families"));
            Set<NetworkId> supportedNetworks = readSupportedNetworks(root.path("supported_networks"));
            Map<RegistryKey, ProtocolRegistryEntry> entriesByKey = new LinkedHashMap<>();
            Map<String, String> methodDescriptions = readMethodDescriptions(root.path("method_ids"));
            Set<NetworkId> coveredNetworks = EnumSet.noneOf(NetworkId.class);

            JsonNode contracts = requireObject(root, "contracts", sourceName);
            Iterator<Map.Entry<String, JsonNode>> fields = contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode entryNode = field.getValue();
                if (!entryNode.isObject() || entryNode.path("family").isMissingNode()) {
                    continue;
                }

                String entryAddress = optionalText(entryNode, "address").orElse(field.getKey());
                String normalizedAddress = OnChainRawTransactionView.normalizeAddress(entryAddress);
                if (normalizedAddress == null) {
                    throw new IllegalStateException("Invalid contract address in protocol registry: " + entryAddress);
                }

                ProtocolRegistryFamily family = parseEnum(ProtocolRegistryFamily.class, requiredText(entryNode, "family", normalizedAddress));
                if (!declaredFamilies.contains(family)) {
                    throw new IllegalStateException("Family " + family + " is missing from top-level families in " + sourceName);
                }
                ProtocolRegistryRole role = parseEnum(ProtocolRegistryRole.class, requiredText(entryNode, "role", normalizedAddress));
                ConfidenceLevel confidence = parseEnum(ConfidenceLevel.class, requiredText(entryNode, "confidence", normalizedAddress));
                ProtocolRegistryEventType eventType = optionalText(entryNode, "event_type")
                        .map(value -> parseEnum(ProtocolRegistryEventType.class, value))
                        .orElse(null);
                ProtocolRegistrySpecialHandlerType specialHandler = optionalText(entryNode, "specialHandler")
                        .map(value -> parseEnum(ProtocolRegistrySpecialHandlerType.class, value))
                        .orElse(null);
                boolean decomposeByLegs = entryNode.path("decomposeByLegs").asBoolean(false);
                Set<NetworkId> networks = readEntryNetworks(entryNode.path("networks"), normalizedAddress);
                coveredNetworks.addAll(networks);

                // RC-5 (ADR-018): a staking/farming wrapper that custodies a position-manager NFT
                // declares the underlying NFPM so its staked LP flows canonicalize to the underlying
                // position identity instead of forming a duplicate wrapper-keyed pool.
                String underlyingPositionManager = optionalText(entryNode, "underlyingPositionManager")
                        .map(OnChainRawTransactionView::normalizeAddress)
                        .orElse(null);

                ProtocolRegistryEntry entry = new ProtocolRegistryEntry(
                        normalizedAddress,
                        Collections.unmodifiableSet(networks),
                        family,
                        role,
                        eventType,
                        confidence,
                        optionalText(entryNode, "protocol").orElse(optionalText(entryNode, "name").orElse(null)),
                        optionalText(entryNode, "version").orElse(null),
                        decomposeByLegs,
                        specialHandler,
                        underlyingPositionManager
                );

                for (NetworkId networkId : networks) {
                    RegistryKey key = new RegistryKey(networkId, normalizedAddress);
                    ProtocolRegistryEntry existing = entriesByKey.putIfAbsent(key, entry);
                    if (existing != null) {
                        throw new IllegalStateException("Duplicate protocol registry contract mapping for " + networkId + " / " + normalizedAddress);
                    }
                }
            }

            Set<NetworkId> uncoveredNetworks = EnumSet.copyOf(supportedNetworks);
            uncoveredNetworks.removeAll(coveredNetworks);
            if (!uncoveredNetworks.isEmpty()) {
                log.warn("Protocol registry supported networks without contract coverage: {}", uncoveredNetworks);
            }

            return new LoadedProtocolRegistry(
                    Collections.unmodifiableMap(entriesByKey),
                    Collections.unmodifiableMap(methodDescriptions),
                    Collections.unmodifiableSet(supportedNetworks)
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse protocol registry from " + sourceName, ex);
        }
    }

    private Set<ProtocolRegistryFamily> readDeclaredFamilies(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalStateException("protocol registry families must be an array");
        }
        EnumSet<ProtocolRegistryFamily> families = EnumSet.noneOf(ProtocolRegistryFamily.class);
        for (JsonNode item : node) {
            families.add(parseEnum(ProtocolRegistryFamily.class, item.asText()));
        }
        return families;
    }

    private Set<NetworkId> readSupportedNetworks(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalStateException("protocol registry supported_networks must be an array");
        }
        EnumSet<NetworkId> networks = EnumSet.noneOf(NetworkId.class);
        for (JsonNode item : node) {
            networks.add(parseEnum(NetworkId.class, item.asText()));
        }
        return networks;
    }

    private Map<String, String> readMethodDescriptions(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> methodDescriptions = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if ("description".equals(field.getKey())) {
                continue;
            }
            String selector = normalizeSelector(field.getKey());
            if (selector == null) {
                throw new IllegalStateException("Invalid protocol registry method selector: " + field.getKey());
            }
            methodDescriptions.put(selector, field.getValue().asText());
        }
        return methodDescriptions;
    }

    private Set<NetworkId> readEntryNetworks(JsonNode node, String address) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("Contract entry must declare at least one network: " + address);
        }
        EnumSet<NetworkId> networks = EnumSet.noneOf(NetworkId.class);
        for (JsonNode item : node) {
            networks.add(parseEnum(NetworkId.class, item.asText()));
        }
        return networks;
    }

    private JsonNode requireObject(JsonNode root, String fieldName, String sourceName) {
        JsonNode node = root.path(fieldName);
        if (!node.isObject()) {
            throw new IllegalStateException("protocol registry field " + fieldName + " must be an object in " + sourceName);
        }
        return node;
    }

    private String requiredText(JsonNode node, String fieldName, String address) {
        return optionalText(node, fieldName)
                .orElseThrow(() -> new IllegalStateException("Missing " + fieldName + " for contract " + address));
    }

    private Optional<String> optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asText().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String value) {
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported " + enumType.getSimpleName() + " value: " + value, ex);
        }
    }

    private String normalizeSelector(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("^0x[0-9a-f]{8}$") ? normalized : null;
    }

    record LoadedProtocolRegistry(
            Map<RegistryKey, ProtocolRegistryEntry> entriesByKey,
            Map<String, String> methodDescriptions,
            Set<NetworkId> supportedNetworks
    ) {
    }

    record RegistryKey(NetworkId networkId, String contractAddress) {
    }
}
