package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.LpPositionLifecycleSupport;
import com.walletradar.application.normalization.pipeline.classification.support.OnChainClassificationSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
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
 * Transfer and reward-claim rules that must resolve before clarified-economic fallback.
 */
@Component
public class TransferClassifier implements OnChainFamilyClassifier {

    private static final String MERKLE_CLAIM_SELECTOR = "0xae0b51df";
    private static final String CLAIM_WITH_SIG_SELECTOR = "0x7796e4ce";
    private static final String HARVEST_SELECTOR = "0x18fccc76";
    private static final String RELEASE_SELECTOR = "0x86d1a69f";
    private static final String GET_REWARD_SELECTOR = "0x7050ccd9";

    private final ProtocolRegistryService protocolRegistryService;

    public TransferClassifier(ProtocolRegistryService protocolRegistryService) {
        this.protocolRegistryService = protocolRegistryService;
    }

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PRE_ECONOMIC_REVIEW;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        if (!onlyInbound(context.movementLegs())) {
            return Optional.empty();
        }

        String functionKey = functionKey(context.view().functionName());
        Optional<ProtocolRegistryEntry> protocolEntry =
                protocolRegistryService.lookup(context.view().networkId(), context.view().toAddress());
        if ((HARVEST_SELECTOR.equals(context.view().methodId()) || "harvest".equals(functionKey))
                && protocolEntry.filter(LpPositionLifecycleSupport::isDexStakeContract).isPresent()) {
            ProtocolRegistryEntry entry = protocolEntry.get();
            return Optional.of(build(
                    context,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.REWARD_CLAIM, entry.confidence()),
                    ClassificationSource.PROTOCOL_REGISTRY,
                    entry.confidence(),
                    entry.protocolName(),
                    entry.protocolVersion()
            ));
        }

        if ((RELEASE_SELECTOR.equals(context.view().methodId()) || "release".equals(functionKey))
                || (GET_REWARD_SELECTOR.equals(context.view().methodId()) || "getreward".equals(functionKey))) {
            return Optional.of(build(
                    context,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.REWARD_CLAIM, ConfidenceLevel.MEDIUM),
                    ClassificationSource.FUNCTION_NAME,
                    ConfidenceLevel.MEDIUM,
                    null,
                    null
            ));
        }

        if (MERKLE_CLAIM_SELECTOR.equals(context.view().methodId())
                || CLAIM_WITH_SIG_SELECTOR.equals(context.view().methodId())) {
            return Optional.of(build(
                    context,
                    NormalizedTransactionType.REWARD_CLAIM,
                    OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.REWARD_CLAIM, ConfidenceLevel.MEDIUM),
                    ClassificationSource.METHOD_ID,
                    ConfidenceLevel.MEDIUM,
                    null,
                    null
            ));
        }

        return Optional.empty();
    }

    private ClassificationDecision build(
            OnChainClassificationContext context,
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            ClassificationSource classifiedBy,
            ConfidenceLevel confidence,
            String protocolName,
            String protocolVersion
    ) {
        List<RawLeg> effectiveLegs = type == NormalizedTransactionType.REWARD_CLAIM
                ? removeExactSelfCancelingPairs(context.movementLegs())
                : context.movementLegs();
        return new ClassificationDecision(
                type,
                status,
                classifiedBy,
                confidence,
                OnChainClassificationSupport.toFlows(effectiveLegs, type),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                protocolName,
                protocolVersion
        );
    }

    private boolean onlyInbound(List<RawLeg> movementLegs) {
        boolean hasInbound = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
        boolean hasOutbound = movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() < 0);
        return hasInbound && !hasOutbound;
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
