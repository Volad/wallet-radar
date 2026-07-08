package com.walletradar.application.normalization.pipeline.descriptor.spi;

import com.walletradar.application.normalization.pipeline.descriptor.ProtocolCapability;
import com.walletradar.application.normalization.pipeline.descriptor.ProtocolDescriptor;
import org.springframework.stereotype.Component;

/**
 * Stub LP presentation SPI bound to the Uniswap protocol descriptor.
 */
@Component
public class UniswapLpPresentationBehaviorStub implements LpPresentationBehavior {

    @Override
    public String protocolName() {
        return "Uniswap";
    }

    @Override
    public boolean supports(ProtocolDescriptor descriptor) {
        return descriptor != null
                && "Uniswap".equalsIgnoreCase(descriptor.protocol())
                && descriptor.capabilities().contains(ProtocolCapability.LP_PRESENTATION);
    }
}
