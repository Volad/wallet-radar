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
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.bson.Document;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Destination-side bridge settlement classifier that must resolve before reward and spam review.
 */
@Component
public class BridgeSettlementClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public BridgeSettlementClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.EARLY_GUARDS;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        boolean selectorProven = BridgeSettlementSupport.isSettlementSelector(context.view());
        boolean passiveSettlementCandidate = isPassiveBridgeSettlementCandidate(context);
        if (!selectorProven && !passiveSettlementCandidate) {
            return Optional.empty();
        }
        if (!onlyInbound(context.movementLegs())) {
            return Optional.empty();
        }

        Optional<ProtocolRegistryEntry> bridgeSettlementEntry = findKnownBridgeSettlementEntry(context);
        if (bridgeSettlementEntry.isPresent()) {
            ProtocolRegistryEntry entry = bridgeSettlementEntry.get();
            return Optional.of(new ClassificationDecision(
                    NormalizedTransactionType.BRIDGE_IN,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_IN, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_IN),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }
        if (selectorProven && BridgeSettlementSupport.requiresVerifiedBridgeEvidence(context.view())) {
            return Optional.empty();
        }

        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.BRIDGE_IN,
                OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.BRIDGE_IN, ConfidenceLevel.MEDIUM),
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.BRIDGE_IN),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private boolean isPassiveBridgeSettlementCandidate(OnChainClassificationContext context) {
        if (context == null || context.view() == null) {
            return false;
        }
        String inputData = context.view().inputData();
        if (inputData != null && !"0x".equals(inputData)) {
            return false;
        }
        String functionName = context.view().functionName();
        if (functionName != null && !functionName.isBlank()) {
            return false;
        }
        String walletAddress = context.view().walletAddress();
        for (Document transfer : context.view().explorerTokenTransfers()) {
            String recipient = context.view().tokenTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            if (protocolRegistryService.lookup(context.view().networkId(), context.view().tokenTransferFrom(transfer))
                    .filter(this::isBridgeEntry)
                    .isPresent()) {
                return true;
            }
        }
        for (Document transfer : context.view().explorerInternalTransfers()) {
            if (context.view().internalTransferErrored(transfer)) {
                continue;
            }
            String recipient = context.view().internalTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            if (protocolRegistryService.lookup(context.view().networkId(), context.view().internalTransferFrom(transfer))
                    .filter(this::isBridgeEntry)
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    private Optional<ProtocolRegistryEntry> findKnownBridgeSettlementEntry(OnChainClassificationContext context) {
        Map<String, ProtocolRegistryEntry> candidates = new LinkedHashMap<>();
        putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress()));
        putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().fromAddress()));
        for (Document transfer : context.view().explorerTokenTransfers()) {
            putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().tokenTransferFrom(transfer)));
        }
        String walletAddress = context.view().walletAddress();
        for (Document transfer : context.view().explorerInternalTransfers()) {
            if (context.view().internalTransferErrored(transfer)) {
                continue;
            }
            String recipient = context.view().internalTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            putBridgeCandidate(candidates, protocolRegistryService.lookup(context.view().networkId(), context.view().internalTransferFrom(transfer)));
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

    private boolean onlyInbound(List<RawLeg> movementLegs) {
        boolean hasInbound = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        return hasInbound && !hasOutbound;
    }
}
