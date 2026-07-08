package com.walletradar.application.cex.acquisition.venue.bybit;

import com.walletradar.domain.sync.BackfillSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BybitBackfillSegmentExecutorTest {

    @Test
    void resolveEffectiveWindow_clampsSegmentStartToProviderLookbackBoundary() {
        BackfillSegment segment = new BackfillSegment();
        segment.setFromTime(Instant.parse("2024-04-07T10:58:00Z"));
        segment.setToTime(Instant.parse("2024-04-14T10:58:00Z"));

        BybitBackfillSegmentExecutor.EffectiveWindow window = BybitBackfillSegmentExecutor.resolveEffectiveWindow(
                segment,
                Instant.parse("2026-04-07T11:00:00Z"),
                2,
                5
        );

        assertThat(window).isNotNull();
        assertThat(window.fromTime()).isEqualTo(Instant.parse("2024-04-07T11:05:00Z"));
        assertThat(window.toTime()).isEqualTo(Instant.parse("2024-04-14T10:58:00Z"));
    }

    @Test
    void resolveEffectiveWindow_returnsNullWhenSegmentIsFullyOutsideProviderLookbackBoundary() {
        BackfillSegment segment = new BackfillSegment();
        segment.setFromTime(Instant.parse("2024-03-20T00:00:00Z"));
        segment.setToTime(Instant.parse("2024-04-01T00:00:00Z"));

        BybitBackfillSegmentExecutor.EffectiveWindow window = BybitBackfillSegmentExecutor.resolveEffectiveWindow(
                segment,
                Instant.parse("2026-04-07T11:00:00Z"),
                2,
                5
        );

        assertThat(window).isNull();
    }
}
