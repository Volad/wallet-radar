package com.walletradar.application.costbasis.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only port for CEX live balance snapshots used by portfolio / ledger clamping.
 */
public interface CexLiveBalancePort {

    enum Availability {
        UNKNOWN,
        KNOWN_EMPTY,
        KNOWN_NON_EMPTY
    }

    record SnapshotView(
            Availability availability,
            Map<String, BigDecimal> umbrella,
            Instant fetchedAt
    ) {
    }

    Optional<SnapshotView> getSnapshotView(String integrationId);
}
