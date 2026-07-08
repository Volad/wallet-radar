package com.walletradar.application.normalization.pipeline.descriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProtocolDescriptorService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDescriptorService.class);

    private final Map<String, ProtocolDescriptor> descriptorsByProtocol;

    public ProtocolDescriptorService(ProtocolDescriptorLoader loader) {
        List<ProtocolDescriptor> descriptors = loader.loadFromClasspath().descriptors();
        this.descriptorsByProtocol = descriptors.stream()
                .collect(Collectors.toUnmodifiableMap(
                        descriptor -> descriptor.protocol().trim(),
                        descriptor -> descriptor,
                        (left, right) -> {
                            throw new IllegalStateException("Duplicate protocol descriptor: " + left.protocol());
                        }
                ));
        log.info("Protocol descriptor service ready: {}", descriptorsByProtocol.keySet());
    }

    public Optional<ProtocolDescriptor> find(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptorsByProtocol.get(protocol.trim()));
    }

    public boolean hasCapability(String protocol, ProtocolCapability capability) {
        return find(protocol)
                .map(ProtocolDescriptor::capabilities)
                .map(capabilities -> capabilities.contains(capability))
                .orElse(false);
    }

    public List<ProtocolDescriptor> all() {
        return List.copyOf(descriptorsByProtocol.values());
    }
}
