package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.lp.PendleLpCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.support.BlockScoutNativeSettlementClarificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class LpSemanticClassifier implements OnChainFamilyClassifier {

    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public LpSemanticClassifier(NativeAssetSymbolResolver nativeAssetSymbolResolver) {
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 151;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        for (NormalizedTransactionType type : List.of(
                NormalizedTransactionType.LP_ENTRY,
                NormalizedTransactionType.LP_EXIT
        )) {
            Optional<ProtocolSemanticHint> hint = context.protocolSemantics().firstBySuggestedType(type);
            if (hint.isPresent()) {
                ProtocolSemanticHint value = hint.orElseThrow();
                // P2 (ADR-018 ETH-C10): Pendle LP txs routed via semantic hints must carry a
                // Pendle correlation ID derived from the LPT asset in the movement legs, so that
                // entries, exits, and fee claims share the same `pendle-lp:{net}:{marketId}` key.
                String correlationId = isPendleProtocol(value)
                        ? PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                                context.view(), context.movementLegs())
                        : null;

                // Fallback: derive Pendle correlationId from movement legs when the protocol hint did not
                // identify this as Pendle (covers Equilibria and other Pendle LP wrappers not in the registry).
                if (correlationId == null) {
                    correlationId = PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                            context.view(), context.movementLegs());
                }

                if (BlockScoutNativeSettlementClarificationSupport.requiresReceiptClarification(
                        context.view(),
                        context.movementLegs(),
                        type,
                        nativeAssetSymbolResolver
                )) {
                    return Optional.of(new ClassificationDecision(
                            type,
                            NormalizedTransactionStatus.PENDING_CLARIFICATION,
                            ClassificationSource.PROTOCOL_REGISTRY,
                            value.confidence(),
                            OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                            List.of(ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code()),
                            correlationId,
                            null,
                            null,
                            null,
                            null,
                            value.protocolName(),
                            value.protocolVersion()
                    ));
                }
                return Optional.of(new ClassificationDecision(
                        type,
                        OnChainClassificationSupport.initialStatus(context.view(), type, value.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        value.confidence(),
                        OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                        List.of(),
                        correlationId,
                        null,
                        null,
                        null,
                        null,
                        value.protocolName(),
                        value.protocolVersion()
                ));
            }
        }
        // No protocol hint found for LP_ENTRY/LP_EXIT — check if movement legs contain Pendle LP tokens.
        // Only applies when there are NO protocol hints at all (i.e. the contract is not in the registry).
        // Covers Equilibria and other Pendle wrappers whose contracts are not registered.
        // Must run before HeuristicClassifier so the correct correlationId is injected.
        if (context.protocolSemantics().hints().isEmpty()) {
            String pendleCorrelationId = PendleLpCorrelationSupport.correlationIdFromMovementLegs(
                    context.view(), context.movementLegs());
            if (pendleCorrelationId != null) {
                NormalizedTransactionType pendleType = PendleLpCorrelationSupport.lpTypeFromMovementLegs(
                        context.movementLegs());
                if (pendleType != null) {
                    return Optional.of(new ClassificationDecision(
                            pendleType,
                            OnChainClassificationSupport.initialStatus(context.view(), pendleType, ConfidenceLevel.MEDIUM),
                            ClassificationSource.HEURISTIC,
                            ConfidenceLevel.MEDIUM,
                            OnChainClassificationSupport.toFlows(context.movementLegs(), pendleType),
                            List.of(),
                            pendleCorrelationId,
                            null,
                            null,
                            null,
                            null,
                            "Pendle",
                            null
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isPendleProtocol(ProtocolSemanticHint hint) {
        return hint != null
                && hint.protocolName() != null
                && "Pendle".equalsIgnoreCase(hint.protocolName().trim());
    }
}
