package com.walletradar.application.normalization.pipeline.classification.registry;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Runtime lookup over the classpath-backed protocol registry.
 */
@Service
public class ProtocolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolRegistryService.class);

    private final Map<ProtocolRegistryLoader.RegistryKey, ProtocolRegistryEntry> entriesByKey;
    private final Map<String, String> methodDescriptions;

    public ProtocolRegistryService(ProtocolRegistryLoader loader) {
        ProtocolRegistryLoader.LoadedProtocolRegistry registry = loader.loadFromClasspath();
        this.entriesByKey = registry.entriesByKey();
        this.methodDescriptions = registry.methodDescriptions();
        log.info("Loaded protocol registry: {} contract mappings, {} method selectors",
                entriesByKey.size(),
                methodDescriptions.size());
    }

    public Optional<ProtocolRegistryEntry> lookup(NetworkId networkId, String contractAddress) {
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(contractAddress);
        if (networkId == null || normalizedAddress == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entriesByKey.get(new ProtocolRegistryLoader.RegistryKey(networkId, normalizedAddress)));
    }

    /**
     * Returns all entries across all networks. Used by classifiers that need to build in-memory
     * indexes at startup (e.g. the LFJ pair index in {@code LpRegistryClassifier}).
     */
    public java.util.Collection<ProtocolRegistryEntry> allEntries() {
        return entriesByKey.values();
    }

    public Optional<String> lookupMethodDescription(String methodId) {
        if (methodId == null || methodId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(methodDescriptions.get(methodId.trim().toLowerCase()));
    }
}
