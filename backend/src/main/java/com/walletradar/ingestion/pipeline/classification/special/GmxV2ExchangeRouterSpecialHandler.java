package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GmxV2ExchangeRouterSpecialHandler implements ProtocolSpecialHandler {

    private static final String CREATE_ORDER = "0x0ad58d2f";
    private static final String CREATE_DEPOSIT = "0x2e7eff49";
    private static final String CREATE_WITHDRAWAL = "0x87d66368";

    @Override
    public ProtocolRegistrySpecialHandlerType handlerType() {
        return ProtocolRegistrySpecialHandlerType.GMX_V2_EXCHANGE_ROUTER;
    }

    @Override
    public SpecialHandlerResult classify(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> legs
    ) {
        String methodId = view.methodId();
        if (CREATE_ORDER.equals(methodId) || SpecialHandlerSupport.contains(view.functionName(), "createorder")) {
            return SpecialHandlerResult.unsupported();
        }
        if (CREATE_DEPOSIT.equals(methodId) || SpecialHandlerSupport.contains(view.functionName(), "createdeposit")) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.LP_ENTRY, entry.confidence(), legs);
        }
        if (CREATE_WITHDRAWAL.equals(methodId) || SpecialHandlerSupport.contains(view.functionName(), "createwithdrawal")) {
            return SpecialHandlerResult.of(view, NormalizedTransactionType.LP_EXIT, entry.confidence(), legs);
        }
        return SpecialHandlerResult.unsupported();
    }
}
