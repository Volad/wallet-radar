package com.walletradar.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedTransactionTest {

    @Test
    @DisplayName("leg quantity sign convention: inbound positive, outbound negative")
    void legQuantitySignConvention() {
        NormalizedTransaction.Leg buy = new NormalizedTransaction.Leg();
        buy.setRole(NormalizedLegRole.BUY);
        buy.setQuantityDelta(new BigDecimal("1.25"));

        NormalizedTransaction.Leg sell = new NormalizedTransaction.Leg();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setQuantityDelta(new BigDecimal("-0.75"));

        assertThat(buy.getQuantityDelta().signum()).isPositive();
        assertThat(sell.getQuantityDelta().signum()).isNegative();
    }

    @Test
    @DisplayName("monetary leg fields are BigDecimal")
    void monetaryLegFieldsAreBigDecimal() {
        NormalizedTransaction.Leg leg = new NormalizedTransaction.Leg();
        leg.setUnitPriceUsd(new BigDecimal("1850.123456789"));
        leg.setValueUsd(new BigDecimal("925.0617283945"));
        leg.setAvcoAtTimeOfSale(new BigDecimal("1800.00"));
        leg.setRealisedPnlUsd(new BigDecimal("50.00"));

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setLegs(List.of(leg));

        assertThat(tx.getLegs()).hasSize(1);
        assertThat(tx.getLegs().get(0).getUnitPriceUsd()).isEqualByComparingTo("1850.123456789");
        assertThat(tx.getLegs().get(0).getValueUsd()).isEqualByComparingTo("925.0617283945");
        assertThat(tx.getLegs().get(0).getAvcoAtTimeOfSale()).isEqualByComparingTo("1800.00");
        assertThat(tx.getLegs().get(0).getRealisedPnlUsd()).isEqualByComparingTo("50.00");
    }
}
