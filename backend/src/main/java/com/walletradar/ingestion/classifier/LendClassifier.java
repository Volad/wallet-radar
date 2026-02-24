package com.walletradar.ingestion.classifier;

import com.walletradar.domain.RawTransaction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classifies lending protocol events (deposit, withdrawal, borrow, repay). MVP: stub returning empty list.
 * Can be extended for Aave, Compound, etc.
 */
@Component
public class LendClassifier implements TxClassifier {

    @Override
    public List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress) {
        return List.of();
    }
}
