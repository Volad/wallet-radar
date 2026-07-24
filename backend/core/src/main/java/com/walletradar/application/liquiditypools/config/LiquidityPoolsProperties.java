package com.walletradar.application.liquiditypools.config;

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

    /** Solana LP on-chain enrichment sources (Meteora DLMM, Raydium CLMM). */
    private Solana solana = new Solana();

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Solana {
        private boolean enabled = true;
        /** Meteora DLMM public data API base URL (pool metadata: mints, symbols, price, bin step). */
        private String meteoraBaseUrl = "https://dlmm.datapi.meteora.ag";
        /** Raydium v3 public API base URL (CLMM pool metadata by pool id). */
        private String raydiumBaseUrl = "https://api-v3.raydium.io";
        /** Per-request HTTP timeout for the Meteora/Raydium REST calls. */
        private long timeoutMs = 8_000L;
    }
}
