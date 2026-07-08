package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.balancer;

import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class BalancerProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    private static final String RESOURCE_PROTOCOL = "Balancer";
    private static final String RESOURCE_VERSION = "v2";
    private static final String PROTOCOL_KEY = "balancer";
    private static final String SEMANTIC_SWAP = "swap";
    private static final String SEMANTIC_LP_ENTRY = "lp_entry";
    private static final String SEMANTIC_LP_EXIT = "lp_exit";

    private final ProtocolResourceDefinition resource;

    public BalancerProtocolSemanticClassifier(ProtocolResourceCatalog protocolResourceCatalog) {
        this.resource = protocolResourceCatalog.find(RESOURCE_PROTOCOL, RESOURCE_VERSION).orElse(null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 140;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        Optional<ProtocolMatch> entry = context.protocolDiscovery().firstSpecialHandlerMatch(
                ProtocolRegistrySpecialHandlerType.BALANCER_VAULT
        );
        if (entry.isEmpty()) {
            return List.of();
        }
        ProtocolMatch value = entry.orElseThrow();
        if (matches("lpEntry", context.view().methodId(), context.view().functionName())) {
            return List.of(hint(value, SEMANTIC_LP_ENTRY, NormalizedTransactionType.LP_ENTRY));
        }
        if (matches("lpExit", context.view().methodId(), context.view().functionName())) {
            return List.of(hint(value, SEMANTIC_LP_EXIT, NormalizedTransactionType.LP_EXIT));
        }
        if (matches("swap", context.view().methodId(), context.view().functionName())) {
            return List.of(hint(value, SEMANTIC_SWAP, NormalizedTransactionType.SWAP));
        }
        return List.of();
    }

    private boolean matches(String group, String methodId, String functionName) {
        if (resource == null) {
            return false;
        }
        return resource.matchesMethodSelector(group, methodId)
                || matchesFunctionKey(group, functionName);
    }

    private boolean matchesFunctionKey(String group, String functionName) {
        if (functionName == null || functionName.isBlank() || resource == null) {
            return false;
        }
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int signatureIndex = normalized.indexOf('(');
        String functionKey = signatureIndex >= 0 ? normalized.substring(0, signatureIndex) : normalized;
        for (String marker : resource.markers().functionMarkers(group)) {
            if (marker != null && functionKey.equals(marker.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
