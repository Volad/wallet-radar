package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionSettingsQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;

    @InjectMocks
    private SessionSettingsQueryService sessionSettingsQueryService;

    @Test
    void returnsSessionWalletsAndIntegrationProgress() {
        UserSession session = new UserSession();
        session.setId("session-1");

        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0xabc");
        wallet.setLabel("Main");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM));
        session.setWallets(List.of(wallet));

        UserSession.SessionSettings settings = new UserSession.SessionSettings();
        settings.setHideSmallAssets(Boolean.TRUE);
        settings.setShowReconciliationWarnings(Boolean.TRUE);
        session.setSettings(settings);

        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
        integration.setDisplayName("Bybit main");
        integration.setAccountRef("BYBIT:33625378");
        integration.setReadOnly(true);
        integration.setCapabilities(List.of("READONLY", "ASSET"));
        integration.setLastValidatedAt(Instant.parse("2026-04-07T08:00:00Z"));
        integration.setLastSyncAt(Instant.parse("2026-04-07T08:05:00Z"));
        integration.setLastError(null);
        UserSession.EncryptedSecret secret = new UserSession.EncryptedSecret();
        secret.setMaskedKey("abcd...wxyz");
        integration.setEncryptedCredentials(secret);
        session.setIntegrations(List.of(integration));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(backfillSegmentRepository.countByIntegrationId("BYBIT-33625378")).thenReturn(10L);
        when(backfillSegmentRepository.countByIntegrationIdAndStatus("BYBIT-33625378", BackfillSegment.SegmentStatus.COMPLETE))
                .thenReturn(4L);
        when(backfillSegmentRepository.countByIntegrationIdAndStatus("BYBIT-33625378", BackfillSegment.SegmentStatus.FAILED))
                .thenReturn(1L);

        SessionSettingsQueryService.SessionSettingsView view = sessionSettingsQueryService
                .findSessionSettings("session-1")
                .orElseThrow();

        assertThat(view.hideSmallAssets()).isTrue();
        assertThat(view.showReconciliationWarnings()).isTrue();
        assertThat(view.wallets()).singleElement().satisfies(resultWallet -> {
            assertThat(resultWallet.address()).isEqualTo("0xabc");
            assertThat(resultWallet.networks()).containsExactly("ETHEREUM", "ARBITRUM");
        });
        assertThat(view.integrations()).singleElement().satisfies(resultIntegration -> {
            assertThat(resultIntegration.provider()).isEqualTo("BYBIT");
            assertThat(resultIntegration.accountRef()).isEqualTo("BYBIT:33625378");
            assertThat(resultIntegration.maskedKey()).isEqualTo("abcd...wxyz");
            assertThat(resultIntegration.totalSegments()).isEqualTo(10);
            assertThat(resultIntegration.completedSegments()).isEqualTo(4);
            assertThat(resultIntegration.failedSegments()).isEqualTo(1);
            assertThat(resultIntegration.progressPct()).isEqualTo(40);
        });
    }

    @Test
    void defaultsSettingsFlagsToTrueWhenMissing() {
        UserSession session = new UserSession();
        session.setId("session-2");

        when(userSessionRepository.findById("session-2")).thenReturn(Optional.of(session));

        SessionSettingsQueryService.SessionSettingsView view = sessionSettingsQueryService
                .findSessionSettings("session-2")
                .orElseThrow();

        assertThat(view.hideSmallAssets()).isTrue();
        assertThat(view.showReconciliationWarnings()).isTrue();
    }
}
