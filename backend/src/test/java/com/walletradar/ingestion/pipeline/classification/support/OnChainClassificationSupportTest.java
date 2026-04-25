package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnChainClassificationSupportTest {

    @Test
    void vaultWithdrawSplitsPrincipalCarryFromExcessAcquisition() {
        List<NormalizedTransaction.Flow> flows = OnChainClassificationSupport.toFlows(
                List.of(
                        RawLeg.asset("0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc", "eUSDC-6", new BigDecimal("-975.179422")),
                        RawLeg.asset("0xaf88d065e77c8cc2239327c5edb3a432268e5831", "USDC", new BigDecimal("1006.08101"))
                ),
                NormalizedTransactionType.VAULT_WITHDRAW
        );

        assertThat(flows).hasSize(3);
        assertThat(flows.get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(flows.get(0).getQuantityDelta()).isEqualByComparingTo("-975.179422");
        assertThat(flows.get(1).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(flows.get(1).getQuantityDelta()).isEqualByComparingTo("975.179422");
        assertThat(flows.get(2).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(flows.get(2).getQuantityDelta()).isEqualByComparingTo("30.901588");
    }

    @Test
    void lendingWithdrawNetsReceiptLegsBeforeMaterializingExcess() {
        List<NormalizedTransaction.Flow> flows = OnChainClassificationSupport.toFlows(
                List.of(
                        RawLeg.asset("0x6d80113e533a2c0fe82eabd35f1875dcea89ea97", "aAvaWAVAX", new BigDecimal("0.001156558399805176")),
                        RawLeg.asset("0x6d80113e533a2c0fe82eabd35f1875dcea89ea97", "aAvaWAVAX", new BigDecimal("-2.348678343097429938")),
                        RawLeg.nativeAsset("AVAX", new BigDecimal("2.348678343097429938"))
                ),
                NormalizedTransactionType.LENDING_WITHDRAW
        );

        assertThat(flows).hasSize(3);
        assertThat(flows.get(0).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(flows.get(0).getQuantityDelta()).isEqualByComparingTo("-2.347521784697624762");
        assertThat(flows.get(1).getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(flows.get(1).getQuantityDelta()).isEqualByComparingTo("2.347521784697624762");
        assertThat(flows.get(2).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(flows.get(2).getQuantityDelta()).isEqualByComparingTo("0.001156558399805176");
    }
}
