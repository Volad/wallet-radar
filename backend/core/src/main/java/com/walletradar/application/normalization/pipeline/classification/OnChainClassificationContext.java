package com.walletradar.application.normalization.pipeline.classification;

import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;

/**
 * Immutable classification input built from the authoritative raw transaction view.
 */
public record OnChainClassificationContext(
        OnChainRawTransactionView view,
        ProtocolDiscoveryResult protocolDiscovery,
        ProtocolSemanticResult protocolSemantics,
        List<RawLeg> movementLegs
) {
}
