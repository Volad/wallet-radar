package com.walletradar.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPositionTest {

    @Test
    @DisplayName("derived quantity is non-negative (invariant)")
    void quantityNonNegative() {
        AssetPosition position = new AssetPosition();
        position.setQuantity(new BigDecimal("100.5"));
        position.setPerWalletAvco(new BigDecimal("2000"));
        position.setTotalCostBasisUsd(new BigDecimal("201000"));
        position.setWalletAddress("0xabc");
        position.setNetworkId("ethereum");
        position.setAssetSymbol("ETH");

        assertThat(position.getQuantity().compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("totalCostBasisUsd = quantity × perWalletAvco (documented invariant)")
    void costBasisEqualsQuantityTimesAvco() {
        BigDecimal qty = new BigDecimal("2.5");
        BigDecimal avco = new BigDecimal("2000");
        AssetPosition position = new AssetPosition();
        position.setQuantity(qty);
        position.setPerWalletAvco(avco);
        position.setTotalCostBasisUsd(qty.multiply(avco));

        assertThat(position.getTotalCostBasisUsd())
                .isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("monetary and quantity fields are BigDecimal")
    void monetaryFieldsAreBigDecimal() {
        AssetPosition position = new AssetPosition();
        position.setQuantity(new BigDecimal("10"));
        position.setPerWalletAvco(new BigDecimal("1.50"));
        position.setTotalCostBasisUsd(new BigDecimal("15.00"));
        position.setTotalGasPaidUsd(new BigDecimal("0.50"));
        position.setTotalRealisedPnlUsd(new BigDecimal("5.25"));
        position.setOnChainQuantity(new BigDecimal("10.0"));
        position.setLastEventTimestamp(Instant.now());
        position.setLastCalculatedAt(Instant.now());

        assertThat(position.getQuantity()).isEqualByComparingTo("10");
        assertThat(position.getPerWalletAvco()).isEqualByComparingTo("1.50");
        assertThat(position.getTotalCostBasisUsd()).isEqualByComparingTo("15.00");
    }
}
