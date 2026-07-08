package com.walletradar.api.dto;

import java.time.Instant;
import java.util.List;

public record RefreshStatusResponse(
        String sessionId,
        List<Item> items,
        boolean anyActive
) {
    public record Item(
            String id,
            String status,
            String trigger,
            Instant requestedAt,
            Instant startedAt,
            Instant completedAt,
            Instant lastSyncedAt,
            String error
    ) {
    }
}
