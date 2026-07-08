package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;

/**
 * SPI stub for LP presentation/read-model behavior (A5p).
 */
public interface LpPresentationBehavior {

    String protocolName();

    boolean supports(ProtocolDescriptor descriptor);
}
