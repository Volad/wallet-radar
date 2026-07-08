package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Trading family classifier. Protocol-owned lifecycle semantics are emitted by protocol semantic
 * classifiers; this family maps those generic hints to canonical trading types and flows.
 */
@Component
public class TradingClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        return derivativeDecision(context)
                .or(() -> dexOrderRequestDecision(context))
                .or(() -> dexOrderSettlementDecision(context));
    }

    private Optional<ClassificationDecision> derivativeDecision(OnChainClassificationContext context) {
        for (NormalizedTransactionType type : List.of(
                NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
                NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
                NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
                NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION
        )) {
            Optional<ProtocolSemanticHint> hint = context.protocolSemantics().firstBySuggestedType(type);
            if (hint.isPresent()) {
                return Optional.of(buildDerivativeDecision(context, hint.orElseThrow(), type));
            }
        }
        return Optional.empty();
    }

    private Optional<ClassificationDecision> dexOrderRequestDecision(OnChainClassificationContext context) {
        return context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.DEX_ORDER_REQUEST)
                .map(hint -> new ClassificationDecision(
                        NormalizedTransactionType.DEX_ORDER_REQUEST,
                        OnChainClassificationSupport.initialStatus(
                                context.view(),
                                NormalizedTransactionType.DEX_ORDER_REQUEST,
                                ConfidenceLevel.MEDIUM
                        ),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.MEDIUM,
                        OnChainClassificationSupport.toFlows(
                                context.movementLegs(),
                                NormalizedTransactionType.DEX_ORDER_REQUEST
                        ),
                        List.of(),
                        hint.correlationId(),
                        null,
                        null,
                        false,
                        null,
                        hint.protocolName(),
                        hint.protocolVersion()
                ));
    }

    private Optional<ClassificationDecision> dexOrderSettlementDecision(OnChainClassificationContext context) {
        return context.protocolSemantics()
                .firstBySuggestedType(NormalizedTransactionType.DEX_ORDER_SETTLEMENT)
                .map(hint -> {
                    NormalizedTransactionStatus status = hint.correlationId() == null
                            ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                            : OnChainClassificationSupport.initialStatus(
                                    context.view(),
                                    NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                                    ConfidenceLevel.MEDIUM
                            );
                    List<String> reasons = hint.correlationId() == null
                            ? List.of(ClassificationReasonCode.COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED.code())
                            : List.of();
                    return new ClassificationDecision(
                            NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                            status,
                            ClassificationSource.HEURISTIC,
                            ConfidenceLevel.MEDIUM,
                            OnChainClassificationSupport.toFlows(
                                    context.movementLegs(),
                                    NormalizedTransactionType.DEX_ORDER_SETTLEMENT
                            ),
                            reasons,
                            hint.correlationId(),
                            null,
                            null,
                            false,
                            null,
                            hint.protocolName(),
                            hint.protocolVersion()
                    );
                });
    }

    private ClassificationDecision buildDerivativeDecision(
            OnChainClassificationContext context,
            ProtocolSemanticHint hint,
            NormalizedTransactionType type
    ) {
        boolean request = type == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST;
        String missingReason = request
                ? ClassificationReasonCode.GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED.code()
                : ClassificationReasonCode.GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED.code();
        NormalizedTransactionStatus status = hint.correlationId() == null
                ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                : OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM);
        List<String> reasons = hint.correlationId() == null ? List.of(missingReason) : List.of();
        return new ClassificationDecision(
                type,
                status,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                reasons,
                hint.correlationId(),
                null,
                null,
                false,
                null,
                hint.protocolName(),
                hint.protocolVersion()
        );
    }
}
