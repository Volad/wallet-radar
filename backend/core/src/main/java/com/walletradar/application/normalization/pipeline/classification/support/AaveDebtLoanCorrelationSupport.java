package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

/**
 * @deprecated Use {@link com.walletradar.accounting.support.leverage.AaveDebtLoanCorrelationSupport}.
 */
@Deprecated
public final class AaveDebtLoanCorrelationSupport {

    public static final String SYNTHETIC_LOAN_PREFIX =
            com.walletradar.accounting.support.leverage.AaveDebtLoanCorrelationSupport.SYNTHETIC_LOAN_PREFIX;
    public static final String LEVERAGE_LOAN_PREFIX =
            com.walletradar.accounting.support.leverage.AaveDebtLoanCorrelationSupport.LEVERAGE_LOAN_PREFIX;

    private AaveDebtLoanCorrelationSupport() {
    }

    public static String leverageLoanCorrelationId(
            NetworkId networkId,
            String debtContract,
            String collateralContract,
            String walletAddress
    ) {
        return com.walletradar.accounting.support.leverage.AaveDebtLoanCorrelationSupport
                .leverageLoanCorrelationId(networkId, debtContract, collateralContract, walletAddress);
    }

    public static boolean isLoanLifecycleType(NormalizedTransactionType type) {
        return com.walletradar.accounting.support.leverage.AaveDebtLoanCorrelationSupport
                .isLoanLifecycleType(type);
    }

    public static String syntheticLoanCorrelationId(NormalizedTransaction transaction) {
        return com.walletradar.accounting.support.leverage.AaveDebtLoanCorrelationSupport
                .syntheticLoanCorrelationId(transaction);
    }
}
