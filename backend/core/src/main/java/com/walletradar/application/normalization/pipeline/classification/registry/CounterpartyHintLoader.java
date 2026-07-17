package com.walletradar.application.normalization.pipeline.classification.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.classification.support.KnownProtocolCounterpartyRegistry.ProtocolAttribution;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.domain.common.NetworkId;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates the classpath {@code counterparty-hints.json} config plane (ADR-059,
 * Wave W2), mirroring {@link ProtocolRegistryLoader}. Fail-fast: any parse or validation error
 * throws {@link IllegalStateException} so the address sets can never silently become empty.
 *
 * <p>Owns five network-agnostic behavioral address sets (bridge routers, reward distributors,
 * bridge payouts, relay sources, LP pools) plus the network-scoped {@code PROTOCOL_COUNTERPARTY}
 * attributions. {@code protocol-registry.json} is a separate plane and is not touched here.</p>
 */
@Component
@RequiredArgsConstructor
public class CounterpartyHintLoader {

    static final String RESOURCE_PATH = "counterparty-hints.json";
    private static final String NETWORK_WILDCARD = "*";
    private static final Logger log = LoggerFactory.getLogger(CounterpartyHintLoader.class);

    private final ObjectMapper objectMapper;

    /** Behavioral counterparty-hint categories declared by the config schema. */
    enum HintCategory {
        BRIDGE_ROUTER,
        REWARD_DISTRIBUTOR,
        BRIDGE_PAYOUT,
        RELAY_SOURCE,
        LP_POOL,
        PROTOCOL_COUNTERPARTY
    }

    public LoadedCounterpartyHints loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return load(inputStream, RESOURCE_PATH);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load counterparty hints from classpath", ex);
        }
    }

    LoadedCounterpartyHints load(InputStream inputStream, String sourceName) {
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            JsonNode entries = root.path("entries");
            if (!entries.isArray()) {
                throw new IllegalStateException("counterparty hints field entries must be an array in " + sourceName);
            }

            Set<String> bridgeRouters = new LinkedHashSet<>();
            Set<String> rewardDistributors = new LinkedHashSet<>();
            Set<String> bridgePayouts = new LinkedHashSet<>();
            Set<String> relaySources = new LinkedHashSet<>();
            Set<String> lpPools = new LinkedHashSet<>();
            Map<CounterpartyKey, ProtocolAttribution> scopedCounterparties = new LinkedHashMap<>();

            for (JsonNode entry : entries) {
                if (!entry.isObject()) {
                    throw new IllegalStateException("counterparty hint entry must be an object in " + sourceName);
                }
                String rawAddress = requiredText(entry, "address", sourceName);
                String normalizedAddress = OnChainRawTransactionView.normalizeAddress(rawAddress);
                if (normalizedAddress == null) {
                    throw new IllegalStateException("Invalid counterparty hint address in " + sourceName + ": " + rawAddress);
                }
                HintCategory category = parseCategory(requiredText(entry, "category", sourceName), sourceName);
                List<String> networks = readNetworks(entry.path("networks"));

                switch (category) {
                    case BRIDGE_ROUTER -> addNetworkAgnostic(bridgeRouters, normalizedAddress, networks, category, sourceName);
                    case REWARD_DISTRIBUTOR -> addNetworkAgnostic(rewardDistributors, normalizedAddress, networks, category, sourceName);
                    case BRIDGE_PAYOUT -> addNetworkAgnostic(bridgePayouts, normalizedAddress, networks, category, sourceName);
                    case RELAY_SOURCE -> addNetworkAgnostic(relaySources, normalizedAddress, networks, category, sourceName);
                    case LP_POOL -> addNetworkAgnostic(lpPools, normalizedAddress, networks, category, sourceName);
                    case PROTOCOL_COUNTERPARTY -> addScopedCounterparty(
                            scopedCounterparties, entry, normalizedAddress, networks, sourceName);
                }
            }

            return new LoadedCounterpartyHints(
                    Collections.unmodifiableSet(bridgeRouters),
                    Collections.unmodifiableSet(rewardDistributors),
                    Collections.unmodifiableSet(bridgePayouts),
                    Collections.unmodifiableSet(relaySources),
                    Collections.unmodifiableSet(lpPools),
                    Collections.unmodifiableMap(scopedCounterparties)
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse counterparty hints from " + sourceName, ex);
        }
    }

    private void addNetworkAgnostic(
            Set<String> target,
            String address,
            List<String> networks,
            HintCategory category,
            String sourceName
    ) {
        if (networks.size() != 1 || !NETWORK_WILDCARD.equals(networks.get(0))) {
            throw new IllegalStateException(
                    "Category " + category + " must be network-agnostic (networks=[\"*\"]) in "
                            + sourceName + " for address " + address);
        }
        target.add(address);
    }

    private void addScopedCounterparty(
            Map<CounterpartyKey, ProtocolAttribution> target,
            JsonNode entry,
            String address,
            List<String> networks,
            String sourceName
    ) {
        if (networks.isEmpty() || networks.contains(NETWORK_WILDCARD)) {
            throw new IllegalStateException(
                    "Category PROTOCOL_COUNTERPARTY must declare concrete networks (not \"*\") in "
                            + sourceName + " for address " + address);
        }
        String name = requiredText(entry, "protocol", sourceName);
        String counterpartyType = requiredText(entry, "counterpartyType", sourceName);
        boolean asBridge = entry.path("asBridge").asBoolean(false);
        ProtocolAttribution attribution = new ProtocolAttribution(name, counterpartyType, asBridge);
        for (String network : networks) {
            NetworkId networkId = parseNetworkId(network, sourceName);
            CounterpartyKey key = new CounterpartyKey(networkId, address);
            ProtocolAttribution existing = target.putIfAbsent(key, attribution);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate PROTOCOL_COUNTERPARTY mapping for " + networkId + " / " + address + " in " + sourceName);
            }
        }
    }

    private List<String> readNetworks(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of(NETWORK_WILDCARD);
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("counterparty hint networks must be a non-empty array when present");
        }
        List<String> networks = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText().trim();
            if (value.isEmpty()) {
                throw new IllegalStateException("counterparty hint networks entry must not be blank");
            }
            networks.add(value);
        }
        return networks;
    }

    private HintCategory parseCategory(String value, String sourceName) {
        try {
            return HintCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported counterparty hint category in " + sourceName + ": " + value, ex);
        }
    }

    private NetworkId parseNetworkId(String value, String sourceName) {
        try {
            return NetworkId.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported network id in " + sourceName + ": " + value, ex);
        }
    }

    private String requiredText(JsonNode node, String fieldName, String sourceName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalStateException("Missing required field " + fieldName + " in " + sourceName);
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("Blank required field " + fieldName + " in " + sourceName);
        }
        return text;
    }

    /** In-memory per-category indexes produced by the loader. */
    public record LoadedCounterpartyHints(
            Set<String> bridgeRouters,
            Set<String> rewardDistributors,
            Set<String> bridgePayouts,
            Set<String> relaySources,
            Set<String> lpPools,
            Map<CounterpartyKey, ProtocolAttribution> scopedCounterparties
    ) {
    }

    /** Network-scoped key for {@code PROTOCOL_COUNTERPARTY} attributions. */
    public record CounterpartyKey(NetworkId networkId, String address) {
    }
}
