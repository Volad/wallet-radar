package com.walletradar.application.costbasis.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Runtime settings for stat validation and AVCO replay.
 */
@ConfigurationProperties(prefix = "walletradar.costbasis")
@NoArgsConstructor
@Getter
@Setter
public class CostBasisProperties {

    private boolean enabled = false;

    private int validationBatchSize = 250;

    private long scheduleIntervalMs = 120_000L;

    private long retryDelaySeconds = 120L;

    /**
     * CEX venue sources whose buy-side trade commissions (stored in
     * {@code NormalizedTransaction.Flow#acquisitionFeeUsd}) are capitalized into the Net AVCO lane.
     * Market AVCO is never affected — it always reflects the clean fill price.
     * Defaults to DZENGI and BYBIT; set to an empty list to disable globally.
     */
    private Set<NormalizedTransactionSource> netAvcoFeeCapitalizationSources =
            Set.of(NormalizedTransactionSource.DZENGI, NormalizedTransactionSource.BYBIT);
}
