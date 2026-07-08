package com.walletradar.application.liquiditypools.enrichment;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

record PoolLiquidityDepthState(
        int tickSpacing,
        BigInteger poolLiquidity,
        int currentTick,
        Map<Integer, BigInteger> liquidityNetByTick,
        Instant capturedAt
) {
}
