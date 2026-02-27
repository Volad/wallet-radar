package com.walletradar.api.dto;

import java.util.List;

/**
 * Cursor-paginated transaction history response.
 */
public record TransactionHistoryResponse(
        List<TransactionHistoryItemResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
