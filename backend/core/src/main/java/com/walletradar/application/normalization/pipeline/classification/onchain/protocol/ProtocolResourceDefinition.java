package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protocol-local discovery metadata loaded from classpath resources.
 *
 * <p>The optional descriptor-plane fields ({@code semanticClassifier}, {@code lpPresentation},
 * {@code lending}, {@code valuationSource}) hold protocol-level metadata folded in from the
 * retired {@code protocol-descriptors/*.json} plane, making {@code protocols/*.json} the single
 * source of truth. They are canonical metadata for future consumers and are intentionally not
 * wired into any runtime behavior yet.
 *
 * <p>{@code handlerContracts} (Wave W3) holds the per-network set of protocol handler/vault
 * contract addresses previously hardcoded in Java support classes (e.g.
 * {@code GmxV2HandlerRegistry}). Keys are network names, values are lowercase {@code 0x}
 * addresses; membership is consumed network-agnostically via {@link #handlerContractAddresses()}.
 *
 * <p>{@code contractSets} (Wave W7) is a generic, key-partitioned set of protocol contract
 * addresses previously hardcoded in Java (e.g. {@code EtherFiOftBridgeInClassifier},
 * {@code PancakeMasterChefEnricher}). Unlike {@code handlerContracts}, the keys are meaningful
 * partitions that MUST stay distinct — either semantic roles (e.g. {@code weethOftTokens},
 * {@code minterProxies}, where a consumer requires one address in one role and another in a
 * different role) or network ids (e.g. {@code BSC}, {@code ETHEREUM}, mapping each chain to its
 * per-network contract). Values are lowercase {@code 0x} addresses; a single partition's set is
 * read via {@link #contractSet(String)}, or the whole map via the record accessor
 * {@link #contractSets()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProtocolResourceDefinition(
        String key,
        String protocol,
        String version,
        List<String> aliases,
        List<String> capabilities,
        List<String> families,
        List<String> clarificationHints,
        ProtocolResourceMarkers markers,
        String semanticClassifier,
        LpPresentationConfig lpPresentation,
        LendingConfig lending,
        ValuationSourceConfig valuationSource,
        Map<String, List<String>> handlerContracts,
        Map<String, List<String>> contractSets
) {

    public ProtocolResourceDefinition {
        aliases = immutableList(aliases);
        capabilities = immutableList(capabilities);
        families = immutableList(families);
        clarificationHints = immutableList(clarificationHints);
        markers = markers == null ? ProtocolResourceMarkers.empty() : markers;
        handlerContracts = ProtocolResourceMarkers.normalizeMap(handlerContracts);
        contractSets = ProtocolResourceMarkers.normalizeMap(contractSets);
    }

    /**
     * Flattened, network-agnostic set of handler/vault contract addresses (lowercase). Used by the
     * runtime binder that populates {@code GmxV2HandlerRegistry} and equivalents.
     */
    public Set<String> handlerContractAddresses() {
        return handlerContracts.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Normalized (lowercase) set of contract addresses registered under a semantic {@code role}
     * in {@code contractSets}, or an empty set if the role is absent. Roles are matched
     * case-insensitively.
     */
    public Set<String> contractSet(String role) {
        String normalizedRole = normalize(role);
        if (normalizedRole == null) {
            return Set.of();
        }
        return Set.copyOf(contractSets.getOrDefault(normalizedRole, List.of()));
    }

    public boolean matchesMethodSelector(String group, String selector) {
        return markers.matchesMethodSelector(group, selector);
    }

    public boolean matchesFunctionMarker(String group, String functionName) {
        return markers.matchesFunctionMarker(group, functionName);
    }

    public boolean matchesEventName(String group, String eventName) {
        return markers.matchesEventName(group, eventName);
    }

    public boolean matchesEventTopic(String group, String eventTopic) {
        return markers.matchesEventTopic(group, eventTopic);
    }

    public boolean matchesSubcallSelector(String group, String selector) {
        return markers.matchesSubcallSelector(group, selector);
    }

    public boolean matchesAssetMarker(String group, String assetValue) {
        return markers.matchesAssetMarker(group, assetValue);
    }

    public List<String> methodSelectors(String group) {
        return markers.methodSelectors(group);
    }

    public List<String> functionMarkers(String group) {
        return markers.functionMarkers(group);
    }

    public List<String> assetMarkers(String group) {
        return markers.assetMarkers(group);
    }

    public List<String> eventTopics(String group) {
        return markers.eventTopics(group);
    }

    private static List<String> immutableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Optional LP-presentation metadata folded in from the retired descriptor plane.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LpPresentationConfig(
            String positionIdentityStrategy,
            List<String> receiptTokenPatterns
    ) {
        public LpPresentationConfig {
            receiptTokenPatterns = immutableList(receiptTokenPatterns);
        }
    }

    /**
     * Optional lending metadata folded in from the retired descriptor plane.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LendingConfig(
            String marketRateSource,
            boolean supportsVariableDebt
    ) {
    }

    /**
     * Optional valuation-source metadata folded in from the retired descriptor plane.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValuationSourceConfig(
            String primarySource,
            List<String> fallbackSources
    ) {
        public ValuationSourceConfig {
            fallbackSources = immutableList(fallbackSources);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProtocolResourceMarkers(
            Map<String, List<String>> methodSelectors,
            Map<String, List<String>> functionNames,
            Map<String, List<String>> eventNames,
            Map<String, List<String>> eventTopics,
            Map<String, List<String>> subcallSelectors,
            Map<String, List<String>> assetMarkers
    ) {

        public ProtocolResourceMarkers {
            methodSelectors = normalizeMap(methodSelectors);
            functionNames = normalizeMap(functionNames);
            eventNames = normalizeMap(eventNames);
            eventTopics = normalizeMap(eventTopics);
            subcallSelectors = normalizeMap(subcallSelectors);
            assetMarkers = normalizeMap(assetMarkers);
        }

        public static ProtocolResourceMarkers empty() {
            return new ProtocolResourceMarkers(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        public boolean matchesMethodSelector(String group, String selector) {
            String normalizedSelector = normalize(selector);
            return normalizedSelector != null && methodSelectors(group).contains(normalizedSelector);
        }

        public boolean matchesFunctionMarker(String group, String functionName) {
            String normalizedFunctionName = normalize(functionName);
            return normalizedFunctionName != null && functionMarkers(group).stream()
                    .anyMatch(normalizedFunctionName::contains);
        }

        public boolean matchesEventName(String group, String eventName) {
            String normalizedEventName = normalize(eventName);
            return normalizedEventName != null && eventMarkers(group).contains(normalizedEventName);
        }

        public boolean matchesEventTopic(String group, String eventTopic) {
            String normalizedEventTopic = normalize(eventTopic);
            return normalizedEventTopic != null && eventTopics(group).contains(normalizedEventTopic);
        }

        public boolean matchesSubcallSelector(String group, String selector) {
            String normalizedSelector = normalize(selector);
            return normalizedSelector != null && subcallSelectors(group).contains(normalizedSelector);
        }

        public boolean matchesAssetMarker(String group, String assetValue) {
            String normalizedAssetValue = normalize(assetValue);
            return normalizedAssetValue != null && assetMarkers(group).stream()
                    .anyMatch(normalizedAssetValue::contains);
        }

        public List<String> methodSelectors(String group) {
            return values(methodSelectors, group);
        }

        public List<String> functionMarkers(String group) {
            return values(functionNames, group);
        }

        public List<String> eventMarkers(String group) {
            return values(eventNames, group);
        }

        public List<String> subcallSelectors(String group) {
            return values(subcallSelectors, group);
        }

        public List<String> assetMarkers(String group) {
            return values(assetMarkers, group);
        }

        public List<String> eventTopics(String group) {
            return values(eventTopics, group);
        }

        private static Map<String, List<String>> normalizeMap(Map<String, List<String>> groups) {
            if (groups == null || groups.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> normalized = new LinkedHashMap<>();
            groups.forEach((group, values) -> {
                String normalizedGroup = normalize(group);
                if (normalizedGroup != null) {
                    normalized.put(normalizedGroup, values == null ? List.of() : values.stream()
                            .map(ProtocolResourceDefinition::normalize)
                            .filter(value -> value != null && !value.isBlank())
                            .distinct()
                            .toList());
                }
            });
            return Map.copyOf(normalized);
        }

        private static List<String> values(Map<String, List<String>> groups, String group) {
            if (groups == null || groups.isEmpty()) {
                return List.of();
            }
            String normalizedGroup = normalize(group);
            if (normalizedGroup == null) {
                return List.of();
            }
            return groups.getOrDefault(normalizedGroup, List.of());
        }
    }
}
