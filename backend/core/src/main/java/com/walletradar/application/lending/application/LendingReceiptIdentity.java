package com.walletradar.application.lending.application;

public record LendingReceiptIdentity(
        String protocol,
        String underlyingSymbol,
        String side,
        String source
) {
}
