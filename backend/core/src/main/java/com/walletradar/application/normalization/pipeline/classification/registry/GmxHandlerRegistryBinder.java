package com.walletradar.application.normalization.pipeline.classification.registry;

import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.application.normalization.pipeline.classification.support.GmxV2HandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Wave W3: binds the static {@link GmxV2HandlerRegistry} membership predicate at startup from the
 * {@code protocols/gmx-v2.json} {@code handlerContracts} config plane.
 *
 * <p>Mirrors {@code CounterpartyHintService}: this eagerly-constructed {@code @Service} moves the
 * GMX V2 handler/vault address set out of hardcoded Java and into the authoritative protocol
 * resource, keeping every existing static call site unchanged.</p>
 */
@Service
public class GmxHandlerRegistryBinder {

    private static final Logger log = LoggerFactory.getLogger(GmxHandlerRegistryBinder.class);

    public GmxHandlerRegistryBinder(ProtocolResourceCatalog protocolResourceCatalog) {
        Set<String> handlers = protocolResourceCatalog.find("GMX", "v2")
                .map(ProtocolResourceDefinition::handlerContractAddresses)
                .orElse(Set.of());

        GmxV2HandlerRegistry.bind(handlers::contains);

        log.info("Loaded GMX V2 handler registry: {} handler/vault contracts", handlers.size());
    }
}
