package com.walletradar.ingestion.job.pricing;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.ClassificationStatus;
import com.walletradar.domain.transaction.normalized.LpLifecycleBoundaryStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.normalized.PricingStatus;
import com.walletradar.domain.event.RecalculateWalletRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Final consistency stage: PENDING_STAT -> CONFIRMED / NEEDS_REVIEW.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NormalizedTransactionStatJob {

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Scheduled(fixedDelayString = "${walletradar.ingestion.normalized-stat.schedule-interval-ms:120000}")
    public void runScheduled() {
        long startedAt = System.currentTimeMillis();
        log.info("NormalizedTransactionStatJob started");
        try {
            List<NormalizedTransaction> pending = normalizedTransactionRepository
                    .findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_STAT);
            Set<String> walletsToRecalc = new LinkedHashSet<>();
            Set<String> lpGroupsToRefresh = new LinkedHashSet<>();
            int confirmed = 0;
            for (NormalizedTransaction tx : pending) {
                if (confirmOne(tx)) {
                    confirmed++;
                    walletsToRecalc.add(tx.getWalletAddress());
                    if (isLpLifecycleType(tx.getType()) && tx.getGroupId() != null && !tx.getGroupId().isBlank()) {
                        lpGroupsToRefresh.add(tx.getGroupId());
                    }
                }
            }
            for (String groupId : lpGroupsToRefresh) {
                refreshBoundaryStatus(groupId);
            }
            for (String wallet : walletsToRecalc) {
                applicationEventPublisher.publishEvent(new RecalculateWalletRequestEvent(wallet));
            }
            log.info("NormalizedTransactionStatJob finished: pending={}, confirmed={}, recalculationRequests={}, durationMs={}",
                    pending.size(), confirmed, walletsToRecalc.size(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("NormalizedTransactionStatJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }

    boolean confirmOne(NormalizedTransaction tx) {
        List<String> reasons = statValidationErrors(tx);
        tx.setUpdatedAt(Instant.now());
        if (!reasons.isEmpty()) {
            tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            tx.setMissingDataReasons(reasons);
            if (isPricingOnlyReview(reasons)) {
                tx.setPricingStatus(PricingStatus.UNRESOLVED);
                if (tx.getClassificationStatus() == null) {
                    tx.setClassificationStatus(ClassificationStatus.CONFIRMED);
                }
            } else {
                tx.setClassificationStatus(ClassificationStatus.NEEDS_REVIEW);
            }
            normalizedTransactionRepository.save(tx);
            return false;
        }
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setConfirmedAt(Instant.now());
        tx.setMissingDataReasons(List.of());
        if (tx.getClassificationStatus() == null) {
            tx.setClassificationStatus(ClassificationStatus.CONFIRMED);
        }
        if (tx.getPricingStatus() == null) {
            tx.setPricingStatus(resolveResolvedPricingStatus(tx.getType()));
        }
        normalizedTransactionRepository.save(tx);
        return true;
    }

    private static boolean isPricingOnlyReview(List<String> reasons) {
        return reasons != null
                && !reasons.isEmpty()
                && reasons.stream().allMatch("MISSING_PRICE"::equals);
    }

    private static List<String> statValidationErrors(NormalizedTransaction tx) {
        if (tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return List.of("MISSING_LEGS");
        }
        boolean hasInbound = false;
        boolean hasOutbound = false;
        for (NormalizedTransaction.Flow leg : tx.getFlows()) {
            BigDecimal qty = leg.getQuantityDelta();
            if (qty == null || qty.signum() == 0) {
                return List.of("MISSING_QUANTITY");
            }
            if (qty.signum() > 0) hasInbound = true;
            if (qty.signum() < 0) hasOutbound = true;
            boolean priceRequired = isPriceRequired(tx.getType(), qty);
            if (priceRequired && leg.getUnitPriceUsd() == null) {
                return List.of("MISSING_PRICE");
            }
        }
        if (tx.getType() == NormalizedTransactionType.SWAP && (!hasInbound || !hasOutbound)) {
            return List.of("INCONSISTENT_SWAP_LEGS");
        }
        return List.of();
    }

    private static boolean isPriceRequired(NormalizedTransactionType type, BigDecimal qty) {
        if (type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE
                || type == NormalizedTransactionType.APPROVAL) {
            return false;
        }
        if (type == NormalizedTransactionType.SWAP) {
            return true;
        }
        return qty.signum() > 0;
    }

    private static PricingStatus resolveResolvedPricingStatus(NormalizedTransactionType type) {
        if (type == NormalizedTransactionType.APPROVAL
                || type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE) {
            return PricingStatus.NOT_REQUIRED;
        }
        return PricingStatus.RESOLVED;
    }

    private void refreshBoundaryStatus(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        List<NormalizedTransaction> grouped = normalizedTransactionRepository.findByGroupIdOrderByBlockTimestampAsc(groupId);
        if (grouped.isEmpty()) {
            return;
        }
        boolean hasOpening = grouped.stream().anyMatch(tx -> tx.getType() == NormalizedTransactionType.LP_ENTRY);
        boolean hasClosing = grouped.stream().anyMatch(tx ->
                tx.getType() == NormalizedTransactionType.LP_EXIT_FINAL
                        || tx.getType() == NormalizedTransactionType.LP_EXIT);

        List<LpLifecycleBoundaryStatus> boundary = new ArrayList<>(2);
        if (!hasOpening) {
            boundary.add(LpLifecycleBoundaryStatus.OPENING_MISSING);
        }
        if (!hasClosing) {
            boundary.add(LpLifecycleBoundaryStatus.CLOSING_MISSING);
        }

        List<NormalizedTransaction> changed = new ArrayList<>();
        for (NormalizedTransaction tx : grouped) {
            if (!isLpLifecycleType(tx.getType())) {
                continue;
            }
            List<LpLifecycleBoundaryStatus> current = tx.getBoundaryStatuses() != null
                    ? tx.getBoundaryStatuses()
                    : List.of();
            if (!current.equals(boundary)) {
                tx.setBoundaryStatuses(boundary);
                tx.setUpdatedAt(Instant.now());
                changed.add(tx);
            }
        }
        if (!changed.isEmpty()) {
            normalizedTransactionRepository.saveAll(changed);
        }
    }

    private static boolean isLpLifecycleType(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL
                || type == NormalizedTransactionType.LP_FEE_CLAIM
                || type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE;
    }
}
