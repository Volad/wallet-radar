package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.EconomicEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Retroactive reclassification: when a wallet is added to the session, find EXTERNAL_INBOUND events
 * whose counterparty is now in the session and update them to INTERNAL_TRANSFER.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalTransferReclassifier {

    private final EconomicEventRepository economicEventRepository;

    /**
     * Find all economic events with eventType EXTERNAL_INBOUND and counterpartyAddress in sessionWallets;
     * update to INTERNAL_TRANSFER and set isInternalTransfer=true. Persist changes.
     *
     * @param sessionWallets all wallet addresses currently in session (including the newly added one)
     * @return list of updated events (for triggering AVCO recalc per wallet×asset)
     */
    public List<EconomicEvent> reclassify(Set<String> sessionWallets) {
        if (sessionWallets == null || sessionWallets.isEmpty()) {
            return List.of();
        }
        List<String> list = new ArrayList<>(sessionWallets);
        List<EconomicEvent> candidates = economicEventRepository.findByEventTypeAndCounterpartyAddressIn(
                EconomicEventType.EXTERNAL_INBOUND, list);
        List<EconomicEvent> updated = new ArrayList<>();
        for (EconomicEvent e : candidates) {
            e.setEventType(EconomicEventType.INTERNAL_TRANSFER);
            e.setInternalTransfer(true);
            economicEventRepository.save(e);
            updated.add(e);
        }
        if (!updated.isEmpty()) {
            log.info("Reclassified {} EXTERNAL_INBOUND events to INTERNAL_TRANSFER (session size={})",
                    updated.size(), sessionWallets.size());
        }
        return updated;
    }
}
