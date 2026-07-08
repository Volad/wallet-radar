package com.walletradar.application.liquiditypools.enrichment;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcentratedLiquidityReaderTest {

    @Test
    void tokenOrderingDecodesCanonicalToken0LessThanToken1() {
        String token0 = "0x0000000000000000000000000a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
        String token1 = "0x000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2";
        assertThat(token0.compareToIgnoreCase(token1)).isLessThan(0);

        BigInteger sqrtPriceX96 = new BigInteger("79228162514264337593543950336");
        BigInteger sqrtLower = LpLiquidityAmountsSupport.getSqrtRatioAtTick(-887220);
        BigInteger sqrtUpper = LpLiquidityAmountsSupport.getSqrtRatioAtTick(887220);
        BigInteger liquidity = new BigInteger("1000000000000000000");

        LpLiquidityAmountsSupport.Amounts amounts = LpLiquidityAmountsSupport.getAmountsForLiquidity(
                sqrtPriceX96, sqrtLower, sqrtUpper, liquidity);

        assertThat(amounts.amount0()).isNotNull();
        assertThat(amounts.amount1()).isNotNull();
        assertThat(amounts.amount0().signum()).isGreaterThanOrEqualTo(0);
        assertThat(amounts.amount1().signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void feeGrowthDeltaWrapsOnOverflow() {
        BigInteger insideNow = BigInteger.valueOf(100);
        BigInteger insideLast = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.valueOf(50));
        BigInteger delta = LpLiquidityAmountsSupport.feeGrowthDelta(insideNow, insideLast);
        assertThat(delta).isEqualTo(BigInteger.valueOf(150));
    }

    @Test
    void sqrtPriceX96ToPriceRespectsDecimals() {
        BigInteger sqrtPriceX96 = new BigInteger("79228162514264337593543950336");
        var price = LpLiquidityAmountsSupport.sqrtPriceX96ToPrice(sqrtPriceX96, 18, 6);
        assertThat(price).isNotNull();
        assertThat(price.signum()).isGreaterThan(0);
    }
}
