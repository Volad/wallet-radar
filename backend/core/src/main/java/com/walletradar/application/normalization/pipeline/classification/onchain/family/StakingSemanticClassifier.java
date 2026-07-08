package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Converts a {@code STAKING_DEPOSIT} protocol-semantic hint (e.g. from
 * {@code EquilibriaProtocolSemanticClassifier}) into a final
 * {@link NormalizedTransactionType#STAKING_DEPOSIT} classification decision.
 *
 * <p>Runs at PRE_PROTOCOL_REVIEW order 148, before {@link LpSemanticClassifier} (+151) and
 * {@link LendingSemanticClassifier} (+152), so the staking hint wins over any heuristic LP_EXIT
 * or registry fallback that would otherwise fire for Equilibria deposit transactions.
 *
 * <p>See ADR-047 for the Equilibria staking deposit / LP corridor design.
 */
@Component
public class StakingSemanticClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 148;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolSemanticHint> hint = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.STAKING_DEPOSIT);
        if (hint.isEmpty()) {
            return Optional.empty();
        }
        ProtocolSemanticHint value = hint.orElseThrow();
        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                NormalizedTransactionType.STAKING_DEPOSIT,
                OnChainClassificationSupport.initialStatus(
                        context.view(),
                        NormalizedTransactionType.STAKING_DEPOSIT,
                        value.confidence()
                ),
                ClassificationSource.PROTOCOL_REGISTRY,
                value.confidence(),
                OnChainClassificationSupport.toFlows(
                        context.movementLegs(),
                        NormalizedTransactionType.STAKING_DEPOSIT
                ),
                List.of(),
                value.protocolName(),
                value.protocolVersion()
        ));
    }
}
