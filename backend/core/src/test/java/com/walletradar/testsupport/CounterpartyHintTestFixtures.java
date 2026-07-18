package com.walletradar.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintLoader;
import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintService;

/**
 * Loads {@link CounterpartyHintService} from classpath {@code counterparty-hints.json} for unit
 * tests. Constructing the service binds the static holders
 * ({@code KnownBridgeRouterRegistry}, {@code KnownProtocolCounterpartyRegistry}) — the same
 * convention as {@link NetworkTestFixtures} binding {@code NetworkNativeAssets}.
 */
public final class CounterpartyHintTestFixtures {

    private static final CounterpartyHintService SERVICE = build();

    private CounterpartyHintTestFixtures() {
    }

    public static CounterpartyHintService service() {
        return SERVICE;
    }

    public static CounterpartyHintLoader.LoadedCounterpartyHints hints() {
        return new CounterpartyHintLoader(new ObjectMapper()).loadFromClasspath();
    }

    private static CounterpartyHintService build() {
        return new CounterpartyHintService(new CounterpartyHintLoader(new ObjectMapper()));
    }
}
