package com.walletradar.application.normalization.pipeline.classification.onchain.protocol;

import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;

/**
 * Immutable protocol-semantic input built before family classifiers run.
 */
public record ProtocolSemanticContext(
        OnChainRawTransactionView view,
        ProtocolDiscoveryResult protocolDiscovery,
        List<RawLeg> movementLegs
) {
}
