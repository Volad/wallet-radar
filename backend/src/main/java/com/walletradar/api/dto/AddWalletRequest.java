package com.walletradar.api.dto;

import com.walletradar.api.validation.SupportedNetworks;
import com.walletradar.api.validation.WalletAddress;
import com.walletradar.domain.NetworkId;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * POST /api/v1/wallets request body. Validated with Jakarta Bean Validation.
 * Empty or null networks = all supported networks (WalletBackfillService).
 */
public record AddWalletRequest(
        @NotBlank(message = "INVALID_ADDRESS")
        @WalletAddress
        String address,

        @SupportedNetworks
        List<NetworkId> networks
) {
}
