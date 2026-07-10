package com.walletradar.application.cex.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Runtime settings for Dzengi integration fetch and authentication.
 */
@ConfigurationProperties(prefix = "walletradar.integration.dzengi")
@Getter
@Setter
public class DzengiIntegrationProperties {

    private String baseUrl = "https://api-adapter.dzengi.com";
    private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private long recvWindowMs = 5_000L;
    private long requestTimeoutMs = 20_000L;
    private long liveBalanceRefreshIntervalMs = 300_000L;
    private int ledgerWindowDays = 30;
    private int depositsWithdrawalsWindowDays = 30;
    private int tradingPositionsWindowDays = 30;
    private int pageLimit = 100;
    /**
     * Dzengi {@code /myTrades} returns only the latest fills for a symbol: {@code symbol} is
     * mandatory, {@code limit} is capped at 100, and there is NO paging ({@code startTime}/
     * {@code endTime} were removed by Dzengi and {@code fromId} is ignored). We therefore probe
     * one segment per symbol and can only recover the last 100 fills per symbol.
     *
     * <p>{@code myTradesQuoteAssets} optionally restricts the probed universe to these quote
     * assets. Empty (default) means all non-leverage spot symbols are probed regardless of quote,
     * so crypto pairs quoted in BTC/EUR/GBP and fiat FX pairs are not missed.
     */
    private List<String> myTradesQuoteAssets = List.of();
    /** Hard API cap for {@code /myTrades} is 100; larger values are rejected with {@code -1128}. */
    private int myTradesMaxResults = 100;
    /**
     * Emergency override: extra v2 equity symbols to probe via {@code MY_TRADES_V2} regardless of
     * dynamic discovery. Leave empty in production — symbols are discovered automatically from
     * (a) Dzengi exchangeInfo (equity/commodity/index instruments), (b) trading position history,
     * and (c) live balance umbrella (any symbol with qty &gt; 0 in {@code dzengi_live_balances}).
     */
    private List<String> myTradesV2AdditionalSymbols = List.of();
    private int historyClampSafetyMinutes = 5;
}
