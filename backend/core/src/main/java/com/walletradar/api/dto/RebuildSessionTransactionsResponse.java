package com.walletradar.api.dto;

/**
 * POST /api/v1/sessions/{sessionId}/transactions/rebuild response.
 */
public record RebuildSessionTransactionsResponse(
        String sessionId,
        long projectedTransactions,
        String message
) {
}
