package com.walletradar.ingestion.classifier;

import com.walletradar.domain.RawTransaction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs all TxClassifiers on a raw transaction and merges results.
 * Applies InternalTransferDetector so EXTERNAL_INBOUND becomes INTERNAL_TRANSFER when counterparty is in session.
 */
@Component
@RequiredArgsConstructor
public class TxClassifierDispatcher {

    private final List<TxClassifier> classifiers;
    private final InternalTransferDetector internalTransferDetector;

    /**
     * Classify the transaction for the given wallet. Runs classifiers in order, merges events,
     * then reclassifies EXTERNAL_INBOUND → INTERNAL_TRANSFER when counterparty is in sessionWallets.
     *
     * @param tx              raw transaction
     * @param walletAddress   wallet we are classifying for
     * @param sessionWallets  all wallet addresses in session (for internal transfer detection)
     * @return merged list of raw classified events (may be empty)
     */
    public List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress, Set<String> sessionWallets) {
        List<RawClassifiedEvent> events = new ArrayList<>();
        for (TxClassifier classifier : classifiers) {
            events.addAll(classifier.classify(tx, walletAddress));
        }
        internalTransferDetector.reclassifyInboundToInternal(events, sessionWallets);
        return events;
    }
}
