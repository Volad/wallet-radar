package com.walletradar.api.dto;

import com.walletradar.api.validation.EvmWalletAddress;
import com.walletradar.api.validation.HexColor;
import com.walletradar.api.validation.SessionId;
import com.walletradar.api.validation.SupportedEvmNetworks;
import com.walletradar.domain.common.NetworkId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * POST /api/v1/sessions request body.
 */
public record AddSessionRequest(
        @NotBlank(message = "INVALID_SESSION_ID")
        @SessionId
        String sessionId,

        @NotEmpty(message = "INVALID_REQUEST")
        @Valid
        List<WalletEntry> wallets
) {
    public record WalletEntry(
            @NotBlank(message = "INVALID_ADDRESS")
            @EvmWalletAddress
            String address,

            @NotBlank(message = "INVALID_LABEL")
            String label,

            @NotBlank(message = "INVALID_COLOR")
            @HexColor
            String color,

            @NotEmpty(message = "INVALID_NETWORK")
            @SupportedEvmNetworks
            List<NetworkId> networks
    ) {
    }
}
