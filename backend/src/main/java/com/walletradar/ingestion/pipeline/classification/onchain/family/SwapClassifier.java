package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Swap family classifier for clarified economic swap patterns.
 */
@Component
public class SwapClassifier implements OnChainFamilyClassifier {

    private static final String PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR = "0x7f457675";
    private static final String ROUTE_SINGLE_SELECTOR = "0xb94c3609";

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_PROTOCOL_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 300;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        String methodId = context.view().methodId();

        if (PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(methodId)
                && hasOutbound(context.movementLegs())) {
            return Optional.of(build(context, ClassificationSource.METHOD_ID, ConfidenceLevel.MEDIUM));
        }

        if (!context.view().hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }

        if (ROUTE_SINGLE_SELECTOR.equals(methodId)
                && hasDistinctNetInboundAndOutboundAssets(context.movementLegs())) {
            return Optional.of(build(context, ClassificationSource.METHOD_ID, ConfidenceLevel.LOW));
        }

        if (PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(methodId)
                && hasSameAssetRefundPattern(context.movementLegs())) {
            return Optional.of(build(context, ClassificationSource.METHOD_ID, ConfidenceLevel.LOW));
        }

        return Optional.empty();
    }

    private ClassificationDecision build(
            OnChainClassificationContext context,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence
    ) {
        return new ClassificationDecision(
                NormalizedTransactionType.SWAP,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.SWAP, confidence),
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.SWAP),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private boolean hasOutbound(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee())
                .anyMatch(leg -> leg.quantityDelta().signum() < 0);
    }

    private boolean hasDistinctNetInboundAndOutboundAssets(List<RawLeg> movementLegs) {
        Map<String, BigDecimal> netByAsset = new LinkedHashMap<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            netByAsset.merge(assetKey(leg), leg.quantityDelta(), BigDecimal::add);
        }
        boolean hasInbound = false;
        boolean hasOutbound = false;
        for (BigDecimal netDelta : netByAsset.values()) {
            if (netDelta == null || netDelta.signum() == 0) {
                continue;
            }
            if (netDelta.signum() > 0) {
                hasInbound = true;
            } else {
                hasOutbound = true;
            }
            if (hasInbound && hasOutbound) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSameAssetRefundPattern(List<RawLeg> movementLegs) {
        MovementSummary summary = MovementSummary.from(movementLegs);
        return summary.tokenOutboundCount == 1
                && summary.tokenInboundCount == 1
                && summary.sameAssetInAndOut
                && hasOutbound(movementLegs);
    }

    private String assetKey(RawLeg leg) {
        if (leg.assetContract() != null) {
            return leg.assetContract();
        }
        String symbol = leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT);
        return "native:" + symbol;
    }

    private record MovementSummary(
            int tokenInboundCount,
            int tokenOutboundCount,
            boolean sameAssetInAndOut
    ) {
        private static MovementSummary from(List<RawLeg> legs) {
            Map<String, Integer> inbound = new LinkedHashMap<>();
            Map<String, Integer> outbound = new LinkedHashMap<>();
            for (RawLeg leg : legs) {
                if (leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                    continue;
                }
                String assetKey = leg.assetContract() != null
                        ? leg.assetContract().toLowerCase(Locale.ROOT)
                        : "symbol:" + (leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT));
                if (leg.quantityDelta().signum() > 0) {
                    inbound.merge(assetKey, 1, Integer::sum);
                } else {
                    outbound.merge(assetKey, 1, Integer::sum);
                }
            }
            return new MovementSummary(
                    inbound.size(),
                    outbound.size(),
                    inbound.size() == 1 && inbound.keySet().equals(outbound.keySet())
            );
        }
    }
}
