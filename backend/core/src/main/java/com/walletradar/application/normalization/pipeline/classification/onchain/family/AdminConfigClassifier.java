package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.support.AdminConfigSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Low-risk admin/config family.
 */
@Component
public class AdminConfigClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.EARLY_GUARDS;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        List<RawLeg> movementLegs = context.movementLegs();
        boolean hasNonFeeMovement = movementLegs.stream().anyMatch(leg -> !leg.fee());
        return AdminConfigSupport.match(context.view(), hasNonFeeMovement)
                .map(match -> FamilyDecisionSupport.build(
                        NormalizedTransactionType.ADMIN_CONFIG,
                        NormalizedTransactionStatus.CONFIRMED,
                        match.classifiedBy(),
                        match.confidence(),
                        movementLegs,
                        List.of()
                ));
    }
}
