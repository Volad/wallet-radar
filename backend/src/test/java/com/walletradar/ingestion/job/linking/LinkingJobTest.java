package com.walletradar.ingestion.job.linking;

import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.LinkingCompletedEvent;
import com.walletradar.domain.event.LinkingRequestedEvent;
import com.walletradar.domain.event.OnChainReclassificationCompletedEvent;
import com.walletradar.ingestion.config.LinkingProperties;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkingJobTest {

    @Mock
    private LinkingBatchProcessor linkingBatchProcessor;
    @Mock
    private LinkingDataGateService linkingDataGateService;
    @Mock
    private SessionPipelineActivityService pipelineActivityService;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;

    @Test
    void reclassificationCompletionRunsLinkingWhenGateIsReady() {
        LinkingProperties properties = new LinkingProperties();
        properties.setEnabled(true);
        properties.setBatchSize(25);
        when(linkingDataGateService.snapshot("session-1"))
                .thenReturn(new LinkingDataGateService.LinkingGateSnapshot(true, 0L, 0L, 0L, 0L, false));
        when(linkingBatchProcessor.processConvergentPasses(org.mockito.ArgumentMatchers.eq(25), any(Runnable.class)))
                .thenReturn(2, 1, 0);
        when(linkingBatchProcessor.runTerminalPasses(org.mockito.ArgumentMatchers.eq(25), any(Runnable.class)))
                .thenReturn(0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        LinkingJob job = new LinkingJob(
                properties,
                linkingBatchProcessor,
                linkingDataGateService,
                publisher,
                pipelineActivityService,
                sessionPipelineStateService
        );

        job.onOnChainReclassificationCompleted(new OnChainReclassificationCompletedEvent("session-1", 3, "reclassification"));

        verify(linkingBatchProcessor, times(3)).processConvergentPasses(org.mockito.ArgumentMatchers.eq(25), any(Runnable.class));
        verify(linkingBatchProcessor, times(1)).runTerminalPasses(org.mockito.ArgumentMatchers.eq(25), any(Runnable.class));
        verify(sessionPipelineStateService).markStageRunning(
                "session-1",
                com.walletradar.domain.session.UserSession.PipelineStage.LINKING,
                "Linking running"
        );
        verify(sessionPipelineStateService).markStageComplete(
                "session-1",
                com.walletradar.domain.session.UserSession.PipelineStage.LINKING,
                "Linking complete"
        );
        assertThat(events).singleElement().isInstanceOfSatisfying(LinkingCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isEqualTo(3);
            assertThat(event.trigger()).isEqualTo("on-chain-reclassification-completed");
        });
    }

    @Test
    void bybitCompletionSkipsWhenGateIsBlocked() {
        LinkingProperties properties = new LinkingProperties();
        properties.setEnabled(true);
        when(linkingDataGateService.snapshot("session-1"))
                .thenReturn(new LinkingDataGateService.LinkingGateSnapshot(false, 0L, 1L, 0L, 0L, true));

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        LinkingJob job = new LinkingJob(
                properties,
                linkingBatchProcessor,
                linkingDataGateService,
                publisher,
                pipelineActivityService,
                sessionPipelineStateService
        );

        job.onBybitNormalizationCompleted(new BybitNormalizationCompletedEvent("session-1", 5, "bybit"));

        verify(linkingBatchProcessor, never()).processConvergentPasses(org.mockito.ArgumentMatchers.anyInt(), any(Runnable.class));
        verify(linkingBatchProcessor, never()).runTerminalPasses(org.mockito.ArgumentMatchers.anyInt(), any(Runnable.class));
        verify(sessionPipelineStateService, never()).markStageRunning(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(events).isEmpty();
    }

    @Test
    void requestedLinkingPublishesCompletionEvenWhenDrainIsEmpty() {
        LinkingProperties properties = new LinkingProperties();
        properties.setEnabled(true);
        properties.setBatchSize(25);
        when(linkingDataGateService.snapshot("session-1"))
                .thenReturn(new LinkingDataGateService.LinkingGateSnapshot(true, 0L, 0L, 0L, 0L, false));
        when(linkingBatchProcessor.processConvergentPasses(org.mockito.ArgumentMatchers.eq(25), any(Runnable.class)))
                .thenReturn(0);
        when(linkingBatchProcessor.runTerminalPasses(org.mockito.ArgumentMatchers.eq(25), any(Runnable.class)))
                .thenReturn(0);

        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        LinkingJob job = new LinkingJob(
                properties,
                linkingBatchProcessor,
                linkingDataGateService,
                publisher,
                pipelineActivityService,
                sessionPipelineStateService
        );

        job.onLinkingRequested(new LinkingRequestedEvent("session-1", "resume-watchdog"));

        assertThat(events).singleElement().isInstanceOfSatisfying(LinkingCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.processed()).isZero();
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }
}
