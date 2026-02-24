package com.walletradar.api.dto;

import com.walletradar.api.validation.SupportedNetworks;
import com.walletradar.api.validation.WalletAddress;
import com.walletradar.domain.NetworkId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * POST /api/v1/wallets request body. Validated with Jakarta Bean Validation.
 */
public record AddWalletRequest(
        @NotBlank(message = "INVALID_ADDRESS")
        @WalletAddress
        String address,

        @NotEmpty(message = "INVALID_NETWORK")
        @SupportedNetworks
        List<NetworkId> networks
) {
}
