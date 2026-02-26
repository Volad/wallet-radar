package com.walletradar.api.dto;

import com.walletradar.api.validation.SupportedNetworks;
import com.walletradar.domain.NetworkId;

import java.util.List;

/**
 * POST /api/v1/wallets/balances/refresh request body.
 */
public record BalanceRefreshRequest(
        List<String> wallets,
        @SupportedNetworks List<NetworkId> networks
) {
}

