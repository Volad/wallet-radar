package com.walletradar.ingestion.classifier;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classifies staking deposit/withdrawal events. MVP: stub returning empty list.
 * Can be extended to detect common staking contract patterns (e.g. Lido, Rocket Pool).
 */
@Component
@Order(110)
public class StakeClassifier implements TxClassifier {

    @Override
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
        return List.of();
    }
}
