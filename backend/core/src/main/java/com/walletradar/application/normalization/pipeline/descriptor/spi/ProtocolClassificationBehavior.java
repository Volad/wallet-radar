package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;

/**
 * SPI stub for protocol-specific classification behavior (A5p).
 */
public interface ProtocolClassificationBehavior {

    String protocolName();

    boolean supports(ProtocolDescriptor descriptor);

    // Future: classify semantic flows from descriptor-driven rules.
}
