package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;

/**
 * SPI stub for lending market behavior (A5p).
 */
public interface LendingBehavior {

    String protocolName();

    boolean supports(ProtocolDescriptor descriptor);
}
