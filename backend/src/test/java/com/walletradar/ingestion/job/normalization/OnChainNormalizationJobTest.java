package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnChainNormalizationJobTest {

    @Test
    @DisplayName("normalization job publishes completion event after draining processed batches")
    void normalizationJobPublishesCompletionEvent() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);

        OnChainNormalizationService normalizationService = mock(OnChainNormalizationService.class);
        when(normalizationService.processNextBatch()).thenReturn(2, 3, 0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;

        OnChainNormalizationJob job = new OnChainNormalizationJob(properties, normalizationService, publisher);

        int processed = job.runNormalization();

        assertThat(processed).isEqualTo(5);
        assertThat(events).singleElement().isInstanceOfSatisfying(OnChainNormalizationCompletedEvent.class, event -> {
            assertThat(event.processed()).isEqualTo(5);
            assertThat(event.trigger()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("normalization job does not publish completion event when nothing was processed")
    void normalizationJobDoesNotPublishEventForEmptyDrain() {
        OnChainNormalizationProperties properties = new OnChainNormalizationProperties();
        properties.setEnabled(true);

        OnChainNormalizationService normalizationService = mock(OnChainNormalizationService.class);
        when(normalizationService.processNextBatch()).thenReturn(0);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        OnChainNormalizationJob job = new OnChainNormalizationJob(properties, normalizationService, publisher);

        int processed = job.runNormalization();

        assertThat(processed).isZero();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
