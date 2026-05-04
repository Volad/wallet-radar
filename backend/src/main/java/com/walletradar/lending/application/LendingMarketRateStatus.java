package com.walletradar.lending.application;

public final class LendingMarketRateStatus {

    public static final String PROTOCOL_SNAPSHOT = "PROTOCOL_SNAPSHOT";
    public static final String API_SNAPSHOT = "API_SNAPSHOT";
    public static final String FALLBACK_ESTIMATE = "FALLBACK_ESTIMATE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String STALE = "STALE";
    public static final String PER_SECOND_COMPOUNDING = "PER_SECOND_COMPOUNDING";
    public static final String REWARDS_COLLECTOR_NOT_IMPLEMENTED = "REWARDS_COLLECTOR_NOT_IMPLEMENTED";

    private LendingMarketRateStatus() {
    }
}
