package com.walletradar.application.normalization.pipeline.classification.registry;

import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintLoader.CounterpartyKey;
import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintLoader.LoadedCounterpartyHints;
import com.walletradar.application.normalization.pipeline.classification.support.KnownBridgeRouterRegistry;
import com.walletradar.application.normalization.pipeline.classification.support.KnownProtocolCounterpartyRegistry;
import com.walletradar.application.normalization.pipeline.classification.support.KnownProtocolCounterpartyRegistry.ProtocolAttribution;
import com.walletradar.domain.common.ConservationCounterpartyHints;
import com.walletradar.domain.common.NetworkId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime lookup over the classpath-backed counterparty-hints config plane (ADR-059, Wave W2).
 *
 * <p>Mirrors {@code NetworkRegistry}: this eagerly-constructed {@code @Service} binds the two
 * static holders ({@link KnownBridgeRouterRegistry}, {@link KnownProtocolCounterpartyRegistry})
 * at startup so their existing static call sites keep working unchanged. It also exposes the
 * three network-agnostic gate sets consumed by {@code PortfolioConservationGate}.</p>
 */
@Service
public class CounterpartyHintService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyHintService.class);

    private final Set<String> bridgeRouters;
    private final Set<String> rewardDistributors;
    private final Set<String> bridgePayouts;
    private final Set<String> relaySources;
    private final Set<String> lpPools;
    private final Map<CounterpartyKey, ProtocolAttribution> scopedCounterparties;

    public CounterpartyHintService(CounterpartyHintLoader loader) {
        LoadedCounterpartyHints hints = loader.loadFromClasspath();
        this.bridgeRouters = hints.bridgeRouters();
        this.rewardDistributors = hints.rewardDistributors();
        this.bridgePayouts = hints.bridgePayouts();
        this.relaySources = hints.relaySources();
        this.lpPools = hints.lpPools();
        this.scopedCounterparties = hints.scopedCounterparties();

        // Bind the static adapters (same convention as NetworkRegistry binding NetworkNativeAssets).
        KnownBridgeRouterRegistry.bind(this.bridgeRouters::contains, this.rewardDistributors::contains);
        KnownProtocolCounterpartyRegistry.bind(this::lookupCounterparty);
        // Bind the read-time conservation-gate bridge (domain.common) so the portfolio read model
        // reads these sets without importing this normalization-pipeline package (module boundary).
        ConservationCounterpartyHints.bind(
                this.bridgePayouts::contains, this.relaySources::contains, this.lpPools::contains);

        log.info(
                "Loaded counterparty hints: {} bridge routers, {} reward distributors, {} bridge payouts, "
                        + "{} relay sources, {} lp pools, {} scoped counterparties",
                bridgeRouters.size(),
                rewardDistributors.size(),
                bridgePayouts.size(),
                relaySources.size(),
                lpPools.size(),
                scopedCounterparties.size());
    }

    public boolean isBridgeRouter(String address) {
        return contains(bridgeRouters, address);
    }

    public boolean isRewardDistributor(String address) {
        return contains(rewardDistributors, address);
    }

    public boolean isBridgePayout(String address) {
        return contains(bridgePayouts, address);
    }

    public boolean isRelaySource(String address) {
        return contains(relaySources, address);
    }

    public boolean isLpPool(String address) {
        return contains(lpPools, address);
    }

    public Optional<ProtocolAttribution> lookupCounterparty(NetworkId networkId, String address) {
        if (networkId == null || address == null || address.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(scopedCounterparties.get(
                new CounterpartyKey(networkId, address.trim().toLowerCase(Locale.ROOT))));
    }

    private static boolean contains(Set<String> set, String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        return set.contains(address.trim().toLowerCase(Locale.ROOT));
    }
}
