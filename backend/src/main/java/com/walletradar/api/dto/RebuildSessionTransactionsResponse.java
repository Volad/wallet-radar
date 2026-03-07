package com.walletradar.api.dto;

/**
 * POST /api/v1/sessions/{sessionId}/transactions/rebuild 202 response.
 */
public record RebuildSessionTransactionsResponse(
        String sessionId,
        Integer projectedTransactions,
        String message
) {
}
