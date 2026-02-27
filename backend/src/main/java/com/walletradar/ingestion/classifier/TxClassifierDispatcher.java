package com.walletradar.ingestion.classifier;

import com.walletradar.domain.RawTransaction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs all TxClassifiers on a raw transaction and merges results.
 */
@Component
@RequiredArgsConstructor
public class TxClassifierDispatcher {

    private final List<TxClassifier> classifiers;

    /**
     * Classify the transaction for the given wallet. Runs classifiers in order and merges events.
     *
     * @param tx              raw transaction
     * @param walletAddress   wallet we are classifying for
     * @return merged list of raw classified events (may be empty)
     */
    public List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress) {
        List<RawClassifiedEvent> events = new ArrayList<>();
        for (TxClassifier classifier : classifiers) {
            events.addAll(classifier.classify(tx, walletAddress));
        }
        return events;
    }
}
