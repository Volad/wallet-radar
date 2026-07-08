package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.cow;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.CowSwapSupport;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CoW Swap protocol-owned async request / settlement semantic detection.
 */
@Component
public class CowSwapProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    public static final String PROTOCOL_KEY = "cow";
    public static final String PROTOCOL_NAME = "CoW Swap";
    public static final String PROTOCOL_VERSION = "v1";
    public static final String SEMANTIC_DEX_ORDER_REQUEST = "dex_order_request";
    public static final String SEMANTIC_DEX_ORDER_SETTLEMENT = "dex_order_settlement";

    private final ProtocolResourceDefinition resource;

    public CowSwapProtocolSemanticClassifier(ProtocolResourceCatalog protocolResourceCatalog) {
        this.resource = protocolResourceCatalog.find(PROTOCOL_NAME, PROTOCOL_VERSION).orElse(null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 110;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        if (context == null || context.view() == null) {
            return List.of();
        }

        List<ProtocolSemanticHint> hints = new ArrayList<>(2);
        if (isEthFlowRequest(context.view())) {
            hints.add(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_DEX_ORDER_REQUEST,
                    CowSwapSupport.PROTOCOL_NAME,
                    CowSwapSupport.ETH_FLOW_VERSION,
                    CowSwapSupport.resolveEthFlowCorrelationId(context.view()),
                    NormalizedTransactionType.DEX_ORDER_REQUEST,
                    ConfidenceLevel.MEDIUM
            ));
        }
        if (isSettlementCandidate(context.view())) {
            hints.add(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_DEX_ORDER_SETTLEMENT,
                    CowSwapSupport.PROTOCOL_NAME,
                    CowSwapSupport.GPV2_VERSION,
                    CowSwapSupport.resolveSettlementCorrelationId(context.view()),
                    NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                    ConfidenceLevel.MEDIUM
            ));
        }
        return List.copyOf(hints);
    }

    private boolean isEthFlowRequest(com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        if (resource != null && !resource.matchesMethodSelector("ethFlowRequest", view.methodId())) {
            return false;
        }
        return CowSwapSupport.isEthFlowRequest(view);
    }

    private boolean isSettlementCandidate(com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        boolean resourceBacked = resource == null
                || resource.matchesMethodSelector("settlement", view.methodId())
                || hasConfiguredTradeTopic(view);
        return resourceBacked && CowSwapSupport.isSettlementCandidate(view);
    }

    private boolean hasConfiguredTradeTopic(com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView view) {
        if (view == null || resource == null || !view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        for (org.bson.Document log : view.persistedLogs()) {
            Object topicsObject = log.get("topics");
            if (!(topicsObject instanceof List<?> topics) || topics.isEmpty() || topics.getFirst() == null) {
                continue;
            }
            String topic = String.valueOf(topics.getFirst()).toLowerCase(java.util.Locale.ROOT);
            if (resource.matchesEventTopic("trade", topic)) {
                return true;
            }
        }
        return false;
    }
}
