package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.BridgeSettlementSupport;
import com.walletradar.application.normalization.pipeline.classification.support.InboundSignalSupport;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extracted promo/phishing inbound family.
 */
@Component
public class SpamClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public SpamClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (hasKnownRewardContract(context)
                || hasKnownRewardInbound(context)
                || findKnownBridgeSettlementEntry(context).isPresent()
                || BridgeSettlementSupport.isSettlementSelector(context.view())) {
            return Optional.empty();
        }
        if (!InboundSignalSupport.isPromoPhishingInbound(context.view(), context.movementLegs())) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.terminalUnknown(
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                context.movementLegs(),
                java.util.List.of(ClassificationReasonCode.PROMO_SPAM_PHISHING.code())
        ));
    }

    private boolean hasKnownRewardInbound(OnChainClassificationContext context) {
        return findKnownRewardEntry(context).isPresent();
    }

    private boolean hasKnownRewardContract(OnChainClassificationContext context) {
        return protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress())
                .filter(this::isRewardEntry)
                .isPresent();
    }

    private Optional<ProtocolRegistryEntry> findKnownBridgeSettlementEntry(OnChainClassificationContext context) {
        if (!BridgeSettlementSupport.isSettlementSelector(context.view())) {
            return Optional.empty();
        }

        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress()));
        putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().fromAddress()));
        for (Document transfer : context.view().explorerTokenTransfers()) {
            putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().tokenTransferFrom(transfer)));
        }
        return candidates.values().stream().findFirst();
    }

    private Optional<ProtocolRegistryEntry> findKnownRewardEntry(OnChainClassificationContext context) {
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putRewardCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress()));
        putRewardCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().fromAddress()));
        for (Document transfer : context.view().explorerTokenTransfers()) {
            putRewardCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().tokenTransferFrom(transfer)));
        }
        return candidates.values().stream().findFirst();
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
        if (value.family() != ProtocolRegistryFamily.BRIDGE) {
            return;
        }
        if (value.role() != ProtocolRegistryRole.BRIDGE_ENTRY && value.role() != ProtocolRegistryRole.ROUTER) {
            return;
        }
        candidates.putIfAbsent(value.contractAddress(), value);
    }
}
