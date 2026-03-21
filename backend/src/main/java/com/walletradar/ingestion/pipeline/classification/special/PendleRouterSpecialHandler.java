package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class PendleRouterSpecialHandler implements ProtocolSpecialHandler {

    private static final Set<String> SWAP_METHODS = Set.of(
            "0x4e7ed11c",
            "0x7e1fe8c0",
            "0xf7f3d2af",
            "0x01a5fe2a",
            "0x03bef7c5"
    );

    @Override
    public ProtocolRegistrySpecialHandlerType handlerType() {
        return ProtocolRegistrySpecialHandlerType.PENDLE_ROUTER;
    }

    @Override
    public SpecialHandlerResult classify(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> legs
    ) {
        String methodId = view.methodId();
        String functionName = view.functionName();

        if (SWAP_METHODS.contains(methodId)
                || SpecialHandlerSupport.containsAny(functionName, "swapexacttokenforpt", "swapexactptfortoken", "swapexacttokenforyt", "swapexactytfortoken", "redeempytotoken")) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.SWAP, entry.confidence(), legs);
        }
        if ("0xb0c7e3f8".equals(methodId) || SpecialHandlerSupport.containsAny(functionName, "addliquiditysingletoken", "addliquiditydualtokenandpt")) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.LP_ENTRY, entry.confidence(), legs);
        }
        if ("0x1ef4b0d8".equals(methodId) || SpecialHandlerSupport.containsAny(functionName, "removeliquiditysingletoken", "removeliquiditydualtokenandpt")) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.LP_EXIT, entry.confidence(), legs);
        }
        return SpecialHandlerResult.unsupported();
    }
}
