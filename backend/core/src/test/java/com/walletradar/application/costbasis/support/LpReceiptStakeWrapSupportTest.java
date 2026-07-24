package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-6 (ADR-047 addendum): staking an LP receipt into an Equilibria/Penpie booster is a non-realizing
 * wrap, not a sale. These tests pin the protocol-generic detection grammar.
 */
class LpReceiptStakeWrapSupportTest {

    @Test
    void detectsEquilibriaStakingDepositOfPendleLpReceipt() {
        NormalizedTransaction tx = stakingDeposit("Equilibria", null, transferLeg("PENDLE-LPT", "-0.445"));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isTrue();
    }

    @Test
    void detectsPenpieStakingDepositOfPendleLpReceipt() {
        NormalizedTransaction tx = stakingDeposit("Penpie", null, transferLeg("pnpPENDLE-LPT", "-1.0"));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isTrue();
    }

    @Test
    void detectsViaPendleLpCorrelationWhenProtocolNameAbsent() {
        NormalizedTransaction tx = stakingDeposit(
                null, "pendle-lp:mantle:pendle-lpt:0x1a87", transferLeg("PENDLE-LPT", "-0.445"));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isTrue();
    }

    @Test
    void detectsSymmetricStakingWithdrawUnwrap() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.STAKING_WITHDRAW);
        tx.setProtocolName("Equilibria");
        tx.setFlows(List.of(transferLeg("PENDLE-LPT", "0.445")));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isTrue();
    }

    @Test
    void ignoresPendleGovernanceTokenReward() {
        // Plain PENDLE is not an -LPT receipt: a claimed reward is income, not basis.
        NormalizedTransaction tx = stakingDeposit("Equilibria", null, transferLeg("PENDLE", "5.0"));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isFalse();
    }

    @Test
    void ignoresPlainLiquidStakingReceipt() {
        // ETH -> stETH liquid staking is a cross-canonical move, not an LP-receipt wrap.
        NormalizedTransaction tx = stakingDeposit("Lido", null, transferLeg("stETH", "-1.0"));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isFalse();
    }

    @Test
    void ignoresLpReceiptSaleClassifiedAsSwap() {
        // Negative case (rule 13): an LP receipt sold into a DEX router is a SWAP, a genuine sale.
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setProtocolName("Uniswap");
        NormalizedTransaction.Flow sell = new NormalizedTransaction.Flow();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetSymbol("PENDLE-LPT");
        sell.setQuantityDelta(new BigDecimal("-0.445"));
        tx.setFlows(List.of(sell));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isFalse();
    }

    @Test
    void ignoresLpExitSoThePoolBackedExitPathIsNeverHijacked() {
        // Regression guard: the zap-out LP_EXIT must keep flowing through the pool-backed exit path.
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LP_EXIT);
        tx.setProtocolName("Equilibria");
        tx.setCorrelationId("pendle-lp:mantle:pendle-lpt:0x1a87");
        tx.setFlows(List.of(transferLeg("eqbPENDLE-LPT", "-0.445")));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isFalse();
    }

    @Test
    void ignoresStakingDepositWithoutBoosterContext() {
        // No Equilibria/Penpie protocol and no LP correlation: not recognised as a booster wrap.
        NormalizedTransaction tx = stakingDeposit(null, null, transferLeg("PENDLE-LPT", "-0.445"));
        assertThat(LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg(tx, tx.getFlows().getFirst()))
                .isFalse();
    }

    private static NormalizedTransaction stakingDeposit(
            String protocolName,
            String correlationId,
            NormalizedTransaction.Flow flow
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setProtocolName(protocolName);
        tx.setCorrelationId(correlationId);
        tx.setFlows(List.of(flow));
        return tx;
    }

    private static NormalizedTransaction.Flow transferLeg(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
