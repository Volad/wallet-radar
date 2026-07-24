package com.walletradar.api.dto;

import com.walletradar.api.validation.HexColor;
import com.walletradar.api.validation.SupportedNetworks;
import com.walletradar.api.validation.WalletAddress;
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

        @Valid
        List<ExternalCustodyDestinationEntry> externalCustodyDestinations,

        Boolean hideSmallAssets,
        Boolean showReconciliationWarnings
) {
    public record WalletEntry(
            @NotBlank(message = "INVALID_ADDRESS")
            @WalletAddress
            String address,

            @NotBlank(message = "INVALID_LABEL")
            String label,

            @NotBlank(message = "INVALID_COLOR")
            @HexColor
            String color,

            @NotNull(message = "INVALID_NETWORK")
            @SupportedNetworks
            List<NetworkId> networks
    ) {
    }

    public record IntegrationEntry(
            @NotBlank(message = "INVALID_PROVIDER")
            String provider,
            String displayName,
            String apiKey,
            String apiSecret,
            String color
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

    /**
     * WS-5 (ADR-072): user-designated external custody destination (e.g. Telegram Earn operator
     * pool). A labeled counterparty only — never a universe member, never hardcoded.
     */
    public record ExternalCustodyDestinationEntry(
            @NotBlank(message = "INVALID_CUSTODY_ADDRESS")
            String address,
            String provider,
            String label,
            List<NetworkId> networks
    ) {
    }
}
