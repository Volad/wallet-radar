package com.walletradar.application.normalization.pipeline.classification;

import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryService;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticService;
import com.walletradar.application.normalization.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a classification context from the raw transaction and its typed view.
 */
@Component
public class OnChainClassificationContextFactory {

    private final ProtocolDiscoveryService protocolDiscoveryService;
    private final ProtocolSemanticService protocolSemanticService;
    private final MovementLegExtractor movementLegExtractor;

    @Autowired
    public OnChainClassificationContextFactory(
            ProtocolDiscoveryService protocolDiscoveryService,
            ProtocolSemanticService protocolSemanticService,
            MovementLegExtractor movementLegExtractor
    ) {
        this.protocolDiscoveryService = protocolDiscoveryService;
        this.protocolSemanticService = protocolSemanticService;
        this.movementLegExtractor = movementLegExtractor;
    }

    public OnChainClassificationContext create(RawTransaction rawTransaction) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        ProtocolDiscoveryResult discovery = protocolDiscoveryService.discover(view);
        List<RawLeg> movementLegs = movementLegExtractor.extract(view);
        ProtocolSemanticResult protocolSemantics = protocolSemanticService.classify(
                new ProtocolSemanticContext(view, discovery, movementLegs)
        );
        return new OnChainClassificationContext(view, discovery, protocolSemantics, movementLegs);
    }
}
