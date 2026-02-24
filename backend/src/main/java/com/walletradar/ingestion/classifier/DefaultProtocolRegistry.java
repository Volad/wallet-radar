package com.walletradar.ingestion.classifier;

import com.walletradar.ingestion.config.ProtocolRegistryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Protocol registry. Address -> name from walletradar.ingestion.protocol-registry.names (application.yml).
 */
@Component
@RequiredArgsConstructor
public class DefaultProtocolRegistry implements ProtocolRegistry {

    private final ProtocolRegistryProperties properties;

    @Override
    public Optional<String> getProtocolName(String addressOrProgramId) {
        if (addressOrProgramId == null || addressOrProgramId.isBlank()) {
            return Optional.empty();
        }
        String key = addressOrProgramId.strip().toLowerCase();
        if (properties.getNames() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getNames().get(key));
    }
}
