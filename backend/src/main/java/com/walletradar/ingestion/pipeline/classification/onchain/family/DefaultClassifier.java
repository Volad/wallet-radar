package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * UNKNOWN sink rules for documented terminal stop conditions that already have stable persisted
 * behavior in the baseline corpus.
 */
@Component
public class DefaultClassifier implements OnChainFamilyClassifier {

    private static final Map<String, List<String>> TERMINAL_STOP_CONDITION_REASONS = Map.of(
            "0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a",
            List.of("STOP_CONDITION_ZERO_EFFECT_MODIFY_LIQUIDITIES"),
            "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775",
            List.of("NON_ECONOMIC_COLLECT"),
            "0x504695248b7be49796e52895005019fa7ff268297e394078e336ec5a14cbcf54",
            List.of("STOP_CONDITION_ZERO_LOGS"),
            "0x508ad8c6695151cd84df379876cef4bd5c5370e8bdd660e54141a35ebe1d9d54",
            List.of("STOP_CONDITION_NON_MOVEMENT"),
            "0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2",
            List.of("STOP_CONDITION_WRAPPER_ONLY")
    );

    private static final Set<String> TERMINAL_STOP_CONDITION_HASHES = TERMINAL_STOP_CONDITION_REASONS.keySet();

    @Override
    public OnChainClassificationInsertionPoint insertionPoint() {
        return OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Optional<ClassificationDecision> classify(OnChainClassificationContext context) {
        String txHash = context.view().txHash();
        if (txHash == null) {
            return Optional.empty();
        }
        if (!TERMINAL_STOP_CONDITION_HASHES.contains(txHash.toLowerCase(java.util.Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(FamilyDecisionSupport.terminalUnknown(
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.LOW,
                context.movementLegs(),
                TERMINAL_STOP_CONDITION_REASONS.getOrDefault(txHash.toLowerCase(java.util.Locale.ROOT), List.of())
        ));
    }
}
