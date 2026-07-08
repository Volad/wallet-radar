package com.walletradar.application.backfill.sync.progress;

import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.application.backfill.config.BackfillProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncProgressTrackerTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillProperties backfillProperties;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private SyncProgressTracker tracker;

    @Test
    @DisplayName("setFailed increments retryCount and sets nextRetryAfter")
    void setFailed_incrementsRetryCountAndSetsNextRetryAfter() {
        SyncStatus status = new SyncStatus();
        status.setId("s1");
        status.setWalletAddress("0xABC");
        status.setNetworkId("ETHEREUM");
        status.setRetryCount(2);
        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", "ETHEREUM"))
                .thenReturn(Optional.of(status));
        when(backfillProperties.getRetryBaseDelayMinutes()).thenReturn(2L);
        when(backfillProperties.getRetryMaxDelayMinutes()).thenReturn(60L);

        tracker.setFailed("0xABC", "ETHEREUM", "RPC timeout");

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getRetryCount()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.FAILED);
        assertThat(saved.getNextRetryAfter()).isNotNull();
        assertThat(saved.getNextRetryAfter()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("setComplete resets retryCount and nextRetryAfter; terminal completion sets backfillComplete")
    void setComplete_resetsRetryState() {
        SyncStatus status = new SyncStatus();
        status.setId("s1");
        status.setWalletAddress("0xABC");
        status.setNetworkId("ETHEREUM");
        status.setRetryCount(3);
        status.setNextRetryAfter(Instant.now().plusSeconds(600));
        status.setRawFetchComplete(true);
        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", "ETHEREUM"))
                .thenReturn(Optional.of(status));

        tracker.setComplete("0xABC", "ETHEREUM");

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getRetryCount()).isEqualTo(0);
        assertThat(saved.getNextRetryAfter()).isNull();
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.COMPLETE);
        assertThat(saved.isBackfillComplete()).isTrue();
    }

    @Test
    @DisplayName("setComplete is authoritative: terminal completion flips both flags even when rawFetchComplete was false")
    void setComplete_terminalCompletionSetsBothFlagsForZeroNewTxRefresh() {
        // Reproduces the LINEA refresh stall: a network reaches terminal COMPLETE through a path that
        // never pre-set rawFetchComplete (e.g. zero new txs / empty-segment finalize / adapter skip).
        SyncStatus status = new SyncStatus();
        status.setId("linea-1");
        status.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        status.setNetworkId("LINEA");
        status.setStatus(SyncStatus.SyncStatusValue.RUNNING);
        status.setRawFetchComplete(false);
        status.setBackfillComplete(false);
        when(syncStatusRepository.findByWalletAddressAndNetworkId(
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "LINEA"))
                .thenReturn(Optional.of(status));

        tracker.setComplete("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "LINEA");

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.COMPLETE);
        assertThat(saved.getProgressPct()).isEqualTo(100);
        assertThat(saved.isRawFetchComplete()).isTrue();
        assertThat(saved.isBackfillComplete()).isTrue();
    }

    @Test
    @DisplayName("calculateNextRetry uses exponential backoff with ceiling")
    void calculateNextRetry_exponentialBackoff() {
        when(backfillProperties.getRetryBaseDelayMinutes()).thenReturn(2L);
        when(backfillProperties.getRetryMaxDelayMinutes()).thenReturn(60L);

        Instant r1 = tracker.calculateNextRetry(1);
        Instant r3 = tracker.calculateNextRetry(3);
        Instant r10 = tracker.calculateNextRetry(10);

        Instant now = Instant.now();
        assertThat(r1).isAfter(now.plusSeconds(60));
        assertThat(r1).isBefore(now.plusSeconds(180));
        assertThat(r3).isAfter(now.plusSeconds(7 * 60));
        assertThat(r10).isBefore(now.plusSeconds(75 * 60));
    }
}
