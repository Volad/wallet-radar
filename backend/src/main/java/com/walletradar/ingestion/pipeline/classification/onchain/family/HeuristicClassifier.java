package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.BridgeSettlementSupport;
import com.walletradar.ingestion.pipeline.classification.support.InboundSignalSupport;
import com.walletradar.ingestion.pipeline.classification.support.KnownBridgeRouterRegistry;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.ingestion.pipeline.classification.support.RelayBridgeClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.SponsoredGasTopUpSupport;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generic heuristic fallback for residual post-registry classification paths.
 * Euler batch/lending ownership now lives in {@link LendingClassifier}; this classifier keeps the
 * remaining generic fallback branches.
 */
@Component
public class HeuristicClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;
    private final TrackedWalletLookupService trackedWalletLookupService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public HeuristicClassifier(
            ProtocolRegistryService protocolRegistryService,
            TrackedWalletLookupService trackedWalletLookupService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.trackedWalletLookupService = trackedWalletLookupService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.FINAL_FALLBACK;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 300;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> bridgeSettlementEntry = findKnownBridgeSettlementEntry(context.view());
        MovementSummary summary = MovementSummary.from(
                context.movementLegs(),
                nativeAssetSymbolResolver.nativeSymbol(context.view().networkId())
        );
        String counterpartyTo = context.view().toAddress();
        String counterpartyFrom = context.view().fromAddress();

        if (summary.nativeOutbound()
                && summary.hasWrappedInbound(nativeAssetSymbolResolver.wrappedNativeContract(context.view().networkId()))) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.WRAP));
        }

        if (summary.nativeInbound()
                && summary.hasWrappedOutbound(nativeAssetSymbolResolver.wrappedNativeContract(context.view().networkId()))) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.UNWRAP));
        }

        if (summary.singleTokenOut() && summary.singleTokenIn() && !summary.sameAssetInAndOut()) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.SWAP));
        }
        if (summary.nativeOutbound() && summary.tokenInboundCount() == 1 && summary.tokenOutboundCount() == 0) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.SWAP));
        }
        if (summary.nativeInbound() && summary.tokenOutboundCount() == 1 && summary.tokenInboundCount() == 0) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.SWAP));
        }
        if (summary.tokenOutboundCount() >= 2 && summary.tokenInboundCount() == 1) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.LP_ENTRY));
        }
        if (summary.tokenOutboundCount() == 1 && summary.tokenInboundCount() >= 2) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.LP_EXIT));
        }

        List<String> flowCounterparties = flowCounterpartyAddresses(context);
        if (KnownBridgeRouterRegistry.touchesKnownRewardDistributor(flowCounterparties)) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.REWARD_CLAIM));
        }
        if (summary.onlyInbound() && touchesKnownBridgeRouter(context)) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.BRIDGE_IN,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.BRIDGE_IN,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_IN),
                    List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")
            ));
        }
        if (summary.onlyOutbound() && touchesKnownBridgeRouter(context)) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.BRIDGE_OUT,
                    OnChainClassificationSupport.initialStatus(
                            context.view(),
                            NormalizedTransactionType.BRIDGE_OUT,
                            ConfidenceLevel.LOW
                    ),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_OUT),
                    List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")
            ));
        }

        if (isTrackedCounterparty(counterpartyTo, context.view().walletAddress())
                || isTrackedCounterparty(counterpartyFrom, context.view().walletAddress())) {
            String matchedCounterparty = isTrackedCounterparty(counterpartyTo, context.view().walletAddress())
                    ? counterpartyTo
                    : counterpartyFrom;
            NormalizedTransactionType transferType = summary.onlyInbound()
                    ? NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                    : NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    transferType,
                    NormalizedTransactionStatus.CONFIRMED,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.HIGH,
                    OnChainClassificationSupport.toFlows(context.movementLegs(), transferType),
                    List.of(),
                    true,
                    matchedCounterparty,
                    null,
                    null
            ));
        }

        if (context.movementLegs().isEmpty()) {
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.UNKNOWN,
                    NormalizedTransactionStatus.NEEDS_REVIEW,
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    List.of(),
                    List.of("CLASSIFICATION_FAILED")
            ));
        }

        if (summary.onlyInbound()) {
            if (bridgeSettlementEntry.isPresent()) {
                ProtocolRegistryEntry entry = bridgeSettlementEntry.get();
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.BRIDGE_IN,
                        OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_IN, entry.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        entry.confidence(),
                        OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_IN),
                        List.of(),
                        entry.protocolName(),
                        entry.protocolVersion()
                ));
            }
            Optional<ProtocolRegistryEntry> sponsoredGasSender = SponsoredGasTopUpSupport.findVerifiedSender(
                    context.view(),
                    context.movementLegs(),
                    protocolRegistryService
            );
            if (sponsoredGasSender.isPresent()) {
                ProtocolRegistryEntry entry = sponsoredGasSender.get();
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.SPONSORED_GAS_IN,
                        OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.SPONSORED_GAS_IN, entry.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        entry.confidence(),
                        OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.SPONSORED_GAS_IN),
                        List.of(),
                        entry.protocolName(),
                        entry.protocolVersion()
                ));
            }
            if (hasKnownRewardContract(context.view()) || hasKnownRewardInbound(context.view())) {
                return Optional.of(knownLowConfidence(context, NormalizedTransactionType.REWARD_CLAIM));
            }
            if (InboundSignalSupport.hasExplicitClaimSelector(context.view())) {
                return Optional.of(knownLowConfidence(context, NormalizedTransactionType.REWARD_CLAIM));
            }
            Optional<ProtocolRegistryEntry> relayPayoutEntry = RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry(
                    protocolRegistryService,
                    context.view()
            );
            if (relayPayoutEntry.isPresent()) {
                ProtocolRegistryEntry entry = relayPayoutEntry.get();
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.BRIDGE_IN,
                        OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_IN, entry.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        entry.confidence(),
                        OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_IN),
                        List.of(),
                        entry.protocolName(),
                        entry.protocolVersion()
                ));
            }
            if (touchesKnownBridgeRouter(context)) {
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.BRIDGE_IN,
                        OnChainClassificationSupport.initialStatus(
                                context.view(),
                                NormalizedTransactionType.BRIDGE_IN,
                                ConfidenceLevel.LOW
                        ),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.LOW,
                        OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_IN),
                        List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")
                ));
            }
            List<String> reasons = InboundSignalSupport.hasRewardLikeSignal(context.view())
                    ? List.of("AMBIGUOUS_INBOUND_VS_REWARD")
                    : List.of();
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.EXTERNAL_TRANSFER_IN, ConfidenceLevel.LOW),
                    ClassificationSource.HEURISTIC,
                    ConfidenceLevel.LOW,
                    OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                    reasons
            ));
        }

        if (summary.onlyOutbound()) {
            Optional<ProtocolRegistryEntry> relayDepositoryEntry = RelayBridgeClassificationSupport.resolveRelayDepositoryBridgeEntry(
                    protocolRegistryService,
                    context.view()
            );
            if (relayDepositoryEntry.isPresent()) {
                ProtocolRegistryEntry entry = relayDepositoryEntry.get();
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.BRIDGE_OUT,
                        OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_OUT, entry.confidence()),
                        ClassificationSource.PROTOCOL_REGISTRY,
                        entry.confidence(),
                        OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_OUT),
                        List.of(),
                        entry.protocolName(),
                        entry.protocolVersion()
                ));
            }
            if (touchesKnownBridgeRouter(context)) {
                return Optional.of(FamilyDecisionSupport.buildWithView(
                        context.view(),
                        NormalizedTransactionType.BRIDGE_OUT,
                        OnChainClassificationSupport.initialStatus(
                                context.view(),
                                NormalizedTransactionType.BRIDGE_OUT,
                                ConfidenceLevel.LOW
                        ),
                        ClassificationSource.HEURISTIC,
                        ConfidenceLevel.LOW,
                        OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_OUT),
                        List.of("BRIDGE_ON_CHAIN_LEG_NOT_FOUND")
                ));
            }
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT));
        }

        if (hasReceiptLikeToken(context.movementLegs())) {
            return Optional.of(knownLowConfidence(context, NormalizedTransactionType.LENDING_DEPOSIT));
        }

        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.NEEDS_REVIEW,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.UNKNOWN),
                List.of("CLASSIFICATION_FAILED")
        ));
    }

    private ClassificationDecision knownLowConfidence(
            OnChainClassificationContext context,
            NormalizedTransactionType type
    ) {
        return FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.LOW),
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                ParityFlowSupport.flows(context.view(), context.movementLegs(), type),
                List.of()
        );
    }

    private static boolean touchesKnownBridgeRouter(OnChainClassificationContext context) {
        return KnownBridgeRouterRegistry.touchesKnownBridgeRouter(flowCounterpartyAddresses(context));
    }

    private static List<String> flowCounterpartyAddresses(OnChainClassificationContext context) {
        List<String> addresses = new ArrayList<>();
        if (context == null || context.view() == null) {
            return addresses;
        }
        OnChainRawTransactionView view = context.view();
        String to = view.toAddress();
        String from = view.fromAddress();
        if (to != null && !to.isBlank()) {
            addresses.add(to);
        }
        if (from != null && !from.isBlank()) {
            addresses.add(from);
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            String tokenFrom = view.tokenTransferFrom(transfer);
            String tokenTo = view.tokenTransferTo(transfer);
            if (tokenFrom != null && !tokenFrom.isBlank()) {
                addresses.add(tokenFrom);
            }
            if (tokenTo != null && !tokenTo.isBlank()) {
                addresses.add(tokenTo);
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            String internalFrom = view.internalTransferFrom(transfer);
            String internalTo = view.internalTransferTo(transfer);
            if (internalFrom != null && !internalFrom.isBlank()) {
                addresses.add(internalFrom);
            }
            if (internalTo != null && !internalTo.isBlank()) {
                addresses.add(internalTo);
            }
        }
        return addresses;
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

    private Optional<ProtocolRegistryEntry> findKnownRewardEntry(OnChainRawTransactionView view) {
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putRewardCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.toAddress()));
        putRewardCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.fromAddress()));
        for (Document transfer : view.explorerTokenTransfers()) {
            putRewardCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.tokenTransferFrom(transfer)));
        }
        return candidates.values().stream().findFirst();
    }

    private Optional<ProtocolRegistryEntry> findKnownBridgeSettlementEntry(OnChainRawTransactionView view) {
        boolean selectorProven = BridgeSettlementSupport.isSettlementSelector(view);
        boolean passiveSettlementCandidate = isPassiveBridgeSettlementCandidate(view);
        if (!selectorProven && !passiveSettlementCandidate) {
            return Optional.empty();
        }
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.toAddress()));
        putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.fromAddress()));
        for (Document transfer : view.explorerTokenTransfers()) {
            putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.tokenTransferFrom(transfer)));
        }
        String walletAddress = view.walletAddress();
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            String recipient = view.internalTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            putBridgeCandidate(candidates, protocolRegistryService.lookup(view.networkId(), view.internalTransferFrom(transfer)));
        }
        return candidates.values().stream().findFirst();
    }

    private boolean isPassiveBridgeSettlementCandidate(OnChainRawTransactionView view) {
        if (view == null) {
            return false;
        }
        String inputData = view.inputData();
        if (inputData != null && !"0x".equals(inputData)) {
            return false;
        }
        String functionName = view.functionName();
        if (functionName != null && !functionName.isBlank()) {
            return false;
        }
        String walletAddress = view.walletAddress();
        for (Document transfer : view.explorerTokenTransfers()) {
            String recipient = view.tokenTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            if (protocolRegistryService.lookup(view.networkId(), view.tokenTransferFrom(transfer))
                    .filter(this::isBridgeEntry)
                    .isPresent()) {
                return true;
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            String recipient = view.internalTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            if (protocolRegistryService.lookup(view.networkId(), view.internalTransferFrom(transfer))
                    .filter(this::isBridgeEntry)
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean isRewardEntry(ProtocolRegistryEntry entry) {
        return entry.normalizedType() == NormalizedTransactionType.REWARD_CLAIM
                || entry.family() == ProtocolRegistryFamily.YIELD
                || entry.role() == ProtocolRegistryRole.REWARD_ROUTER;
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

    private void putBridgeCandidate(
            Map<String, ProtocolRegistryEntry> candidates,
            Optional<ProtocolRegistryEntry> entry
    ) {
        if (entry.isEmpty()) {
            return;
        }
        ProtocolRegistryEntry value = entry.get();
        if (!isBridgeEntry(value)) {
            return;
        }
        candidates.putIfAbsent(value.contractAddress(), value);
    }

    private boolean isBridgeEntry(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.BRIDGE
                && (entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                || entry.role() == ProtocolRegistryRole.ROUTER);
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
            if (needle != null && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
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
