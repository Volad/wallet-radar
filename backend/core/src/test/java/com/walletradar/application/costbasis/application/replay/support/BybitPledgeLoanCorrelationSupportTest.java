package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BybitPledgeLoanCorrelationSupportTest {

    private static final String UTA_WALLET = "BYBIT:33625378:UTA";

    @Test
    void borrowAndRepayOfSameBybitPledgeAssetShareDeterministicRevolvingKey() {
        // Different per-row correlation ids (UUID on borrow, server loan id on repay) — the real
        // shape that previously prevented liability matching.
        NormalizedTransaction borrow = pledge(
                NormalizedTransactionType.BORROW,
                "BYBIT-33625378:TRANSACTION_LOG:33625378-109167781-819ff960-bce9-4721-a175-7638789eeed3",
                flow("MNT", new BigDecimal("150"))
        );
        NormalizedTransaction repay = pledge(
                NormalizedTransactionType.REPAY,
                "BYBIT-33625378:TRANSACTION_LOG:33625378-109167781-uta_pledge-loan-server_1910732310958247939",
                flow("MNT", new BigDecimal("-150"))
        );

        String borrowId = BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(borrow, borrow.getFlows().getFirst());
        String repayId = BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(repay, repay.getFlows().getFirst());

        assertThat(borrowId).isEqualTo("bybit-pledge:33625378:MNT");
        assertThat(repayId).isEqualTo(borrowId);
    }

    @Test
    void differentAssetsGetDistinctRevolvingKeysSoMntAndDogsNeverCrossMatch() {
        NormalizedTransaction mnt = pledge(NormalizedTransactionType.BORROW, "x", flow("MNT", new BigDecimal("150")));
        NormalizedTransaction dogs = pledge(NormalizedTransactionType.BORROW, "y", flow("DOGS", new BigDecimal("1300000")));

        assertThat(BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(mnt, mnt.getFlows().getFirst()))
                .isEqualTo("bybit-pledge:33625378:MNT");
        assertThat(BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(dogs, dogs.getFlows().getFirst()))
                .isEqualTo("bybit-pledge:33625378:DOGS");
    }

    @Test
    void prefixIsDisjointFromOnChainEvmAndRawNumericOrderIds() {
        NormalizedTransaction borrow = pledge(NormalizedTransactionType.BORROW, "x", flow("MNT", new BigDecimal("150")));
        String loanId = BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(borrow, borrow.getFlows().getFirst());
        assertThat(loanId).startsWith(BybitPledgeLoanCorrelationSupport.SYNTHETIC_LOAN_PREFIX);
        assertThat(loanId).doesNotStartWith("evm:");
        assertThat(loanId).doesNotMatch("\\d+");
    }

    @Test
    void returnsNullForNonBybitSourceSoOnChainLoansAreUnaffected() {
        NormalizedTransaction onChain = pledge(NormalizedTransactionType.BORROW, "evm:BASE:0xabc:0xdef", flow("MNT", new BigDecimal("150")));
        onChain.setSource(NormalizedTransactionSource.ON_CHAIN);
        onChain.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        onChain.getFlows().getFirst().setAccountRef("evm:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(onChain, onChain.getFlows().getFirst()))
                .isNull();
    }

    @Test
    void returnsNullForNonLoanLifecycleType() {
        NormalizedTransaction swap = pledge(NormalizedTransactionType.SWAP, "x", flow("MNT", new BigDecimal("150")));
        assertThat(BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(swap, swap.getFlows().getFirst()))
                .isNull();
    }

    @Test
    void prefersFlowAccountRefUidWhenWalletAddressIsAbsent() {
        NormalizedTransaction borrow = pledge(NormalizedTransactionType.BORROW, "x", flow("MNT", new BigDecimal("150")));
        borrow.setWalletAddress(null);
        borrow.getFlows().getFirst().setAccountRef("BYBIT:99999999:UTA");
        assertThat(BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(borrow, borrow.getFlows().getFirst()))
                .isEqualTo("bybit-pledge:99999999:MNT");
    }

    private static NormalizedTransaction pledge(
            NormalizedTransactionType type,
            String correlationId,
            NormalizedTransaction.Flow flow
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(type);
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setWalletAddress(UTA_WALLET);
        transaction.setCorrelationId(correlationId);
        transaction.setFlows(List.of(flow));
        return transaction;
    }

    private static NormalizedTransaction.Flow flow(String symbol, BigDecimal quantityDelta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(quantityDelta.signum() >= 0 ? NormalizedLegRole.BUY : NormalizedLegRole.SELL);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(quantityDelta);
        flow.setAccountRef(UTA_WALLET);
        return flow;
    }
}
