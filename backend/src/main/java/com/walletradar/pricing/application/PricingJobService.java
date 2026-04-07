package com.walletradar.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Batch processor for the pricing stage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricingJobService {

    private final PendingPricingQueryService pendingPricingQueryService;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final PriceResolutionService priceResolutionService;
    private final PricingResultMapper pricingResultMapper;
    private final PricingProperties pricingProperties;

    public int processNextBatch() {
        List<NormalizedTransaction> batch = pendingPricingQueryService.loadNextBatch(
                pricingProperties.getBatchSize(),
                pricingProperties.getRetryDelaySeconds()
        );

        int processed = 0;
        for (NormalizedTransaction transaction : batch) {
            Instant now = Instant.now();
            try {
                NormalizedTransaction priced = priceResolutionService.resolve(transaction, now);
                normalizedTransactionRepository.save(priced);
                processed++;
            } catch (RuntimeException error) {
                log.warn(
                        "Pricing failed for normalizedTxId={}: {}",
                        transaction.getId(),
                        error.getMessage(),
                        error
                );
                NormalizedTransaction failed = pricingResultMapper.copy(transaction);
                pricingResultMapper.markFailedAttempt(failed, now);
                normalizedTransactionRepository.save(failed);
            }
        }
        return processed;
    }
}
