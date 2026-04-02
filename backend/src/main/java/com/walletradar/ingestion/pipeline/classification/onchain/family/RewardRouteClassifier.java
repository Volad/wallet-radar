package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.InboundSignalSupport;
import com.walletradar.ingestion.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
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
 * Reward-route family that resolves known claim routers before spam review.
 */
@Component
public class RewardRouteClassifier implements OnChainFamilyClassifier {

    private final ProtocolRegistryService protocolRegistryService;

    public RewardRouteClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.EARLY_GUARDS;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 300;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!InboundSignalSupport.hasExplicitClaimSignal(context.view())) {
            return Optional.empty();
        }
        Optional<ProtocolRegistryEntry> rewardEntry = findKnownRewardEntry(context);
        if (rewardEntry.isEmpty()) {
            return Optional.empty();
        }

        ProtocolRegistryEntry entry = rewardEntry.get();
        boolean hasInboundMovement = context.movementLegs().stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        if (hasInboundMovement) {
            return Optional.of(new ClassificationDecision(
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.REWARD_CLAIM, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    OnChainClassificationSupport.toFlows(removeExactSelfCancelingPairs(context.movementLegs()), NormalizedTransactionType.REWARD_CLAIM),
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

        return Optional.of(new ClassificationDecision(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.CONFIRMED,
                ClassificationSource.PROTOCOL_REGISTRY,
                entry.confidence(),
                OnChainClassificationSupport.toFlows(context.movementLegs(), NormalizedTransactionType.ADMIN_CONFIG),
                List.of("CLAIM_WITHOUT_MOVEMENT"),
                null,
                null,
                null,
                null,
                null,
                entry.protocolName(),
                entry.protocolVersion()
        ));
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
}
