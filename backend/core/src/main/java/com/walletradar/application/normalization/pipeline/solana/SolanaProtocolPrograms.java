package com.walletradar.application.normalization.pipeline.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry-backed view over the Solana program IDs declared in
 * {@code classpath:protocol-registry.json} (WS-8b / WS-1 DoD / W12).
 *
 * <p>Single source of truth for every recognised Solana program — base58 address, protocol name,
 * family, classifier key, and display name. No program constant is duplicated in application code.
 * Fail-fast at class initialisation when an expected program is missing so classification paths
 * never run against a stale program set.</p>
 *
 * <h2>Registry contract</h2>
 * <ul>
 *   <li>Each entry that must be dispatch-visible in the classifier carries a {@code classifier_key}
 *       field (e.g. {@code "meteora-dlmm"}) and a human-readable {@code name} (the display name
 *       the classifier emits).</li>
 *   <li>Entries without a {@code family} field (e.g. Bubblegum / compressed-NFT) are loaded for
 *       address-dispatch only; the classifier emits its own type/family for those.</li>
 *   <li>Entries are keyed by their unique {@code (protocol, version)} pair, which the named
 *       constants below use for fail-fast startup validation.</li>
 * </ul>
 */
public final class SolanaProtocolPrograms {

    private static final String RESOURCE = "protocol-registry.json";
    private static final String SOLANA_NETWORK = "SOLANA";
    private static final String JUPITER_LEND_PROTOCOL = "jupiterlend";

    /** Program IDs (case-sensitive base58) grouped by lower-cased {@code protocol} for SOLANA. */
    private static final Map<String, Set<String>> PROGRAMS_BY_PROTOCOL = load();

    /**
     * Reverse lookup: case-sensitive base58 address → {@link SolanaProgramClass}.
     * Loaded from the same registry pass as {@link #PROGRAMS_BY_PROTOCOL}.
     */
    private static final Map<String, SolanaProgramClass> BY_ADDRESS = loadByAddress();

    /**
     * Secondary index: {@code "protocol:version"} (case-sensitive, as in registry) → address.
     * Used only by the named program-ID constants below to bootstrap fail-fast.
     */
    private static final Map<String, String> ADDRESS_BY_PROTO_VERSION = buildProtoVersionIndex(BY_ADDRESS);

    // -----------------------------------------------------------------------
    // Named program-ID constants (W12): loaded from registry, never hardcoded.
    // Fail-fast at JVM startup if the expected entry is absent.
    // -----------------------------------------------------------------------

    /** Kamino Lend borrow/earn program. */
    public static final String KAMINO_LEND_ID        = requireAddress("Kamino", "v1");
    /** Kamino Vault (yield) program. */
    public static final String KAMINO_VAULT_ID       = requireAddress("Kamino", "Vault");
    /** Meteora DLMM concentrated-bin liquidity. */
    public static final String METEORA_DLMM_ID       = requireAddress("Meteora", "DLMM");
    /** Meteora Dynamic Vault (yield, idle DAMM liquidity). */
    public static final String METEORA_VAULT_ID      = requireAddress("Meteora", "DynamicVault");
    /** Meteora Dynamic AMM v1 constant-product pool. */
    public static final String METEORA_DYNAMIC_AMM_ID = requireAddress("Meteora", "DAMMv1");
    /** Meteora Dynamic AMM v2 constant-product pool. */
    public static final String METEORA_DAMM_V2_ID    = requireAddress("Meteora", "DAMMv2");
    /** Meteora Farm LP stake / reward program. */
    public static final String METEORA_FARM_ID       = requireAddress("Meteora", "Farm");
    /** Hawksight automation wrapper over Meteora DLMM. */
    public static final String HAWKSIGHT_ID          = requireAddress("Hawksight", "v1");
    /** Raydium CLMM concentrated-liquidity AMM. */
    public static final String RAYDIUM_CLMM_ID       = requireAddress("Raydium", "CLMM");
    /** Raydium AMM v4 constant-product pool. */
    public static final String RAYDIUM_AMM_V4_ID     = requireAddress("Raydium", "v4");
    /** Raydium CPMM constant-product pool. */
    public static final String RAYDIUM_CPMM_ID       = requireAddress("Raydium", "CPMM");
    /** Marinade native liquid-staking program. */
    public static final String MARINADE_ID           = requireAddress("Marinade", "v1");
    /** Jito stake-pool liquid-staking program. */
    public static final String JITO_ID               = requireAddress("Jito", "v1");
    /** Jupiter Aggregator v6 swap router. */
    public static final String JUPITER_SWAP_V6_ID    = requireAddress("Jupiter", "v6");
    /** Jupiter Aggregator v4 swap router. */
    public static final String JUPITER_SWAP_V4_ID    = requireAddress("Jupiter", "v4");
    /** Jupiter RFQ Order Engine (taker/maker swap). */
    public static final String JUPITER_RFQ_ID        = requireAddress("Jupiter", "OrderEngine");
    /** DFlow aggregator router. */
    public static final String DFLOW_ID              = requireAddress("DFlow", "v1");
    /** OKX DEX Router aggregator. */
    public static final String OKX_DEX_ROUTER_ID     = requireAddress("OKX", "v1");
    /** Metaplex Bubblegum compressed-NFT mint / transfer program. */
    public static final String BUBBLEGUM_ID          = requireAddress("Metaplex", "Bubblegum");

    private SolanaProtocolPrograms() {
    }

    // -----------------------------------------------------------------------
    // Accessor API
    // -----------------------------------------------------------------------

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

    /**
     * Reverse lookup: returns the {@link SolanaProgramClass} for a given case-sensitive base58
     * {@code programId}, or {@link Optional#empty()} when the program is not in the registry.
     *
     * <p>Solana program IDs are case-sensitive base58 — never lowercase or 0x-prefixed.</p>
     */
    public static Optional<SolanaProgramClass> classify(String programId) {
        if (programId == null || programId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ADDRESS.get(programId.trim()));
    }

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

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

    /**
     * Builds the {@link #BY_ADDRESS} reverse lookup from the registry.
     * Each SOLANA entry with a {@code protocol} and {@code networks} field contributes an entry.
     * The {@code classifier_key} field is required for dispatch-visible programs; entries without
     * it are still indexed but will have a {@code null} protocolKey (non-DeFi / unsupported).
     */
    private static Map<String, SolanaProgramClass> loadByAddress() {
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
            Map<String, SolanaProgramClass> result = new HashMap<>();
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
                String address = entry.path("address").isMissingNode()
                        ? field.getKey()
                        : entry.path("address").asText();
                if (address == null || address.isBlank()) {
                    continue;
                }
                address = address.trim();

                String protocol = entry.path("protocol").asText().trim();
                String classifierKey = textOrNull(entry, "classifier_key");
                String family = textOrNull(entry, "family");
                String eventTypeHint = textOrNull(entry, "event_type");
                String displayName = textOrNull(entry, "name");

                result.put(address, new SolanaProgramClass(protocol, classifierKey, family, eventTypeHint, displayName));
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, ex);
        }
    }

    private static Map<String, String> buildProtoVersionIndex(Map<String, SolanaProgramClass> byAddress) {
        // Re-read the raw version field (not in SolanaProgramClass) from the registry.
        // Built lazily as a pass over the contracts node. This map is only used at class-init
        // time by the requireAddress() calls below and is not exposed publicly.
        try (InputStream inputStream =
                     SolanaProtocolPrograms.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(inputStream);
            JsonNode contracts = root.path("contracts");
            Map<String, String> index = new HashMap<>();
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
                String address = entry.path("address").isMissingNode()
                        ? field.getKey()
                        : entry.path("address").asText();
                if (address == null || address.isBlank()) {
                    continue;
                }
                String protocol = textOrNull(entry, "protocol");
                String version = textOrNull(entry, "version");
                if (protocol != null && version != null) {
                    String key = protocol + ":" + version;
                    String existing = index.put(key, address.trim());
                    if (existing != null && !existing.equals(address.trim())) {
                        throw new IllegalStateException(
                                "Duplicate (protocol, version) key in SOLANA registry: " + key
                                        + " maps to both " + existing + " and " + address.trim());
                    }
                }
            }
            return Collections.unmodifiableMap(index);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build proto-version index from " + RESOURCE, ex);
        }
    }

    /**
     * Returns the program address for the given {@code protocol} + {@code version} pair,
     * failing fast at class initialisation if no such entry exists in the registry.
     */
    private static String requireAddress(String protocol, String version) {
        String key = protocol + ":" + version;
        String address = ADDRESS_BY_PROTO_VERSION.get(key);
        if (address == null) {
            throw new IllegalStateException(
                    "Expected SOLANA program for protocol=" + protocol + " version=" + version
                            + " is missing from " + RESOURCE + " — registry and code are out of sync");
        }
        return address;
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

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
