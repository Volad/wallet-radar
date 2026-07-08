package com.walletradar.application.cex.acquisition.venue.bybit;

public enum BybitIntegrationStream {
    TRANSACTION_LOG,
    EXECUTION_LINEAR("linear"),
    EXECUTION_INVERSE("inverse"),
    EXECUTION_SPOT("spot"),
    EXECUTION_OPTION("option"),
    FUNDING_HISTORY,
    INTERNAL_TRANSFER,
    UNIVERSAL_TRANSFER,
    DEPOSIT_ONCHAIN,
    DEPOSIT_INTERNAL,
    WITHDRAWAL,
    CONVERT_HISTORY,
    EARN_FLEXIBLE_SAVING("FlexibleSaving");

    private final String category;

    BybitIntegrationStream() {
        this.category = null;
    }

    BybitIntegrationStream(String category) {
        this.category = category;
    }

    public String category() {
        return category;
    }
}
