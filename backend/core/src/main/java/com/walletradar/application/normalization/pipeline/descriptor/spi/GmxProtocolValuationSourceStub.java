package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolCapability;
import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;
import org.springframework.stereotype.Component;

/**
 * Stub valuation SPI bound to the GMX protocol descriptor.
 */
@Component
public class GmxProtocolValuationSourceStub implements ProtocolValuationSource {

    @Override
    public String protocolName() {
        return "GMX";
    }

    @Override
    public boolean supports(ProtocolDescriptor descriptor) {
        return descriptor != null
                && "GMX".equalsIgnoreCase(descriptor.protocol())
                && descriptor.capabilities().contains(ProtocolCapability.VALUATION);
    }
}
