package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Staking family classifier. Protocol-owned lifecycle detection comes from the protocol semantic
 * layer; this family maps those hints to staking types and canonical flows.
 */
@Component
public class StakingClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolSemanticHint> withdrawRequest = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.STAKING_WITHDRAW_REQUEST);
        if (withdrawRequest.isPresent()) {
            return Optional.of(buildResolvDecision(
                    context,
                    withdrawRequest.get(),
                    NormalizedTransactionType.STAKING_WITHDRAW_REQUEST,
                    OnChainClassificationSupport.toFlows(
                            context.movementLegs(),
                            NormalizedTransactionType.STAKING_WITHDRAW_REQUEST
                    )
            ));
        }

        Optional<ProtocolSemanticHint> withdrawSettlement = context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.STAKING_WITHDRAW);
        if (withdrawSettlement.isPresent()) {
            return Optional.of(buildResolvDecision(
                    context,
                    withdrawSettlement.get(),
                    NormalizedTransactionType.STAKING_WITHDRAW,
                    buildResolvSettlementFlows(context.movementLegs())
            ));
        }

        return Optional.empty();
    }

    private ClassificationDecision buildResolvDecision(
            OnChainClassificationContext context,
            ProtocolSemanticHint semanticHint,
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows
    ) {
        return new ClassificationDecision(
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM),
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                flows,
                List.of(),
                semanticHint.correlationId(),
                null,
                null,
                false,
                null,
                semanticHint.protocolName(),
                semanticHint.protocolVersion()
        );
    }

    private List<NormalizedTransaction.Flow> buildResolvSettlementFlows(List<RawLeg> movementLegs) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            flow.setRole(leg.fee() ? NormalizedLegRole.FEE : NormalizedLegRole.TRANSFER);
            flows.add(flow);
        }
        return flows;
    }
}
