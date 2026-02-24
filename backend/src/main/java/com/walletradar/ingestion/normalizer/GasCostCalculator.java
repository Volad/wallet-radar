package com.walletradar.ingestion.normalizer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Computes gas cost in USD: gasUsed × gasPriceWei × nativeTokenPriceUsd / 1e18 (03-accounting).
 * Native price is provided by the caller (from price resolver chain).
 */
@Component
public class GasCostCalculator {

    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1e18");

    /**
     * Compute gas cost in USD.
     *
     * @param gasUsed           gas used (EVM)
     * @param gasPriceWei       gas price in wei
     * @param nativePriceUsd    native token (e.g. ETH) price in USD at block time
     * @return gas cost in USD, or zero if any argument is null/zero
     */
    public BigDecimal gasCostUsd(Long gasUsed, BigInteger gasPriceWei, BigDecimal nativePriceUsd) {
        if (gasUsed == null || gasUsed <= 0
                || gasPriceWei == null || gasPriceWei.signum() <= 0
                || nativePriceUsd == null || nativePriceUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal used = BigDecimal.valueOf(gasUsed);
        BigDecimal priceWei = new BigDecimal(gasPriceWei);
        return used.multiply(priceWei).multiply(nativePriceUsd).divide(WEI_PER_ETH, 18, RoundingMode.HALF_UP);
    }
}
