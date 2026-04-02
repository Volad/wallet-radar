package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.ingestion.pipeline.classification.onchain.family.FamilyDecisionSupport;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;

/**
 * Thin decision builder for registry-backed rows. Contains no family-specific heuristics.
 */
public final class RegistryDecisionSupport {

    private RegistryDecisionSupport() {
    }

    public static ClassificationDecision registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<RawLeg> movementLegs
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                ParityFlowSupport.flows(view, movementLegs, type),
                List.of(),
                entry.protocolName(),
                entry.protocolVersion()
        );
    }

    public static ClassificationDecision registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                flows,
                missingDataReasons,
                entry.protocolName(),
                entry.protocolVersion()
        );
    }

    public static ClassificationDecision pendingRegistryReview(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            String reason
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                List.of(),
                List.of(reason),
                entry.protocolName(),
                entry.protocolVersion()
        );
    }

    public static ClassificationDecision pendingRegistryReview(
            OnChainRawTransactionView view,
            ProtocolMatch match,
            String reason
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.PROTOCOL_REGISTRY,
                match.confidence(),
                List.of(),
                List.of(reason),
                match.protocolName(),
                match.protocolVersion()
        );
    }
}
