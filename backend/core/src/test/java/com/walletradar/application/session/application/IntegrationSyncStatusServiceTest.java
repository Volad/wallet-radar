package com.walletradar.application.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationSyncStatusServiceTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;

    @Test
    void initializeKeepsLatestIntegrationSyncRowAndDeletesDuplicates() {
        IntegrationSyncStatusService service = new IntegrationSyncStatusService(syncStatusRepository);

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setAccountRef("BYBIT:33625378");

        SyncStatus latest = new SyncStatus();
        latest.setId("latest");
        latest.setUpdatedAt(Instant.parse("2026-04-07T14:00:00Z"));
        SyncStatus stale = new SyncStatus();
        stale.setId("stale");
        stale.setUpdatedAt(Instant.parse("2026-04-07T13:00:00Z"));

        when(syncStatusRepository.findAllByIntegrationIdOrderByUpdatedAtDescIdDesc("BYBIT-33625378"))
                .thenReturn(List.of(latest, stale));

        service.initialize(integration, 7);

        verify(syncStatusRepository).deleteAll(List.of(stale));
        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("latest");
        assertThat(saved.getIntegrationId()).isEqualTo("BYBIT-33625378");
        assertThat(saved.getWalletAddress()).isEqualTo("BYBIT:33625378");
        assertThat(saved.getNetworkId()).isEqualTo("BYBIT");
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.PENDING);
        assertThat(saved.getProgressPct()).isZero();
    }

    @Test
    void updatePreservesLastSyncedAtWhenRepairingCompletedIntegrationWithoutActiveWindow() {
        IntegrationSyncStatusService service = new IntegrationSyncStatusService(syncStatusRepository);

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setAccountRef("BYBIT:33625378");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        integration.setLastSyncAt(Instant.parse("2026-04-18T22:47:01Z"));

        SyncStatus latest = new SyncStatus();
        latest.setId("latest");
        latest.setLastSyncedAt(Instant.parse("2026-04-18T22:47:01Z"));
        latest.setWindowFromTime(null);
        latest.setWindowToTime(null);

        when(syncStatusRepository.findAllByIntegrationIdOrderByUpdatedAtDescIdDesc("BYBIT-33625378"))
                .thenReturn(List.of(latest));

        service.update(integration, 8, 8, 0, null);

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getLastSyncedAt()).isEqualTo(Instant.parse("2026-04-18T22:47:01Z"));
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.COMPLETE);
        assertThat(saved.isBackfillComplete()).isTrue();
        assertThat(saved.isRawFetchComplete()).isTrue();
    }

    @Test
    void updateFallsBackToIntegrationLastSyncAtForLegacyCompletedIntegrationWithoutCheckpoint() {
        IntegrationSyncStatusService service = new IntegrationSyncStatusService(syncStatusRepository);

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setAccountRef("BYBIT:33625378");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        integration.setLastSyncAt(Instant.parse("2026-04-18T22:47:01Z"));

        SyncStatus latest = new SyncStatus();
        latest.setId("latest");
        latest.setWindowFromTime(null);
        latest.setWindowToTime(null);

        when(syncStatusRepository.findAllByIntegrationIdOrderByUpdatedAtDescIdDesc("BYBIT-33625378"))
                .thenReturn(List.of(latest));

        service.update(integration, 8, 8, 0, null);

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getLastSyncedAt()).isEqualTo(Instant.parse("2026-04-18T22:47:01Z"));
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.COMPLETE);
    }
}
