package com.walletradar.application.normalization.pipeline.classification.support;

import java.math.BigInteger;

/**
 * Raw decoded amounts from {@code DecreaseLiquidity} + {@code Collect} events for a V3/Slipstream
 * LP exit. Amounts are in the smallest token unit (wei, satoshi, etc.) with no decimal scaling.
 *
 * <p>Slot ordering (0 / 1) matches the pool's internal token0/token1 canonical order and is
 * preserved through to {@link LpExitFeeDecomposer#feeFractionsForContracts}.
 */
public record LpExitFeeAmounts(
        BigInteger principalRaw0,
        BigInteger principalRaw1,
        BigInteger totalRaw0,
        BigInteger totalRaw1
) {

    public BigInteger feeRaw0() {
        if (totalRaw0 == null || principalRaw0 == null) {
            return BigInteger.ZERO;
        }
        BigInteger fee = totalRaw0.subtract(principalRaw0);
        return fee.signum() < 0 ? BigInteger.ZERO : fee;
    }

    public BigInteger feeRaw1() {
        if (totalRaw1 == null || principalRaw1 == null) {
            return BigInteger.ZERO;
        }
        BigInteger fee = totalRaw1.subtract(principalRaw1);
        return fee.signum() < 0 ? BigInteger.ZERO : fee;
    }

    public boolean hasFee() {
        return feeRaw0().signum() > 0 || feeRaw1().signum() > 0;
    }
}
