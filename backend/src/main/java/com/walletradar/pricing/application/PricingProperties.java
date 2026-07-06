package com.walletradar.pricing.application;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the pricing stage.
 */
@ConfigurationProperties(prefix = "walletradar.pricing")
@NoArgsConstructor
@Getter
@Setter
public class PricingProperties {

    private boolean enabled = false;

    private int batchSize = 1000;

    private int parallelLanes = 4;

    private int quoteResolveParallelLanes = 16;

    private long scheduleIntervalMs = 120_000L;

    private long retryDelaySeconds = 120L;

    /**
     * Cycle/11 S1: settings for {@code BRIDGE_OUT} upstream-basis fallback repricing.
     */
    private BridgeOut bridgeOut = new BridgeOut();

    private External external = new External();

    @NoArgsConstructor
    @Getter
    @Setter
    public static class BridgeOut {

        /**
         * How far before a {@code BRIDGE_OUT} timestamp to search for a priced positive inflow
         * on the same wallet/network/asset family.
         */
        private int upstreamLookbackHours = 24;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class External {

        private long requestTimeoutMs = 10_000L;

        private Bybit bybit = new Bybit();

        private Ecb ecb = new Ecb();

        private Binance binance = new Binance();

        private CoinGecko coinGecko = new CoinGecko();
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Bybit {

        private String baseUrl = "https://api.bybit.com";
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Ecb {

        private String baseUrl = "https://data-api.ecb.europa.eu";
        private int backfillDays = 7;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Binance {

        private String baseUrl = "https://api.binance.com";
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class CoinGecko {

        private String baseUrl = "https://api.coingecko.com/api/v3";
    }
}
