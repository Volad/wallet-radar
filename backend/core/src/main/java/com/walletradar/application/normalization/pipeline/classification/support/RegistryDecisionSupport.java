package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.family.FamilyDecisionSupport;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

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

    public static ClassificationDecision registryResultWithMatchedCounterparty(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<RawLeg> movementLegs,
            String matchedCounterparty
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                ParityFlowSupport.flows(view, movementLegs, type),
                List.of(),
                false,
                matchedCounterparty,
                entry.protocolName(),
                entry.protocolVersion()
        );
    }

    public static ClassificationDecision registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<RawLeg> movementLegs,
            String correlationId
    ) {
        return registryResult(
                view,
                entry,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                ParityFlowSupport.flows(view, movementLegs, type),
                List.of(),
                correlationId
        );
    }

    public static ClassificationDecision registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons
    ) {
        return registryResult(
                view,
                entry,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                flows,
                missingDataReasons
        );
    }

    public static ClassificationDecision registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            String correlationId
    ) {
        ClassificationDecision decision = FamilyDecisionSupport.buildWithView(
                view,
                type,
                status,
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                flows,
                missingDataReasons,
                entry.protocolName(),
                entry.protocolVersion()
        );
        if (correlationId == null || correlationId.isBlank()) {
            return decision;
        }
        return new ClassificationDecision(
                decision.type(),
                decision.status(),
                decision.classifiedBy(),
                decision.confidence(),
                decision.flows(),
                decision.missingDataReasons(),
                correlationId,
                decision.continuityCandidate(),
                decision.matchedCounterparty(),
                decision.excludedFromAccounting(),
                decision.accountingExclusionReason(),
                decision.protocolName(),
                decision.protocolVersion()
        );
    }

    public static ClassificationDecision registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons
    ) {
        return FamilyDecisionSupport.buildWithView(
                view,
                type,
                status,
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
