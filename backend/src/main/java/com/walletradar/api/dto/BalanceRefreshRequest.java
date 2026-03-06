package com.walletradar.api.dto;

import com.walletradar.api.validation.SupportedNetworks;
import com.walletradar.domain.common.NetworkId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * POST /api/v1/wallets/balances/refresh request body.
 */
public record BalanceRefreshRequest(
        @NotEmpty(message = "INVALID_REQUEST")
        List<@NotBlank(message = "INVALID_REQUEST") String> wallets,
        @SupportedNetworks List<NetworkId> networks
) {
}
