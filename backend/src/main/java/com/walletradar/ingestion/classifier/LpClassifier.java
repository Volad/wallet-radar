package com.walletradar.ingestion.classifier;

import com.walletradar.domain.RawTransaction;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classifies LP add/remove liquidity events. MVP: stub returning empty list.
 * Will emit LP_ENTRY / LP_EXIT with LP_MANUAL_REQUIRED flag when implemented.
 */
@Component
public class LpClassifier implements TxClassifier {

    @Override
    public List<RawClassifiedEvent> classify(RawTransaction tx, String walletAddress) {
        return List.of();
    }
}
