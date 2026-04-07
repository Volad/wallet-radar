package com.walletradar.ingestion.pipeline.classification.onchain.protocol.pendle;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PendleProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    private static final String RESOURCE_PROTOCOL = "Pendle";
    private static final String RESOURCE_VERSION = "v2";
    private static final String PROTOCOL_KEY = "pendle";
    private static final String SEMANTIC_SWAP = "swap";
    private static final String SEMANTIC_LP_ENTRY = "lp_entry";
    private static final String SEMANTIC_LP_EXIT = "lp_exit";

    private final ProtocolResourceDefinition resource;

    public PendleProtocolSemanticClassifier(ProtocolResourceCatalog protocolResourceCatalog) {
        this.resource = protocolResourceCatalog.find(RESOURCE_PROTOCOL, RESOURCE_VERSION).orElse(null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 141;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        Optional<ProtocolMatch> entry = context.protocolDiscovery().firstSpecialHandlerMatch(
                ProtocolRegistrySpecialHandlerType.PENDLE_ROUTER
        );
        if (entry.isEmpty()) {
            return List.of();
        }
        ProtocolMatch value = entry.orElseThrow();
        if (matches("swap", context.view().methodId(), context.view().functionName())) {
            return List.of(hint(value, SEMANTIC_SWAP, NormalizedTransactionType.SWAP));
        }
        if (matches("lpEntry", context.view().methodId(), context.view().functionName())) {
            return List.of(hint(value, SEMANTIC_LP_ENTRY, NormalizedTransactionType.LP_ENTRY));
        }
        if (matches("lpExit", context.view().methodId(), context.view().functionName())) {
            return List.of(hint(value, SEMANTIC_LP_EXIT, NormalizedTransactionType.LP_EXIT));
        }
        return List.of();
    }

    private boolean matches(String group, String methodId, String functionName) {
        if (resource == null) {
            return false;
        }
        return resource.matchesMethodSelector(group, methodId)
                || resource.matchesFunctionMarker(group, functionName);
    }

    private ProtocolSemanticHint hint(
            ProtocolMatch entry,
            String semanticType,
            NormalizedTransactionType type
    ) {
        return new ProtocolSemanticHint(
                PROTOCOL_KEY,
                semanticType,
                entry.protocolName(),
                entry.protocolVersion(),
                null,
                type,
                entry.confidence()
        );
    }
}
