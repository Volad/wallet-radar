package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BalancerVaultSpecialHandler implements ProtocolSpecialHandler {

    @Override
    public ProtocolRegistrySpecialHandlerType handlerType() {
        return ProtocolRegistrySpecialHandlerType.BALANCER_VAULT;
    }

    @Override
    public SpecialHandlerResult classify(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> legs
    ) {
        String functionName = view.functionName();
        if (SpecialHandlerSupport.containsAny(functionName, "joinpool", "join")) {
            return SpecialHandlerResult.of(view, com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LP_ENTRY, entry.confidence(), legs);
        }
        if (SpecialHandlerSupport.containsAny(functionName, "exitpool", "exit")) {
            return SpecialHandlerResult.of(view, com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LP_EXIT, entry.confidence(), legs);
        }
        if (SpecialHandlerSupport.containsAny(functionName, "swap", "batchswap")) {
            return SpecialHandlerResult.of(view, com.walletradar.domain.transaction.normalized.NormalizedTransactionType.SWAP, entry.confidence(), legs);
        }
        return SpecialHandlerResult.unsupported();
    }
}
