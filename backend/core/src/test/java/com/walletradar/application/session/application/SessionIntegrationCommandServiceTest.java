package com.walletradar.application.session.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walletradar.application.session.application.AccountUniverseSyncPlanScheduler;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionIntegrationCommandServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private SessionSecretCryptoService sessionSecretCryptoService;
    @Mock
    private BybitApiClient bybitApiClient;
    @Mock
    private IntegrationSyncStatusService integrationSyncStatusService;
    @Mock
    private AccountUniverseSyncPlanScheduler accountUniverseSyncPlanScheduler;

    private SessionIntegrationCommandService sessionIntegrationCommandService;

    @BeforeEach
    void setUp() {
        sessionIntegrationCommandService = new SessionIntegrationCommandService(
                userSessionRepository,
                backfillSegmentRepository,
                sessionSecretCryptoService,
                bybitApiClient,
                integrationSyncStatusService,
                accountUniverseSyncPlanScheduler,
                new ObjectMapper()
        );
    }

    @Test
    void savesValidatedBybitIntegrationIntoSession() {
        UserSession session = new UserSession();
        session.setId("session-1");
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        ObjectNode permissions = new ObjectMapper().createObjectNode();
        permissions.putArray("asset").add("read");
        when(bybitApiClient.validateCredentials("api-key-1234", "super-secret")).thenReturn(
                new BybitApiClient.CredentialInfo("33625378", true, permissions, "UNIFIED")
        );

        UserSession.EncryptedSecret encryptedSecret = new UserSession.EncryptedSecret();
        encryptedSecret.setKeyVersion("local-v1");
        encryptedSecret.setNonceB64("nonce");
        encryptedSecret.setCiphertextB64("cipher");
        encryptedSecret.setMaskedKey("api-...1234");
        when(sessionSecretCryptoService.encrypt("{\"apiKey\":\"api-key-1234\",\"apiSecret\":\"super-secret\"}", "api-...1234"))
                .thenReturn(encryptedSecret);

        SessionIntegrationCommandService.IntegrationCommandResult result = sessionIntegrationCommandService
                .upsertBybit("session-1", "Bybit main", "api-key-1234", "super-secret")
                .orElseThrow();

        assertThat(result.integrationId()).isEqualTo("BYBIT-33625378");
        assertThat(result.accountRef()).isEqualTo("BYBIT:33625378");
        assertThat(result.provider()).isEqualTo("BYBIT");
        assertThat(result.status()).isEqualTo("CONNECTED");

        ArgumentCaptor<UserSession> sessionCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(sessionCaptor.capture());
        UserSession saved = sessionCaptor.getValue();
        assertThat(saved.getIntegrations()).singleElement().satisfies(integration -> {
            assertThat(integration.getIntegrationId()).isEqualTo("BYBIT-33625378");
            assertThat(integration.getProvider()).isEqualTo(UserSession.IntegrationProvider.BYBIT);
            assertThat(integration.getStatus()).isEqualTo(UserSession.IntegrationStatus.CONNECTED);
            assertThat(integration.getDisplayName()).isEqualTo("Bybit main");
            assertThat(integration.getAccountRef()).isEqualTo("BYBIT:33625378");
            assertThat(integration.isReadOnly()).isTrue();
            assertThat(integration.getCapabilities()).containsExactly("ASSET");
            assertThat(integration.getEncryptedCredentials()).isSameAs(encryptedSecret);
            assertThat(integration.getSyncState()).isNotNull();
        });
        verify(accountUniverseSyncPlanScheduler).schedule("session-1", saved.getUpdatedAt());
    }

    @Test
    void removesIntegrationAndDeletesItsSegments() {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        session.setIntegrations(new java.util.ArrayList<>(java.util.List.of(integration)));
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        SessionIntegrationCommandService.IntegrationRemovalResult result = sessionIntegrationCommandService
                .removeIntegration("session-1", "BYBIT-33625378")
                .orElseThrow();

        assertThat(result.integrationId()).isEqualTo("BYBIT-33625378");
        verify(backfillSegmentRepository).deleteByIntegrationId("BYBIT-33625378");
        verify(integrationSyncStatusService).delete("BYBIT-33625378");
        verify(userSessionRepository).save(session);
        assertThat(session.getIntegrations()).isEmpty();
        verify(accountUniverseSyncPlanScheduler).schedule("session-1", session.getUpdatedAt());
    }
}
