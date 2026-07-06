package com.walletradar.liquiditypools.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "walletradar.liquidity-pools")
@NoArgsConstructor
@Getter
@Setter
public class LiquidityPoolsProperties {

    private boolean enabled = true;
    private long refreshIntervalMs = 3_600_000L;
    private long onDemandDebounceMs = 20_000L;
    private int staleMultiplier = 2;
    private BigDecimal dustThresholdUsd = new BigDecimal("10");
    /** TTL for pool-level liquidity depth cache (histogram RPC scan). */
    private long depthIntervalMs = 21_600_000L;
}
