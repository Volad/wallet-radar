package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.SameWalletSwapShapeSupport;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.walletradar.domain.transaction.normalized.ClassificationSource.PROTOCOL_REGISTRY;
import static com.walletradar.domain.transaction.normalized.NormalizedTransactionType.BRIDGE_OUT;

/**
 * Method-aware bridge-out classifier for explicit protocol-backed bridge entry paths.
 */
@Component
public class BridgeMethodAwareClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public BridgeMethodAwareClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.POST_SPAM_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        Optional<ProtocolRegistryEntry> transferBackedBridgeEntry = findKnownBridgeEntryFromOutboundTransfer(context);
        if (transferBackedBridgeEntry.isPresent() && isAcrossDepositV3(transferBackedBridgeEntry.get(), context)) {
            if (SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(context.movementLegs())) {
                return Optional.empty();
            }
            ProtocolRegistryEntry entry = transferBackedBridgeEntry.get();
            return Optional.of(build(context, entry));
        }

        Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress());
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        ProtocolRegistryEntry bridgeEntry = entry.get();
        if (isAcrossDepositV3(bridgeEntry, context) || isMethodAwareBridgeOut(bridgeEntry, context)) {
            if (SameWalletSwapShapeSupport.hasSameWalletInboundTransfer(context.movementLegs())) {
                return Optional.empty();
            }
            return Optional.of(build(context, bridgeEntry));
        }
        return Optional.empty();
    }

    private ClassificationDecision build(OnChainClassificationContext context, ProtocolRegistryEntry entry) {
        return new ClassificationDecision(
                BRIDGE_OUT,
                OnChainClassificationSupport.initialStatus(context.view(), BRIDGE_OUT, entry.confidence()),
                PROTOCOL_REGISTRY,
                entry.confidence(),
                OnChainClassificationSupport.toFlows(context.view(), context.movementLegs(), BRIDGE_OUT),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                entry.protocolName(),
                entry.protocolVersion()
        );
    }

    private boolean isAcrossDepositV3(ProtocolRegistryEntry entry, OnChainClassificationContext context) {
        if (entry.family() != ProtocolRegistryFamily.BRIDGE || entry.role() != ProtocolRegistryRole.BRIDGE_ENTRY) {
            return false;
        }
        String protocolName = entry.protocolName();
        if (protocolName == null || !protocolName.toLowerCase(Locale.ROOT).contains("across")) {
            return false;
        }
        return "0x7b939232".equals(context.view().methodId()) || contains(context.view().functionName(), "depositv3");
    }

    private boolean isMethodAwareBridgeOut(ProtocolRegistryEntry entry, OnChainClassificationContext context) {
        if (entry.family() != ProtocolRegistryFamily.BRIDGE || entry.role() != ProtocolRegistryRole.BRIDGE_ENTRY) {
            return false;
        }
        return "0xae0b91e5".equals(context.view().methodId())
                || "0x30c48952".equals(context.view().methodId());
    }

    private Optional<ProtocolRegistryEntry> findKnownBridgeEntryFromOutboundTransfer(OnChainClassificationContext context) {
        if (!"0x7b939232".equals(context.view().methodId()) && !contains(context.view().functionName(), "depositv3")) {
            return Optional.empty();
        }
        String walletAddress = context.view().walletAddress();
        if (walletAddress == null) {
            return Optional.empty();
        }
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress()));
        for (Document transfer : context.view().explorerTokenTransfers()) {
            if (!walletAddress.equals(context.view().tokenTransferFrom(transfer))) {
                continue;
            }
            putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().tokenTransferTo(transfer)));
        }
        return candidates.values().stream().findFirst();
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

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
