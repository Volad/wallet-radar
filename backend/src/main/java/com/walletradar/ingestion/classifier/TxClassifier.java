package com.walletradar.ingestion.classifier;

import com.walletradar.domain.RawTransaction;

import java.util.List;

/**
 * Classifies a raw transaction into zero or more raw economic events.
 * Multiple classifiers can run; results are merged. Order may matter (e.g. swap before transfer).
 */
public interface TxClassifier {

    /**
     * Classify the transaction and emit raw events for the given wallet (session wallet).
     *
     * @param tx            raw transaction (EVM or Solana)
     * @param walletAddress the wallet we are classifying for (must be participant in tx)
     * @return list of raw classified events (can be empty if this classifier does not match)
     */
    List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress);
}
