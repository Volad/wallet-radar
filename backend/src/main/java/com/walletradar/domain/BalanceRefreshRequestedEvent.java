package com.walletradar.domain;

import java.util.List;

/**
 * Application event: request immediate current-balance refresh for a wallet and network set.
 * Triggered on wallet add and consumed asynchronously by balance refresh listener.
 */
public record BalanceRefreshRequestedEvent(String walletAddress, List<NetworkId> networks) {
}

