package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.lp.GmxMarketCorrelationSupport;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * GMX V2 async LP family classifier. Protocol semantic classifiers own request / settlement
 * detection and correlation; this family maps those generic hints to canonical LP lifecycle rows.
 */
@Component
public class GmxLpClassifier implements OnChainFamilyClassifier {

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 120;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        for (NormalizedTransactionType type : List.of(
                NormalizedTransactionType.LP_ENTRY_REQUEST,
                NormalizedTransactionType.LP_EXIT_REQUEST,
                NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                NormalizedTransactionType.LP_EXIT_SETTLEMENT
        )) {
            Optional<ProtocolSemanticHint> hint = context.protocolSemantics().firstBySuggestedType(type);
            if (hint.isPresent()) {
                return Optional.of(type == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                                || type == NormalizedTransactionType.LP_EXIT_SETTLEMENT
                        ? buildSettlementDecision(context, hint.orElseThrow(), type)
                        : buildRequestDecision(context, hint.orElseThrow(), type));
            }
        }
        return Optional.empty();
    }

    private static final String GMX_LP_PREFIX = "gmx-lp:";
    private static final String GMX_EXIT_RAW_KEY_PREFIX = "gmx-exit-raw-key:";

    private ClassificationDecision buildRequestDecision(
            OnChainClassificationContext context,
            ProtocolSemanticHint hint,
            NormalizedTransactionType type
    ) {
        // For LP_EXIT_REQUEST the GM/GLV tokens are OUTGOING (negative delta), so the standard
        // positive-only scan misses them. Use the all-legs variant which accepts any sign.
        String resolvedCorrelationId = GmxMarketCorrelationSupport.correlationIdFromAllLegs(
                context.view(), context.movementLegs());
        if (resolvedCorrelationId == null) {
            resolvedCorrelationId = hint.correlationId();
        }

        List<String> reasons = new ArrayList<>();
        if (resolvedCorrelationId == null) {
            reasons.add(missingReason(type));
        } else if (type == NormalizedTransactionType.LP_EXIT_REQUEST
                && hint.correlationId() != null
                && !hint.correlationId().isBlank()
                && !hint.correlationId().startsWith(GMX_LP_PREFIX)) {
            // Store the raw event key so GmxExitSettlementLinkService can match the paired
            // LP_EXIT_SETTLEMENT (which carries the same raw key as its correlationId).
            reasons.add(GMX_EXIT_RAW_KEY_PREFIX + hint.correlationId().toLowerCase(Locale.ROOT));
        }

        NormalizedTransactionStatus status = resolvedCorrelationId == null
                ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                : OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM);
        return new ClassificationDecision(
                type,
                status,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(context.movementLegs(), type),
                reasons,
                resolvedCorrelationId,
                null,
                null,
                false,
                null,
                hint.protocolName(),
                hint.protocolVersion()
        );
    }

    private ClassificationDecision buildSettlementDecision(
            OnChainClassificationContext context,
            ProtocolSemanticHint hint,
            NormalizedTransactionType type
    ) {
        String correlationId = resolveSettlementCorrelationId(context, hint, type);
        NormalizedTransactionStatus status = correlationId == null
                ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                : OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.MEDIUM);
        List<String> reasons = correlationId == null
                ? List.of(missingReason(type))
                : List.of();
        return new ClassificationDecision(
                type,
                status,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(orderAsyncLpSettlementLegs(context.movementLegs()), type),
                reasons,
                correlationId,
                null,
                null,
                false,
                null,
                hint.protocolName(),
                hint.protocolVersion()
        );
    }

    private String missingReason(NormalizedTransactionType type) {
        return switch (type) {
            case LP_ENTRY_REQUEST -> ClassificationReasonCode.GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED.code();
            case LP_EXIT_REQUEST -> ClassificationReasonCode.GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED.code();
            case LP_ENTRY_SETTLEMENT -> ClassificationReasonCode.GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED.code();
            case LP_EXIT_SETTLEMENT -> ClassificationReasonCode.GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED.code();
            default -> throw new IllegalArgumentException("Unsupported GMX LP semantic type: " + type);
        };
    }

    private List<RawLeg> orderAsyncLpSettlementLegs(List<RawLeg> movementLegs) {
        List<RawLeg> ordered = new ArrayList<>(movementLegs);
        ordered.sort(Comparator.comparingInt(this::asyncLpSettlementOrder));
        return ordered;
    }

    private int asyncLpSettlementOrder(RawLeg leg) {
        if (leg == null) {
            return 3;
        }
        if (leg.fee()) {
            return 2;
        }
        if (leg.quantityDelta() != null && leg.quantityDelta().signum() > 0 && !isGmxShareLikeSymbol(leg.assetSymbol())) {
            return 0;
        }
        return 1;
    }

    private boolean isGmxShareLikeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return symbol.regionMatches(true, 0, "GM:", 0, 3)
                || symbol.regionMatches(true, 0, "GLV", 0, 3);
    }

    private String resolveSettlementCorrelationId(
            OnChainClassificationContext context,
            ProtocolSemanticHint hint,
            NormalizedTransactionType type
    ) {
        String marketCorrelationId = GmxMarketCorrelationSupport.correlationIdFromMovementLegs(
                context.view(),
                context.movementLegs()
        );
        if (marketCorrelationId != null) {
            return marketCorrelationId;
        }
        if (hint.correlationId() != null && !hint.correlationId().isBlank()) {
            return hint.correlationId();
        }
        return null;
    }
}
