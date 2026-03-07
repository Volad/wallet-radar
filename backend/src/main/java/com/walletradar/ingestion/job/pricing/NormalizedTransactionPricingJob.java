package com.walletradar.ingestion.job.pricing;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.PricingStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pricing stage for canonical normalized transactions: PENDING_PRICE -> PENDING_STAT / NEEDS_REVIEW.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NormalizedTransactionPricingJob {

    private static final int SCALE = 18;

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final HistoricalPriceResolverChain historicalPriceResolverChain;

    @Value("${walletradar.ingestion.normalized-pricing.max-retries:3}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${walletradar.ingestion.normalized-pricing.schedule-interval-ms:120000}")
    public void runScheduled() {
        long startedAt = System.currentTimeMillis();
        log.info("NormalizedTransactionPricingJob started");
        try {
            List<NormalizedTransaction> pending = normalizedTransactionRepository
                    .findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_PRICE);
            int processed = 0;
            for (NormalizedTransaction tx : pending) {
                priceOne(tx);
                processed++;
            }
            log.info("NormalizedTransactionPricingJob finished: pending={}, processed={}, durationMs={}",
                    pending.size(), processed, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("NormalizedTransactionPricingJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }

    void priceOne(NormalizedTransaction tx) {
        if (tx.getFlows() == null || tx.getFlows().isEmpty()) {
            markNeedsReview(tx, "MISSING_LEGS");
            return;
        }
        Set<String> unresolvedReasons = new LinkedHashSet<>();

        for (int i = 0; i < tx.getFlows().size(); i++) {
            NormalizedTransaction.Flow leg = tx.getFlows().get(i);
            if (leg.getQuantityDelta() == null || leg.getQuantityDelta().signum() == 0) {
                unresolvedReasons.add("MISSING_QUANTITY");
                continue;
            }
            boolean priceRequired = isPriceRequired(tx.getType(), leg.getQuantityDelta());
            if (!priceRequired) {
                continue;
            }
            if (leg.getUnitPriceUsd() != null) {
                leg.setValueUsd(leg.getUnitPriceUsd().multiply(leg.getQuantityDelta().abs()).setScale(SCALE, RoundingMode.HALF_UP));
                continue;
            }
            if (leg.getAssetContract() == null || leg.getAssetContract().isBlank()) {
                unresolvedReasons.add("MISSING_ASSET_CONTRACT");
                continue;
            }
            HistoricalPriceRequest request = new HistoricalPriceRequest();
            request.setAssetContract(leg.getAssetContract());
            request.setNetworkId(tx.getNetworkId());
            request.setBlockTimestamp(tx.getBlockTimestamp());
            fillSwapCounterpart(tx, i, request);
            PriceResolutionResult result = historicalPriceResolverChain.resolve(request);
            if (result.isUnknown() || result.getPriceUsd().isEmpty()) {
                unresolvedReasons.add("PRICE_UNRESOLVED:" + leg.getAssetContract());
                continue;
            }
            BigDecimal priceUsd = result.getPriceUsd().get();
            leg.setUnitPriceUsd(priceUsd);
            leg.setPriceSource(result.getPriceSource());
            leg.setValueUsd(priceUsd.multiply(leg.getQuantityDelta().abs()).setScale(SCALE, RoundingMode.HALF_UP));
        }

        tx.setUpdatedAt(Instant.now());
        if (unresolvedReasons.isEmpty()) {
            tx.setMissingDataReasons(List.of());
            tx.setPricingStatus(resolveResolvedPricingStatus(tx.getType()));
            tx.setStatus(NormalizedTransactionStatus.PENDING_STAT);
            normalizedTransactionRepository.save(tx);
            return;
        }

        int attempts = tx.getPricingAttempts() == null ? 0 : tx.getPricingAttempts();
        attempts++;
        tx.setPricingAttempts(attempts);
        tx.setMissingDataReasons(new ArrayList<>(unresolvedReasons));
        if (attempts >= Math.max(1, maxRetries)) {
            tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
            tx.setPricingStatus(PricingStatus.UNRESOLVED);
        } else {
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            tx.setPricingStatus(PricingStatus.PENDING);
        }
        normalizedTransactionRepository.save(tx);
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

    private static void fillSwapCounterpart(NormalizedTransaction tx, int index, HistoricalPriceRequest request) {
        if (tx.getFlows() == null || tx.getFlows().size() < 2 || index >= tx.getFlows().size()) {
            return;
        }
        NormalizedTransaction.Flow leg = tx.getFlows().get(index);
        for (int i = 0; i < tx.getFlows().size(); i++) {
            if (i == index) continue;
            NormalizedTransaction.Flow counterpart = tx.getFlows().get(i);
            if (counterpart.getAssetContract() == null || counterpart.getAssetContract().isBlank()
                    || counterpart.getQuantityDelta() == null || counterpart.getQuantityDelta().signum() == 0) {
                continue;
            }
            request.setCounterpartContract(counterpart.getAssetContract());
            request.setCounterpartAmount(counterpart.getQuantityDelta().abs());
            request.setOurAmount(leg.getQuantityDelta().abs());
            return;
        }
    }

    private void markNeedsReview(NormalizedTransaction tx, String reason) {
        tx.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        tx.setMissingDataReasons(List.of(reason));
        tx.setPricingStatus(PricingStatus.UNRESOLVED);
        tx.setUpdatedAt(Instant.now());
        normalizedTransactionRepository.save(tx);
    }
}
