package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.pricing.application.PriceableFlowPolicy;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-6 (ADR-047 addendum): an LP-receipt leg on a STAKING_DEPOSIT / STAKING_WITHDRAW is a
 * non-realizing wrap, so it must be a TRANSFER (never SELL/BUY) and be excluded from market pricing.
 */
class StakeWrapLegRoleTest {

    @Test
    void stakingDepositLpReceiptLegBecomesTransferNotSell() {
        List<NormalizedTransaction.Flow> flows = OnChainClassificationSupport.toFlows(
                List.of(RawLeg.asset("0x6304ccbda63a7fb94919c705de54f9889f3ce047", "PENDLE-LPT",
                        new BigDecimal("-0.445041029858104302"))),
                NormalizedTransactionType.STAKING_DEPOSIT
        );

        assertThat(flows).hasSize(1);
        assertThat(flows.getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void stakingWithdrawWrappedLpReceiptLegBecomesTransfer() {
        List<NormalizedTransaction.Flow> flows = OnChainClassificationSupport.toFlows(
                List.of(RawLeg.asset("0xwrapper", "eqbPENDLE-LPT", new BigDecimal("0.445"))),
                NormalizedTransactionType.STAKING_WITHDRAW
        );

        assertThat(flows.getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void swapLpReceiptSaleStaysSell() {
        // Negative case (rule 13): an LP receipt sold into a DEX router is a genuine sale.
        List<NormalizedTransaction.Flow> flows = OnChainClassificationSupport.toFlows(
                List.of(
                        RawLeg.asset("0x6304ccbda63a7fb94919c705de54f9889f3ce047", "PENDLE-LPT",
                                new BigDecimal("-0.445")),
                        RawLeg.asset("0xunrelated", "USDC", new BigDecimal("100"))
                ),
                NormalizedTransactionType.SWAP
        );

        assertThat(flows)
                .filteredOn(flow -> "PENDLE-LPT".equals(flow.getAssetSymbol()))
                .extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.SELL);
    }

    @Test
    void stakingDepositLpReceiptTransferLegIsNotPriced() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        NormalizedTransaction.Flow transferLeg = new NormalizedTransaction.Flow();
        transferLeg.setRole(NormalizedLegRole.TRANSFER);
        transferLeg.setAssetSymbol("PENDLE-LPT");
        transferLeg.setQuantityDelta(new BigDecimal("-0.445"));
        tx.setFlows(List.of(transferLeg));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, transferLeg)).isFalse();
    }

    @Test
    void stakingDepositLpReceiptSellLegWouldBePriced() {
        // Confirms the defect basis: a SELL-role LP receipt leg IS priced (produces phantom proceeds).
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        NormalizedTransaction.Flow sellLeg = new NormalizedTransaction.Flow();
        sellLeg.setRole(NormalizedLegRole.SELL);
        sellLeg.setAssetSymbol("PENDLE-LPT");
        sellLeg.setQuantityDelta(new BigDecimal("-0.445"));
        tx.setFlows(List.of(sellLeg));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, sellLeg)).isTrue();
    }
}
