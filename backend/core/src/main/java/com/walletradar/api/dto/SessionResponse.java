package com.walletradar.api.dto;

import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId} response.
 */
public record SessionResponse(String sessionId, List<SessionWalletEntry> wallets) {

    public record SessionWalletEntry(
            String address,
            String label,
            String color,
            List<String> networks
    ) {
    }
}
