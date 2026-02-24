package com.walletradar.ingestion.normalizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GasCostCalculatorTest {

    private GasCostCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new GasCostCalculator();
    }

    @Test
    void gasCostUsd_returnsZero_whenGasUsedNull() {
        BigDecimal result = calculator.gasCostUsd(null, BigInteger.valueOf(20_000_000_000L), BigDecimal.valueOf(2000));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void gasCostUsd_returnsZero_whenNativePriceZero() {
        BigDecimal result = calculator.gasCostUsd(21000L, BigInteger.valueOf(20_000_000_000L), BigDecimal.ZERO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void gasCostUsd_withStubPrice_computesCorrectly() {
        // 21000 gas * 20 Gwei * $2000/ETH = 21000 * 20e9 * 2000 / 1e18 = 0.00084 ETH * 2000 = $0.84
        BigDecimal result = calculator.gasCostUsd(
                21_000L,
                BigInteger.valueOf(20_000_000_000L),
                BigDecimal.valueOf(2000));

        assertThat(result).isEqualByComparingTo("0.84");
    }

    @Test
    void gasCostUsd_largeGas_computesCorrectly() {
        // 300000 gas * 50 Gwei * $2500 = 300000 * 50e9 * 2500 / 1e18 = 37.5 USD
        BigDecimal result = calculator.gasCostUsd(
                300_000L,
                BigInteger.valueOf(50_000_000_000L),
                BigDecimal.valueOf(2500));

        assertThat(result).isEqualByComparingTo("37.5");
    }
}
