package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.costbasis.support.leverage.AaveDebtLoanCorrelationSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AaveDebtLoanCorrelationSupportTest {

    private static final String WALLET = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
    private static final String DEBT_CONTRACT = "0x0169Aae0bB8aF66Df6e95EFA92EC4f4aDcBc807c";

    @Test
    void borrowAndRepayOfSameAaveDebtShareDeterministicSyntheticLoanId() {
        NormalizedTransaction borrow = loanTransaction(
                NormalizedTransactionType.BORROW,
                debtLeg(new BigDecimal("2500")),
                underlyingLeg("USDE", new BigDecimal("2500"))
        );
        NormalizedTransaction repay = loanTransaction(
                NormalizedTransactionType.REPAY,
                debtLeg(new BigDecimal("-2500")),
                underlyingLeg("USDE", new BigDecimal("-2500"))
        );

        String borrowId = AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId(borrow);
        String repayId = AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId(repay);

        assertThat(borrowId)
                .isEqualTo("evm:MANTLE:" + DEBT_CONTRACT.toLowerCase() + ":" + WALLET.toLowerCase());
        assertThat(repayId).isEqualTo(borrowId);
    }

    @Test
    void syntheticLoanIdUsesEvmPrefixSoItCannotCollideWithBybitNumericOrderIds() {
        NormalizedTransaction borrow = loanTransaction(
                NormalizedTransactionType.BORROW,
                debtLeg(new BigDecimal("600")),
                underlyingLeg("USDC", new BigDecimal("600"))
        );
        String loanId = AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId(borrow);
        assertThat(loanId).startsWith(AaveDebtLoanCorrelationSupport.SYNTHETIC_LOAN_PREFIX);
        assertThat(loanId).doesNotMatch("\\d+");
    }

    @Test
    void returnsNullWhenNotALoanLifecycleOrNoDebtMarker() {
        NormalizedTransaction swap = loanTransaction(
                NormalizedTransactionType.SWAP,
                underlyingLeg("USDC", new BigDecimal("100"))
        );
        assertThat(AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId(swap)).isNull();

        NormalizedTransaction borrowNoDebtMarker = loanTransaction(
                NormalizedTransactionType.BORROW,
                underlyingLeg("USDC", new BigDecimal("600"))
        );
        assertThat(AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId(borrowNoDebtMarker)).isNull();
    }

    private static NormalizedTransaction loanTransaction(
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(type);
        transaction.setNetworkId(NetworkId.MANTLE);
        transaction.setWalletAddress(WALLET);
        transaction.setFlows(List.of(flows));
        return transaction;
    }

    private static NormalizedTransaction.Flow debtLeg(BigDecimal quantityDelta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("VARIABLEDEBTMANUSDE");
        flow.setAssetContract(DEBT_CONTRACT);
        flow.setQuantityDelta(quantityDelta);
        return flow;
    }

    private static NormalizedTransaction.Flow underlyingLeg(String symbol, BigDecimal quantityDelta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(quantityDelta.signum() >= 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(quantityDelta);
        return flow;
    }
}
