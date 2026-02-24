package com.walletradar.ingestion.classifier;

import com.walletradar.domain.RawTransaction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classifies staking deposit/withdrawal events. MVP: stub returning empty list.
 * Can be extended to detect common staking contract patterns (e.g. Lido, Rocket Pool).
 */
@Component
public class StakeClassifier implements TxClassifier {

    @Override
    public List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress) {
        return List.of();
    }
}
