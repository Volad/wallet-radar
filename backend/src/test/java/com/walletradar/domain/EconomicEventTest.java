package com.walletradar.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicEventTest {

    @Test
    @DisplayName("quantityDelta sign: positive = received, negative = sent")
    void quantityDeltaSignConvention() {
        EconomicEvent buy = new EconomicEvent();
        buy.setQuantityDelta(new BigDecimal("1.5"));
        buy.setEventType(EconomicEventType.SWAP_BUY);
        assertThat(buy.getQuantityDelta().signum()).isPositive();

        EconomicEvent sell = new EconomicEvent();
        sell.setQuantityDelta(new BigDecimal("-0.5"));
        sell.setEventType(EconomicEventType.SWAP_SELL);
        assertThat(sell.getQuantityDelta().signum()).isNegative();
    }

    @Test
    @DisplayName("monetary fields are BigDecimal")
    void monetaryFieldsAreBigDecimal() {
        EconomicEvent event = new EconomicEvent();
        event.setTotalValueUsd(new BigDecimal("100.50"));
        event.setGasCostUsd(new BigDecimal("2.30"));
        event.setPriceUsd(new BigDecimal("2000.00"));
        event.setRealisedPnlUsd(new BigDecimal("50.25"));
        event.setAvcoAtTimeOfSale(new BigDecimal("1950.00"));

        assertThat(event.getTotalValueUsd()).isEqualByComparingTo("100.50");
        assertThat(event.getGasCostUsd()).isEqualByComparingTo("2.30");
        assertThat(event.getPriceUsd()).isEqualByComparingTo("2000.00");
        assertThat(event.getRealisedPnlUsd()).isEqualByComparingTo("50.25");
        assertThat(event.getAvcoAtTimeOfSale()).isEqualByComparingTo("1950.00");
    }

    @Test
    @DisplayName("manual compensating event has no txHash")
    void manualCompensatingHasNoTxHash() {
        EconomicEvent manual = new EconomicEvent();
        manual.setEventType(EconomicEventType.MANUAL_COMPENSATING);
        manual.setTxHash(null);
        manual.setClientId("client-uuid-123");
        manual.setQuantityDelta(new BigDecimal("10"));
        manual.setBlockTimestamp(Instant.now());

        assertThat(manual.getTxHash()).isNull();
        assertThat(manual.getClientId()).isEqualTo("client-uuid-123");
    }
}
