package com.walletradar.api.dto;

import com.walletradar.api.validation.EvmWalletAddress;
import com.walletradar.api.validation.HexColor;
import com.walletradar.api.validation.SupportedEvmNetworks;
import com.walletradar.domain.common.NetworkId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Full session settings overwrite request.
 */
public record PutSessionSettingsRequest(
        @NotNull(message = "INVALID_REQUEST")
        @Valid
        List<WalletEntry> wallets,

        @Valid
        List<IntegrationEntry> integrations,

        @Valid
        List<ExternalVenueEntry> externalVenues,

        Boolean hideSmallAssets,
        Boolean showReconciliationWarnings
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

            @NotNull(message = "INVALID_NETWORK")
            @SupportedEvmNetworks
            List<NetworkId> networks
    ) {
    }

    public record IntegrationEntry(
            @NotBlank(message = "INVALID_PROVIDER")
            String provider,
            String displayName,
            String apiKey,
            String apiSecret
    ) {
    }

    /**
     * Cycle/9 S2: counterparty address on a user-owned third-party venue.
     */
    public record ExternalVenueEntry(
            @NotBlank(message = "INVALID_VENUE_ADDRESS")
            String address,
            String provider,
            String label,
            List<NetworkId> networks
    ) {
    }
}
