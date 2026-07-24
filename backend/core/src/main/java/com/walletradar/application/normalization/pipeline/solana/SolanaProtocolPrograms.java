package com.walletradar.application.normalization.pipeline.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry-backed view over the Solana program IDs declared in
 * {@code classpath:protocol-registry.json} (WS-8b / WS-1 DoD).
 *
 * <p>The protocol registry is the single source of truth for which on-chain programs belong to a
 * given protocol. Instead of duplicating those base58 program IDs as hardcoded constants that can
 * silently drift from the registry, callers derive the recognised set from here (mirroring the
 * {@link TonJettonMetadataRegistry} static-load pattern). Fail-fast on a missing/malformed
 * resource so a classification path never runs against an empty program set.</p>
 */
public final class SolanaProtocolPrograms {

    private static final String RESOURCE = "protocol-registry.json";
    private static final String SOLANA_NETWORK = "SOLANA";
    private static final String JUPITER_LEND_PROTOCOL = "jupiterlend";

    /** Program IDs (case-sensitive base58) grouped by lower-cased {@code protocol} for SOLANA. */
    private static final Map<String, Set<String>> PROGRAMS_BY_PROTOCOL = load();

    private SolanaProtocolPrograms() {
    }

    /**
     * All Jupiter Lend program IDs (borrow / earn / liquidity sub-programs) declared for SOLANA.
     * Jupiter Lend spreads a single logical action across these cooperating programs, so
     * classification must recognise any of them, not only the borrow router.
     */
    public static Set<String> jupiterLendProgramIds() {
        return PROGRAMS_BY_PROTOCOL.getOrDefault(JUPITER_LEND_PROTOCOL, Set.of());
    }

    /** Solana program IDs declared under the given {@code protocol} (case-insensitive), or empty. */
    public static Set<String> programIdsForProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return Set.of();
        }
        return PROGRAMS_BY_PROTOCOL.getOrDefault(protocol.trim().toLowerCase(Locale.ROOT), Set.of());
    }

    private static Map<String, Set<String>> load() {
        try (InputStream inputStream =
                     SolanaProtocolPrograms.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(inputStream);
            JsonNode contracts = root.path("contracts");
            if (!contracts.isObject()) {
                throw new IllegalStateException("Malformed " + RESOURCE + ": contracts must be an object");
            }
            Map<String, Set<String>> byProtocol = new java.util.HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode entry = field.getValue();
                if (!entry.isObject() || entry.path("protocol").isMissingNode()) {
                    continue;
                }
                if (!declaresNetwork(entry.path("networks"), SOLANA_NETWORK)) {
                    continue;
                }
                String protocol = entry.path("protocol").asText().trim().toLowerCase(Locale.ROOT);
                if (protocol.isEmpty()) {
                    continue;
                }
                String address = entry.path("address").isMissingNode()
                        ? field.getKey()
                        : entry.path("address").asText();
                if (address == null || address.isBlank()) {
                    continue;
                }
                byProtocol.computeIfAbsent(protocol, key -> new LinkedHashSet<>()).add(address.trim());
            }
            Map<String, Set<String>> immutable = new java.util.HashMap<>();
            byProtocol.forEach((protocol, ids) -> immutable.put(protocol, Set.copyOf(ids)));
            return Map.copyOf(immutable);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    private static boolean declaresNetwork(JsonNode networks, String network) {
        if (!networks.isArray()) {
            return false;
        }
        for (JsonNode item : networks) {
            if (network.equalsIgnoreCase(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
