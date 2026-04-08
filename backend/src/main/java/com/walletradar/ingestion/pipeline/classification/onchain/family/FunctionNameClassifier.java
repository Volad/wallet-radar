package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.BridgeSettlementSupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.ingestion.pipeline.classification.support.WrappedNativeSupport;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Function-name fallback for late classification paths that still rely on deterministic
 * method-name evidence.
 */
@Component
public class FunctionNameClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;
    private final NativeAssetSymbolResolver nativeAssetSymbolResolver;

    public FunctionNameClassifier(
            ProtocolRegistryService protocolRegistryService,
            NativeAssetSymbolResolver nativeAssetSymbolResolver
    ) {
        this.protocolRegistryService = protocolRegistryService;
        this.nativeAssetSymbolResolver = nativeAssetSymbolResolver;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.FINAL_FALLBACK;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        String functionName = context.view().functionName();
        if (functionName == null) {
            return Optional.empty();
        }
        String functionKey = functionKey(functionName);
        if (findKnownBridgeSettlementEntry(context.view()).isPresent()
                && BridgeSettlementSupport.isSettlementSelector(context.view())) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> transferBackedBridgeEntry = findKnownBridgeEntryFromOutboundTransfer(context.view());
        if (transferBackedBridgeEntry.isPresent() && isAcrossDepositV3(transferBackedBridgeEntry.get(), context.view())) {
            ProtocolRegistryEntry entry = transferBackedBridgeEntry.get();
            return Optional.of(FamilyDecisionSupport.buildWithView(
                    context.view(),
                    NormalizedTransactionType.BRIDGE_OUT,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_OUT, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    OnChainClassificationSupport.toFlows(
                            context.view(),
                            context.movementLegs(),
                            NormalizedTransactionType.BRIDGE_OUT
                    ),
                    List.of(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }
        if (WrappedNativeSupport.detectType(context.view(), nativeAssetSymbolResolver).isPresent()
                && WrappedNativeSupport.hasWrappedNativeIdentity(context.view(), nativeAssetSymbolResolver)) {
            return Optional.empty();
        }

        NormalizedTransactionType type = null;
        if (containsAny(functionKey, "claim")) {
            if (!hasKnownRewardContract(context.view()) && !hasKnownRewardInbound(context.view())) {
                return Optional.empty();
            }
            type = hasOutbound(context.movementLegs()) ? NormalizedTransactionType.SWAP : NormalizedTransactionType.REWARD_CLAIM;
        } else if (containsAny(functionKey, "swap", "exchange", "trade")) {
            type = NormalizedTransactionType.SWAP;
        } else if (containsAny(functionKey, "addliquidity", "increaseliquidity", "modifyliquidities", "modifyliquidity")) {
            type = NormalizedTransactionType.LP_ENTRY;
        } else if (containsAny(functionKey, "removeliquidity", "decreaseliquidity")) {
            type = NormalizedTransactionType.LP_EXIT;
        } else if (containsAny(functionKey, "collect")) {
            type = NormalizedTransactionType.LP_FEE_CLAIM;
        } else if (containsAny(functionKey, "deposit", "supply", "provide")) {
            type = hasReceiptLikeToken(context.movementLegs())
                    ? NormalizedTransactionType.LENDING_DEPOSIT
                    : NormalizedTransactionType.VAULT_DEPOSIT;
        } else if (containsAny(functionKey, "withdraw", "redeem", "exit")) {
            type = hasReceiptLikeToken(context.movementLegs())
                    ? NormalizedTransactionType.LENDING_WITHDRAW
                    : NormalizedTransactionType.VAULT_WITHDRAW;
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

        return Optional.of(FamilyDecisionSupport.buildWithView(
                context.view(),
                type,
                OnChainClassificationSupport.initialStatus(context.view(), type, ConfidenceLevel.LOW),
                ClassificationSource.FUNCTION_NAME,
                type == NormalizedTransactionType.APPROVE ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW,
                ParityFlowSupport.flows(context.view(), context.movementLegs(), type),
                List.of()
        ));
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

    private boolean hasOutbound(List<RawLeg> movementLegs) {
        return movementLegs.stream()
                .filter(leg -> !leg.fee())
                .anyMatch(leg -> leg.quantityDelta().signum() < 0);
    }

    private boolean isAcrossDepositV3(ProtocolRegistryEntry entry, OnChainRawTransactionView view) {
        if (entry == null || view == null) {
            return false;
        }
        if (entry.family() != ProtocolRegistryFamily.BRIDGE) {
            return false;
        }
        String functionName = view.functionName();
        return "0x7b939232".equals(view.methodId()) || containsAny(functionName, "depositv3");
    }
}
