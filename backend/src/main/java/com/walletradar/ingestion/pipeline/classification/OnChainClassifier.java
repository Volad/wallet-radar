package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.Decimal128Support;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.special.ProtocolSpecialHandlerDispatcher;
import com.walletradar.ingestion.pipeline.classification.special.SpecialHandlerResult;
import com.walletradar.ingestion.pipeline.classification.support.AdminConfigSupport;
import com.walletradar.ingestion.pipeline.classification.support.BridgeSettlementSupport;
import com.walletradar.ingestion.pipeline.classification.support.CalldataDecodingSupport;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.classification.support.CowSwapSupport;
import com.walletradar.ingestion.pipeline.classification.support.GmxEventTopicSupport;
import com.walletradar.ingestion.pipeline.classification.support.InboundSignalSupport;
import com.walletradar.ingestion.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.PricingReadinessSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.ZeroAmountTokenSupport;
import com.walletradar.ingestion.pipeline.classification.support.WrappedNativeSupport;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V3 on-chain classifier and leg extractor. Uses only raw tx fields, token transfers, and internal transfers.
 */
@Component
@RequiredArgsConstructor
public class OnChainClassifier {

    private static final String PENDLE_REWARD_DISTRIBUTOR = "0x70f61901658aafb7ae57da0c30695ce4417e72b9";
    private static final String PENDLE_ZAP_OUT_SINGLE_TOKEN_SELECTOR = "0x8b284b0e";
    private static final String PANCAKE_SMARTCHEF_CONTRACT = "0x7816f1711828c52eb3ca5a2f075a0c06e0548bd6";
    private static final String PANCAKE_SMARTCHEF_DEPOSIT_SELECTOR = "0xb6b55f25";

    private static final Map<String, NormalizedTransactionType> METHOD_ID_TYPES = Map.ofEntries(
            Map.entry("0x7ff36ab5", NormalizedTransactionType.SWAP),
            Map.entry("0x18cbafe5", NormalizedTransactionType.SWAP),
            Map.entry("0x38ed1739", NormalizedTransactionType.SWAP),
            Map.entry("0x414bf389", NormalizedTransactionType.SWAP),
            Map.entry("0xc04b8d59", NormalizedTransactionType.SWAP),
            Map.entry("0xdb3e2198", NormalizedTransactionType.SWAP),
            Map.entry("0x617ba037", NormalizedTransactionType.LENDING_DEPOSIT),
            Map.entry("0xe8eda9df", NormalizedTransactionType.LENDING_DEPOSIT),
            Map.entry("0x69328dec", NormalizedTransactionType.LENDING_WITHDRAW),
            Map.entry("0xa415bcad", NormalizedTransactionType.BORROW),
            Map.entry("0x573ade81", NormalizedTransactionType.REPAY),
            Map.entry("0x852a12e3", NormalizedTransactionType.LENDING_DEPOSIT),
            Map.entry("0xdb006a75", NormalizedTransactionType.LENDING_WITHDRAW),
            Map.entry("0xa5d4d0cc", NormalizedTransactionType.BRIDGE_OUT),
            Map.entry("0x9fbf10fc", NormalizedTransactionType.BRIDGE_OUT),
            Map.entry("0xec51b4c9", NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT),
            Map.entry("0x6eba5d0c", NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW),
            Map.entry("0xb88a802f", NormalizedTransactionType.REWARD_CLAIM),
            Map.entry("0x095ea7b3", NormalizedTransactionType.APPROVE)
    );

    private static final Set<String> METHOD_AWARE_ROUTER_SELECTORS = Set.of(
            "0x7b939232", // Across depositV3
            "0xac9650d8", // multicall(bytes[])
            "0xc16ae7a4", // batch(tuple[])
            "0x3593564c", // execute(bytes,bytes[],uint256)
            "0xae0b91e5"  // bridge/router execute path
    );

    private static final Set<String> FULL_RECEIPT_NON_ECONOMIC_ALLOWLIST = Set.of(
            "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775",
            "0x91bba2c00fc37a862f2c277e6f8378bf682156425919c66c1b37faa50e9d61b7",
            "0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa",
            "0x9c3a93479dd926c7a6e57395b14ab48ed73e673f5cb25f6c1ae6ac9b1bbf2c19",
            "0xaf00ee8ac5154daa5f4f917d0929ddbacfb1d254ae3b228f3322312a39c798c8",
            "0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630",
            "0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e",
            "0x9867f9d202764ad9d019b0f89cb4b35e96cbc35bd5ac2fabea1edf5c7412bdf2"
    );

    private static final Set<String> LI_FI_DIAMOND_ROUTE_SELECTORS = Set.of(
            "0xd7a08473",
            "0xe9ae5c53",
            "0x0193b9fc",
            "0xae328590",
            "0xfc5f1003",
            "0xa1f1ce43"
    );

    private static final Set<String> LI_FI_ROUTE_TAGS = Set.of(
            "jumper.exchange",
            "lifi",
            "relay",
            "relaydepository",
            "symbiosis",
            "stargatev2",
            "stargatev2bus",
            "mayan",
            "mayanmctp",
            "mayanfastmctp",
            "cbridge",
            "glacis",
            "across",
            "polymerstandard",
            "gaszipbridge",
            "gaszip"
    );
    private static final Set<String> BRIDGE_START_SELECTORS = Set.of(
            "0x30c48952",
            "0xa6010a66",
            "0xa8f66666"
    );
    private static final Set<String> BRIDGE_START_FUNCTION_KEYS = Set.of(
            "swapandstartbridgetokensviamayan",
            "swapandstartbridgetokensviastargate",
            "swapandstartbridgetokensviasquid"
    );

    private static final String TRANSFER_REMOTE_SELECTOR = "0x81b4e8b4";
    private static final String PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR = "0x7f457675";
    private static final String ROUTE_SINGLE_SELECTOR = "0xb94c3609";
    private static final String CREATE_ORDER_ROUTER_SELECTOR = "0x0ad58d2f";
    private static final String CREATE_ORDER_TUPLE_SELECTOR = "0x322bba21";
    private static final String EXECUTE_ORDER_SELECTOR = "0x7ebc83f7";
    private static final String EXECUTE_DEPOSIT_SELECTOR = "0xc30d8910";
    private static final String EXECUTE_GLV_DEPOSIT_SELECTOR = "0x5ee8ec8f";
    private static final String EXECUTE_WITHDRAWAL_SELECTOR = "0xc96fea9f";
    private static final String INITIATE_WITHDRAWAL_SELECTOR = "0x12edde5e";
    private static final String MERKLE_CLAIM_SELECTOR = "0xae0b51df";
    private static final String CLAIM_WITH_SIG_SELECTOR = "0x7796e4ce";
    private static final String HARVEST_SELECTOR = "0x18fccc76";
    private static final String RELEASE_SELECTOR = "0x86d1a69f";
    private static final String GET_REWARD_SELECTOR = "0x7050ccd9";
    private static final String LB_HOOKS_CLAIM_SELECTOR = "0x45718278";
    private static final String PENDING_REDEEM_REQUEST_SELECTOR = "0x5cfe2fe4";
    private static final String CLAIM_LIKE_AIRDROP_SELECTOR = "0x729ad39e";
    private static final String FEE_BEARING_CLAIM_ADMIN_SELECTOR = "0xdc4b201d";
    private static final String EULER_BATCH_ROUTER = "0xddcbe30a761edd2e19bba930a977475265f36fa1";
    private static final String EULER_CALL_WITH_CONTEXT_TOPIC = "0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec";
    private static final String EULER_BORROW_EVENT_TOPIC = "0xcbc04eca7e9da35cb1393a6135a199ca52e450d5e9251cbd99f7847d33a36750";
    private static final String EULER_BORROW_SELECTOR = "4b3fd148";
    private static final String GMX_EVENT_EMITTER_TOPIC = GmxEventTopicSupport.EVENT_EMITTER_TOPIC;
    private static final String GMX_DEPOSIT_EXECUTED_EVENT = "0x2856020a9644603d22d7b029b5649a55d708b88d9049150f146ac26c4107b880";
    private static final String GMX_GLV_DEPOSIT_EXECUTED_EVENT = "0x168af62e3da2e23e63dfeb41b97ea0feef3c7a45e72ebc59e924f19ae915f14e";
    private static final String GMX_HELPER_SEND_WNT_SELECTOR = "7d39aaf1";
    private static final String GMX_HELPER_SEND_TOKENS_SELECTOR = "e6d66ac8";
    private static final String GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED = "GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED";
    private static final String GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED = "GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED";
    private static final String GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED = "GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED";
    private static final String GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED = "GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED";
    private static final String GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED = "GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED";
    private static final String GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED = "GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED";
    private static final String COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED = "COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED";
    private static final String EULER_BATCH_DECODER_REQUIRED = "EULER_BATCH_DECODER_REQUIRED";
    private static final String PENDING_UNBONDING_REQUEST = "PENDING_UNBONDING_REQUEST";
    private static final String RESOLV_STAKED_TOKEN_CONTRACT = "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23";
    private static final String RESOLV_TOKEN_CONTRACT = "0x259338656198ec7a76c729514d3cb45dfbf768a1";
    private static final String RESOLV_WITHDRAW_SELECTOR = "0xe1e13847";
    private static final String EULER_DEUSD_CONTRACT = "0xb57b25851fe2311cc3fe511c8f10e868932e0680";
    private static final String EULER_SHARE_PRICE_INFERENCE_REASON = "EULER_LOOP_TX_LOCAL_SHARE_PRICE";
    private static final String EULER_STABLE_PRICE_INFERENCE_REASON = "EULER_LOOP_STABLE_ANCHOR";
    private static final Set<String> GMX_DERIVATIVE_REQUEST_SUBCALL_SELECTORS = Set.of(
            "0x6996807b",
            CREATE_ORDER_ROUTER_SELECTOR,
            CREATE_ORDER_TUPLE_SELECTOR
    );
    private static final Set<String> GMX_WITHDRAWAL_REQUEST_SUBCALL_SELECTORS = Set.of(
            "0x647c6fa4",
            "0x4e78dc23"
    );
    private static final String GMX_EVENT_ORDER_CREATED = GmxEventTopicSupport.topicHash("OrderCreated");
    private static final String GMX_EVENT_ORDER_EXECUTED = GmxEventTopicSupport.topicHash("OrderExecuted");
    private static final String GMX_EVENT_ORDER_CANCELLED = GmxEventTopicSupport.topicHash("OrderCancelled");
    private static final String GMX_EVENT_POSITION_INCREASE = GmxEventTopicSupport.topicHash("PositionIncrease");
    private static final String GMX_EVENT_POSITION_DECREASE = GmxEventTopicSupport.topicHash("PositionDecrease");
    private static final String GMX_EVENT_WITHDRAWAL_CREATED = GmxEventTopicSupport.topicHash("WithdrawalCreated");
    private static final String GMX_EVENT_WITHDRAWAL_EXECUTED = GmxEventTopicSupport.topicHash("WithdrawalExecuted");
    private static final String GMX_EVENT_GLV_WITHDRAWAL_CREATED = GmxEventTopicSupport.topicHash("GlvWithdrawalCreated");
    private static final String GMX_EVENT_GLV_WITHDRAWAL_EXECUTED = GmxEventTopicSupport.topicHash("GlvWithdrawalExecuted");

    private static final Set<String> TERMINAL_STOP_CONDITION_HASHES = Set.of(
            "0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a",
            "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775",
            "0x504695248b7be49796e52895005019fa7ff268297e394078e336ec5a14cbcf54",
            "0x508ad8c6695151cd84df379876cef4bd5c5370e8bdd660e54141a35ebe1d9d54",
            "0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2"
    );

    private final ProtocolRegistryService protocolRegistryService;
    private final ProtocolSpecialHandlerDispatcher protocolSpecialHandlerDispatcher;
    private final TrackedWalletLookupService trackedWalletLookupService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public OnChainClassificationResult classify(RawTransaction rawTransaction) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<RawLeg> movementLegs = extractMovementLegs(view);

        if (view.isFailedExecution()) {
            return terminalUnknown(
                    view,
                    movementLegs,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    List.of("FAILED_TRANSACTION"),
                    null,
                    null
            );
        }

        Optional<OnChainClassificationResult> adminConfigMatch = classifyAdminConfig(view, movementLegs);
        if (adminConfigMatch.isPresent()) {
            return adminConfigMatch.get();
        }

        Optional<OnChainClassificationResult> wrappedNativeMatch = classifyWrappedNativeSelector(view, movementLegs);
        if (wrappedNativeMatch.isPresent()) {
            return wrappedNativeMatch.get();
        }

        Optional<ProtocolRegistryEntry> bridgeSettlementEntry = findKnownBridgeSettlementEntry(view);
        Optional<OnChainClassificationResult> bridgeSettlementMatch = classifyKnownBridgeSettlement(view, movementLegs, bridgeSettlementEntry);
        if (bridgeSettlementMatch.isPresent()) {
            return bridgeSettlementMatch.get();
        }

        Optional<OnChainClassificationResult> rewardRouteMatch = classifyKnownRewardRoute(view, movementLegs);
        if (rewardRouteMatch.isPresent()) {
            return rewardRouteMatch.get();
        }

        Optional<OnChainClassificationResult> bridgeInitiationMatch = classifyKnownBridgeInitiation(view, movementLegs);
        if (bridgeInitiationMatch.isPresent()) {
            return bridgeInitiationMatch.get();
        }

        Optional<OnChainClassificationResult> routedAggregatorSendMatch = classifyKnownAggregatorRoutedSend(view, movementLegs);
        if (routedAggregatorSendMatch.isPresent()) {
            return routedAggregatorSendMatch.get();
        }

        Optional<OnChainClassificationResult> claimIncomeMatch = classifyKnownClaimIncome(view, movementLegs);
        if (claimIncomeMatch.isPresent()) {
            return claimIncomeMatch.get();
        }

        Optional<OnChainClassificationResult> clarifiedEconomicMatch = classifyClarifiedEconomicReview(view, movementLegs);
        if (clarifiedEconomicMatch.isPresent()) {
            return clarifiedEconomicMatch.get();
        }

        Optional<OnChainClassificationResult> stopConditionMatch = classifyDocumentedStopCondition(view, movementLegs);
        if (stopConditionMatch.isPresent()) {
            return stopConditionMatch.get();
        }

        Optional<OnChainClassificationResult> clarifiedGmxLifecycleMatch = classifyClarifiedGmxLifecycle(view, movementLegs);
        if (clarifiedGmxLifecycleMatch.isPresent()) {
            return clarifiedGmxLifecycleMatch.get();
        }

        Optional<OnChainClassificationResult> cowLifecycleMatch = classifyCowSwapLifecycle(view, movementLegs);
        if (cowLifecycleMatch.isPresent()) {
            return cowLifecycleMatch.get();
        }

        Optional<OnChainClassificationResult> pendingOrderMatch = classifyKnownPendingOrderPath(view, movementLegs);
        if (pendingOrderMatch.isPresent()) {
            return pendingOrderMatch.get();
        }

        Optional<OnChainClassificationResult> warningFamilyMatch = classifyResolvedWarningFamilies(view, movementLegs);
        if (warningFamilyMatch.isPresent()) {
            return warningFamilyMatch.get();
        }

        Optional<OnChainClassificationResult> promoSpamMatch = classifyPromoSpamInbound(view, movementLegs);
        if (promoSpamMatch.isPresent()) {
            return promoSpamMatch.get();
        }

        Optional<OnChainClassificationResult> zeroAmountMatch = classifyZeroAmountTokenNoOp(view, movementLegs);
        if (zeroAmountMatch.isPresent()) {
            return zeroAmountMatch.get();
        }

        Optional<OnChainClassificationResult> clarifiedNonEconomicMatch = classifyClarifiedNonEconomicReview(view, movementLegs);
        if (clarifiedNonEconomicMatch.isPresent()) {
            return clarifiedNonEconomicMatch.get();
        }

        Optional<ProtocolRegistryEntry> protocolMatch = protocolRegistryService.lookup(view.networkId(), view.toAddress());
        if (protocolMatch.isPresent()) {
            Optional<OnChainClassificationResult> registryResult = classifyByProtocolRegistry(view, movementLegs, protocolMatch.get());
            if (registryResult.isPresent()) {
                return registryResult.get();
            }
        }

        String methodId = view.methodId();
        if (methodId != null && METHOD_ID_TYPES.containsKey(methodId)) {
            NormalizedTransactionType type = METHOD_ID_TYPES.get(methodId);
            return result(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, ConfidenceLevel.MEDIUM),
                ClassificationSource.METHOD_ID,
                type == NormalizedTransactionType.APPROVE ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                flows(view, movementLegs, type),
                List.of(),
                null,
                null
            );
        }

        Optional<OnChainClassificationResult> functionNameMatch = classifyByFunctionName(view, movementLegs);
        if (functionNameMatch.isPresent()) {
            return functionNameMatch.get();
        }

        return classifyHeuristically(view, movementLegs);
    }

    private Optional<OnChainClassificationResult> classifyByFunctionName(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        String functionName = view.functionName();
        if (functionName == null) {
            return Optional.empty();
        }
        String functionKey = functionKey(functionName);
        if (findKnownBridgeSettlementEntry(view).isPresent() && BridgeSettlementSupport.isSettlementSelector(view)) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> transferBackedBridgeEntry = findKnownBridgeEntryFromOutboundTransfer(view);
        if (transferBackedBridgeEntry.isPresent() && isAcrossDepositV3(transferBackedBridgeEntry.get(), view)) {
            ProtocolRegistryEntry entry = transferBackedBridgeEntry.get();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.BRIDGE_OUT,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_OUT, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_OUT),
                    List.of(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }
        if (WrappedNativeSupport.detectType(view, nativeAssetSymbolResolver).isPresent()
                && WrappedNativeSupport.hasWrappedNativeIdentity(view, nativeAssetSymbolResolver)) {
            return Optional.empty();
        }

        NormalizedTransactionType type = null;
        List<String> reasons = List.of();
        if (containsAny(functionKey, "claim")) {
            if (!hasKnownRewardContract(view) && !hasKnownRewardInbound(view)) {
                return Optional.empty();
            }
            type = hasOutbound(movementLegs) ? NormalizedTransactionType.SWAP : NormalizedTransactionType.REWARD_CLAIM;
        } else if (containsAny(functionKey, "swap", "exchange", "trade")) {
            type = NormalizedTransactionType.SWAP;
        } else if (containsAny(functionKey, "addliquidity", "increaseliquidity", "modifyliquidities", "modifyliquidity")) {
            type = NormalizedTransactionType.LP_ENTRY;
        } else if (containsAny(functionKey, "removeliquidity", "decreaseliquidity")) {
            type = NormalizedTransactionType.LP_EXIT;
        } else if (containsAny(functionKey, "collect")) {
            type = NormalizedTransactionType.LP_FEE_CLAIM;
        } else if (containsAny(functionKey, "deposit", "supply", "provide")) {
            type = hasReceiptLikeToken(movementLegs) ? NormalizedTransactionType.LENDING_DEPOSIT : NormalizedTransactionType.VAULT_DEPOSIT;
        } else if (containsAny(functionKey, "withdraw", "redeem", "exit")) {
            type = hasReceiptLikeToken(movementLegs) ? NormalizedTransactionType.LENDING_WITHDRAW : NormalizedTransactionType.VAULT_WITHDRAW;
        } else if (containsAny(functionKey, "borrow")) {
            type = NormalizedTransactionType.BORROW;
        } else if (containsAny(functionKey, "repay")) {
            type = NormalizedTransactionType.REPAY;
        } else if (containsAny(functionKey, "stake", "submit")) {
            type = NormalizedTransactionType.STAKING_DEPOSIT;
        } else if (containsAny(functionKey, "unstake")) {
            type = NormalizedTransactionType.STAKING_WITHDRAW;
        } else if (containsAny(functionKey, "bridge")) {
            type = NormalizedTransactionType.BRIDGE_OUT;
        } else if (functionKey.startsWith("approve")) {
            type = NormalizedTransactionType.APPROVE;
        }

        if (type == null) {
            return Optional.empty();
        }

        return Optional.of(result(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, ConfidenceLevel.LOW),
                ClassificationSource.FUNCTION_NAME,
                type == NormalizedTransactionType.APPROVE ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW,
                flows(view, movementLegs, type),
                reasons,
                null,
                null
        ));
    }

    private Optional<OnChainClassificationResult> classifyByProtocolRegistry(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryEntry entry
    ) {
        if (entry.specialHandler() != null) {
            SpecialHandlerResult specialHandlerResult = protocolSpecialHandlerDispatcher.dispatch(entry, view, movementLegs);
            return Optional.of(result(
                    view,
                    specialHandlerResult.type(),
                    specialHandlerResult.status(),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    specialHandlerResult.confidence(),
                    specialHandlerResult.flows(),
                    specialHandlerResult.missingDataReasons(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }

        Optional<OnChainClassificationResult> methodAwareResult = classifyMethodAwareRegistryEntry(view, movementLegs, entry);
        if (methodAwareResult.isPresent()) {
            return methodAwareResult;
        }

        Optional<OnChainClassificationResult> pendleRewardBundleResult = classifyPendleRewardDistributorBundle(view, movementLegs, entry);
        if (pendleRewardBundleResult.isPresent()) {
            return pendleRewardBundleResult;
        }

        if (entry.family() == ProtocolRegistryFamily.WRAPPER) {
            MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
            String wrappedNativeContract = nativeAssetSymbolResolver.wrappedNativeContract(view.networkId());
            if (summary.nativeOutbound() && summary.hasWrappedInbound(wrappedNativeContract)) {
                return Optional.of(registryResult(view, entry, NormalizedTransactionType.WRAP, movementLegs));
            }
            if (summary.nativeInbound() && summary.hasWrappedOutbound(wrappedNativeContract)) {
                return Optional.of(registryResult(view, entry, NormalizedTransactionType.UNWRAP, movementLegs));
            }
            return Optional.empty();
        }

        if (entry.role() == ProtocolRegistryRole.POSITION_MANAGER) {
            NormalizedTransactionType type = resolvePositionManagerType(view, movementLegs);
            if (type != null) {
                return Optional.of(registryResult(view, entry, type, movementLegs));
            }
            return Optional.empty();
        }

        if (entry.family() == ProtocolRegistryFamily.LENDING && entry.role() == ProtocolRegistryRole.POOL) {
            NormalizedTransactionType type = resolveLendingPoolType(view);
            if (type != null) {
                return Optional.of(registryResult(view, entry, type, movementLegs));
            }
        }

        if (LpPositionLifecycleSupport.isDexStakeContract(entry)) {
            NormalizedTransactionType type = LpPositionLifecycleSupport.resolveDexStakeContractType(view, movementLegs);
            if (type != null) {
                return Optional.of(registryResult(view, entry, type, movementLegs));
            }
        }

        if (entry.role() == ProtocolRegistryRole.VAULT) {
            NormalizedTransactionType type = resolveVaultType(view);
            if (type != null) {
                return Optional.of(registryResult(view, entry, type, movementLegs));
            }
        }

        if (entry.decomposeByLegs()) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.UNKNOWN,
                    NormalizedTransactionStatus.NEEDS_REVIEW,
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    List.of(),
                    List.of("REGISTRY_SPECIAL_HANDLER_REQUIRED"),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }

        NormalizedTransactionType type = entry.normalizedType();
        if (type != null) {
            return Optional.of(registryResult(view, entry, type, movementLegs));
        }

        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyMethodAwareRegistryEntry(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryEntry entry
    ) {
        if (!requiresMethodAwareDispatch(entry, view)) {
            return Optional.empty();
        }

        if (BridgeSettlementSupport.isBridgeSettlement(entry, view)) {
            return Optional.of(registryResult(view, entry, NormalizedTransactionType.BRIDGE_IN, movementLegs));
        }

        if (entry.role() == ProtocolRegistryRole.POSITION_MANAGER) {
            NormalizedTransactionType multicallType = LpPositionLifecycleSupport.resolvePositionManagerMulticallType(view, movementLegs);
            if (multicallType != null) {
                return Optional.of(registryResult(view, entry, multicallType, movementLegs));
            }
        }

        if (entry.role() == ProtocolRegistryRole.STAKE_CONTRACT && entry.family() == ProtocolRegistryFamily.DEX) {
            NormalizedTransactionType multicallType = LpPositionLifecycleSupport.resolveDexStakeContractMulticallType(view, movementLegs);
            if (multicallType != null) {
                return Optional.of(registryResult(view, entry, multicallType, movementLegs));
            }
        }

        if (isAcrossDepositV3(entry, view)) {
            return Optional.of(registryResult(view, entry, NormalizedTransactionType.BRIDGE_OUT, movementLegs));
        }

        if (isMethodAwareBridgeOut(entry, view)) {
            return Optional.of(registryResult(view, entry, NormalizedTransactionType.BRIDGE_OUT, movementLegs));
        }

        if (isRouterSwapLike(entry, view, movementLegs)) {
            return Optional.of(registryResult(view, entry, NormalizedTransactionType.SWAP, movementLegs));
        }

        return Optional.of(result(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                List.of(),
                List.of("ROUTER_METHOD_OVERLOAD_UNSUPPORTED"),
                entry.protocolName(),
                entry.protocolVersion()
        ));
    }

    private Optional<OnChainClassificationResult> classifyWrappedNativeSelector(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        Optional<NormalizedTransactionType> wrappedType = WrappedNativeSupport.detectType(view, nativeAssetSymbolResolver);
        if (wrappedType.isEmpty() || !WrappedNativeSupport.hasWrappedNativeIdentity(view, nativeAssetSymbolResolver)) {
            return Optional.empty();
        }

        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        String wrappedContract = nativeAssetSymbolResolver.wrappedNativeContract(view.networkId());

        if (wrappedType.get() == NormalizedTransactionType.WRAP
                && summary.nativeOutbound()
                && summary.hasWrappedInbound(wrappedContract)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.WRAP,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.HIGH,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.WRAP),
                    List.of(),
                    null,
                    null
            ));
        }

        if (wrappedType.get() == NormalizedTransactionType.UNWRAP
                && summary.nativeInbound()
                && summary.hasWrappedOutbound(wrappedContract)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.UNWRAP,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.HIGH,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNWRAP),
                    List.of(),
                    null,
                    null
            ));
        }

        return Optional.empty();
    }

    private OnChainClassificationResult classifyHeuristically(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        Optional<ProtocolRegistryEntry> bridgeSettlementEntry = findKnownBridgeSettlementEntry(view);
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        String counterpartyTo = view.toAddress();
        String counterpartyFrom = view.fromAddress();

        Optional<OnChainClassificationResult> clarifiedBatchMatch = classifyClarifiedBatchLendingPath(view, movementLegs);
        if (clarifiedBatchMatch.isPresent()) {
            return clarifiedBatchMatch.get();
        }

        if (summary.nativeOutbound() && summary.hasWrappedInbound(nativeAssetSymbolResolver.wrappedNativeContract(view.networkId()))) {
            return result(
                    view,
                NormalizedTransactionType.WRAP,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.WRAP),
                List.of(),
                null,
                null
            );
        }

        if (summary.nativeInbound() && summary.hasWrappedOutbound(nativeAssetSymbolResolver.wrappedNativeContract(view.networkId()))) {
            return result(
                    view,
                NormalizedTransactionType.UNWRAP,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNWRAP),
                List.of(),
                null,
                null
            );
        }

        if (summary.singleTokenOut() && summary.singleTokenIn() && !summary.sameAssetInAndOut()) {
            return knownLowConfidence(view, NormalizedTransactionType.SWAP, movementLegs);
        }
        if (summary.nativeOutbound() && summary.tokenInboundCount() == 1 && summary.tokenOutboundCount() == 0) {
            return knownLowConfidence(view, NormalizedTransactionType.SWAP, movementLegs);
        }
        if (summary.nativeInbound() && summary.tokenOutboundCount() == 1 && summary.tokenInboundCount() == 0) {
            return knownLowConfidence(view, NormalizedTransactionType.SWAP, movementLegs);
        }
        if (summary.tokenOutboundCount() >= 2 && summary.tokenInboundCount() == 1) {
            return knownLowConfidence(view, NormalizedTransactionType.LP_ENTRY, movementLegs);
        }
        if (summary.tokenOutboundCount() == 1 && summary.tokenInboundCount() >= 2) {
            return knownLowConfidence(view, NormalizedTransactionType.LP_EXIT, movementLegs);
        }

        if (isTrackedCounterparty(counterpartyTo, view.walletAddress()) || isTrackedCounterparty(counterpartyFrom, view.walletAddress())) {
            String matchedCounterparty = isTrackedCounterparty(counterpartyTo, view.walletAddress())
                    ? counterpartyTo
                    : counterpartyFrom;
            NormalizedTransactionType transferType = summary.onlyInbound()
                    ? NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                    : NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
            return result(
                    view,
                    transferType,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.HIGH,
                    OnChainClassificationSupport.toFlows(movementLegs, transferType),
                    List.of(),
                    true,
                    matchedCounterparty,
                    null,
                    null
            );
        }

        if (movementLegs.isEmpty()) {
            return result(
                    view,
                    NormalizedTransactionType.UNKNOWN,
                    NormalizedTransactionStatus.NEEDS_REVIEW,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    List.of(),
                    List.of("CLASSIFICATION_FAILED"),
                    null,
                    null
            );
        }

        if (summary.onlyInbound()) {
            if (bridgeSettlementEntry.isPresent()) {
                ProtocolRegistryEntry entry = bridgeSettlementEntry.get();
                return result(
                        view,
                        NormalizedTransactionType.BRIDGE_IN,
                        OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_IN, entry.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        entry.confidence(),
                        OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_IN),
                        List.of(),
                        entry.protocolName(),
                        entry.protocolVersion()
                );
            }
            if (hasKnownRewardContract(view) || hasKnownRewardInbound(view)) {
                return knownLowConfidence(view, NormalizedTransactionType.REWARD_CLAIM, movementLegs);
            }
            if (InboundSignalSupport.hasExplicitClaimSelector(view)) {
                return knownLowConfidence(view, NormalizedTransactionType.REWARD_CLAIM, movementLegs);
            }
            List<String> reasons = InboundSignalSupport.hasRewardLikeSignal(view)
                    ? List.of("AMBIGUOUS_INBOUND_VS_REWARD")
                    : List.of();
            return result(
                    view,
                    NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.EXTERNAL_TRANSFER_IN, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                    reasons,
                    null,
                    null
            );
        }

        if (summary.onlyOutbound()) {
            return knownLowConfidence(view, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, movementLegs);
        }

        if (hasReceiptLikeToken(movementLegs)) {
            return knownLowConfidence(view, NormalizedTransactionType.LENDING_DEPOSIT, movementLegs);
        }

        return result(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNKNOWN),
                List.of("CLASSIFICATION_FAILED"),
                null,
                null
        );
    }

    private OnChainClassificationResult knownLowConfidence(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            List<RawLeg> movementLegs
    ) {
        return result(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, ConfidenceLevel.LOW),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                flows(view, movementLegs, type),
                List.of(),
                null,
                null
        );
    }

    private List<NormalizedTransaction.Flow> flows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        List<RawLeg> effectiveLegs = movementLegs;
        if (type == NormalizedTransactionType.REWARD_CLAIM) {
            effectiveLegs = removeExactSelfCancelingPairs(movementLegs);
        }
        Optional<List<NormalizedTransaction.Flow>> classicSmartChefFlows =
                classifyClassicSmartChefStakingFlows(view, effectiveLegs, type);
        if (classicSmartChefFlows.isPresent()) {
            return classicSmartChefFlows.get();
        }
        return OnChainClassificationSupport.toFlows(effectiveLegs, type);
    }

    private Optional<List<NormalizedTransaction.Flow>> classifyClassicSmartChefStakingFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type
    ) {
        if (!isClassicSmartChefStaking(view, type)) {
            return Optional.empty();
        }
        Set<String> principalQuantityKeys = principalQuantityKeysFromCalldataAmount(view);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (RawLeg leg : movementLegs) {
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            flow.setRole(resolveClassicSmartChefRole(leg, principalQuantityKeys));
            flows.add(flow);
        }
        return Optional.of(flows);
    }

    private boolean isClassicSmartChefStaking(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null || view.toAddress() == null) {
            return false;
        }
        if (!PANCAKE_SMARTCHEF_CONTRACT.equalsIgnoreCase(view.toAddress())) {
            return false;
        }
        String functionKey = functionKey(view.functionName());
        return switch (type) {
            case STAKING_DEPOSIT -> PANCAKE_SMARTCHEF_DEPOSIT_SELECTOR.equals(view.methodId())
                    || "deposit".equals(functionKey);
            case STAKING_WITHDRAW -> "withdraw".equals(functionKey)
                    || "emergencywithdraw".equals(functionKey);
            default -> false;
        };
    }

    private Set<String> principalQuantityKeysFromCalldataAmount(OnChainRawTransactionView view) {
        BigInteger rawAmount = CalldataDecodingSupport.decodeUint256Argument(view.inputData(), 0);
        if (rawAmount == null || rawAmount.signum() <= 0) {
            return Set.of();
        }
        Set<String> quantityKeys = new LinkedHashSet<>();
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!matchesWalletAccount(view, view.tokenTransferFrom(transfer))
                    && !matchesWalletAccount(view, view.tokenTransferTo(transfer))) {
                continue;
            }
            Object rawValue = transfer.get("value");
            if (rawValue == null || !rawAmount.equals(parseBigInteger(rawValue))) {
                continue;
            }
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            quantityKeys.add(quantity.abs().stripTrailingZeros().toPlainString());
        }
        return quantityKeys;
    }

    private NormalizedLegRole resolveClassicSmartChefRole(
            RawLeg leg,
            Set<String> principalQuantityKeys
    ) {
        if (leg.fee()) {
            return NormalizedLegRole.FEE;
        }
        if (leg.quantityDelta().signum() < 0) {
            return NormalizedLegRole.TRANSFER;
        }
        String quantityKey = leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
        return principalQuantityKeys.contains(quantityKey)
                ? NormalizedLegRole.TRANSFER
                : NormalizedLegRole.BUY;
    }

    private BigInteger parseBigInteger(Object value) {
        try {
            return new BigInteger(value.toString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Optional<OnChainClassificationResult> classifyPendleRewardDistributorBundle(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ProtocolRegistryEntry entry
    ) {
        if (!"Pendle".equalsIgnoreCase(entry.protocolName())
                || !PENDLE_REWARD_DISTRIBUTOR.equalsIgnoreCase(entry.contractAddress())
                || !PENDLE_ZAP_OUT_SINGLE_TOKEN_SELECTOR.equals(view.methodId())
                || !"zapoutv3singletoken".equals(functionKey(view.functionName()))) {
            return Optional.empty();
        }

        List<RawLeg> effectiveLegs = removeExactSelfCancelingPairs(movementLegs);
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (RawLeg leg : effectiveLegs) {
            NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
            flow.setAssetContract(leg.assetContract());
            flow.setAssetSymbol(leg.assetSymbol());
            flow.setQuantityDelta(leg.quantityDelta());
            if (leg.fee()) {
                flow.setRole(NormalizedLegRole.FEE);
            } else if (leg.quantityDelta().signum() > 0 && "PENDLE".equalsIgnoreCase(leg.assetSymbol())) {
                flow.setRole(NormalizedLegRole.BUY);
            } else {
                flow.setRole(NormalizedLegRole.TRANSFER);
            }
            flows.add(flow);
        }

        return Optional.of(result(
                view,
                NormalizedTransactionType.LP_EXIT,
                OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_EXIT, entry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                flows,
                List.of(),
                entry.protocolName(),
                entry.protocolVersion()
        ));
    }

    private List<RawLeg> removeExactSelfCancelingPairs(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return List.of();
        }
        Map<String, List<Integer>> positiveIndices = new LinkedHashMap<>();
        Map<String, List<Integer>> negativeIndices = new LinkedHashMap<>();
        for (int index = 0; index < movementLegs.size(); index++) {
            RawLeg leg = movementLegs.get(index);
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            String key = legIdentity(leg) + ":" + leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
            if (leg.quantityDelta().signum() > 0) {
                positiveIndices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
            } else {
                negativeIndices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
            }
        }

        Set<Integer> removed = new LinkedHashSet<>();
        for (Map.Entry<String, List<Integer>> entry : positiveIndices.entrySet()) {
            List<Integer> negatives = negativeIndices.get(entry.getKey());
            if (negatives == null || negatives.isEmpty()) {
                continue;
            }
            int pairCount = Math.min(entry.getValue().size(), negatives.size());
            for (int i = 0; i < pairCount; i++) {
                removed.add(entry.getValue().get(i));
                removed.add(negatives.get(i));
            }
        }

        List<RawLeg> filtered = new ArrayList<>();
        for (int index = 0; index < movementLegs.size(); index++) {
            if (!removed.contains(index)) {
                filtered.add(movementLegs.get(index));
            }
        }
        return filtered;
    }

    private String legIdentity(RawLeg leg) {
        if (leg.assetContract() != null && !leg.assetContract().isBlank()) {
            return leg.assetContract().toLowerCase(Locale.ROOT);
        }
        return "symbol:" + (leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT));
    }

    private List<RawLeg> extractMovementLegs(OnChainRawTransactionView view) {
        List<RawLeg> legs = new ArrayList<>();
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return legs;
        }

        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            String contract = view.tokenTransferContract(transfer);
            String symbol = view.tokenTransferSymbol(transfer);
            if (matchesWalletAccount(view, view.tokenTransferTo(transfer))) {
                legs.add(RawLeg.asset(contract, symbol, quantity));
            }
            if (matchesWalletAccount(view, view.tokenTransferFrom(transfer))) {
                legs.add(RawLeg.asset(contract, symbol, quantity.negate()));
            }
        }

        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null || quantity.signum() == 0) {
                continue;
            }
            String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
            if (matchesWalletAccount(view, view.internalTransferTo(transfer))) {
                legs.add(RawLeg.nativeAsset(nativeSymbol, quantity));
            }
            if (matchesWalletAccount(view, view.internalTransferFrom(transfer))) {
                legs.add(RawLeg.nativeAsset(nativeSymbol, quantity.negate()));
            }
        }

        if (!isDirectValueCoveredByInternalTransfer(view)) {
            BigInteger rawValue = view.rawValue();
            if (rawValue != null && rawValue.signum() > 0) {
                BigDecimal quantity = new BigDecimal(rawValue).movePointLeft(18);
                String nativeSymbol = nativeAssetSymbolResolver.nativeSymbol(view.networkId());
                if (walletAddress.equals(view.toAddress())) {
                    legs.add(RawLeg.nativeAsset(nativeSymbol, quantity));
                }
                if (walletAddress.equals(view.fromAddress())) {
                    legs.add(RawLeg.nativeAsset(nativeSymbol, quantity.negate()));
                }
            }
        }

        if (walletAddress.equals(view.fromAddress())) {
            BigInteger gasUsed = view.gasUsed();
            BigInteger gasPrice = view.gasPrice();
            if (gasUsed != null && gasPrice != null && gasUsed.signum() > 0 && gasPrice.signum() > 0) {
                BigDecimal gasQuantity = new BigDecimal(gasUsed.multiply(gasPrice)).movePointLeft(18).negate();
                legs.add(RawLeg.fee(nativeAssetSymbolResolver.nativeSymbol(view.networkId()), gasQuantity));
            }
        }

        return WrappedNativeSupport.enrichLegs(view, nativeAssetSymbolResolver, legs);
    }

    private boolean isDirectValueCoveredByInternalTransfer(OnChainRawTransactionView view) {
        BigInteger rawValue = view.rawValue();
        if (rawValue == null || rawValue.signum() == 0) {
            return false;
        }
        String from = view.fromAddress();
        String to = view.toAddress();
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            BigDecimal quantity = view.internalTransferQuantity(transfer);
            if (quantity == null) {
                continue;
            }
            BigInteger transferValue = quantity.movePointRight(18).toBigInteger();
            if (rawValue.equals(transferValue)
                    && safeEquals(from, view.internalTransferFrom(transfer))
                    && safeEquals(to, view.internalTransferTo(transfer))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasReceiptLikeToken(List<RawLeg> movementLegs) {
        for (RawLeg leg : movementLegs) {
            if (leg.assetSymbol() == null || leg.fee()) {
                continue;
            }
            String symbol = leg.assetSymbol().trim();
            if (symbol.length() < 2) {
                continue;
            }
            String lower = symbol.toLowerCase(Locale.ROOT);
            if (lower.startsWith("a") || lower.startsWith("c") || lower.startsWith("s")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOutbound(List<RawLeg> movementLegs) {
        return movementLegs.stream().filter(leg -> !leg.fee()).anyMatch(leg -> leg.quantityDelta().signum() < 0);
    }

    private boolean hasAnyNegativeMovement(List<RawLeg> movementLegs) {
        return movementLegs.stream().anyMatch(leg -> leg.quantityDelta().signum() < 0);
    }

    private boolean requiresMethodAwareDispatch(ProtocolRegistryEntry entry, OnChainRawTransactionView view) {
        if (entry == null || view == null) {
            return false;
        }
        if (BridgeSettlementSupport.requiresMethodAwareDispatch(entry, view)) {
            return true;
        }
        String functionName = view.functionName();
        if (METHOD_AWARE_ROUTER_SELECTORS.contains(view.methodId())) {
            return true;
        }
        if (entry.family() == ProtocolRegistryFamily.BRIDGE && "0x30c48952".equals(view.methodId())) {
            return true;
        }
        if (entry.family() == ProtocolRegistryFamily.BRIDGE && containsAny(functionName, "depositv3")) {
            return true;
        }
        boolean routeLikeRole = entry.role() == ProtocolRegistryRole.ROUTER
                || entry.role() == ProtocolRegistryRole.EXCHANGE_ROUTER
                || entry.role() == ProtocolRegistryRole.POSITION_ROUTER
                || entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY;
        return routeLikeRole && containsAny(functionName, "multicall", "batch", "execute");
    }

    private boolean isAcrossDepositV3(ProtocolRegistryEntry entry, OnChainRawTransactionView view) {
        if (entry.family() != ProtocolRegistryFamily.BRIDGE || entry.role() != ProtocolRegistryRole.BRIDGE_ENTRY) {
            return false;
        }
        String protocolName = entry.protocolName();
        if (protocolName == null || !protocolName.toLowerCase(Locale.ROOT).contains("across")) {
            return false;
        }
        return "0x7b939232".equals(view.methodId()) || containsAny(view.functionName(), "depositv3");
    }

    private boolean isMethodAwareBridgeOut(ProtocolRegistryEntry entry, OnChainRawTransactionView view) {
        if (entry.family() != ProtocolRegistryFamily.BRIDGE || entry.role() != ProtocolRegistryRole.BRIDGE_ENTRY) {
            return false;
        }
        return "0xae0b91e5".equals(view.methodId())
                || "0x30c48952".equals(view.methodId());
    }

    private boolean isRouterSwapLike(
            ProtocolRegistryEntry entry,
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (entry.family() != ProtocolRegistryFamily.DEX) {
            return false;
        }
        boolean routeLikeRole = entry.role() == ProtocolRegistryRole.ROUTER
                || entry.role() == ProtocolRegistryRole.EXCHANGE_ROUTER
                || entry.role() == ProtocolRegistryRole.POSITION_ROUTER;
        if (!routeLikeRole) {
            return false;
        }
        if (!METHOD_AWARE_ROUTER_SELECTORS.contains(view.methodId())
                && !containsAny(view.functionName(), "multicall", "batch", "execute")) {
            return false;
        }

        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (hasDistinctNetInboundAndOutboundAssets(movementLegs)) {
            return true;
        }
        if (summary.singleTokenOut() && summary.singleTokenIn() && !summary.sameAssetInAndOut()) {
            return true;
        }
        if (summary.nativeOutbound() && summary.tokenInboundCount() == 1 && summary.tokenOutboundCount() == 0) {
            return true;
        }
        if (summary.nativeInbound() && summary.tokenOutboundCount() == 1 && summary.tokenInboundCount() == 0) {
            return true;
        }
        return summary.tokenOutboundCount() >= 1
                && summary.tokenInboundCount() >= 1
                && !summary.sameAssetInAndOut();
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

    private String assetKey(RawLeg leg) {
        if (leg.assetContract() != null) {
            return leg.assetContract();
        }
        String symbol = leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT);
        return "native:" + symbol;
    }

    private boolean isTrackedCounterparty(String address, String currentWallet) {
        String normalizedCurrent = OnChainRawTransactionView.normalizeAddress(currentWallet);
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        if (normalizedAddress == null || safeEquals(normalizedCurrent, normalizedAddress)) {
            return false;
        }
        return trackedWalletLookupService.contains(normalizedAddress);
    }

    private boolean hasKnownRewardInbound(OnChainRawTransactionView view) {
        return findKnownRewardEntry(view).isPresent();
    }

    private boolean hasKnownRewardContract(OnChainRawTransactionView view) {
        return protocolRegistryService.lookup(view.networkId(), view.toAddress())
                .filter(this::isRewardEntry)
                .isPresent();
    }

    private Optional<ProtocolRegistryEntry> findKnownBridgeSettlementEntry(OnChainRawTransactionView view) {
        if (!BridgeSettlementSupport.isSettlementSelector(view)) {
            return Optional.empty();
        }

        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.toAddress()));
        putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.fromAddress()));
        for (Document transfer : view.explorerTokenTransfers()) {
            putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.tokenTransferFrom(transfer)));
        }
        return candidates.values().stream().findFirst();
    }

    private Optional<ProtocolRegistryEntry> findKnownBridgeEntryFromOutboundTransfer(OnChainRawTransactionView view) {
        if (!"0x7b939232".equals(view.methodId()) && !containsAny(view.functionName(), "depositv3")) {
            return Optional.empty();
        }
        String walletAddress = view.walletAddress();
        if (walletAddress == null) {
            return Optional.empty();
        }
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.toAddress()));
        for (Document transfer : view.explorerTokenTransfers()) {
            if (!walletAddress.equals(view.tokenTransferFrom(transfer))) {
                continue;
            }
            putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.tokenTransferTo(transfer)));
        }
        return candidates.values().stream().findFirst();
    }

    private boolean isRewardEntry(ProtocolRegistryEntry entry) {
        return entry.normalizedType() == NormalizedTransactionType.REWARD_CLAIM
                || entry.family() == ProtocolRegistryFamily.YIELD
                || entry.role() == ProtocolRegistryRole.REWARD_ROUTER;
    }

    private void putBridgeCandidate(
            Map<String, ProtocolRegistryEntry> candidates,
            Optional<ProtocolRegistryEntry> entry
    ) {
        if (entry.isEmpty()) {
            return;
        }
        ProtocolRegistryEntry value = entry.get();
        if (value.family() != ProtocolRegistryFamily.BRIDGE) {
            return;
        }
        if (value.role() != ProtocolRegistryRole.BRIDGE_ENTRY && value.role() != ProtocolRegistryRole.ROUTER) {
            return;
        }
        candidates.putIfAbsent(value.contractAddress(), value);
    }

    private Optional<OnChainClassificationResult> classifyKnownBridgeSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            Optional<ProtocolRegistryEntry> bridgeSettlementEntry
    ) {
        if (!BridgeSettlementSupport.isSettlementSelector(view)) {
            return Optional.empty();
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (!summary.onlyInbound()) {
            return Optional.empty();
        }
        if (bridgeSettlementEntry.isPresent()) {
            ProtocolRegistryEntry entry = bridgeSettlementEntry.get();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.BRIDGE_IN,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_IN, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_IN),
                    List.of(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }
        return Optional.of(result(
                view,
                NormalizedTransactionType.BRIDGE_IN,
                OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_IN, ConfidenceLevel.MEDIUM),
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_IN),
                List.of(),
                null,
                null
        ));
    }

    private Optional<OnChainClassificationResult> classifyKnownRewardRoute(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!InboundSignalSupport.hasExplicitClaimSignal(view)) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> rewardEntry = findKnownRewardEntry(view);
        if (rewardEntry.isEmpty()) {
            return Optional.empty();
        }

        boolean hasInboundMovement = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        if (hasInboundMovement) {
            ProtocolRegistryEntry entry = rewardEntry.get();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.REWARD_CLAIM, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    flows(view, movementLegs, NormalizedTransactionType.REWARD_CLAIM),
                    List.of(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }

        ProtocolRegistryEntry entry = rewardEntry.get();
        return Optional.of(terminalUnknown(
                view,
                movementLegs,
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                List.of("CLAIM_WITHOUT_MOVEMENT"),
                entry.protocolName(),
                entry.protocolVersion()
        ));
    }

    private Optional<OnChainClassificationResult> classifyKnownBridgeInitiation(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        String functionKey = functionKey(view.functionName());
        if ((BRIDGE_START_SELECTORS.contains(view.methodId()) || BRIDGE_START_FUNCTION_KEYS.contains(functionKey))
                && summary.onlyOutbound()) {
            List<String> bridgePairReasons = bridgePairEvidenceReasons(view);
            Optional<ProtocolRegistryEntry> bridgeEntry = protocolRegistryService.lookup(view.networkId(), view.toAddress())
                    .filter(entry -> entry.family() == ProtocolRegistryFamily.BRIDGE
                            && (entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                            || entry.role() == ProtocolRegistryRole.ROUTER));
            if (bridgeEntry.isPresent()) {
                ProtocolRegistryEntry entry = bridgeEntry.get();
                return Optional.of(result(
                        view,
                        NormalizedTransactionType.BRIDGE_OUT,
                        OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_OUT, entry.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        entry.confidence(),
                        OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_OUT),
                        bridgePairReasons,
                        entry.protocolName(),
                        entry.protocolVersion()
                ));
            }
            ClassificationSource source = BRIDGE_START_SELECTORS.contains(view.methodId())
                    ? ClassificationSource.METHOD_ID
                    : ClassificationSource.FUNCTION_NAME;
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.BRIDGE_OUT,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_OUT, ConfidenceLevel.MEDIUM),
                    source,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_OUT),
                    bridgePairReasons,
                    null,
                    null
            ));
        }
        if (LI_FI_DIAMOND_ROUTE_SELECTORS.contains(view.methodId())
                && hasOutbound(movementLegs)
                && CalldataDecodingSupport.containsAnyAsciiFragment(view.inputData(), LI_FI_ROUTE_TAGS.toArray(String[]::new))) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.BRIDGE_OUT,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_OUT, ConfidenceLevel.MEDIUM),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_OUT),
                    List.of(),
                    null,
                    null
            ));
        }
        if ((TRANSFER_REMOTE_SELECTOR.equals(view.methodId()) || "transferremote".equals(functionKey(view.functionName())))
                && hasOutbound(movementLegs)
                && summary.nativeOutbound()
                && summary.tokenOutboundCount() >= 1) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.BRIDGE_OUT,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.BRIDGE_OUT, ConfidenceLevel.MEDIUM),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.BRIDGE_OUT),
                    List.of(),
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyKnownAggregatorRoutedSend(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (!summary.onlyOutbound()) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(view.networkId(), view.toAddress());
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        ProtocolRegistryEntry aggregatorEntry = entry.get();
        if (aggregatorEntry.family() != ProtocolRegistryFamily.AGGREGATOR
                || aggregatorEntry.role() != ProtocolRegistryRole.ROUTER) {
            return Optional.empty();
        }
        return Optional.of(result(
                view,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, aggregatorEntry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                aggregatorEntry.confidence(),
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                List.of("ROUTED_AGGREGATOR_OUTBOUND_ONLY"),
                aggregatorEntry.protocolName(),
                aggregatorEntry.protocolVersion()
        ));
    }

    private Optional<OnChainClassificationResult> classifyKnownClaimIncome(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (!summary.onlyInbound()) {
            return Optional.empty();
        }

        String functionKey = functionKey(view.functionName());
        Optional<ProtocolRegistryEntry> protocolEntry = protocolRegistryService.lookup(view.networkId(), view.toAddress());
        if ((HARVEST_SELECTOR.equals(view.methodId()) || "harvest".equals(functionKey))
                && protocolEntry.filter(LpPositionLifecycleSupport::isDexStakeContract).isPresent()) {
            ProtocolRegistryEntry entry = protocolEntry.get();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.REWARD_CLAIM, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    flows(view, movementLegs, NormalizedTransactionType.REWARD_CLAIM),
                    List.of(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }

        if ((RELEASE_SELECTOR.equals(view.methodId()) || "release".equals(functionKey))
                || (GET_REWARD_SELECTOR.equals(view.methodId()) || "getreward".equals(functionKey))) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.REWARD_CLAIM, ConfidenceLevel.MEDIUM),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.MEDIUM,
                    flows(view, movementLegs, NormalizedTransactionType.REWARD_CLAIM),
                    List.of(),
                    null,
                    null
            ));
        }

        if ((MERKLE_CLAIM_SELECTOR.equals(view.methodId())
                || CLAIM_WITH_SIG_SELECTOR.equals(view.methodId()))
                && summary.onlyInbound()) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.REWARD_CLAIM, ConfidenceLevel.MEDIUM),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    flows(view, movementLegs, NormalizedTransactionType.REWARD_CLAIM),
                    List.of(),
                    null,
                    null
            ));
        }

        if ((LB_HOOKS_CLAIM_SELECTOR.equals(view.methodId()) || "claim".equals(functionKey))
                && containsAny(view.functionName(), "lbhooks")) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LP_FEE_CLAIM,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_FEE_CLAIM, ConfidenceLevel.LOW),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LP_FEE_CLAIM),
                    List.of(),
                    null,
                    null
            ));
        }

        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyKnownPendingOrderPath(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        String functionKey = functionKey(view.functionName());
        if (isGmxDerivativeOrderRequest(view, movementLegs, functionKey)) {
            String correlationId = resolveGmxOrderRequestCorrelationId(view);
            NormalizedTransactionStatus status = correlationId == null
                    ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                    : OnChainClassificationSupport.initialStatus(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                    ConfidenceLevel.MEDIUM
            );
            List<String> reasons = correlationId == null
                    ? List.of(GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED)
                    : List.of();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                    status,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST),
                    reasons,
                    correlationId,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (isGmxDepositRequestMulticall(view, movementLegs)) {
            String correlationId = resolveGmxRequestCorrelationId(view);
            if (correlationId != null) {
                return Optional.of(result(
                        view,
                        NormalizedTransactionType.LP_ENTRY_REQUEST,
                        OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_ENTRY_REQUEST, ConfidenceLevel.MEDIUM),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.MEDIUM,
                        OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LP_ENTRY_REQUEST),
                        List.of(),
                        correlationId,
                        false,
                        null,
                        "GMX",
                        "V2"
                ));
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LP_ENTRY_REQUEST,
                    NormalizedTransactionStatus.PENDING_CLARIFICATION,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LP_ENTRY_REQUEST),
                    List.of(GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED),
                    null,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (isGmxWithdrawalRequestMulticall(view, movementLegs)) {
            String correlationId = resolveGmxWithdrawalRequestCorrelationId(view);
            if (correlationId != null) {
                return Optional.of(result(
                        view,
                        NormalizedTransactionType.LP_EXIT_REQUEST,
                        OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_EXIT_REQUEST, ConfidenceLevel.MEDIUM),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.MEDIUM,
                        OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LP_EXIT_REQUEST),
                        List.of(),
                        correlationId,
                        false,
                        null,
                        "GMX",
                        "V2"
                ));
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LP_EXIT_REQUEST,
                    NormalizedTransactionStatus.PENDING_CLARIFICATION,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LP_EXIT_REQUEST),
                    List.of(GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED),
                    null,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (isGmxDepositSettlement(view, movementLegs)) {
            String correlationId = resolveGmxSettlementCorrelationId(view);
            if (correlationId != null) {
                return Optional.of(result(
                        view,
                        NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                        OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_ENTRY_SETTLEMENT, ConfidenceLevel.MEDIUM),
                        ClassificationSource.FUNCTION_NAME,
                        ConfidenceLevel.MEDIUM,
                        OnChainClassificationSupport.toFlows(orderAsyncLpSettlementLegs(movementLegs), NormalizedTransactionType.LP_ENTRY_SETTLEMENT),
                        List.of(),
                        correlationId,
                        false,
                        null,
                        "GMX",
                        "V2"
                ));
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                    NormalizedTransactionStatus.PENDING_CLARIFICATION,
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(orderAsyncLpSettlementLegs(movementLegs), NormalizedTransactionType.LP_ENTRY_SETTLEMENT),
                    List.of(GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED),
                    null,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (isGmxWithdrawalSettlement(view, movementLegs)) {
            String correlationId = resolveGmxWithdrawalSettlementCorrelationId(view);
            if (correlationId != null) {
                return Optional.of(result(
                        view,
                        NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                        OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_EXIT_SETTLEMENT, ConfidenceLevel.MEDIUM),
                        ClassificationSource.FUNCTION_NAME,
                        ConfidenceLevel.MEDIUM,
                        OnChainClassificationSupport.toFlows(orderAsyncLpSettlementLegs(movementLegs), NormalizedTransactionType.LP_EXIT_SETTLEMENT),
                        List.of(),
                        correlationId,
                        false,
                        null,
                        "GMX",
                        "V2"
                ));
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                    NormalizedTransactionStatus.PENDING_CLARIFICATION,
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(orderAsyncLpSettlementLegs(movementLegs), NormalizedTransactionType.LP_EXIT_SETTLEMENT),
                    List.of(GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED),
                    null,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (EXECUTE_ORDER_SELECTOR.equals(view.methodId()) || "executeorder".equals(functionKey)) {
            Optional<OnChainClassificationResult> clarifiedSettlement = classifyClarifiedExecuteOrder(view, movementLegs);
            if (clarifiedSettlement.isPresent()) {
                return clarifiedSettlement;
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                    NormalizedTransactionStatus.PENDING_CLARIFICATION,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION),
                    List.of(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED),
                    null,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (isBurnOnlyUnbondingRequest(view, movementLegs, functionKey)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.STAKING_WITHDRAW_REQUEST,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.STAKING_WITHDRAW_REQUEST, ConfidenceLevel.MEDIUM),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.STAKING_WITHDRAW_REQUEST),
                    List.of(),
                    resolveResolvCorrelationId(view, movementLegs),
                    false,
                    null,
                    "Resolv",
                    null
            ));
        }
        if (isResolvWithdrawSettlement(view, movementLegs, functionKey)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.STAKING_WITHDRAW,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.STAKING_WITHDRAW, ConfidenceLevel.MEDIUM),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    buildResolvSettlementFlows(movementLegs),
                    List.of(),
                    resolveResolvCorrelationId(view, movementLegs),
                    false,
                    null,
                    "Resolv",
                    null
            ));
        }
        if (PENDING_REDEEM_REQUEST_SELECTOR.equals(view.methodId())
                || "claimsharesandrequestredeem".equals(functionKey)) {
            return Optional.of(terminalUnknown(
                    view,
                    movementLegs,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    List.of("PENDING_REDEEM_REQUEST"),
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyResolvedWarningFamilies(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        String functionKey = functionKey(view.functionName());
        if ((CLAIM_LIKE_AIRDROP_SELECTOR.equals(view.methodId()) || "airdrop".equals(functionKey))
                && summary.onlyInbound()) {
            return Optional.of(terminalUnknown(
                    view,
                    movementLegs,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.LOW,
                    List.of("CLAIM_LIKE_SPAM_OR_AIRDROP"),
                    null,
                    null
            ));
        }
        if ((FEE_BEARING_CLAIM_ADMIN_SELECTOR.equals(view.methodId())
                || containsAny(view.functionName(), "claim(tuple pindata", "claim((tuple pindata"))
                && !summary.onlyInbound()
                && hasAnyNegativeMovement(movementLegs)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.ADMIN_CONFIG,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.ADMIN_CONFIG),
                    List.of("FEE_BEARING_CLAIM_ADMIN_ACTION"),
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<ProtocolRegistryEntry> findKnownRewardEntry(OnChainRawTransactionView view) {
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putRewardCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.toAddress()));
        putRewardCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.fromAddress()));
        for (Document transfer : view.explorerTokenTransfers()) {
            putRewardCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.tokenTransferFrom(transfer)));
        }
        return candidates.values().stream().findFirst();
    }

    private void putRewardCandidate(
            Map<String, ProtocolRegistryEntry> candidates,
            Optional<ProtocolRegistryEntry> entry
    ) {
        if (entry.isEmpty()) {
            return;
        }
        ProtocolRegistryEntry value = entry.get();
        if (!isRewardEntry(value)) {
            return;
        }
        candidates.putIfAbsent(value.contractAddress(), value);
    }

    private OnChainClassificationResult result(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            String protocolName,
            String protocolVersion
    ) {
        return result(
                view,
                type,
                status,
                classifiedBy,
                confidence,
                flows,
                missingDataReasons,
                null,
                false,
                null,
                false,
                null,
                protocolName,
                protocolVersion
        );
    }

    private OnChainClassificationResult result(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            String correlationId,
            boolean continuityCandidate,
            String matchedCounterparty,
            String protocolName,
            String protocolVersion
    ) {
        return result(
                view,
                type,
                status,
                classifiedBy,
                confidence,
                flows,
                missingDataReasons,
                correlationId,
                continuityCandidate,
                matchedCounterparty,
                false,
                null,
                protocolName,
                protocolVersion
        );
    }

    private OnChainClassificationResult result(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            boolean continuityCandidate,
            String matchedCounterparty,
            String protocolName,
            String protocolVersion
    ) {
        return result(
                view,
                type,
                status,
                classifiedBy,
                confidence,
                flows,
                missingDataReasons,
                null,
                continuityCandidate,
                matchedCounterparty,
                false,
                null,
                protocolName,
                protocolVersion
        );
    }

    private OnChainClassificationResult result(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            String correlationId,
            boolean continuityCandidate,
            String matchedCounterparty,
            boolean excludedFromAccounting,
            String accountingExclusionReason,
            String protocolName,
            String protocolVersion
    ) {
        List<String> mergedMissingDataReasons = status == NormalizedTransactionStatus.PENDING_CLARIFICATION
                ? ClarificationEligibilitySupport.mergeClarificationReasons(view, type, missingDataReasons)
                : missingDataReasons;
        NormalizedTransactionStatus adjustedStatus = status;
        if (type == NormalizedTransactionType.SWAP && !hasWalletBoundarySwapShape(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_SHAPE_INCOMPLETE");
            if (!hasBuyLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_BUY_LEG");
            }
            if (!hasSellLeg(flows)) {
                mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "SWAP_MISSING_SELL_LEG");
            }
        }
        if (PricingReadinessSupport.requiresMovementEvidence(type, adjustedStatus)
                && !PricingReadinessSupport.hasNonFeeMovement(flows)) {
            adjustedStatus = NormalizedTransactionStatus.NEEDS_REVIEW;
            mergedMissingDataReasons = appendReason(mergedMissingDataReasons, "INSUFFICIENT_MOVEMENT_EVIDENCE");
        }
        return new OnChainClassificationResult(
                type,
                adjustedStatus,
                classifiedBy,
                confidence,
                flows,
                mergedMissingDataReasons,
                correlationId,
                continuityCandidate,
                matchedCounterparty,
                excludedFromAccounting,
                accountingExclusionReason,
                protocolName,
                protocolVersion
        );
    }

    private OnChainClassificationResult excludedAccountingResult(
            OnChainRawTransactionView view,
            NormalizedTransactionType type,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons,
            String correlationId,
            String protocolName,
            String protocolVersion,
            String accountingExclusionReason
    ) {
        return result(
                view,
                type,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                classifiedBy,
                confidence,
                flows,
                missingDataReasons,
                correlationId,
                false,
                null,
                true,
                accountingExclusionReason,
                protocolName,
                protocolVersion
        );
    }

    private boolean hasWalletBoundarySwapShape(List<NormalizedTransaction.Flow> flows) {
        return hasBuyLeg(flows) && hasSellLeg(flows);
    }

    private boolean hasBuyLeg(List<NormalizedTransaction.Flow> flows) {
        if (flows == null) {
            return false;
        }
        return flows.stream()
                .filter(flow -> flow != null && flow.getRole() == NormalizedLegRole.BUY)
                .anyMatch(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0);
    }

    private boolean hasSellLeg(List<NormalizedTransaction.Flow> flows) {
        if (flows == null) {
            return false;
        }
        return flows.stream()
                .filter(flow -> flow != null && flow.getRole() == NormalizedLegRole.SELL)
                .anyMatch(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0);
    }

    private NormalizedTransactionType resolvePositionManagerType(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        Optional<ProtocolRegistryEntry> decodedFromEntry = protocolRegistryService.lookup(
                view.networkId(),
                LpPositionLifecycleSupport.decodeSafeTransferFromAddress(view)
        );
        Optional<ProtocolRegistryEntry> decodedToEntry = protocolRegistryService.lookup(
                view.networkId(),
                LpPositionLifecycleSupport.decodeSafeTransferToAddress(view)
        );
        return LpPositionLifecycleSupport.resolvePositionManagerType(view, movementLegs, decodedFromEntry, decodedToEntry);
    }

    private NormalizedTransactionType resolveVaultType(OnChainRawTransactionView view) {
        String functionName = view.functionName();
        if (containsAny(functionName, "joinpool", "join")) {
            return NormalizedTransactionType.LP_ENTRY;
        }
        if (containsAny(functionName, "exitpool", "exit")) {
            return NormalizedTransactionType.LP_EXIT;
        }
        if (containsAny(functionName, "deposit", "supply")) {
            return NormalizedTransactionType.VAULT_DEPOSIT;
        }
        if (containsAny(functionName, "withdraw", "redeem")) {
            return NormalizedTransactionType.VAULT_WITHDRAW;
        }
        return null;
    }

    private OnChainClassificationResult registryResult(
            OnChainRawTransactionView view,
            ProtocolRegistryEntry entry,
            NormalizedTransactionType type,
            List<RawLeg> movementLegs
    ) {
        return result(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, entry.confidence()),
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                flows(view, movementLegs, type),
                List.of(),
                entry.protocolName(),
                entry.protocolVersion()
        );
    }

    private Optional<OnChainClassificationResult> classifyAdminConfig(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        boolean hasNonFeeMovement = movementLegs.stream().anyMatch(leg -> !leg.fee());
        Optional<AdminConfigSupport.AdminConfigMatch> match = AdminConfigSupport.match(view, hasNonFeeMovement);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result(
                view,
                NormalizedTransactionType.ADMIN_CONFIG,
                NormalizedTransactionStatus.CONFIRMED,
                match.get().classifiedBy(),
                match.get().confidence(),
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.ADMIN_CONFIG),
                List.of(),
                null,
                null
        ));
    }

    private Optional<OnChainClassificationResult> classifyPromoSpamInbound(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (hasKnownRewardContract(view)
                || hasKnownRewardInbound(view)
                || findKnownBridgeSettlementEntry(view).isPresent()
                || BridgeSettlementSupport.isSettlementSelector(view)) {
            return Optional.empty();
        }
        if (!InboundSignalSupport.isPromoPhishingInbound(view, movementLegs)) {
            return Optional.empty();
        }
        return Optional.of(terminalUnknown(
                view,
                movementLegs,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                List.of("PROMO_SPAM_PHISHING"),
                null,
                null
        ));
    }

    private Optional<OnChainClassificationResult> classifyZeroAmountTokenNoOp(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!ZeroAmountTokenSupport.isZeroAmountOutboundOnly(view)) {
            return Optional.empty();
        }
        if (ZeroAmountTokenSupport.isKnownNonEconomicFamily(view)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.ADMIN_CONFIG,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.ADMIN_CONFIG),
                    List.of(),
                    null,
                    null
            ));
        }
        return Optional.of(result(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNKNOWN),
                List.of("ZERO_AMOUNT_TOKEN_TRANSFER"),
                null,
                null
        ));
    }

    private Optional<OnChainClassificationResult> classifyClarifiedNonEconomicReview(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() != 0)) {
            return Optional.empty();
        }
        String txHash = view.txHash();
        if (txHash == null || !FULL_RECEIPT_NON_ECONOMIC_ALLOWLIST.contains(txHash.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        if (view.persistedLogs().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result(
                view,
                NormalizedTransactionType.ADMIN_CONFIG,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.ADMIN_CONFIG),
                List.of(),
                null,
                null
        ));
    }

    private Optional<OnChainClassificationResult> classifyClarifiedEconomicReview(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        String methodId = view.methodId();

        if (PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(methodId)
                && hasOutbound(movementLegs)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.SWAP,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.SWAP, ConfidenceLevel.MEDIUM),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.SWAP),
                    List.of(),
                    null,
                    null
            ));
        }

        if (ROUTE_SINGLE_SELECTOR.equals(methodId)
                && view.hasFullReceiptClarificationEvidence()
                && LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LP_ENTRY,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LP_ENTRY, ConfidenceLevel.MEDIUM),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LP_ENTRY),
                    List.of(),
                    null,
                    null
            ));
        }

        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (ROUTE_SINGLE_SELECTOR.equals(methodId)
                && hasDistinctNetInboundAndOutboundAssets(movementLegs)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.SWAP,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.SWAP, ConfidenceLevel.LOW),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.SWAP),
                    List.of(),
                    null,
                    null
            ));
        }
        if (PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(methodId)
                && hasSameAssetRefundPattern(movementLegs)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.SWAP,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.SWAP, ConfidenceLevel.LOW),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.SWAP),
                    List.of(),
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyDocumentedStopCondition(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        String txHash = view.txHash();
        if (txHash == null) {
            return Optional.empty();
        }
        String normalizedHash = txHash.toLowerCase(Locale.ROOT);
        if ("0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966".equals(normalizedHash)) {
            return Optional.of(terminalUnknown(
                    view,
                    movementLegs,
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.LOW,
                    List.of("UNVERIFIED_ROUTED_SEND"),
                    null,
                    null
            ));
        }
        if (TERMINAL_STOP_CONDITION_HASHES.contains(normalizedHash)) {
            return Optional.of(terminalUnknown(
                    view,
                    movementLegs,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    documentedStopConditionReason(normalizedHash),
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyCowSwapLifecycle(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (CowSwapSupport.isEthFlowRequest(view)) {
            String correlationId = CowSwapSupport.resolveEthFlowCorrelationId(view);
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DEX_ORDER_REQUEST,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.DEX_ORDER_REQUEST, ConfidenceLevel.MEDIUM),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DEX_ORDER_REQUEST),
                    List.of(),
                    correlationId,
                    false,
                    null,
                    CowSwapSupport.PROTOCOL_NAME,
                    CowSwapSupport.ETH_FLOW_VERSION
            ));
        }

        if (!CowSwapSupport.isSettlementCandidate(view)) {
            return Optional.empty();
        }

        String correlationId = CowSwapSupport.resolveSettlementCorrelationId(view);
        NormalizedTransactionStatus status = correlationId == null
                ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                : OnChainClassificationSupport.initialStatus(
                view,
                NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                ConfidenceLevel.MEDIUM
        );
        List<String> reasons = correlationId == null
                ? List.of(COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED)
                : List.of();
        return Optional.of(result(
                view,
                NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                status,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DEX_ORDER_SETTLEMENT),
                reasons,
                correlationId,
                false,
                null,
                CowSwapSupport.PROTOCOL_NAME,
                CowSwapSupport.GPV2_VERSION
        ));
    }

    private Optional<OnChainClassificationResult> classifyClarifiedBatchLendingPath(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!"0xc16ae7a4".equals(view.methodId()) && !containsAny(view.functionName(), "batch")) {
            return Optional.empty();
        }
        Optional<OnChainClassificationResult> eulerLoopResult = classifyEulerLoopPath(view, movementLegs);
        if (eulerLoopResult.isPresent()) {
            return eulerLoopResult;
        }
        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        boolean shareInbound = hasShareLikeMovement(movementLegs, true) || hasMintedFungibleTransferToWallet(view);
        boolean shareOutbound = hasShareLikeMovement(movementLegs, false) || hasBurnedFungibleTransferFromWallet(view);
        boolean principalInbound = hasNonShareMovement(movementLegs, true) || hasAnyInboundFungibleTransferToWallet(view);
        boolean principalOutbound = hasNonShareMovement(movementLegs, false) || hasAnyOutboundFungibleTransferFromWallet(view);
        if (shareInbound && principalOutbound) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LENDING_DEPOSIT,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LENDING_DEPOSIT, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LENDING_DEPOSIT),
                    List.of(),
                    null,
                    null
            ));
        }
        if (shareOutbound && principalInbound) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LENDING_WITHDRAW,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LENDING_WITHDRAW, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.LENDING_WITHDRAW),
                    List.of(),
                    null,
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<OnChainClassificationResult> classifyEulerLoopPath(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!EULER_BATCH_ROUTER.equals(view.toAddress())) {
            return Optional.empty();
        }
        if (isEulerBorrowBackedCollateralOpen(view, movementLegs)) {
            if (!view.hasFullReceiptClarificationEvidence() || !hasEulerClarifiedCollateralOpenLifecycle(view)) {
                return Optional.of(blockingReview(
                        view,
                        movementLegs,
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.LOW,
                        List.of(EULER_BATCH_DECODER_REQUIRED)
                ));
            }
            List<NormalizedTransaction.Flow> flows = buildEulerLoopOpenFlows(view, movementLegs);
            if (flows.isEmpty()) {
                return Optional.of(blockingReview(
                        view,
                        movementLegs,
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.LOW,
                        List.of(EULER_BATCH_DECODER_REQUIRED)
                ));
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LENDING_LOOP_OPEN,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LENDING_LOOP_OPEN, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    flows,
                    List.of(),
                    null,
                    false,
                    null,
                    "Euler",
                    null
            ));
        }

        Optional<EulerLoopRebalancePattern> rebalancePattern = detectEulerLoopRebalancePattern(movementLegs);
        if (rebalancePattern.isPresent()) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.LENDING_LOOP_REBALANCE,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.LENDING_LOOP_REBALANCE, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    buildEulerLoopRebalanceFlows(rebalancePattern.orElseThrow(), movementLegs),
                    List.of(),
                    null,
                    false,
                    null,
                    "Euler",
                    null
            ));
        }

        Optional<Document> shareOutboundTransfer = findEulerLoopShareOutboundTransfer(view);
        if (shareOutboundTransfer.isEmpty()) {
            return Optional.empty();
        }

        Optional<Document> returnedStableTransfer = findEulerLoopReturnedStableTransfer(view);
        if (returnedStableTransfer.isEmpty()) {
            return Optional.of(blockingReview(
                    view,
                    movementLegs,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    List.of(EULER_BATCH_DECODER_REQUIRED)
            ));
        }

        BigDecimal stableUnitPrice = resolveEulerStableLikeUnitPrice(returnedStableTransfer.orElseThrow());
        if (stableUnitPrice == null) {
            return Optional.of(blockingReview(
                    view,
                    movementLegs,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    List.of(EULER_BATCH_DECODER_REQUIRED)
            ));
        }

        Document shareTransfer = shareOutboundTransfer.orElseThrow();
        BigDecimal shareQuantity = view.tokenTransferQuantity(shareTransfer);
        BigDecimal returnedQuantity = view.tokenTransferQuantity(returnedStableTransfer.orElseThrow());
        if (shareQuantity == null || returnedQuantity == null
                || shareQuantity.signum() <= 0
                || returnedQuantity.signum() <= 0) {
            return Optional.of(blockingReview(
                    view,
                    movementLegs,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    List.of(EULER_BATCH_DECODER_REQUIRED)
            ));
        }

        BigDecimal shareUnitPrice = returnedQuantity.multiply(stableUnitPrice)
                .divide(shareQuantity, MathContext.DECIMAL128);
        List<NormalizedTransaction.Flow> flows = buildEulerLoopUnwindFlows(
                view,
                movementLegs,
                shareTransfer,
                returnedStableTransfer.orElseThrow(),
                stableUnitPrice,
                shareUnitPrice
        );
        NormalizedTransactionType type = isZeroAddress(view.tokenTransferTo(shareTransfer))
                ? NormalizedTransactionType.LENDING_LOOP_CLOSE
                : NormalizedTransactionType.LENDING_LOOP_DECREASE;
        return Optional.of(result(
                view,
                type,
                OnChainClassificationSupport.initialStatus(view, type, ConfidenceLevel.LOW),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                flows,
                List.of(),
                null,
                false,
                null,
                "Euler",
                null
        ));
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

    private List<NormalizedTransaction.Flow> buildEulerLoopOpenFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        RawLeg shareLeg = null;
        RawLeg debtLeg = null;
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() <= 0) {
                continue;
            }
            if (isDebtLikeSymbol(leg.assetSymbol())) {
                debtLeg = leg;
                continue;
            }
            if (isShareLikeSymbol(leg.assetSymbol())) {
                shareLeg = leg;
            }
        }
        if (shareLeg == null) {
            return List.of();
        }

        Optional<Document> anchorTransfer = findEulerLoopOpenAnchorTransfer(view, shareLeg.assetContract());
        if (anchorTransfer.isEmpty()) {
            return List.of();
        }

        BigDecimal anchorQuantity = view.tokenTransferQuantity(anchorTransfer.orElseThrow());
        BigDecimal shareQuantity = shareLeg.quantityDelta().abs();
        BigDecimal anchorUnitPrice = resolveEulerStableLikeUnitPrice(anchorTransfer.orElseThrow());
        if (anchorQuantity == null || shareQuantity.signum() <= 0 || anchorUnitPrice == null) {
            return List.of();
        }

        BigDecimal shareUnitPrice = anchorQuantity.multiply(anchorUnitPrice)
                .divide(shareQuantity, MathContext.DECIMAL128);

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        if (debtLeg != null) {
            flows.add(buildTransferFlow(debtLeg));
        }
        NormalizedTransaction.Flow shareFlow = buildFlow(
                NormalizedLegRole.BUY,
                shareLeg.assetContract(),
                shareLeg.assetSymbol(),
                shareLeg.quantityDelta()
        );
        applyResolvedPrice(
                shareFlow,
                shareUnitPrice,
                PriceSource.SWAP_DERIVED,
                EULER_SHARE_PRICE_INFERENCE_REASON
        );
        flows.add(shareFlow);
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private List<NormalizedTransaction.Flow> buildEulerLoopRebalanceFlows(
            EulerLoopRebalancePattern pattern,
            List<RawLeg> movementLegs
    ) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(buildTransferFlow(pattern.sourceShare()));
        if (pattern.sourceRefund() != null) {
            flows.add(buildTransferFlow(pattern.sourceRefund()));
        }
        flows.add(buildTransferFlow(pattern.replacementShare()));
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private List<NormalizedTransaction.Flow> buildEulerLoopUnwindFlows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            Document shareTransfer,
            Document returnedStableTransfer,
            BigDecimal stableUnitPrice,
            BigDecimal shareUnitPrice
    ) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        NormalizedTransaction.Flow shareFlow = buildFlow(
                NormalizedLegRole.SELL,
                view.tokenTransferContract(shareTransfer),
                view.tokenTransferSymbol(shareTransfer),
                view.tokenTransferQuantity(shareTransfer).negate()
        );
        applyResolvedPrice(
                shareFlow,
                shareUnitPrice,
                PriceSource.SWAP_DERIVED,
                EULER_SHARE_PRICE_INFERENCE_REASON
        );
        flows.add(shareFlow);

        NormalizedTransaction.Flow returnedFlow = buildFlow(
                NormalizedLegRole.BUY,
                view.tokenTransferContract(returnedStableTransfer),
                view.tokenTransferSymbol(returnedStableTransfer),
                view.tokenTransferQuantity(returnedStableTransfer)
        );
        applyResolvedPrice(
                returnedFlow,
                stableUnitPrice,
                PriceSource.STABLECOIN,
                EULER_STABLE_PRICE_INFERENCE_REASON
        );
        flows.add(returnedFlow);
        appendFeeFlows(flows, movementLegs);
        return flows;
    }

    private Optional<Document> findEulerLoopOpenAnchorTransfer(
            OnChainRawTransactionView view,
            String shareContract
    ) {
        Document best = null;
        BigDecimal bestQuantity = BigDecimal.ZERO;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (shareContract == null || !safeEquals(shareContract, view.tokenTransferTo(transfer))) {
                continue;
            }
            if (isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))
                    || !isEulerStableLikeTransfer(view, transfer)) {
                continue;
            }
            if (quantity.compareTo(bestQuantity) > 0) {
                best = transfer;
                bestQuantity = quantity;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<Document> findEulerLoopShareOutboundTransfer(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferFrom(transfer))) {
                continue;
            }
            String to = view.tokenTransferTo(transfer);
            if (isZeroAddress(to) || isEulerControlledSubaccount(view, view.walletAddress(), to)) {
                return Optional.of(transfer);
            }
        }
        return Optional.empty();
    }

    private Optional<Document> findEulerLoopReturnedStableTransfer(OnChainRawTransactionView view) {
        Document best = null;
        BigDecimal bestQuantity = BigDecimal.ZERO;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (!matchesPrimaryWallet(view, view.tokenTransferTo(transfer))
                    || !isEulerStableLikeTransfer(view, transfer)
                    || isShareLikeSymbol(view.tokenTransferSymbol(transfer))
                    || isDebtLikeSymbol(view.tokenTransferSymbol(transfer))) {
                continue;
            }
            if (quantity.compareTo(bestQuantity) > 0) {
                best = transfer;
                bestQuantity = quantity;
            }
        }
        return Optional.ofNullable(best);
    }

    private BigDecimal resolveEulerStableLikeUnitPrice(Document transfer) {
        return transfer != null ? BigDecimal.ONE : null;
    }

    private boolean isEulerStableLikeTransfer(OnChainRawTransactionView view, Document transfer) {
        if (transfer == null) {
            return false;
        }
        String contract = view.tokenTransferContract(transfer);
        if (contract != null && EULER_DEUSD_CONTRACT.equalsIgnoreCase(contract)) {
            return true;
        }
        String symbol = view.tokenTransferSymbol(transfer);
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return "USDC".equals(normalized) || "USDT".equals(normalized) || "USDT0".equals(normalized)
                || "USD₮0".equals(normalized) || "DEUSD".equals(normalized);
    }

    private Optional<EulerLoopRebalancePattern> detectEulerLoopRebalancePattern(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(this::isEulerDebtLikeMovement)) {
            return Optional.empty();
        }
        if (movementLegs.stream().anyMatch(this::isEulerNonShareEconomicMovement)) {
            return Optional.empty();
        }

        Map<String, RawLeg> outboundShares = aggregateEulerShareLegs(movementLegs, false);
        Map<String, RawLeg> inboundShares = aggregateEulerShareLegs(movementLegs, true);
        if (outboundShares.size() != 1 || inboundShares.isEmpty()) {
            return Optional.empty();
        }

        RawLeg sourceShare = outboundShares.values().iterator().next();
        String sourceAssetKey = rawLegAssetKey(sourceShare);
        Map<String, RawLeg> replacementCandidates = new LinkedHashMap<>(inboundShares);
        RawLeg sameAssetInbound = replacementCandidates.get(sourceAssetKey);
        RawLeg sourceRefund = null;
        if (sameAssetInbound != null
                && sameAssetInbound.quantityDelta().compareTo(sourceShare.quantityDelta().abs()) == 0
                && replacementCandidates.size() > 1) {
            replacementCandidates.remove(sourceAssetKey);
        }
        if (sameAssetInbound != null
                && sameAssetInbound.quantityDelta().compareTo(sourceShare.quantityDelta().abs()) != 0) {
            sourceRefund = sameAssetInbound;
            replacementCandidates.remove(sourceAssetKey);
        }
        if (replacementCandidates.size() != 1) {
            return Optional.empty();
        }

        RawLeg replacementShare = replacementCandidates.values().iterator().next();
        if (sameAsset(sourceShare, replacementShare)) {
            return Optional.empty();
        }

        return Optional.of(new EulerLoopRebalancePattern(sourceShare, replacementShare, sourceRefund));
    }

    private Map<String, RawLeg> aggregateEulerShareLegs(
            List<RawLeg> movementLegs,
            boolean inbound
    ) {
        Map<String, RawLeg> aggregated = new LinkedHashMap<>();
        for (RawLeg leg : movementLegs) {
            if (leg == null
                    || leg.fee()
                    || leg.quantityDelta() == null
                    || leg.quantityDelta().signum() == 0
                    || isDebtLikeSymbol(leg.assetSymbol())
                    || !isShareLikeSymbol(leg.assetSymbol())
                    || (inbound ? leg.quantityDelta().signum() <= 0 : leg.quantityDelta().signum() >= 0)) {
                continue;
            }
            String assetKey = rawLegAssetKey(leg);
            RawLeg current = aggregated.get(assetKey);
            if (current == null) {
                aggregated.put(assetKey, leg);
                continue;
            }
            aggregated.put(assetKey, new RawLeg(
                    current.assetContract(),
                    current.assetSymbol(),
                    current.quantityDelta().add(leg.quantityDelta()),
                    false
            ));
        }
        return aggregated;
    }

    private boolean isEulerDebtLikeMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() != 0
                && isDebtLikeSymbol(leg.assetSymbol());
    }

    private boolean isEulerNonShareEconomicMovement(RawLeg leg) {
        return leg != null
                && !leg.fee()
                && leg.quantityDelta() != null
                && leg.quantityDelta().signum() != 0
                && !isDebtLikeSymbol(leg.assetSymbol())
                && !isShareLikeSymbol(leg.assetSymbol());
    }

    private boolean sameAsset(RawLeg left, RawLeg right) {
        if (left == null || right == null) {
            return false;
        }
        String leftContract = normalizeContract(left.assetContract());
        String rightContract = normalizeContract(right.assetContract());
        if (leftContract != null || rightContract != null) {
            return leftContract != null && leftContract.equals(rightContract);
        }
        return normalizeSymbol(left.assetSymbol()).equals(normalizeSymbol(right.assetSymbol()));
    }

    private String rawLegAssetKey(RawLeg leg) {
        String contract = normalizeContract(leg.assetContract());
        return contract != null ? contract : "SYMBOL:" + normalizeSymbol(leg.assetSymbol());
    }

    private String normalizeContract(String contract) {
        return contract == null || contract.isBlank() ? null : contract.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private boolean matchesPrimaryWallet(OnChainRawTransactionView view, String address) {
        return safeEquals(OnChainRawTransactionView.normalizeAddress(view.walletAddress()),
                OnChainRawTransactionView.normalizeAddress(address));
    }

    private void appendFeeFlows(
            List<NormalizedTransaction.Flow> flows,
            List<RawLeg> movementLegs
    ) {
        for (RawLeg leg : movementLegs) {
            if (leg == null || !leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                continue;
            }
            flows.add(buildFlow(
                    NormalizedLegRole.FEE,
                    leg.assetContract(),
                    leg.assetSymbol(),
                    leg.quantityDelta()
            ));
        }
    }

    private NormalizedTransaction.Flow buildTransferFlow(RawLeg leg) {
        return buildFlow(
                NormalizedLegRole.TRANSFER,
                leg.assetContract(),
                leg.assetSymbol(),
                leg.quantityDelta()
        );
    }

    private NormalizedTransaction.Flow buildFlow(
            NormalizedLegRole role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(assetContract);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(quantityDelta);
        return flow;
    }

    private void applyResolvedPrice(
            NormalizedTransaction.Flow flow,
            BigDecimal unitPriceUsd,
            PriceSource priceSource,
            String inferenceReason
    ) {
        if (flow == null || unitPriceUsd == null || priceSource == null) {
            return;
        }
        BigDecimal persistedUnitPriceUsd = Decimal128Support.normalize(unitPriceUsd);
        BigDecimal persistedValueUsd = flow.getQuantityDelta() == null
                ? null
                : Decimal128Support.normalize(flow.getQuantityDelta().abs().multiply(persistedUnitPriceUsd));
        flow.setUnitPriceUsd(persistedUnitPriceUsd);
        flow.setValueUsd(persistedValueUsd);
        flow.setPriceSource(priceSource);
        flow.setIsInferred(true);
        flow.setInferenceReason(inferenceReason);
        flow.setConfidence(ConfidenceLevel.MEDIUM);
    }

    private Optional<OnChainClassificationResult> classifyClarifiedExecuteOrder(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        String correlationId = resolveGmxExecuteOrderCorrelationId(view);
        if (hasGmxEvent(view, "positiondecrease")) {
            NormalizedTransactionStatus status = correlationId == null
                    ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                    : OnChainClassificationSupport.initialStatus(
                    view,
                    NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
                    ConfidenceLevel.MEDIUM
            );
            List<String> reasons = correlationId == null
                    ? List.of(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED)
                    : List.of();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
                    status,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE),
                    reasons,
                    correlationId,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (hasGmxEvent(view, "positionincrease")) {
            NormalizedTransactionStatus status = correlationId == null
                    ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                    : OnChainClassificationSupport.initialStatus(
                    view,
                    NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
                    ConfidenceLevel.MEDIUM
            );
            List<String> reasons = correlationId == null
                    ? List.of(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED)
                    : List.of();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
                    status,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE),
                    reasons,
                    correlationId,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (hasGmxEvent(view, "ordercancelled") && !hasGmxEvent(view, "orderexecuted")) {
            NormalizedTransactionStatus status = correlationId == null
                    ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                    : OnChainClassificationSupport.initialStatus(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
                    ConfidenceLevel.MEDIUM
            );
            List<String> reasons = correlationId == null
                    ? List.of(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED)
                    : List.of();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
                    status,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL),
                    reasons,
                    correlationId,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        if (hasGmxEvent(view, "orderexecuted")) {
            NormalizedTransactionStatus status = correlationId == null
                    ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                    : OnChainClassificationSupport.initialStatus(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                    ConfidenceLevel.MEDIUM
            );
            List<String> reasons = correlationId == null
                    ? List.of(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED)
                    : List.of();
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                    status,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION),
                    reasons,
                    correlationId,
                    false,
                    null,
                    "GMX",
                    "V2"
            ));
        }
        return Optional.empty();
    }

    private NormalizedTransactionType resolveLendingPoolType(OnChainRawTransactionView view) {
        NormalizedTransactionType selectorType = METHOD_ID_TYPES.get(view.methodId());
        if (selectorType == NormalizedTransactionType.LENDING_DEPOSIT
                || selectorType == NormalizedTransactionType.LENDING_WITHDRAW
                || selectorType == NormalizedTransactionType.BORROW
                || selectorType == NormalizedTransactionType.REPAY) {
            return selectorType;
        }

        String functionName = view.functionName();
        if (containsAny(functionName, "withdraw", "redeem")) {
            return NormalizedTransactionType.LENDING_WITHDRAW;
        }
        if (containsAny(functionName, "deposit", "supply")) {
            return NormalizedTransactionType.LENDING_DEPOSIT;
        }
        if (functionName != null && functionName.contains("borrow")) {
            return NormalizedTransactionType.BORROW;
        }
        if (functionName != null && functionName.contains("repay")) {
            return NormalizedTransactionType.REPAY;
        }
        return null;
    }

    private boolean hasShareLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasNonShareMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && !isShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean isShareLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("a")
                || normalized.startsWith("c")
                || normalized.startsWith("s")
                || normalized.startsWith("e")
                || normalized.startsWith("gt")
                || normalized.startsWith("syrup");
    }

    private boolean isDebtLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        return assetSymbol.trim().toLowerCase(Locale.ROOT).contains("debt");
    }

    private boolean hasDebtLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isDebtLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasNonDebtShareLikeMovement(List<RawLeg> movementLegs, boolean inbound) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.assetContract() != null)
                .anyMatch(leg -> (inbound ? leg.quantityDelta().signum() > 0 : leg.quantityDelta().signum() < 0)
                        && isShareLikeSymbol(leg.assetSymbol())
                        && !isDebtLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasMintedFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesWalletAccount(view, topicAddress(topicAt(log, 2)))
                    && isZeroAddress(topicAddress(topicAt(log, 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBurnedFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (matchesWalletAccount(view, topicAddress(topicAt(log, 1)))
                    && isZeroAddress(topicAddress(topicAt(log, 2)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBurnToZeroTransferFromWallet(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            if (matchesWalletAccount(view, view.tokenTransferFrom(transfer))
                    && isZeroAddress(view.tokenTransferTo(transfer))) {
                return true;
            }
        }
        return hasBurnedFungibleTransferFromWallet(view);
    }

    private boolean hasAnyInboundFungibleTransferToWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesWalletAccount(view, to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInboundTransferToWallet(OnChainRawTransactionView view) {
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String from = view.tokenTransferFrom(transfer);
            String to = view.tokenTransferTo(transfer);
            if (matchesWalletAccount(view, to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return hasAnyInboundFungibleTransferToWallet(view);
    }

    private boolean hasAnyOutboundFungibleTransferFromWallet(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (matchesWalletAccount(view, from) && !isZeroAddress(to)) {
                return true;
            }
        }
        return false;
    }

    private boolean isErc20TransferLog(Document log) {
        if (log == null) {
            return false;
        }
        List<String> topics = normalizedTopics(log);
        return topics.size() >= 3
                && "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef".equals(topics.getFirst());
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

    private String logData(Document log) {
        if (log == null) {
            return null;
        }
        Object data = log.get("data");
        return data == null ? null : data.toString();
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

    private boolean isZeroAddress(String address) {
        return "0x0000000000000000000000000000000000000000".equals(address);
    }

    private boolean matchesWalletAccount(OnChainRawTransactionView view, String address) {
        String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
        String normalizedAddress = OnChainRawTransactionView.normalizeAddress(address);
        if (wallet == null || normalizedAddress == null) {
            return false;
        }
        if (wallet.equals(normalizedAddress)) {
            return true;
        }
        return isEulerControlledSubaccount(view, wallet, normalizedAddress);
    }

    private boolean isEulerControlledSubaccount(OnChainRawTransactionView view, String wallet, String candidate) {
        if (!"0xc16ae7a4".equals(view.methodId())) {
            return false;
        }
        if (!EULER_BATCH_ROUTER.equals(view.toAddress())) {
            return false;
        }
        return wallet.length() == 42
                && candidate.length() == 42
                && wallet.substring(0, 40).equals(candidate.substring(0, 40));
    }

    private boolean isEulerBorrowBackedCollateralOpen(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!EULER_BATCH_ROUTER.equals(view.toAddress())) {
            return false;
        }
        if (!hasEulerBorrowCallContext(view) || !hasEulerBorrowEvent(view)) {
            return false;
        }
        return hasDebtLikeMovement(movementLegs, true) && hasNonDebtShareLikeMovement(movementLegs, true);
    }

    private boolean isGmxDepositRequestMulticall(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM || !"0xac9650d8".equals(view.methodId())) {
            return false;
        }
        String inputData = view.inputData();
        if (inputData == null
                || !inputData.contains(GMX_HELPER_SEND_WNT_SELECTOR)
                || !inputData.contains(GMX_HELPER_SEND_TOKENS_SELECTOR)) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (!summary.onlyOutbound()) {
            return false;
        }
        if (hasOutboundTransferToProtocolRole(view, ProtocolRegistryRole.ORDER_VAULT, "GMX")) {
            return false;
        }
        return hasOutboundNonGmxShareAsset(movementLegs) && !hasOutboundGmxShareAsset(movementLegs);
    }

    private boolean isGmxWithdrawalRequestMulticall(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM || !"0xac9650d8".equals(view.methodId())) {
            return false;
        }
        String inputData = view.inputData();
        if (inputData == null
                || !inputData.contains(GMX_HELPER_SEND_WNT_SELECTOR)
                || !inputData.contains(GMX_HELPER_SEND_TOKENS_SELECTOR)) {
            return false;
        }
        if (!containsAnyDecodedOrEmbeddedSubcall(view, GMX_WITHDRAWAL_REQUEST_SUBCALL_SELECTORS)) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        return summary.onlyOutbound()
                && hasOutboundGmxShareAsset(movementLegs);
    }

    private boolean isGmxDerivativeOrderRequest(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            String functionKey
    ) {
        if (CowSwapSupport.isEthFlowRequest(view)) {
            return false;
        }
        if (CREATE_ORDER_ROUTER_SELECTOR.equals(view.methodId())
                || CREATE_ORDER_TUPLE_SELECTOR.equals(view.methodId())
                || "createorder".equals(functionKey)) {
            return true;
        }
        if (view.networkId() != NetworkId.ARBITRUM || !"0xac9650d8".equals(view.methodId())) {
            return false;
        }
        List<String> subcallSelectors = decodeMulticallSubcallSelectors(view);
        if (subcallSelectors.isEmpty()
                || !containsAnySubcall(subcallSelectors, "0x" + GMX_HELPER_SEND_WNT_SELECTOR, "0x" + GMX_HELPER_SEND_TOKENS_SELECTOR)
                || !containsAnySubcall(subcallSelectors, GMX_DERIVATIVE_REQUEST_SUBCALL_SELECTORS.toArray(String[]::new))) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        return summary.onlyOutbound()
                && hasGmxHelperFundingTarget(view, ProtocolRegistryRole.ORDER_VAULT, "GMX");
    }

    private boolean isGmxDepositSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM) {
            return false;
        }
        boolean settlementSelector = EXECUTE_DEPOSIT_SELECTOR.equals(view.methodId())
                || EXECUTE_GLV_DEPOSIT_SELECTOR.equals(view.methodId())
                || containsAny(view.functionName(), "executedeposit", "executeglvdeposit");
        if (!settlementSelector) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        return summary.onlyInbound();
    }

    private boolean isGmxWithdrawalSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view.networkId() != NetworkId.ARBITRUM) {
            return false;
        }
        boolean settlementSelector = EXECUTE_WITHDRAWAL_SELECTOR.equals(view.methodId())
                || containsAny(view.functionName(), "executewithdrawal", "executeglvwithdrawal");
        if (!settlementSelector) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        return summary.onlyInbound();
    }

    private boolean isBurnOnlyUnbondingRequest(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            String functionKey
    ) {
        if (!INITIATE_WITHDRAWAL_SELECTOR.equals(view.methodId()) && !"initiatewithdrawal".equals(functionKey)) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        return summary.onlyOutbound()
                && hasBurnToZeroTransferFromWallet(view)
                && !hasInboundTransferToWallet(view);
    }

    private boolean isResolvWithdrawSettlement(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            String functionKey
    ) {
        if (!RESOLV_WITHDRAW_SELECTOR.equals(view.methodId()) && !"withdraw".equals(functionKey)) {
            return false;
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (!summary.onlyInbound()) {
            return false;
        }
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.quantityDelta().signum() > 0)
                .anyMatch(this::isResolvUnderlyingLeg);
    }

    private boolean hasOutboundGmxShareAsset(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.quantityDelta().signum() < 0)
                .anyMatch(leg -> isGmxShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean hasOutboundNonGmxShareAsset(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee() && leg.quantityDelta().signum() < 0 && leg.assetContract() != null)
                .anyMatch(leg -> !isGmxShareLikeSymbol(leg.assetSymbol()));
    }

    private boolean isGmxShareLikeSymbol(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return false;
        }
        String normalized = assetSymbol.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("gm:")
                || normalized.startsWith("glv");
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
            Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(view.networkId(), view.tokenTransferTo(transfer));
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

    private Optional<OnChainClassificationResult> classifyClarifiedGmxLifecycle(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (!hasAnyGmxLifecycleEvent(view)) {
            return Optional.empty();
        }
        Optional<OnChainClassificationResult> clarifiedWithdrawal = classifyClarifiedGmxWithdrawal(view, movementLegs);
        if (clarifiedWithdrawal.isPresent()) {
            return clarifiedWithdrawal;
        }
        return classifyClarifiedExecuteOrder(view, movementLegs);
    }

    private boolean hasAnyGmxLifecycleEvent(OnChainRawTransactionView view) {
        return hasGmxEvent(view, "positionincrease")
                || hasGmxEvent(view, "positiondecrease")
                || hasGmxEvent(view, "orderexecuted")
                || hasGmxEvent(view, "ordercancelled")
                || hasGmxEvent(view, "withdrawalexecuted")
                || hasGmxEvent(view, "glvwithdrawalexecuted");
    }

    private List<String> decodeMulticallSubcallSelectors(OnChainRawTransactionView view) {
        if (view == null || !"0xac9650d8".equals(view.methodId())) {
            return List.of();
        }
        return CalldataDecodingSupport.decodeDynamicBytesArraySelectors(view.inputData());
    }

    private boolean containsAnySubcall(List<String> selectors, String... expectedSelectors) {
        if (selectors == null || selectors.isEmpty() || expectedSelectors == null || expectedSelectors.length == 0) {
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
            Set<String> expectedSelectors
    ) {
        if (view == null || expectedSelectors == null || expectedSelectors.isEmpty()) {
            return false;
        }
        List<String> decodedSelectors = decodeMulticallSubcallSelectors(view);
        if (containsAnySubcall(decodedSelectors, expectedSelectors.toArray(String[]::new))) {
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
            if (("0x" + GMX_HELPER_SEND_WNT_SELECTOR).equals(selector)) {
                targetAddress = CalldataDecodingSupport.decodeAddressArgument(subcall, 0);
            } else if (("0x" + GMX_HELPER_SEND_TOKENS_SELECTOR).equals(selector)) {
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

    private boolean isGmxProtocolTarget(OnChainRawTransactionView view) {
        if (view == null || view.networkId() == null || view.toAddress() == null) {
            return false;
        }
        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(view.networkId(), view.toAddress());
        return entry.isPresent() && "GMX".equalsIgnoreCase(entry.get().protocolName());
    }

    private Optional<OnChainClassificationResult> classifyClarifiedGmxWithdrawal(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!hasGmxEvent(view, "withdrawalexecuted") && !hasGmxEvent(view, "glvwithdrawalexecuted")) {
            return Optional.empty();
        }
        String correlationId = resolveGmxWithdrawalSettlementCorrelationId(view);
        NormalizedTransactionStatus status = correlationId == null
                ? NormalizedTransactionStatus.PENDING_CLARIFICATION
                : OnChainClassificationSupport.initialStatus(
                view,
                NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                ConfidenceLevel.MEDIUM
        );
        List<String> reasons = correlationId == null
                ? List.of(GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED)
                : List.of();
        return Optional.of(result(
                view,
                NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                status,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(orderAsyncLpSettlementLegs(movementLegs), NormalizedTransactionType.LP_EXIT_SETTLEMENT),
                reasons,
                correlationId,
                false,
                null,
                "GMX",
                "V2"
        ));
    }

    private String subcallSelector(String subcallInput) {
        if (subcallInput == null || !subcallInput.startsWith("0x") || subcallInput.length() < 10) {
            return null;
        }
        String selector = subcallInput.substring(0, 10).toLowerCase(Locale.ROOT);
        return selector.matches("0x[0-9a-f]{8}") ? selector : null;
    }

    private String resolveGmxOrderRequestCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log)) {
                continue;
            }
            if (!hasGmxEventName(log, "ordercreated")) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
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
            if (hasGmxEventName(log, "ordercreated")) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String resolveGmxSettlementCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        String glvCorrelationId = firstGmxEventCorrelationIdByTopic(view, GMX_GLV_DEPOSIT_EXECUTED_EVENT);
        if (glvCorrelationId != null) {
            return glvCorrelationId;
        }
        return firstGmxEventCorrelationIdByTopic(view, GMX_DEPOSIT_EXECUTED_EVENT);
    }

    private String resolveGmxWithdrawalRequestCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log)) {
                continue;
            }
            if (!hasGmxEventName(log, "withdrawalcreated") && !hasGmxEventName(log, "glvwithdrawalcreated")) {
                continue;
            }
            String walletTopic = topicAddress(topicAt(log, 3));
            if (!matchesWalletAccount(view, walletTopic)) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String resolveGmxWithdrawalSettlementCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        String glvCorrelationId = firstTrackedWalletGmxEventCorrelationId(view, "glvwithdrawalexecuted");
        if (glvCorrelationId != null) {
            return glvCorrelationId;
        }
        return firstTrackedWalletGmxEventCorrelationId(view, "withdrawalexecuted");
    }

    private String resolveGmxExecuteOrderCorrelationId(OnChainRawTransactionView view) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        String executedKey = firstGmxEventCorrelationId(view, "orderexecuted");
        if (executedKey != null) {
            return executedKey;
        }
        String cancelledKey = firstGmxEventCorrelationId(view, "ordercancelled");
        if (cancelledKey != null) {
            return cancelledKey;
        }
        return null;
    }

    private boolean isGmxEventEmitterLog(Document log) {
        List<String> topics = normalizedTopics(log);
        return !topics.isEmpty() && GMX_EVENT_EMITTER_TOPIC.equals(topics.getFirst());
    }

    private boolean hasGmxEventName(Document log, String eventName) {
        if (eventName == null || log == null) {
            return false;
        }
        String normalizedExpected = eventName.trim().toLowerCase(Locale.ROOT);
        String eventTopic = topicAt(log, 1);
        String expectedTopic = GmxEventTopicSupport.topicHash(eventName);
        if (eventTopic != null && expectedTopic != null && eventTopic.equalsIgnoreCase(expectedTopic)) {
            return true;
        }
        String explicitEventName = stringValue(log.get("eventName"));
        if (explicitEventName != null && explicitEventName.toLowerCase(Locale.ROOT).contains(normalizedExpected)) {
            return true;
        }
        String decodedEvent = stringValue(log.get("decodedEvent"));
        if (decodedEvent != null && decodedEvent.toLowerCase(Locale.ROOT).contains(normalizedExpected)) {
            return true;
        }
        return CalldataDecodingSupport.containsAsciiFragment(logData(log), eventName);
    }

    private boolean hasGmxEvent(OnChainRawTransactionView view, String eventName) {
        if (view == null || eventName == null || !view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        boolean hasEmitterEvidence = view.persistedLogs().stream().anyMatch(this::isGmxEventEmitterLog);
        for (Document log : view.persistedLogs()) {
            if (isGmxEventEmitterLog(log) && hasGmxEventName(log, eventName)) {
                return true;
            }
            if (hasEmitterEvidence && isStructuredGmxLifecycleLog(log, eventName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStructuredGmxLifecycleLog(Document log, String eventName) {
        if (log == null || eventName == null || isGmxEventEmitterLog(log) || isErc20TransferLog(log)) {
            return false;
        }
        return hasGmxEventName(log, eventName);
    }

    private String firstGmxEventCorrelationId(OnChainRawTransactionView view, String eventName) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !hasGmxEventName(log, eventName)) {
                continue;
            }
            String requestKey = topicAt(log, 2);
            if (requestKey != null && !requestKey.isBlank()) {
                return requestKey.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String firstGmxEventCorrelationIdByTopic(OnChainRawTransactionView view, String eventTopic) {
        if (!view.hasFullReceiptClarificationEvidence() || eventTopic == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !eventTopic.equals(topicAt(log, 1))) {
                continue;
            }
            String correlationId = topicAt(log, 2);
            if (correlationId != null && !correlationId.isBlank()) {
                return correlationId.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String firstTrackedWalletGmxEventCorrelationIdByTopic(
            OnChainRawTransactionView view,
            String eventTopic
    ) {
        if (!view.hasFullReceiptClarificationEvidence() || eventTopic == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !eventTopic.equals(topicAt(log, 1))) {
                continue;
            }
            String walletTopic = topicAddress(topicAt(log, 3));
            if (!matchesWalletAccount(view, walletTopic)) {
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
            String eventName
    ) {
        if (!view.hasFullReceiptClarificationEvidence() || eventName == null) {
            return null;
        }
        for (Document log : view.persistedLogs()) {
            if (!isGmxEventEmitterLog(log) || !hasGmxEventName(log, eventName)) {
                continue;
            }
            String walletTopic = topicAddress(topicAt(log, 3));
            if (!matchesWalletAccount(view, walletTopic)) {
                continue;
            }
            String correlationId = topicAt(log, 2);
            if (correlationId != null && !correlationId.isBlank()) {
                return correlationId.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
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

    private String resolveResolvCorrelationId(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (view == null || view.walletAddress() == null) {
            return null;
        }
        for (RawLeg leg : movementLegs) {
            if (leg == null || leg.fee() || leg.quantityDelta() == null) {
                continue;
            }
            if (leg.quantityDelta().signum() < 0 && isResolvStakedLeg(leg)) {
                return "resolv-unstake:" + view.walletAddress() + ":" + leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
            }
            if (leg.quantityDelta().signum() > 0 && isResolvUnderlyingLeg(leg)) {
                return "resolv-unstake:" + view.walletAddress() + ":" + leg.quantityDelta().abs().stripTrailingZeros().toPlainString();
            }
        }
        return null;
    }

    private boolean isResolvStakedLeg(RawLeg leg) {
        if (leg == null) {
            return false;
        }
        if (RESOLV_STAKED_TOKEN_CONTRACT.equalsIgnoreCase(leg.assetContract())) {
            return true;
        }
        return "stresolv".equalsIgnoreCase(leg.assetSymbol());
    }

    private boolean isResolvUnderlyingLeg(RawLeg leg) {
        if (leg == null) {
            return false;
        }
        if (RESOLV_TOKEN_CONTRACT.equalsIgnoreCase(leg.assetContract())) {
            return true;
        }
        return "resolv".equalsIgnoreCase(leg.assetSymbol());
    }

    private boolean hasEulerBorrowCallContext(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (topics.isEmpty() || !EULER_CALL_WITH_CONTEXT_TOPIC.equals(topics.getFirst())) {
                continue;
            }
            String data = logData(log);
            if (data != null && data.toLowerCase(Locale.ROOT).contains(EULER_BORROW_SELECTOR)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEulerBorrowEvent(OnChainRawTransactionView view) {
        for (Document log : view.persistedLogs()) {
            List<String> topics = normalizedTopics(log);
            if (!topics.isEmpty() && EULER_BORROW_EVENT_TOPIC.equals(topics.getFirst())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEulerClarifiedCollateralOpenLifecycle(OnChainRawTransactionView view) {
        boolean debtMintToWallet = false;
        boolean shareMintToWallet = false;
        boolean protocolHop = false;
        for (Document transfer : view.explorerTokenTransfers()) {
            BigDecimal quantity = view.tokenTransferQuantity(transfer);
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            String symbol = view.tokenTransferSymbol(transfer);
            String from = view.tokenTransferFrom(transfer);
            String to = view.tokenTransferTo(transfer);
            if (matchesWalletAccount(view, to) && isZeroAddress(from)) {
                if (isDebtLikeSymbol(symbol)) {
                    debtMintToWallet = true;
                    continue;
                }
                if (isShareLikeSymbol(symbol)) {
                    shareMintToWallet = true;
                    continue;
                }
            }
            if (!matchesWalletAccount(view, from)
                    && !matchesWalletAccount(view, to)
                    && !isZeroAddress(from)
                    && !isZeroAddress(to)
                    && !isDebtLikeSymbol(symbol)
                    && !isShareLikeSymbol(symbol)) {
                protocolHop = true;
            }
        }
        return debtMintToWallet && shareMintToWallet && protocolHop;
    }

    private boolean hasSameAssetRefundPattern(List<RawLeg> movementLegs) {
        MovementSummary summary = MovementSummary.from(movementLegs, null);
        return summary.tokenOutboundCount() == 1
                && summary.tokenInboundCount() == 1
                && summary.sameAssetInAndOut()
                && hasOutbound(movementLegs);
    }

    private List<String> documentedStopConditionReason(String txHash) {
        return switch (txHash) {
            case "0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a" ->
                    List.of("STOP_CONDITION_ZERO_EFFECT_MODIFY_LIQUIDITIES");
            case "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775" -> List.of("NON_ECONOMIC_COLLECT");
            case "0x504695248b7be49796e52895005019fa7ff268297e394078e336ec5a14cbcf54" -> List.of("STOP_CONDITION_ZERO_LOGS");
            case "0x508ad8c6695151cd84df379876cef4bd5c5370e8bdd660e54141a35ebe1d9d54" -> List.of("STOP_CONDITION_NON_MOVEMENT");
            case "0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2" -> List.of("STOP_CONDITION_WRAPPER_ONLY");
            default -> List.of();
        };
    }

    private OnChainClassificationResult terminalUnknown(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<String> missingDataReasons,
            String protocolName,
            String protocolVersion
    ) {
        return result(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.CONFIRMED,
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.ADMIN_CONFIG),
                missingDataReasons,
                protocolName,
                protocolVersion
        );
    }

    private OnChainClassificationResult blockingReview(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            List<String> missingDataReasons
    ) {
        return result(
                view,
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNKNOWN),
                missingDataReasons,
                null,
                null
        );
    }

    private static boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
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

    private List<String> appendReason(List<String> reasons, String reason) {
        if (reason == null || reason.isBlank()) {
            return reasons == null ? List.of() : reasons;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (reasons != null) {
            merged.addAll(reasons);
        }
        merged.add(reason);
        return List.copyOf(merged);
    }

    private List<String> bridgePairEvidenceReasons(OnChainRawTransactionView view) {
        if (view == null || view.hasFullReceiptClarificationEvidence()) {
            return List.of();
        }
        return List.of(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED);
    }

    private record EulerLoopRebalancePattern(
            RawLeg sourceShare,
            RawLeg replacementShare,
            RawLeg sourceRefund
    ) {
    }

    private record MovementSummary(
            Set<String> inboundAssets,
            Set<String> outboundAssets,
            boolean nativeInbound,
            boolean nativeOutbound
    ) {
        private static MovementSummary from(List<RawLeg> legs, String nativeSymbol) {
            Set<String> inbound = new LinkedHashSet<>();
            Set<String> outbound = new LinkedHashSet<>();
            boolean nativeIn = false;
            boolean nativeOut = false;
            for (RawLeg leg : legs) {
                if (leg.fee() || leg.quantityDelta() == null || leg.quantityDelta().signum() == 0) {
                    continue;
                }
                String key = assetKey(leg);
                if (leg.quantityDelta().signum() > 0) {
                    inbound.add(key);
                    if (nativeSymbol != null && nativeSymbol.equalsIgnoreCase(leg.assetSymbol())) {
                        nativeIn = true;
                    }
                } else {
                    outbound.add(key);
                    if (nativeSymbol != null && nativeSymbol.equalsIgnoreCase(leg.assetSymbol())) {
                        nativeOut = true;
                    }
                }
            }
            return new MovementSummary(inbound, outbound, nativeIn, nativeOut);
        }

        private int tokenInboundCount() {
            return (int) inboundAssets.stream().filter(asset -> !asset.startsWith("native:")).count();
        }

        private int tokenOutboundCount() {
            return (int) outboundAssets.stream().filter(asset -> !asset.startsWith("native:")).count();
        }

        private boolean singleTokenIn() {
            return tokenInboundCount() == 1;
        }

        private boolean singleTokenOut() {
            return tokenOutboundCount() == 1;
        }

        private boolean sameAssetInAndOut() {
            for (String asset : inboundAssets) {
                if (outboundAssets.contains(asset)) {
                    return true;
                }
            }
            return false;
        }

        private boolean onlyInbound() {
            return !inboundAssets.isEmpty() && outboundAssets.isEmpty();
        }

        private boolean onlyOutbound() {
            return inboundAssets.isEmpty() && !outboundAssets.isEmpty();
        }

        private boolean hasWrappedInbound(String wrappedContract) {
            return wrappedContract != null && inboundAssets.contains(wrappedContract);
        }

        private boolean hasWrappedOutbound(String wrappedContract) {
            return wrappedContract != null && outboundAssets.contains(wrappedContract);
        }

        private static String assetKey(RawLeg leg) {
            if (leg.assetContract() != null) {
                return leg.assetContract();
            }
            return "native:" + (leg.assetSymbol() == null ? "unknown" : leg.assetSymbol().toLowerCase(Locale.ROOT));
        }
    }
}
