package com.walletradar.ingestion.pipeline.classification.onchain.protocol.gmx;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticClassifier;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.lp.GmxMarketCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.CalldataDecodingSupport;
import com.walletradar.ingestion.pipeline.classification.support.CowSwapSupport;
import com.walletradar.ingestion.pipeline.classification.support.GmxEventTopicSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * GMX V2 protocol-owned derivative request / execution semantic detection.
 */
@Component
public class GmxProtocolSemanticClassifier implements ProtocolSemanticClassifier {

    public static final String PROTOCOL_KEY = "gmx";
    public static final String PROTOCOL_NAME = "GMX";
    public static final String PROTOCOL_VERSION = "V2";

    public static final String SEMANTIC_DERIVATIVE_ORDER_REQUEST = "derivative_order_request";
    public static final String SEMANTIC_DERIVATIVE_ORDER_EXECUTION = "derivative_order_execution";
    public static final String SEMANTIC_DERIVATIVE_ORDER_CANCEL = "derivative_order_cancel";
    public static final String SEMANTIC_DERIVATIVE_POSITION_INCREASE = "derivative_position_increase";
    public static final String SEMANTIC_DERIVATIVE_POSITION_DECREASE = "derivative_position_decrease";
    public static final String SEMANTIC_LP_ENTRY_REQUEST = "lp_entry_request";
    public static final String SEMANTIC_LP_EXIT_REQUEST = "lp_exit_request";
    public static final String SEMANTIC_LP_ENTRY_SETTLEMENT = "lp_entry_settlement";
    public static final String SEMANTIC_LP_EXIT_SETTLEMENT = "lp_exit_settlement";

    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final ProtocolRegistryService protocolRegistryService;
    private final ProtocolResourceDefinition resource;

    public GmxProtocolSemanticClassifier(
            ProtocolRegistryService protocolRegistryService,
            ProtocolResourceCatalog protocolResourceCatalog
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.resource = protocolResourceCatalog.find(PROTOCOL_NAME, PROTOCOL_VERSION).orElse(null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 120;
    }

    @Override
    public List<ProtocolSemanticHint> classify(ProtocolSemanticContext context) {
        if (context == null || context.view() == null) {
            return List.of();
        }

        Optional<ProtocolSemanticHint> exchangeRouterLifecycle = classifyExchangeRouterLifecycle(context);
        if (exchangeRouterLifecycle.isPresent()) {
            return List.of(exchangeRouterLifecycle.orElseThrow());
        }

        Optional<ProtocolSemanticHint> derivativeRequest = classifyDerivativeRequest(context.view(), context.movementLegs());
        if (derivativeRequest.isPresent()) {
            return List.of(derivativeRequest.orElseThrow());
        }

        Optional<ProtocolSemanticHint> executeOrder = classifyExecuteOrder(context.view());
        if (executeOrder.isPresent()) {
            return List.of(executeOrder.orElseThrow());
        }

        Optional<ProtocolSemanticHint> lpLifecycle = classifyLpLifecycle(context.view(), context.movementLegs());
        return lpLifecycle.map(List::of).orElseGet(List::of);
    }

    private Optional<ProtocolSemanticHint> classifyExchangeRouterLifecycle(ProtocolSemanticContext context) {
        Optional<ProtocolMatch> match = context.protocolDiscovery().firstSpecialHandlerMatch(
                ProtocolRegistrySpecialHandlerType.GMX_V2_EXCHANGE_ROUTER
        );
        if (match.isEmpty()) {
            return Optional.empty();
        }
        OnChainRawTransactionView view = context.view();
        ProtocolMatch value = match.orElseThrow();
        if (matchesMethodSelector(view, "exchangeRouterLpEntry", "0x2e7eff49")
                || matchesFunctionMarker(view.functionName(), "exchangeRouterLpEntry", "createdeposit")) {
            return Optional.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_LP_ENTRY_REQUEST,
                    value.protocolName(),
                    value.protocolVersion(),
                    null,
                    NormalizedTransactionType.LP_ENTRY,
                    value.confidence()
            ));
        }
        if (matchesMethodSelector(view, "exchangeRouterLpExit", "0x87d66368")
                || matchesFunctionMarker(view.functionName(), "exchangeRouterLpExit", "createwithdrawal")) {
            return Optional.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_LP_EXIT_REQUEST,
                    value.protocolName(),
                    value.protocolVersion(),
                    null,
                    NormalizedTransactionType.LP_EXIT,
                    value.confidence()
            ));
        }
        return Optional.empty();
    }

    private boolean matchesFunctionMarker(
            String functionName,
            String group,
            String... fallbackMarkers
    ) {
        if (functionName == null) {
            return false;
        }
        if (resource != null && resource.matchesFunctionMarker(group, functionName)) {
            return true;
        }
        String normalized = functionName.toLowerCase(Locale.ROOT);
        for (String fallbackMarker : fallbackMarkers) {
            if (fallbackMarker != null && normalized.contains(fallbackMarker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Optional<ProtocolSemanticHint> classifyDerivativeRequest(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        String functionKey = functionKey(view.functionName());
        if (!isGmxDerivativeOrderRequest(view, movementLegs, functionKey)) {
            return Optional.empty();
        }
        return Optional.of(new ProtocolSemanticHint(
                PROTOCOL_KEY,
                SEMANTIC_DERIVATIVE_ORDER_REQUEST,
                PROTOCOL_NAME,
                PROTOCOL_VERSION,
                resolveGmxOrderRequestCorrelationId(view),
                NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                com.walletradar.domain.common.ConfidenceLevel.MEDIUM
        ));
    }

    private Optional<ProtocolSemanticHint> classifyExecuteOrder(OnChainRawTransactionView view) {
        String functionKey = functionKey(view.functionName());
        boolean selectorBacked = matchesMethodSelector(view, "executeOrder", "0x7ebc83f7")
                || "executeorder".equals(functionKey);
        boolean clarifiedLifecycle = view.hasFullReceiptClarificationEvidence()
                && (hasGmxEvent(view, "positionDecrease", "PositionDecrease")
                || hasGmxEvent(view, "positionIncrease", "PositionIncrease")
                || hasGmxEvent(view, "orderCancelled", "OrderCancelled")
                || hasGmxEvent(view, "orderExecuted", "OrderExecuted"));
        if (!selectorBacked && !clarifiedLifecycle) {
            return Optional.empty();
        }

        String semanticType = SEMANTIC_DERIVATIVE_ORDER_EXECUTION;
        if (hasGmxEvent(view, "positionDecrease", "PositionDecrease")) {
            semanticType = SEMANTIC_DERIVATIVE_POSITION_DECREASE;
        } else if (hasGmxEvent(view, "positionIncrease", "PositionIncrease")) {
            semanticType = SEMANTIC_DERIVATIVE_POSITION_INCREASE;
        } else if (hasGmxEvent(view, "orderCancelled", "OrderCancelled")
                && !hasGmxEvent(view, "orderExecuted", "OrderExecuted")) {
            semanticType = SEMANTIC_DERIVATIVE_ORDER_CANCEL;
        }

        return Optional.of(new ProtocolSemanticHint(
                PROTOCOL_KEY,
                semanticType,
                PROTOCOL_NAME,
                PROTOCOL_VERSION,
                resolveGmxExecuteOrderCorrelationId(view),
                suggestedType(semanticType),
                com.walletradar.domain.common.ConfidenceLevel.MEDIUM
        ));
    }

    private Optional<ProtocolSemanticHint> classifyLpLifecycle(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (isGmxDepositRequestMulticall(view, movementLegs)) {
            return Optional.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_LP_ENTRY_REQUEST,
                    PROTOCOL_NAME,
                    PROTOCOL_VERSION,
                    resolveGmxRequestCorrelationId(view),
                    NormalizedTransactionType.LP_ENTRY_REQUEST,
                    com.walletradar.domain.common.ConfidenceLevel.MEDIUM
            ));
        }
        if (isGmxWithdrawalRequestMulticall(view, movementLegs)) {
            return Optional.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_LP_EXIT_REQUEST,
                    PROTOCOL_NAME,
                    PROTOCOL_VERSION,
                    resolveGmxWithdrawalRequestCorrelationId(view),
                    NormalizedTransactionType.LP_EXIT_REQUEST,
                    com.walletradar.domain.common.ConfidenceLevel.MEDIUM
            ));
        }
        if (isGmxDepositSettlement(view, movementLegs)) {
            return Optional.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_LP_ENTRY_SETTLEMENT,
                    PROTOCOL_NAME,
                    PROTOCOL_VERSION,
                    resolveGmxSettlementCorrelationId(view, movementLegs),
                    NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                    com.walletradar.domain.common.ConfidenceLevel.MEDIUM
            ));
        }
        if (isGmxWithdrawalSettlement(view, movementLegs)
                || hasGmxEvent(view, "withdrawalExecuted", "WithdrawalExecuted")
                || hasGmxEvent(view, "glvWithdrawalExecuted", "GlvWithdrawalExecuted")) {
            return Optional.of(new ProtocolSemanticHint(
                    PROTOCOL_KEY,
                    SEMANTIC_LP_EXIT_SETTLEMENT,
                    PROTOCOL_NAME,
                    PROTOCOL_VERSION,
                    resolveGmxWithdrawalSettlementCorrelationId(view, movementLegs),
                    NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                    com.walletradar.domain.common.ConfidenceLevel.MEDIUM
            ));
        }
        return Optional.empty();
    }

    private NormalizedTransactionType suggestedType(String semanticType) {
        return switch (semanticType) {
            case SEMANTIC_DERIVATIVE_ORDER_REQUEST -> NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST;
            case SEMANTIC_DERIVATIVE_ORDER_EXECUTION -> NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION;
            case SEMANTIC_DERIVATIVE_ORDER_CANCEL -> NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL;
            case SEMANTIC_DERIVATIVE_POSITION_INCREASE -> NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE;
            case SEMANTIC_DERIVATIVE_POSITION_DECREASE -> NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE;
            case SEMANTIC_LP_ENTRY_REQUEST -> NormalizedTransactionType.LP_ENTRY_REQUEST;
            case SEMANTIC_LP_EXIT_REQUEST -> NormalizedTransactionType.LP_EXIT_REQUEST;
            case SEMANTIC_LP_ENTRY_SETTLEMENT -> NormalizedTransactionType.LP_ENTRY_SETTLEMENT;
            case SEMANTIC_LP_EXIT_SETTLEMENT -> NormalizedTransactionType.LP_EXIT_SETTLEMENT;
            default -> null;
        };
    }

    private boolean isGmxDerivativeOrderRequest(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            String functionKey
    ) {
        if (CowSwapSupport.isEthFlowRequest(view)) {
            return false;
        }
        if (matchesMethodSelector(view, "orderRequest", "0x0ad58d2f", "0x322bba21")
                || "createorder".equals(functionKey)) {
            return true;
        }
        if (view.networkId() != NetworkId.ARBITRUM
                || !matchesMethodSelector(view, "multicall", "0xac9650d8")) {
            return false;
        }
        List<String> subcallSelectors = CalldataDecodingSupport.decodeDynamicBytesArraySelectors(view.inputData());
        if (subcallSelectors.isEmpty()
                || !containsAnySubcall(
                subcallSelectors,
                configuredSubcallSelectors("helperFunding", "0x7d39aaf1", "0xe6d66ac8")
        )
                || !containsAnySubcall(
                subcallSelectors,
                configuredSubcallSelectors("derivativeOrderRequest", "0x6996807b", "0x0ad58d2f", "0x322bba21")
        )) {
            return false;
        }
        return onlyOutbound(movementLegs)
                && hasGmxHelperFundingTarget(view, ProtocolRegistryRole.ORDER_VAULT, PROTOCOL_NAME);
    }

    private boolean isGmxDepositRequestMulticall(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM
                || !matchesMethodSelector(view, "multicall", "0xac9650d8")) {
            return false;
        }
        String inputData = view.inputData();
        if (inputData == null
                || !containsEmbeddedSelector(inputData, configuredSubcallSelectors("helperFunding", "0x7d39aaf1", "0xe6d66ac8"))) {
            return false;
        }
        if (!onlyOutbound(movementLegs)) {
            return false;
        }
        if (hasOutboundTransferToProtocolRole(view, ProtocolRegistryRole.ORDER_VAULT, PROTOCOL_NAME)) {
            return false;
        }
        return hasOutboundNonGmxShareAsset(movementLegs) && !hasOutboundGmxShareAsset(movementLegs);
    }

    private boolean isGmxWithdrawalRequestMulticall(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM
                || !matchesMethodSelector(view, "multicall", "0xac9650d8")) {
            return false;
        }
        String inputData = view.inputData();
        if (inputData == null
                || !containsEmbeddedSelector(inputData, configuredSubcallSelectors("helperFunding", "0x7d39aaf1", "0xe6d66ac8"))) {
            return false;
        }
        if (!containsAnyDecodedOrEmbeddedSubcall(
                view,
                configuredSubcallSelectors("withdrawalRequest", "0x647c6fa4", "0x4e78dc23")
        )) {
            return false;
        }
        return onlyOutbound(movementLegs) && hasOutboundGmxShareAsset(movementLegs);
    }

    private boolean isGmxDepositSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM) {
            return false;
        }
        boolean settlementSelector = matchesMethodSelector(view, "depositSettlement", "0xc30d8910")
                || matchesMethodSelector(view, "glvDepositSettlement", "0x5ee8ec8f")
                || containsAny(view.functionName(), "executedeposit", "executeglvdeposit");
        return settlementSelector && onlyInbound(movementLegs);
    }

    private boolean isGmxWithdrawalSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM) {
            return false;
        }
        boolean settlementSelector = matchesMethodSelector(view, "withdrawalSettlement", "0xc96fea9f")
                || containsAny(view.functionName(), "executewithdrawal", "executeglvwithdrawal");
        return settlementSelector && onlyInbound(movementLegs);
    }

    private String resolveGmxOrderRequestCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log)) {
                continue;
            }
            if (hasGmxEventName(log, "orderCreated", "OrderCreated")) {
                String requestKey = topicAt(log, 2);
                if (requestKey != null && !requestKey.isBlank()) {
                    return requestKey.toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private String resolveGmxRequestCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log)) {
                continue;
            }
            if (hasGmxEventName(log, "orderCreated", "OrderCreated")) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String resolveGmxExecuteOrderCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        String executedKey = firstGmxEventCorrelationId(view, "orderExecuted", "OrderExecuted");
        if (executedKey != null) {
            return executedKey;
        }
        return firstGmxEventCorrelationId(view, "orderCancelled", "OrderCancelled");
    }

    private String resolveGmxSettlementCorrelationId(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.hasFullReceiptClarificationEvidence()) {
            String glvCorrelationId = firstGmxEventCorrelationIdByTopic(
                    view,
                    configuredEventTopics("glvDepositExecuted", "0x168af62e3da2e23e63dfeb41b97ea0feef3c7a45e72ebc59e924f19ae915f14e")
            );
            if (glvCorrelationId != null) {
                return glvCorrelationId;
            }
            String depositCorrelationId = firstGmxEventCorrelationIdByTopic(
                    view,
                    configuredEventTopics("depositExecuted", "0x2856020a9644603d22d7b029b5649a55d708b88d9049150f146ac26c4107b880")
            );
            if (depositCorrelationId != null) {
                return depositCorrelationId;
            }
        }
        return GmxMarketCorrelationSupport.correlationIdFromMovementLegs(view, movementLegs);
    }

    private String resolveGmxWithdrawalRequestCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log)) {
                continue;
            }
            if (!hasGmxEventName(log, "withdrawalCreated", "WithdrawalCreated")
                    && !hasGmxEventName(log, "glvWithdrawalCreated", "GlvWithdrawalCreated")) {
                continue;
            }
            String walletTopic = topicAddress(topicAt(log, 3));
            if (!matchesPrimaryWallet(view, walletTopic)) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String resolveGmxWithdrawalSettlementCorrelationId(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.hasFullReceiptClarificationEvidence()) {
            String glvCorrelationId = firstTrackedWalletGmxEventCorrelationId(
                    view,
                    "glvWithdrawalExecuted",
                    "GlvWithdrawalExecuted"
            );
            if (glvCorrelationId != null) {
                return glvCorrelationId;
            }
            String withdrawalCorrelationId = firstTrackedWalletGmxEventCorrelationId(
                    view,
                    "withdrawalExecuted",
                    "WithdrawalExecuted"
            );
            if (withdrawalCorrelationId != null) {
                return withdrawalCorrelationId;
            }
        }
        return GmxMarketCorrelationSupport.correlationIdFromMovementLegs(view, movementLegs);
    }

    private String firstGmxEventCorrelationId(
            OnChainRawTransactionView view,
            String eventGroup,
            String fallbackEventName
    ) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !hasGmxEventName(log, eventGroup, fallbackEventName)) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String firstGmxEventCorrelationIdByTopic(
            OnChainRawTransactionView view,
            List<String> eventTopics
    ) {
        if (!view.hasFullReceiptClarificationEvidence() || eventTopics == null || eventTopics.isEmpty()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !matchesAnyTopic(topicAt(log, 1), eventTopics)) {
                continue;
            }
            String correlationId = topicAt(log, 2);
            if (correlationId != null && !correlationId.isBlank()) {
                return correlationId.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String firstTrackedWalletGmxEventCorrelationId(
            OnChainRawTransactionView view,
            String eventGroup,
            String fallbackEventName
    ) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !hasGmxEventName(log, eventGroup, fallbackEventName)) {
                continue;
            }
            String walletTopic = topicAddress(topicAt(log, 3));
            if (!matchesPrimaryWallet(view, walletTopic)) {
                continue;
            }
            String correlationId = topicAt(log, 2);
            if (correlationId != null && !correlationId.isBlank()) {
                return correlationId.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private boolean hasGmxEvent(
            OnChainRawTransactionView view,
            String eventGroup,
            String fallbackEventName
    ) {
        if (view == null || fallbackEventName == null || !view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        boolean hasEmitterEvidence = view.persistedLogs().stream().anyMatch(this::isGmxEventEmitterLog);
        for (Document log : view.persistedLogs()) {
            if (isGmxEventEmitterLog(log) && hasGmxEventName(log, eventGroup, fallbackEventName)) {
                return true;
            }
            if (hasEmitterEvidence && isStructuredGmxLifecycleLog(log, eventGroup, fallbackEventName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStructuredGmxLifecycleLog(Document log, String eventGroup, String fallbackEventName) {
        if (log == null || fallbackEventName == null || isGmxEventEmitterLog(log) || isErc20TransferLog(log)) {
            return false;
        }
        return hasGmxEventName(log, eventGroup, fallbackEventName);
    }

    private boolean isGmxEventEmitterLog(Document log) {
        List<String> topics = normalizedTopics(log);
        return !topics.isEmpty() && matchesAnyTopic(
                topics.getFirst(),
                configuredEventTopics("eventEmitter", GmxEventTopicSupport.EVENT_EMITTER_TOPIC)
        );
    }

    private boolean hasGmxEventName(Document log, String eventGroup, String fallbackEventName) {
        if (fallbackEventName == null || log == null) {
            return false;
        }
        List<String> expectedEventNames = configuredEventNames(eventGroup, fallbackEventName);
        String normalizedExpected = fallbackEventName.trim().toLowerCase(Locale.ROOT);
        String eventTopic = topicAt(log, 1);
        if (eventTopic != null) {
            for (String expectedEventName : expectedEventNames) {
                String expectedTopic = GmxEventTopicSupport.topicHash(expectedEventName);
                if (expectedTopic != null && eventTopic.equalsIgnoreCase(expectedTopic)) {
                    return true;
                }
            }
        }
        String explicitEventName = stringValue(log.get("eventName"));
        if (explicitEventName != null) {
            String normalizedExplicit = explicitEventName.toLowerCase(Locale.ROOT);
            if (expectedEventNames.stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(normalizedExplicit::contains)) {
                return true;
            }
        }
        String decodedEvent = stringValue(log.get("decodedEvent"));
        if (decodedEvent != null) {
            String normalizedDecoded = decodedEvent.toLowerCase(Locale.ROOT);
            if (expectedEventNames.stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(normalizedDecoded::contains)) {
                return true;
            }
        }
        for (String expectedEventName : expectedEventNames) {
            if (CalldataDecodingSupport.containsAsciiFragment(logData(log), expectedEventName)) {
                return true;
            }
        }
        return CalldataDecodingSupport.containsAsciiFragment(logData(log), normalizedExpected);
    }

    private boolean containsAnySubcall(List<String> selectors, List<String> expectedSelectors) {
        if (selectors == null || selectors.isEmpty() || expectedSelectors == null || expectedSelectors.isEmpty()) {
            return false;
        }
        for (String selector : selectors) {
            if (selector == null) {
                continue;
            }
            for (String expectedSelector : expectedSelectors) {
                if (expectedSelector != null && selector.equalsIgnoreCase(expectedSelector)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAnyDecodedOrEmbeddedSubcall(
            OnChainRawTransactionView view,
            List<String> expectedSelectors
    ) {
        if (view == null || expectedSelectors == null || expectedSelectors.isEmpty()) {
            return false;
        }
        List<String> decodedSelectors = CalldataDecodingSupport.decodeDynamicBytesArraySelectors(view.inputData());
        if (containsAnySubcall(decodedSelectors, expectedSelectors)) {
            return true;
        }
        String inputData = view.inputData();
        if (inputData == null || inputData.isBlank()) {
            return false;
        }
        for (String expectedSelector : expectedSelectors) {
            if (expectedSelector != null
                    && CalldataDecodingSupport.containsEmbeddedSelector(inputData, expectedSelector)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGmxHelperFundingTarget(
            OnChainRawTransactionView view,
            ProtocolRegistryRole role,
            String protocolName
    ) {
        if (hasOutboundTransferToProtocolRole(view, role, protocolName)) {
            return true;
        }
        for (String subcall : CalldataDecodingSupport.decodeDynamicBytesArrayElements(view.inputData())) {
            String selector = subcallSelector(subcall);
            String targetAddress = null;
            if (configuredSubcallSelectors("helperSendWnt", "0x7d39aaf1").contains(selector)) {
                targetAddress = CalldataDecodingSupport.decodeAddressArgument(subcall, 0);
            } else if (configuredSubcallSelectors("helperSendTokens", "0xe6d66ac8").contains(selector)) {
                targetAddress = CalldataDecodingSupport.decodeAddressArgument(subcall, 1);
            }
            if (targetAddress == null) {
                continue;
            }
            Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(view.networkId(), targetAddress);
            if (entry.isPresent()
                    && entry.get().role() == role
                    && (protocolName == null || protocolName.equalsIgnoreCase(entry.get().protocolName()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOutboundTransferToProtocolRole(
            OnChainRawTransactionView view,
            ProtocolRegistryRole role,
            String protocolName
    ) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))) {
                continue;
            }
            Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                    view.networkId(),
                    view.tokenTransferTo(transfer)
            );
            if (entry.isEmpty()) {
                continue;
            }
            ProtocolRegistryEntry value = entry.get();
            if (value.role() == role
                    && (protocolName == null || protocolName.equalsIgnoreCase(value.protocolName()))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPrimaryWallet(OnChainRawTransactionView view, String address) {
        String normalizedWallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        return normalizedWallet != null && normalizedWallet.equals(normalizedAddress);
    }

    private String subcallSelector(String subcallInput) {
        if (subcallInput == null || !subcallInput.startsWith("0x") || subcallInput.length() < 10) {
            return null;
        }
        String selector = subcallInput.substring(0, 10).toLowerCase(Locale.ROOT);
        return selector.matches("0x[0-9a-f]{8}") ? selector : null;
    }

    private boolean onlyOutbound(List<RawLeg> movementLegs) {
        boolean hasOutbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() > 0) {
                return false;
            }
            hasOutbound = true;
        }
        return hasOutbound;
    }

    private boolean onlyInbound(List<RawLeg> movementLegs) {
        boolean hasInbound = false;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0) {
                return false;
            }
            hasInbound = true;
        }
        return hasInbound;
    }

    private boolean hasOutboundGmxShareAsset(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null && !leg.fee() && leg.quantityDelta() != null && leg.quantityDelta().signum() < 0)
                .anyMatch(leg -> isGmxShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasOutboundNonGmxShareAsset(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> leg != null
                        && !leg.fee()
                        && leg.quantityDelta() != null
                        && leg.quantityDelta().signum() < 0
                        && leg.assetContract() != null)
                .anyMatch(leg -> !isGmxShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean isGmxShareLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toLowerCase(Locale.ROOT);
        if (resource != null && resource.matchesAssetMarker("shareSymbolPrefixes", normalized)) {
            return true;
        }
        return normalized.startsWith("gm:") || normalized.startsWith("glv");
    }

    private String functionKey(String functionName) {
        if (functionName == null) {
            return "";
        }
        String normalized = functionName.trim().toLowerCase(Locale.ROOT);
        int signatureSeparator = normalized.indexOf('(');
        if (signatureSeparator > 0) {
            return normalized.substring(0, signatureSeparator);
        }
        return normalized;
    }

    private boolean isErc20TransferLog(Document log) {
        if (log == null) {
            return false;
        }
        List<String> topics = normalizedTopics(log);
        return topics.size() >= 3 && ERC20_TRANSFER_TOPIC.equals(topics.getFirst());
    }

    private List<String> normalizedTopics(Document log) {
        if (log == null) {
            return List.of();
        }
        Object topicsObject = log.get("topics");
        if (!(topicsObject instanceof List<?> topics) || topics.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(topics.size());
        for (Object topic : topics) {
            String value = topic == null ? null : topic.toString();
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String topicAt(Document log, int index) {
        List<String> topics = normalizedTopics(log);
        if (index < 0 || index >= topics.size()) {
            return null;
        }
        return topics.get(index);
    }

    private String topicAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.startsWith("0x") ? topic.substring(2) : topic;
        if (normalized.length() < 40) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(normalized.substring(normalized.length() - 40));
    }

    private String logData(Document log) {
        if (log == null) {
            return null;
        }
        Object data = log.get("data");
        return data == null ? null : data.toString();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean matchesMethodSelector(
            OnChainRawTransactionView view,
            String group,
            String... fallbackSelectors
    ) {
        if (view == null) {
            return false;
        }
        String selector = view.methodId();
        if (selector == null) {
            return false;
        }
        if (resource != null && resource.matchesMethodSelector(group, selector)) {
            return true;
        }
        for (String fallbackSelector : fallbackSelectors) {
            if (fallbackSelector != null && fallbackSelector.equalsIgnoreCase(selector)) {
                return true;
            }
        }
        return false;
    }

    private List<String> configuredSubcallSelectors(String group, String... fallbackSelectors) {
        if (resource != null) {
            List<String> configured = resource.markers().subcallSelectors(group);
            if (!configured.isEmpty()) {
                return configured;
            }
        }
        return List.of(fallbackSelectors);
    }

    private List<String> configuredEventTopics(String group, String... fallbackTopics) {
        if (resource != null) {
            List<String> configured = resource.eventTopics(group);
            if (!configured.isEmpty()) {
                return configured;
            }
        }
        return List.of(fallbackTopics);
    }

    private List<String> configuredEventNames(String group, String... fallbackEventNames) {
        if (resource != null) {
            List<String> configured = resource.markers().eventMarkers(group);
            if (!configured.isEmpty()) {
                return configured;
            }
        }
        return List.of(fallbackEventNames);
    }

    private boolean matchesAnyTopic(String candidate, List<String> expectedTopics) {
        if (candidate == null || expectedTopics == null || expectedTopics.isEmpty()) {
            return false;
        }
        for (String expectedTopic : expectedTopics) {
            if (expectedTopic != null && expectedTopic.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEmbeddedSelector(String inputData, List<String> selectors) {
        if (inputData == null || inputData.isBlank() || selectors == null || selectors.isEmpty()) {
            return false;
        }
        for (String selector : selectors) {
            if (selector != null && CalldataDecodingSupport.containsEmbeddedSelector(inputData, selector)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... fragments) {
        if (value == null || fragments == null || fragments.length == 0) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            if (fragment != null && normalized.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
