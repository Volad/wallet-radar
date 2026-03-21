package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ProtocolSpecialHandlerDispatcher {

    private final Map<ProtocolRegistrySpecialHandlerType, ProtocolSpecialHandler> handlers;

    public ProtocolSpecialHandlerDispatcher(List<ProtocolSpecialHandler> registeredHandlers) {
        this.handlers = new EnumMap<>(ProtocolRegistrySpecialHandlerType.class);
        for (ProtocolSpecialHandler handler : registeredHandlers) {
            ProtocolSpecialHandler previous = handlers.put(handler.handlerType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate special handler: " + handler.handlerType());
            }
        }
    }

    public SpecialHandlerResult dispatch(
            ProtocolRegistryEntry entry,
            com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView view,
            List<RawLeg> legs
    ) {
        if (entry.specialHandler() == null) {
            return SpecialHandlerResult.missingHandler();
        }
        ProtocolSpecialHandler handler = handlers.get(entry.specialHandler());
        if (handler == null) {
            log.error("No special handler implementation registered for {}", entry.specialHandler());
            return SpecialHandlerResult.missingHandler();
        }
        return handler.classify(entry, view, legs);
    }
}
