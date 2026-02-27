package com.walletradar.costbasis.engine;

import com.walletradar.domain.EconomicEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AvcoEventTypeHelperTest {

    @Test
    @DisplayName("BUY types are SWAP_BUY, BORROW, STAKE_WITHDRAWAL, LEND_WITHDRAWAL, EXTERNAL_INBOUND, MANUAL_COMPENSATING")
    void buyTypes() {
        assertThat(AvcoEventTypeHelper.isBuyType(EconomicEventType.SWAP_BUY)).isTrue();
        assertThat(AvcoEventTypeHelper.isBuyType(EconomicEventType.BORROW)).isTrue();
        assertThat(AvcoEventTypeHelper.isBuyType(EconomicEventType.STAKE_WITHDRAWAL)).isTrue();
        assertThat(AvcoEventTypeHelper.isBuyType(EconomicEventType.SWAP_SELL)).isFalse();
        assertThat(AvcoEventTypeHelper.isBuyType(EconomicEventType.EXTERNAL_TRANSFER_OUT)).isFalse();
    }

    @Test
    @DisplayName("SELL types generate realised P&L")
    void sellTypes() {
        assertThat(AvcoEventTypeHelper.isSellType(EconomicEventType.SWAP_SELL)).isTrue();
        assertThat(AvcoEventTypeHelper.isSellType(EconomicEventType.LP_EXIT)).isTrue();
        assertThat(AvcoEventTypeHelper.isSellType(EconomicEventType.SWAP_BUY)).isFalse();
    }

    @Test
    @DisplayName("first event SELL or transfer-out sets hasIncompleteHistory")
    void firstEventIncomplete() {
        assertThat(AvcoEventTypeHelper.isFirstEventIncomplete(EconomicEventType.SWAP_SELL, new BigDecimal("-1"))).isTrue();
        assertThat(AvcoEventTypeHelper.isFirstEventIncomplete(EconomicEventType.EXTERNAL_TRANSFER_OUT, new BigDecimal("-1"))).isTrue();
        assertThat(AvcoEventTypeHelper.isFirstEventIncomplete(EconomicEventType.SWAP_BUY, new BigDecimal("1"))).isFalse();
    }
}
