package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolCapability;
import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;
import org.springframework.stereotype.Component;

/**
 * Stub classification SPI bound to the Aave protocol descriptor.
 */
@Component
public class AaveProtocolClassificationBehaviorStub implements ProtocolClassificationBehavior {

    @Override
    public String protocolName() {
        return "Aave";
    }

    @Override
    public boolean supports(ProtocolDescriptor descriptor) {
        return descriptor != null
                && "Aave".equalsIgnoreCase(descriptor.protocol())
                && descriptor.capabilities().contains(ProtocolCapability.CLASSIFICATION);
    }
}
