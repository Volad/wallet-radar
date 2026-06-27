package com.walletradar.liquiditypools.enrichment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Uniswap V3-style concentrated liquidity amount math.
 */
public final class LpLiquidityAmountsSupport {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);
    private static final BigDecimal Q96_DECIMAL = new BigDecimal(Q96);

    private LpLiquidityAmountsSupport() {
    }

    public static BigDecimal sqrtPriceX96ToPrice(BigInteger sqrtPriceX96, int decimals0, int decimals1) {
        if (sqrtPriceX96 == null || sqrtPriceX96.signum() <= 0) {
            return null;
        }
        BigDecimal sqrtRatio = new BigDecimal(sqrtPriceX96).divide(Q96_DECIMAL, MC);
        BigDecimal ratio = sqrtRatio.multiply(sqrtRatio, MC);
        int decimalAdjust = decimals0 - decimals1;
        if (decimalAdjust > 0) {
            ratio = ratio.multiply(BigDecimal.TEN.pow(decimalAdjust), MC);
        } else if (decimalAdjust < 0) {
            ratio = ratio.divide(BigDecimal.TEN.pow(-decimalAdjust), MC);
        }
        return ratio;
    }

    public static BigInteger getSqrtRatioAtTick(int tick) {
        double ratio = Math.pow(1.0001, tick);
        BigDecimal sqrt = BigDecimal.valueOf(Math.sqrt(ratio));
        return sqrt.multiply(Q96_DECIMAL, MC).toBigInteger();
    }

    public static Amounts getAmountsForLiquidity(
            BigInteger sqrtRatioX96,
            BigInteger sqrtRatioAX96,
            BigInteger sqrtRatioBX96,
            BigInteger liquidity
    ) {
        if (liquidity == null || liquidity.signum() <= 0) {
            return new Amounts(BigInteger.ZERO, BigInteger.ZERO);
        }
        BigInteger lower = sqrtRatioAX96.min(sqrtRatioBX96);
        BigInteger upper = sqrtRatioAX96.max(sqrtRatioBX96);
        BigInteger amount0;
        BigInteger amount1;
        if (sqrtRatioX96.compareTo(lower) <= 0) {
            amount0 = getAmount0ForLiquidity(lower, upper, liquidity);
            amount1 = BigInteger.ZERO;
        } else if (sqrtRatioX96.compareTo(upper) < 0) {
            amount0 = getAmount0ForLiquidity(sqrtRatioX96, upper, liquidity);
            amount1 = getAmount1ForLiquidity(lower, sqrtRatioX96, liquidity);
        } else {
            amount0 = BigInteger.ZERO;
            amount1 = getAmount1ForLiquidity(lower, upper, liquidity);
        }
        return new Amounts(amount0, amount1);
    }

    public static BigInteger getAmount0ForLiquidity(BigInteger sqrtRatioAX96, BigInteger sqrtRatioBX96, BigInteger liquidity) {
        if (sqrtRatioAX96.compareTo(sqrtRatioBX96) > 0) {
            BigInteger tmp = sqrtRatioAX96;
            sqrtRatioAX96 = sqrtRatioBX96;
            sqrtRatioBX96 = tmp;
        }
        BigInteger numerator = liquidity.shiftLeft(96).multiply(sqrtRatioBX96.subtract(sqrtRatioAX96));
        BigInteger denominator = sqrtRatioBX96.multiply(sqrtRatioAX96);
        if (denominator.signum() == 0) {
            return BigInteger.ZERO;
        }
        return numerator.divide(denominator);
    }

    public static BigInteger getAmount1ForLiquidity(BigInteger sqrtRatioAX96, BigInteger sqrtRatioBX96, BigInteger liquidity) {
        if (sqrtRatioAX96.compareTo(sqrtRatioBX96) > 0) {
            BigInteger tmp = sqrtRatioAX96;
            sqrtRatioAX96 = sqrtRatioBX96;
            sqrtRatioBX96 = tmp;
        }
        return liquidity.multiply(sqrtRatioBX96.subtract(sqrtRatioAX96)).shiftRight(96);
    }

    public static BigDecimal toHuman(BigInteger raw, int decimals) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw).divide(BigDecimal.TEN.pow(decimals), MC);
    }

    public static BigInteger feeGrowthDelta(BigInteger insideNow, BigInteger insideLast) {
        if (insideNow == null || insideLast == null) {
            return BigInteger.ZERO;
        }
        if (insideNow.compareTo(insideLast) >= 0) {
            return insideNow.subtract(insideLast);
        }
        return BigInteger.ONE.shiftLeft(256).subtract(insideLast).add(insideNow);
    }

    public static BigDecimal unclaimedFeesUsd(
            BigInteger feeGrowthDelta,
            BigInteger liquidity,
            int decimals,
            BigDecimal priceUsd
    ) {
        if (feeGrowthDelta == null || liquidity == null || priceUsd == null || priceUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigInteger rawFees = feeGrowthDelta.multiply(liquidity).shiftRight(128);
        BigDecimal human = toHuman(rawFees, decimals);
        return human.multiply(priceUsd, MC);
    }

    public record Amounts(BigInteger amount0, BigInteger amount1) {
    }
}
