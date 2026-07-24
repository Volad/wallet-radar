package com.walletradar.application.costbasis.breakeven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ADR-062 config-plane loader (ADR-059 pattern). Reads {@code break-even-attribution.json} into an
 * immutable source→target map. Source keys are {@code CLUSTER:*} or {@code FAMILY:*}; targets are
 * {@code FAMILY:*}. Read-model only — no replay/AVCO effect.
 *
 * <p>ADR-062 (2026-07-23 Wave 3 amendment) adds an optional per-entry {@code foldHeldExposure}
 * flag (AC-9 / D8): when {@code true} the source family's <b>held</b> market basis and
 * ETH-equivalent covered quantity are folded into the target family's break-even denominator and
 * basis (not just its realized P&amp;L). Absent → {@code false} (P&amp;L-only rollup, the default
 * ADR-062 behaviour).</p>
 */
@Component
@RequiredArgsConstructor
public class BreakEvenAttributionLoader {

    static final String RESOURCE_PATH = "break-even-attribution.json";
    private static final String FAMILY_PREFIX = "FAMILY:";
    private static final String CLUSTER_PREFIX = "CLUSTER:";

    private final ObjectMapper objectMapper;

    public LoadedBreakEvenAttribution loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return load(inputStream, RESOURCE_PATH);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load break-even attribution from classpath", ex);
        }
    }

    LoadedBreakEvenAttribution load(InputStream inputStream, String sourceName) {
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            OffsetLane offsetLane = parseOffsetLane(root.path("offsetLane"), sourceName);
            JsonNode attributions = root.path("attributions");
            Map<String, String> sourceToTarget = new LinkedHashMap<>();
            Set<String> foldHeldExposureSources = new LinkedHashSet<>();
            if (attributions.isArray()) {
                for (JsonNode entry : attributions) {
                    String source = requiredText(entry, "source", sourceName);
                    String target = requiredText(entry, "target", sourceName);
                    if (!source.startsWith(CLUSTER_PREFIX) && !source.startsWith(FAMILY_PREFIX)) {
                        throw new IllegalStateException(
                                "break-even attribution source must be CLUSTER:* or FAMILY:* in " + sourceName + ": " + source);
                    }
                    if (!target.startsWith(FAMILY_PREFIX)) {
                        throw new IllegalStateException(
                                "break-even attribution target must be FAMILY:* in " + sourceName + ": " + target);
                    }
                    String existing = sourceToTarget.putIfAbsent(source, target);
                    if (existing != null && !existing.equals(target)) {
                        throw new IllegalStateException(
                                "Duplicate break-even attribution source with conflicting target in " + sourceName + ": " + source);
                    }
                    if (entry.path("foldHeldExposure").asBoolean(false)) {
                        foldHeldExposureSources.add(source);
                    }
                }
            }
            return new LoadedBreakEvenAttribution(
                    Collections.unmodifiableMap(sourceToTarget),
                    Collections.unmodifiableSet(foldHeldExposureSources),
                    offsetLane);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse break-even attribution from " + sourceName, ex);
        }
    }

    /**
     * ADR-062 (2026-07-21 amendment): optional top-level {@code offsetLane}. Absent/null/blank →
     * {@link OffsetLane#NET} (default). Accepts case-insensitive {@code NET}/{@code MARKET}; any other
     * value is rejected.
     */
    private static OffsetLane parseOffsetLane(JsonNode node, String sourceName) {
        if (node.isMissingNode() || node.isNull()) {
            return OffsetLane.NET;
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return OffsetLane.NET;
        }
        String normalized = text.toUpperCase(java.util.Locale.ROOT);
        if (OffsetLane.NET.name().equals(normalized)) {
            return OffsetLane.NET;
        }
        if (OffsetLane.MARKET.name().equals(normalized)) {
            return OffsetLane.MARKET;
        }
        throw new IllegalStateException(
                "break-even attribution offsetLane must be NET or MARKET in " + sourceName + ": " + text);
    }

    private static String requiredText(JsonNode node, String fieldName, String sourceName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalStateException("Missing " + fieldName + " in break-even attribution entry in " + sourceName);
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("Blank " + fieldName + " in break-even attribution entry in " + sourceName);
        }
        return text;
    }

    public record LoadedBreakEvenAttribution(
            Map<String, String> sourceToTarget,
            Set<String> foldHeldExposureSources,
            OffsetLane offsetLane) {
    }
}
