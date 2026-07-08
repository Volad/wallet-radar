package com.walletradar.application.cex.port;

import java.time.Instant;
import java.util.Map;

/**
 * Design-ready extracted-row view (Track B1) before canonical builder mapping.
 * Immutable evidence — normalization rebuilds {@code normalized_transactions} from these events.
 */
public interface CexLedgerEvent {

    /** Stable id in venue raw/extracted store. */
    String sourceRowId();

    /** Venue-reported event time (UTC). */
    Instant eventTime();

    /** Venue-native type string (e.g. {@code Deposit}, {@code UniversalTransfer}). */
    String originalType();

    /** Structured payload for venue-specific canonical mapper. */
    Map<String, Object> payload();
}
