package com.walletradar.application.normalization.pipeline.classification;

import org.springframework.stereotype.Component;

/**
 * Maps the internal classification decision contract to the persisted classification result.
 */
@Component
public class ClassificationDecisionMapper {

    public OnChainClassificationResult toResult(ClassificationDecision decision) {
        return new OnChainClassificationResult(
                decision.type(),
                decision.status(),
                decision.classifiedBy(),
                decision.confidence(),
                decision.flows(),
                decision.missingDataReasons(),
                decision.correlationId(),
                decision.continuityCandidate(),
                decision.matchedCounterparty(),
                decision.excludedFromAccounting(),
                decision.accountingExclusionReason(),
                decision.protocolName(),
                decision.protocolVersion()
        );
    }
}
