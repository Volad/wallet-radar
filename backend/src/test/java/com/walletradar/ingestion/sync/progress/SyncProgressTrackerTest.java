package com.walletradar.ingestion.sync.progress;

import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.config.BackfillProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @DisplayName("setComplete resets retryCount and nextRetryAfter; backfillComplete=rawFetchComplete (ADR-021)")
    void setComplete_resetsRetryState() {
        SyncStatus status = new SyncStatus();
        status.setId("s1");
        status.setWalletAddress("0xABC");
        status.setNetworkId("ETHEREUM");
        status.setRetryCount(3);
        status.setNextRetryAfter(Instant.now().plusSeconds(600));
        status.setRawFetchComplete(true); // ADR-021: setComplete is called after setRawFetchComplete
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
