package com.walletradar.application.normalization.pipeline.classification.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * Concentrated-liquidity tick/sqrtPrice math shared between the normalization classification pipeline
 * (V4 fee decomposer) and the LP enrichment layer.
 *
 * <p>Identical to {@link com.walletradar.application.liquiditypools.enrichment.LpLiquidityAmountsSupport}
 * but located in the classification support package so classification code can use it without creating
 * a {@code normalization → liquiditypools.enrichment} dependency edge (see ArchTest R4).
 */
public final class LpClMathSupport {

    private static final MathContext MC = MathContext.DECIMAL128;
    static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);
    private static final BigDecimal Q96_DECIMAL = new BigDecimal(Q96);

    private LpClMathSupport() {
    }

    public static BigInteger getSqrtRatioAtTick(int tick) {
        double ratio = Math.pow(1.0001, tick);
        BigDecimal sqrt = BigDecimal.valueOf(Math.sqrt(ratio));
        return sqrt.multiply(Q96_DECIMAL, MC).toBigInteger();
    }

    /**
     * Computes token0 and token1 principal amounts for the given liquidity at the given
     * sqrt price and tick range. Handles all three concentration cases:
     * <ul>
     *   <li>price below lower tick → all token0, no token1</li>
     *   <li>price above upper tick → no token0, all token1</li>
     *   <li>in-range → both token0 and token1</li>
     * </ul>
     */
    public static Amounts getAmountsForLiquidity(
            BigInteger sqrtRatioX96,
            int tickLower,
            int tickUpper,
            BigInteger liquidity
    ) {
        if (liquidity == null || liquidity.signum() <= 0) {
            return new Amounts(BigInteger.ZERO, BigInteger.ZERO);
        }
        BigInteger sqrtA = getSqrtRatioAtTick(tickLower);
        BigInteger sqrtB = getSqrtRatioAtTick(tickUpper);
        return getAmountsForLiquidity(sqrtRatioX96, sqrtA, sqrtB, liquidity);
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

    /**
     * Returns true when the given sqrtPriceX96 falls strictly within [sqrtA, sqrtB) of the
     * tick range — i.e. the position is in-range and holds both tokens.
     */
    public static boolean isInRange(BigInteger sqrtPriceX96, int tickLower, int tickUpper) {
        if (sqrtPriceX96 == null || sqrtPriceX96.signum() <= 0) {
            return false;
        }
        BigInteger sqrtA = getSqrtRatioAtTick(tickLower);
        BigInteger sqrtB = getSqrtRatioAtTick(tickUpper);
        return sqrtPriceX96.compareTo(sqrtA) > 0 && sqrtPriceX96.compareTo(sqrtB) < 0;
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

    public record Amounts(BigInteger amount0, BigInteger amount1) {
    }
}
