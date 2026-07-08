package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Protocol-local discovery metadata loaded from classpath resources.
 */
public record ProtocolResourceDefinition(
        String key,
        String protocol,
        String version,
        List<String> aliases,
        List<String> capabilities,
        List<String> families,
        List<String> clarificationHints,
        ProtocolResourceMarkers markers
) {

    public ProtocolResourceDefinition {
        aliases = immutableList(aliases);
        capabilities = immutableList(capabilities);
        families = immutableList(families);
        clarificationHints = immutableList(clarificationHints);
        markers = markers == null ? ProtocolResourceMarkers.empty() : markers;
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

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

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
