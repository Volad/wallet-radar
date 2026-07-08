package com.walletradar.application.cex.acquisition.venue.bybit;

import java.time.Instant;

/**
 * Per-stream Bybit ingestion progress for settings UI and operator clarity.
 *
 * @param stream                     {@link BybitIntegrationStream} name
 * @param lastSegmentCompletedAt     max {@link com.walletradar.domain.sync.BackfillSegment#getCompletedAt()} for COMPLETE segments
 * @param newestStoredEventAt        max {@code timeUtc} in {@code bybit_extracted_events} for the stream
 */
public record BybitStreamSyncSnapshot(String stream, Instant lastSegmentCompletedAt, Instant newestStoredEventAt) {
}
