package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for per-network token identity overrides (WS-7), loaded once from
 * {@code classpath:network-descriptors.yml} ({@code walletradar.networks.entries.*.token-overrides}).
 * This replaces the retired {@code token-metadata.json} quad-store.
 *
 * <p>An override entry carries any of:</p>
 * <ul>
 *   <li>{@code symbol} — fallback/seed symbol applied only when the source payload omits one;</li>
 *   <li>{@code decimals} — fallback/seed decimals applied only when the source omits them;</li>
 *   <li>{@code decimal-override} — authoritative decimals that ALWAYS replace a known-wrong source
 *       value (e.g. soUSDC where the explorer misreports {@code tokenDecimal}).</li>
 * </ul>
 *
 * <p>Keys are normalised per network address format: EVM lowercased; Solana base58 matched exactly
 * (case-sensitive); TON jetton masters expanded through
 * {@link TonAddressCanonicalizer#lookupKeys(String)} so any canonical form resolves the same entry.
 * Overrides are the highest-priority tier of the normalization resolution order
 * (descriptor override → persistent cache → live resolver → explicit unresolved) and are loaded
 * statically so plain unit tests resolve them without a Spring context.</p>
 */
public final class NetworkTokenOverrides {

    private static final String RESOURCE = "network-descriptors.yml";

    private static final Map<NetworkId, Map<String, Override>> BY_NETWORK = new EnumMap<>(NetworkId.class);
    private static final Map<String, Override> EVM_UNION = new LinkedHashMap<>();

    static {
        load();
    }

    private NetworkTokenOverrides() {
    }

    /** Highest-priority per-network override for the given contract/mint/jetton-master, or empty. */
    public static Optional<Override> find(NetworkId networkId, String contract) {
        if (networkId == null || contract == null || contract.isBlank()) {
            return Optional.empty();
        }
        Map<String, Override> map = BY_NETWORK.get(networkId);
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        for (String key : lookupKeys(networkId, contract)) {
            Override override = map.get(key);
            if (override != null) {
                return Optional.of(override);
            }
        }
        return Optional.empty();
    }

    /**
     * Network-agnostic EVM lookup (union across every EVM-address-format network) for the legacy
     * static EVM call sites that do not carry a {@link NetworkId}. EVM contract addresses are
     * globally namespaced by the {@code 0x…} format, so a union lookup is safe.
     */
    public static Optional<Override> findEvm(String contract) {
        if (contract == null || contract.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(EVM_UNION.get(contract.trim().toLowerCase(Locale.ROOT)));
    }

    private static List<String> lookupKeys(NetworkId networkId, String contract) {
        String trimmed = contract.trim();
        return switch (addressFormat(networkId)) {
            case TON -> {
                List<String> keys = TonAddressCanonicalizer.lookupKeys(trimmed);
                yield keys.isEmpty() ? List.of(trimmed) : keys;
            }
            case SOLANA -> List.of(trimmed);
            default -> List.of(trimmed.toLowerCase(Locale.ROOT));
        };
    }

    private enum Format { EVM, SOLANA, TON }

    private static Format addressFormat(NetworkId networkId) {
        return switch (networkId) {
            case SOLANA -> Format.SOLANA;
            case TON -> Format.TON;
            default -> Format.EVM;
        };
    }

    @SuppressWarnings("unchecked")
    private static void load() {
        try (InputStream inputStream =
                     NetworkTokenOverrides.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            Map<String, Object> root = new Yaml().load(inputStream);
            Map<String, Object> entries = navigate(root, "walletradar", "networks", "entries");
            if (entries == null) {
                return;
            }
            for (Map.Entry<String, Object> entry : entries.entrySet()) {
                NetworkId networkId = parseNetworkId(entry.getKey());
                if (networkId == null || !(entry.getValue() instanceof Map<?, ?> config)) {
                    continue;
                }
                Object overridesNode = ((Map<String, Object>) config).get("token-overrides");
                if (!(overridesNode instanceof Map<?, ?> overrides)) {
                    continue;
                }
                indexNetwork(networkId, (Map<String, Object>) overrides);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load token overrides from " + RESOURCE, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void indexNetwork(NetworkId networkId, Map<String, Object> overrides) {
        Map<String, Override> map = new LinkedHashMap<>();
        boolean evm = addressFormat(networkId) == Format.EVM;
        overrides.forEach((contract, value) -> {
            if (contract == null || contract.isBlank() || !(value instanceof Map<?, ?> fields)) {
                return;
            }
            Override override = toOverride((Map<String, Object>) fields);
            if (override.isEmpty()) {
                return;
            }
            for (String key : lookupKeys(networkId, contract)) {
                map.put(key, override);
                if (evm) {
                    EVM_UNION.putIfAbsent(key, override);
                }
            }
        });
        if (!map.isEmpty()) {
            BY_NETWORK.put(networkId, Map.copyOf(map));
        }
    }

    private static Override toOverride(Map<String, Object> fields) {
        String symbol = asString(fields.get("symbol"));
        Integer decimals = asInteger(fields.get("decimals"));
        Integer decimalOverride = asInteger(fields.get("decimal-override"));
        return new Override(symbol, decimals, decimalOverride);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigate(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            if (current == null) {
                return null;
            }
            Object next = current.get(key);
            current = next instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        }
        return current;
    }

    private static NetworkId parseNetworkId(String key) {
        try {
            return NetworkId.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * A per-network token override. Any field may be {@code null}; {@link #isEmpty()} entries are
     * dropped at load time.
     */
    public record Override(String symbol, Integer decimals, Integer decimalOverride) {

        public boolean isEmpty() {
            return symbol == null && decimals == null && decimalOverride == null;
        }

        /** Decimals to apply: the authoritative override when present, else the seed decimals. */
        public Integer effectiveDecimals() {
            return decimalOverride != null ? decimalOverride : decimals;
        }
    }
}
