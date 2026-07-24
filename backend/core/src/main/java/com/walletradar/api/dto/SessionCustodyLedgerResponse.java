package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/custody-ledger response (WS-5, ADR-072).
 *
 * <p>Informational only: per-venue tally of deposited (X) / withdrawn (Y) flows to user-designated
 * external custody destinations. Never part of portfolio totals, AVCO, or the accounting universe.</p>
 */
public record SessionCustodyLedgerResponse(
        String sessionId,
        List<CustodyVenue> venues
) {
    public record CustodyVenue(
            String venueAddress,
            String label,
            String provider,
            List<CustodyAsset> assets
    ) {
    }

    public record CustodyAsset(
            String asset,
            BigDecimal depositedQty,
            BigDecimal withdrawnQty,
            BigDecimal netQty,
            BigDecimal depositedUsd,
            BigDecimal withdrawnUsd
    ) {
    }
}
