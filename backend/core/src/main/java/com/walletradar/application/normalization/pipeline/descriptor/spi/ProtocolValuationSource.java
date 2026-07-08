package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;

/**
 * SPI stub for protocol valuation source selection (A5p).
 */
public interface ProtocolValuationSource {

    String protocolName();

    boolean supports(ProtocolDescriptor descriptor);
}
