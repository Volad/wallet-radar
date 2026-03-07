package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs all TxClassifiers on a raw transaction and merges results.
 */
@Component
@RequiredArgsConstructor
public class TxClassifierDispatcher {

    private static final Set<String> TRUSTED_LEND_WITHDRAW_SELECTORS = Set.of(
            "0x80500d20",
            "0x69328dec",
            "0xba087652",
            "0xb460af94"
    );

    private final List<TxClassifier> classifiers;

    /**
     * Classify the transaction for the given wallet. Runs classifiers in order and merges events.
     *
     * @param tx              raw transaction
     * @param walletAddress   wallet we are classifying for
     * @return merged list of raw classified events (may be empty)
     */
    public List<RawClassifiedEvent> classify(RawTransactionNormalizationView txView, String walletAddress) {
        List<TxClassifier> orderedClassifiers = new ArrayList<>(classifiers);
        orderedClassifiers.sort(Comparator
                .comparingInt(TxClassifierDispatcher::orderOf)
                .thenComparing(classifier -> classifier.getClass().getName()));

        List<RawClassifiedEvent> events = new ArrayList<>();
        for (TxClassifier classifier : orderedClassifiers) {
            events.addAll(classifier.classify(txView, walletAddress));
        }
        return resolveOverlaps(txView, events);
    }

    private static int orderOf(TxClassifier classifier) {
        if (classifier == null) {
            return Ordered.LOWEST_PRECEDENCE;
        }
        return OrderUtils.getOrder(classifier.getClass(), Ordered.LOWEST_PRECEDENCE);
    }

    private static List<RawClassifiedEvent> resolveOverlaps(
            RawTransactionNormalizationView txView,
            List<RawClassifiedEvent> events
    ) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        Map<String, RawClassifiedEvent> deduped = new LinkedHashMap<>();
        for (RawClassifiedEvent event : events) {
            if (event == null) {
                continue;
            }
            String key = movementKey(event);
            RawClassifiedEvent current = deduped.get(key);
            if (current == null) {
                deduped.put(key, event);
                continue;
            }
            deduped.put(key, chooseEvent(current, event));
        }
        return applyTrustedSelectorOverrides(txView, new ArrayList<>(deduped.values()));
    }

    private static List<RawClassifiedEvent> applyTrustedSelectorOverrides(
            RawTransactionNormalizationView txView,
            List<RawClassifiedEvent> events
    ) {
        if (txView == null || events == null || events.isEmpty()) {
            return events == null ? List.of() : events;
        }
        String selector = txView.selector();
        if (selector == null || !TRUSTED_LEND_WITHDRAW_SELECTORS.contains(selector.toLowerCase(Locale.ROOT))) {
            return events;
        }
        boolean hasLendWithdrawal = events.stream()
                .anyMatch(event -> event != null && event.getEventType() == EconomicEventType.LEND_WITHDRAWAL);
        if (!hasLendWithdrawal) {
            return events;
        }
        List<RawClassifiedEvent> filtered = new ArrayList<>();
        for (RawClassifiedEvent event : events) {
            if (event == null) {
                continue;
            }
            if (event.getEventType() == EconomicEventType.EXTERNAL_TRANSFER_OUT
                    || event.getEventType() == EconomicEventType.EXTERNAL_INBOUND) {
                continue;
            }
            filtered.add(event);
        }
        return filtered;
    }

    private static RawClassifiedEvent chooseEvent(RawClassifiedEvent left, RawClassifiedEvent right) {
        int leftPriority = priority(left != null ? left.getEventType() : null);
        int rightPriority = priority(right != null ? right.getEventType() : null);
        if (rightPriority > leftPriority) {
            return right;
        }
        return left;
    }

    private static String movementKey(RawClassifiedEvent event) {
        String wallet = normalize(event.getWalletAddress());
        String asset = normalize(event.getAssetContract());
        String quantity = event.getQuantityDelta() != null ? event.getQuantityDelta().stripTrailingZeros().toPlainString() : "null";
        String logIndex = event.getLogIndex() != null ? Integer.toString(event.getLogIndex()) : "null";
        String positionId = event.getPositionId() != null ? event.getPositionId() : "null";
        return wallet + "|" + asset + "|" + quantity + "|" + logIndex + "|" + positionId;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "null";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static int priority(EconomicEventType eventType) {
        if (eventType == null) {
            return 0;
        }
        return switch (eventType) {
            case LP_ENTRY, LP_EXIT, LP_EXIT_PARTIAL, LP_EXIT_FINAL, LP_FEE_CLAIM,
                 LP_POSITION_ENTRY, LP_POSITION_EXIT, LP_POSITION_STAKE, LP_POSITION_UNSTAKE, LP_ADJUST -> 500;
            case LEND_DEPOSIT, LEND_WITHDRAWAL, BORROW, REPAY -> 400;
            case WRAP, UNWRAP -> 350;
            case SWAP_BUY, SWAP_SELL -> 300;
            case EXTERNAL_INBOUND, EXTERNAL_TRANSFER_OUT -> 200;
            default -> 100;
        };
    }
}
