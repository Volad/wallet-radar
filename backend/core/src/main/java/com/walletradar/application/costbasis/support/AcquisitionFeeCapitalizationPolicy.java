package com.walletradar.application.costbasis.support;

import com.walletradar.application.costbasis.application.CostBasisProperties;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Determines whether a BUY flow's {@link NormalizedTransaction.Flow#getAcquisitionFeeUsd()}
 * should be capitalized into the Net AVCO lane for a given transaction.
 *
 * <p>Only CEX venues configured in
 * {@link CostBasisProperties#getNetAvcoFeeCapitalizationSources()} are eligible. Market AVCO
 * is never affected — it always reflects the clean fill price.
 */
@Component
@RequiredArgsConstructor
public class AcquisitionFeeCapitalizationPolicy {

    private final CostBasisProperties properties;

    /**
     * Returns the capitalizable fee in USD for the given BUY flow, or {@code null} if
     * capitalization is not applicable (wrong venue, wrong role, or no fee data).
     *
     * @param transaction the transaction being replayed
     * @param flow        the individual flow leg
     * @return positive USD fee amount to capitalize, or {@code null}
     */
    public BigDecimal capitalizableFeeUsd(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (transaction == null || flow == null) {
            return null;
        }
        if (flow.getRole() != NormalizedLegRole.BUY) {
            return null;
        }
        BigDecimal feeUsd = flow.getAcquisitionFeeUsd();
        if (feeUsd == null || feeUsd.signum() <= 0) {
            return null;
        }
        NormalizedTransactionSource source = transaction.getSource();
        if (source == null) {
            return null;
        }
        if (!properties.getNetAvcoFeeCapitalizationSources().contains(source)) {
            return null;
        }
        return feeUsd;
    }
}
