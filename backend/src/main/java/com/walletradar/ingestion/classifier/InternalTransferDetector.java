package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * At classification time: reclassifies EXTERNAL_INBOUND to INTERNAL_TRANSFER when counterparty is in session.
 * Call after all classifiers have run; mutates events in place.
 */
@Component
public class InternalTransferDetector {

    /**
     * For each raw event with eventType EXTERNAL_INBOUND and counterpartyAddress in sessionWallets,
     * set eventType to INTERNAL_TRANSFER.
     *
     * @param events         mutable list of raw classified events
     * @param sessionWallets set of wallet addresses currently in session (including the one we're classifying for)
     */
    public void reclassifyInboundToInternal(List<RawClassifiedEvent> events, Set<String> sessionWallets) {
        if (sessionWallets == null || sessionWallets.isEmpty()) {
            return;
        }
        Set<String> normalized = sessionWallets.stream()
                .map(a -> a == null ? "" : a.strip().toLowerCase())
                .collect(Collectors.toSet());
        for (RawClassifiedEvent e : events) {
            if (e.getEventType() != EconomicEventType.EXTERNAL_INBOUND) {
                continue;
            }
            String counterparty = e.getCounterpartyAddress();
            if (counterparty == null || counterparty.isBlank()) {
                continue;
            }
            if (normalized.contains(counterparty.strip().toLowerCase())) {
                e.setEventType(EconomicEventType.INTERNAL_TRANSFER);
            }
        }
    }
}
