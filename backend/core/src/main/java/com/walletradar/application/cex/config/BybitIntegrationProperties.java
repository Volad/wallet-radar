package com.walletradar.application.cex.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for Bybit integration fetch and authentication.
 */
@ConfigurationProperties(prefix = "walletradar.integration.bybit")
@Getter
@Setter
public class BybitIntegrationProperties {

    private String baseUrl = "https://api.bybit.com";
    private long recvWindowMs = 10_000L;
    private long requestTimeoutMs = 15_000L;
    private long liveBalanceRefreshIntervalMs = 300_000L;
    private int transactionLogWindowDays = 7;
    private int executionWindowDays = 7;
    private int fundingHistoryWindowDays = 7;
    private int transferWindowDays = 7;
    private int depositWithdrawalWindowDays = 30;
    private int convertWindowDays = 30;
    private int earnWindowDays = 7;
    private int pageLimit = 50;
    private int historyClampSafetyMinutes = 5;
}
