package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;

import java.util.List;

public interface ProtocolSpecialHandler {

    ProtocolRegistrySpecialHandlerType handlerType();

    SpecialHandlerResult classify(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> legs
    );
}
