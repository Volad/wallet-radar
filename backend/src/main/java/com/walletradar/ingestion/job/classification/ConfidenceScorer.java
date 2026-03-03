package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Heuristic confidence scoring for normalization output.
 * Returns score in [0..1].
 */
@Component
public class ConfidenceScorer {

    public BigDecimal score(List<RawClassifiedEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return new BigDecimal("0.35");
        }
        Set<EconomicEventType> eventTypes = new LinkedHashSet<>();
        for (RawClassifiedEvent event : rawEvents) {
            if (event != null && event.getEventType() != null) {
                eventTypes.add(event.getEventType());
            }
        }
        boolean hasSwapBuy = eventTypes.contains(EconomicEventType.SWAP_BUY);
        boolean hasSwapSell = eventTypes.contains(EconomicEventType.SWAP_SELL);
        if (hasSwapBuy && hasSwapSell) {
            return new BigDecimal("0.95");
        }
        if (eventTypes.size() == 1 && (
                eventTypes.contains(EconomicEventType.LEND_DEPOSIT)
                        || eventTypes.contains(EconomicEventType.LEND_WITHDRAWAL)
        )) {
            return new BigDecimal("0.90");
        }
        if (hasSwapBuy || hasSwapSell) {
            return new BigDecimal("0.55");
        }
        if (rawEvents.size() == 1) {
            return new BigDecimal("0.88");
        }
        if (eventTypes.contains(EconomicEventType.EXTERNAL_INBOUND)
                && eventTypes.contains(EconomicEventType.EXTERNAL_TRANSFER_OUT)) {
            return new BigDecimal("0.80");
        }
        return new BigDecimal("0.82");
    }
}
