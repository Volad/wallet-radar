package com.walletradar.ingestion.pipeline.classification.onchain.protocol;

import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

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
