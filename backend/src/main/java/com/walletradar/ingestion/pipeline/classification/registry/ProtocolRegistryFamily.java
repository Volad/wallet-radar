package com.walletradar.ingestion.pipeline.classification.registry;

public enum ProtocolRegistryFamily {
    DEX,
    LENDING,
    STAKING,
    BRIDGE,
    CUSTODY,
    AGGREGATOR,
    YIELD,
    WRAPPER,
    PERP,
    /**
     * Cycle/9 S3: liquidity-pool contracts that mint a single fungible receipt token in exchange
     * for a basket of two or more underlying assets (Curve/Balancer stable pools, AAVE GHO/USDT/USDC
     * pool, etc.). Distinct from {@code DEX} swap pools.
     */
    LP
}
