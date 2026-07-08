package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds structured protocol discovery hints from registry matches and protocol-local resources.
 */
@Service
public class ProtocolDiscoveryService {

    private final ProtocolRegistryService protocolRegistryService;
    private final ProtocolResourceCatalog protocolResourceCatalog;

    @Autowired
    public ProtocolDiscoveryService(
            ProtocolRegistryService protocolRegistryService,
            ProtocolResourceCatalog protocolResourceCatalog
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.protocolResourceCatalog = protocolResourceCatalog;
    }

    private ProtocolDiscoveryService() {
        this.protocolRegistryService = null;
        this.protocolResourceCatalog = ProtocolResourceCatalog.empty();
    }

    public static ProtocolDiscoveryService noop() {
        return new ProtocolDiscoveryService();
    }

    public ProtocolDiscoveryResult discover(OnChainRawTransactionView view) {
        if (view == null || protocolRegistryService == null) {
            return ProtocolDiscoveryResult.empty();
        }

        List<ProtocolMatch> matches = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        addRegistryMatch(matches, dedupe, view, view.toAddress(), "TO_ADDRESS");
        addRegistryMatch(matches, dedupe, view, view.fromAddress(), "FROM_ADDRESS");

        String methodDescription = protocolRegistryService.lookupMethodDescription(view.methodId()).orElse(null);
        return new ProtocolDiscoveryResult(List.copyOf(matches), methodDescription);
    }

    private void addRegistryMatch(
            List<ProtocolMatch> matches,
            Set<String> dedupe,
            OnChainRawTransactionView view,
            String address,
            String source
    ) {
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(view.networkId(), address);
        if (entry.isEmpty()) {
            return;
        }

        ProtocolRegistryEntry value = entry.get();
        String key = source + "|" + value.protocolName() + "|" + value.protocolVersion() + "|" + value.contractAddress();
        if (!dedupe.add(key)) {
            return;
        }

        matches.add(new ProtocolMatch(
                value.protocolName(),
                value.protocolVersion(),
                value.family(),
                value.role(),
                value.confidence(),
                value.contractAddress(),
                address,
                source,
                protocolResourceCatalog.find(value.protocolName(), value.protocolVersion()).orElse(null),
                value.specialHandler()
        ));
    }
}
