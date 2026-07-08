package com.walletradar.application.backfill.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackfillPropertiesBindingTest {

    @Test
    @DisplayName("binds backfill segments defaults and by-rpc profiles")
    void bindsBackfillSegmentProfiles() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "walletradar.ingestion.backfill.window-blocks", "42",
                "walletradar.ingestion.backfill.segments.defaults.segment-stale-after-ms", "180000",
                "walletradar.ingestion.backfill.segments.defaults.parallel-segments", "2",
                "walletradar.ingestion.backfill.segments.defaults.parallel-segment-workers", "2",
                "walletradar.ingestion.backfill.segments.by-rpc.segment-stale-after-ms", "120000",
                "walletradar.ingestion.backfill.segments.by-rpc.parallel-segments", "6",
                "walletradar.ingestion.backfill.segments.by-rpc.parallel-segment-workers", "4"
        ));

        BackfillProperties properties = new Binder(source)
                .bind("walletradar.ingestion.backfill", Bindable.of(BackfillProperties.class))
                .orElseThrow(() -> new IllegalStateException("Failed to bind backfill properties"));

        assertThat(properties.getWindowBlocks()).isEqualTo(42L);
        assertThat(properties.getSegments().getDefaults().getSegmentStaleAfterMs()).isEqualTo(180_000L);
        assertThat(properties.getSegments().getDefaults().getParallelSegments()).isEqualTo(2);
        assertThat(properties.getSegments().getDefaults().getParallelSegmentWorkers()).isEqualTo(2);
        assertThat(properties.getSegments().getByRpc().getSegmentStaleAfterMs()).isEqualTo(120_000L);
        assertThat(properties.getSegments().getByRpc().getParallelSegments()).isEqualTo(6);
        assertThat(properties.getSegments().getByRpc().getParallelSegmentWorkers()).isEqualTo(4);
    }
}
