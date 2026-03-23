package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
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
import java.util.ArrayList;
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
            Map.entry("0x0ad58d2f", NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT),
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

    private static final String TRANSFER_REMOTE_SELECTOR = "0x81b4e8b4";
    private static final String PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR = "0x7f457675";
    private static final String ROUTE_SINGLE_SELECTOR = "0xb94c3609";
    private static final String CREATE_ORDER_TUPLE_SELECTOR = "0x322bba21";
    private static final String EXECUTE_ORDER_SELECTOR = "0x7ebc83f7";
    private static final String MERKLE_CLAIM_SELECTOR = "0xae0b51df";
    private static final String CLAIM_WITH_SIG_SELECTOR = "0x7796e4ce";
    private static final String HARVEST_SELECTOR = "0x18fccc76";
    private static final String RELEASE_SELECTOR = "0x86d1a69f";
    private static final String GET_REWARD_SELECTOR = "0x7050ccd9";
    private static final String LB_HOOKS_CLAIM_SELECTOR = "0x45718278";
    private static final String PENDING_REDEEM_REQUEST_SELECTOR = "0x5cfe2fe4";
    private static final String CLAIM_LIKE_AIRDROP_SELECTOR = "0x729ad39e";
    private static final String FEE_BEARING_CLAIM_ADMIN_SELECTOR = "0xdc4b201d";

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

        Optional<OnChainClassificationResult> claimIncomeMatch = classifyKnownClaimIncome(view, movementLegs);
        if (claimIncomeMatch.isPresent()) {
            return claimIncomeMatch.get();
        }

        Optional<OnChainClassificationResult> clarifiedEconomicMatch = classifyClarifiedEconomicReview(view, movementLegs);
        if (clarifiedEconomicMatch.isPresent()) {
            return clarifiedEconomicMatch.get();
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
                OnChainClassificationSupport.toFlows(movementLegs, type),
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
                OnChainClassificationSupport.toFlows(movementLegs, type),
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
            return result(
                    view,
                    NormalizedTransactionType.INTERNAL_TRANSFER,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.HIGH,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.INTERNAL_TRANSFER),
                    List.of(),
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
                    NormalizedTransactionType.EXTERNAL_INBOUND,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.EXTERNAL_INBOUND, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.EXTERNAL_INBOUND),
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
                OnChainClassificationSupport.toFlows(movementLegs, type),
                List.of(),
                null,
                null
        );
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
            if (walletAddress.equals(view.tokenTransferTo(transfer))) {
                legs.add(RawLeg.asset(contract, symbol, quantity));
            }
            if (walletAddress.equals(view.tokenTransferFrom(transfer))) {
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
            if (walletAddress.equals(view.internalTransferTo(transfer))) {
                legs.add(RawLeg.nativeAsset(nativeSymbol, quantity));
            }
            if (walletAddress.equals(view.internalTransferFrom(transfer))) {
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
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.REWARD_CLAIM),
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
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.REWARD_CLAIM),
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
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.REWARD_CLAIM),
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
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.REWARD_CLAIM),
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
        if (CREATE_ORDER_TUPLE_SELECTOR.equals(view.methodId()) || "createorder".equals(functionKey)) {
            return Optional.of(terminalUnknown(
                    view,
                    movementLegs,
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.MEDIUM,
                    List.of("GMX_ORDER_INITIATION_PENDING"),
                    null,
                    null
            ));
        }
        if (EXECUTE_ORDER_SELECTOR.equals(view.methodId()) || "executeorder".equals(functionKey)) {
            Optional<OnChainClassificationResult> clarifiedSettlement = classifyClarifiedExecuteOrder(view, movementLegs);
            if (clarifiedSettlement.isPresent()) {
                return clarifiedSettlement;
            }
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.UNKNOWN,
                    NormalizedTransactionStatus.NEEDS_REVIEW,
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.MEDIUM,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.UNKNOWN),
                    List.of("GMX_ORDER_SETTLEMENT_UNRESOLVED"),
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
        List<String> mergedMissingDataReasons = status == NormalizedTransactionStatus.PENDING_CLARIFICATION
                ? ClarificationEligibilitySupport.mergeClarificationReasons(view, type, missingDataReasons)
                : missingDataReasons;
        NormalizedTransactionStatus adjustedStatus = status;
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
                protocolName,
                protocolVersion
        );
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
                OnChainClassificationSupport.toFlows(movementLegs, type),
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
        if (!view.hasClarificationEvidence()) {
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
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));

        if (PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(methodId)
                && hasDistinctNetInboundAndOutboundAssets(movementLegs)) {
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
                && hasOutbound(movementLegs)
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

        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        if (PARASWAP_SWAP_EXACT_AMOUNT_OUT_SELECTOR.equals(methodId)
                && summary.nativeOutbound()
                && summary.singleTokenIn()
                && summary.tokenOutboundCount() == 0) {
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

    private Optional<OnChainClassificationResult> classifyClarifiedBatchLendingPath(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!view.hasClarificationEvidence()) {
            return Optional.empty();
        }
        if (!"0xc16ae7a4".equals(view.methodId()) && !containsAny(view.functionName(), "batch")) {
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

    private Optional<OnChainClassificationResult> classifyClarifiedExecuteOrder(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs
    ) {
        if (!view.hasFullReceiptClarificationEvidence()) {
            return Optional.empty();
        }
        MovementSummary summary = MovementSummary.from(movementLegs, nativeAssetSymbolResolver.nativeSymbol(view.networkId()));
        if (hasDistinctNetInboundAndOutboundAssets(movementLegs)) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.SWAP,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.SWAP, ConfidenceLevel.LOW),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.SWAP),
                    List.of(),
                    null,
                    null
            ));
        }
        if (summary.onlyInbound()) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW, ConfidenceLevel.LOW),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW),
                    List.of(),
                    null,
                    null
            ));
        }
        if (summary.onlyOutbound()) {
            return Optional.of(result(
                    view,
                    NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT,
                    OnChainClassificationSupport.initialStatus(view, NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT, ConfidenceLevel.LOW),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(movementLegs, NormalizedTransactionType.PROTOCOL_CUSTODY_DEPOSIT),
                    List.of(),
                    null,
                    null
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

    private boolean hasMintedFungibleTransferToWallet(OnChainRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (wallet.equals(topicAddress(topicAt(log, 2)))
                    && isZeroAddress(topicAddress(topicAt(log, 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBurnedFungibleTransferFromWallet(OnChainRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            if (wallet.equals(topicAddress(topicAt(log, 1)))
                    && isZeroAddress(topicAddress(topicAt(log, 2)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyInboundFungibleTransferToWallet(OnChainRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (wallet.equals(to) && !isZeroAddress(from)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyOutboundFungibleTransferFromWallet(OnChainRawTransactionView view) {
        String wallet = view.walletAddress();
        if (wallet == null) {
            return false;
        }
        for (Document log : view.persistedLogs()) {
            if (!isErc20TransferLog(log)) {
                continue;
            }
            String from = topicAddress(topicAt(log, 1));
            String to = topicAddress(topicAt(log, 2));
            if (wallet.equals(from) && !isZeroAddress(to)) {
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
