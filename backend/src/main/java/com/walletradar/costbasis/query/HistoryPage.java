package com.walletradar.costbasis.query;

import java.util.List;

/**
 * Internal cursor-paginated transaction history page.
 */
public record HistoryPage(
        List<HistoryItem> items,
        String nextCursor,
        boolean hasMore
) {
}
