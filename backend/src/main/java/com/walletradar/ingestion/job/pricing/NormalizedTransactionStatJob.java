package com.walletradar.ingestion.job.pricing;

import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.domain.RecalculateWalletRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
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
        List<NormalizedTransaction> pending = normalizedTransactionRepository
                .findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_STAT);
        Set<String> walletsToRecalc = new LinkedHashSet<>();
        for (NormalizedTransaction tx : pending) {
            if (confirmOne(tx)) {
                walletsToRecalc.add(tx.getWalletAddress());
            }
        }
        for (String wallet : walletsToRecalc) {
            applicationEventPublisher.publishEvent(new RecalculateWalletRequestEvent(wallet));
        }
    }

    boolean confirmOne(NormalizedTransaction tx) {
        List<String> reasons = statValidationErrors(tx);
        tx.setUpdatedAt(Instant.now());
        if (!reasons.isEmpty()) {
            tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            tx.setMissingDataReasons(reasons);
            normalizedTransactionRepository.save(tx);
            return false;
        }
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setConfirmedAt(Instant.now());
        tx.setMissingDataReasons(List.of());
        normalizedTransactionRepository.save(tx);
        return true;
    }

    private static List<String> statValidationErrors(NormalizedTransaction tx) {
        if (tx.getLegs() == null || tx.getLegs().isEmpty()) {
            return List.of("MISSING_LEGS");
        }
        boolean hasInbound = false;
        boolean hasOutbound = false;
        for (NormalizedTransaction.Leg leg : tx.getLegs()) {
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
        if (type == NormalizedTransactionType.SWAP) {
            return true;
        }
        return qty.signum() > 0;
    }
}
