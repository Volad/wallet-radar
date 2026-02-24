package com.walletradar.costbasis.override;

import com.walletradar.costbasis.engine.AvcoEngine;
import com.walletradar.costbasis.event.OverrideSavedEvent;
import com.walletradar.domain.AssetPosition;
import com.walletradar.domain.AssetPositionRepository;
import com.walletradar.domain.RecalcJob;
import com.walletradar.domain.RecalcJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecalcJobServiceTest {

    private static final String JOB_ID = "job-1";
    private static final String WALLET = "0xwallet";
    private static final String NETWORK_ID = "ETHEREUM";
    private static final String ASSET_CONTRACT = "0xasset";

    @Mock
    RecalcJobRepository recalcJobRepository;
    @Mock
    AvcoEngine avcoEngine;
    @Mock
    AssetPositionRepository assetPositionRepository;
    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    RecalcJobService recalcJobService;

    @Test
    @DisplayName("onOverrideSaved runs recalculate, sets COMPLETE and newPerWalletAvco, publishes RecalcCompleteEvent")
    void onOverrideSaved_complete() {
        RecalcJob job = new RecalcJob();
        job.setId(JOB_ID);
        job.setStatus(RecalcJob.RecalcStatus.PENDING);
        job.setWalletAddress(WALLET);
        job.setNetworkId(NETWORK_ID);
        job.setAssetContract(ASSET_CONTRACT);
        job.setCreatedAt(Instant.now());

        when(recalcJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        AssetPosition position = new AssetPosition();
        position.setPerWalletAvco(new BigDecimal("2104.33"));
        when(assetPositionRepository.findByWalletAddressAndNetworkIdAndAssetContract(WALLET, NETWORK_ID, ASSET_CONTRACT))
                .thenReturn(Optional.of(position));

        recalcJobService.onOverrideSaved(new OverrideSavedEvent(this, JOB_ID));

        verify(avcoEngine).recalculate(WALLET, com.walletradar.domain.NetworkId.ETHEREUM, ASSET_CONTRACT);
        verify(recalcJobRepository, atLeastOnce()).save(job);
        assertThat(job.getStatus()).isEqualTo(RecalcJob.RecalcStatus.COMPLETE);
        assertThat(job.getNewPerWalletAvco()).isEqualByComparingTo("2104.33");
        assertThat(job.getCompletedAt()).isNotNull();

        ArgumentCaptor<com.walletradar.costbasis.event.RecalcCompleteEvent> eventCaptor =
                ArgumentCaptor.forClass(com.walletradar.costbasis.event.RecalcCompleteEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getJobId()).isEqualTo(JOB_ID);
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("onOverrideSaved when job not found does nothing")
    void onOverrideSaved_jobNotFound() {
        when(recalcJobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        recalcJobService.onOverrideSaved(new OverrideSavedEvent(this, JOB_ID));

        verify(avcoEngine, never()).recalculate(any(), any(), any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("onOverrideSaved when job not PENDING skips")
    void onOverrideSaved_alreadyProcessed() {
        RecalcJob job = new RecalcJob();
        job.setId(JOB_ID);
        job.setStatus(RecalcJob.RecalcStatus.COMPLETE);
        when(recalcJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        recalcJobService.onOverrideSaved(new OverrideSavedEvent(this, JOB_ID));

        verify(avcoEngine, never()).recalculate(any(), any(), any());
    }

    @Test
    @DisplayName("onOverrideSaved when engine throws sets FAILED and publishes RecalcCompleteEvent FAILED")
    void onOverrideSaved_engineFails() {
        RecalcJob job = new RecalcJob();
        job.setId(JOB_ID);
        job.setStatus(RecalcJob.RecalcStatus.PENDING);
        job.setWalletAddress(WALLET);
        job.setNetworkId(NETWORK_ID);
        job.setAssetContract(ASSET_CONTRACT);
        when(recalcJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        doThrow(new RuntimeException("DB error")).when(avcoEngine).recalculate(eq(WALLET), any(), eq(ASSET_CONTRACT));

        recalcJobService.onOverrideSaved(new OverrideSavedEvent(this, JOB_ID));

        verify(recalcJobRepository, atLeastOnce()).save(job);
        assertThat(job.getStatus()).isEqualTo(RecalcJob.RecalcStatus.FAILED);
        assertThat(job.getCompletedAt()).isNotNull();
        ArgumentCaptor<com.walletradar.costbasis.event.RecalcCompleteEvent> eventCaptor =
                ArgumentCaptor.forClass(com.walletradar.costbasis.event.RecalcCompleteEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("FAILED");
    }
}
