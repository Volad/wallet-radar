package com.walletradar.session.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walletradar.session.application.AccountUniverseSyncPlanScheduler;
import com.walletradar.api.dto.PutSessionSettingsRequest;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.session.application.TrackedWalletProjectionService;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionSettingsCommandServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private TrackedWalletProjectionService trackedWalletProjectionService;
    @Mock
    private AccountUniverseSyncPlanScheduler accountUniverseSyncPlanScheduler;
    @Mock
    private SessionSecretCryptoService sessionSecretCryptoService;
    @Mock
    private BybitApiClient bybitApiClient;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private IntegrationSyncStatusService integrationSyncStatusService;

    private SessionSettingsCommandService sessionSettingsCommandService;

    @BeforeEach
    void setUp() {
        sessionSettingsCommandService = new SessionSettingsCommandService(
                userSessionRepository,
                trackedWalletProjectionService,
                accountUniverseSyncPlanScheduler,
                sessionSecretCryptoService,
                bybitApiClient,
                backfillSegmentRepository,
                integrationSyncStatusService,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("overwriteSessionSettings preserves Solana address case for linking-only wallets")
    void overwriteSessionSettings_preservesSolanaAddressCase() {
        UserSession session = new UserSession();
        session.setId("session-sol");
        session.setWallets(new ArrayList<>());
        when(userSessionRepository.findById("session-sol")).thenReturn(Optional.of(session));

        String solAddress = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
        PutSessionSettingsRequest request = new PutSessionSettingsRequest(
                List.of(new PutSessionSettingsRequest.WalletEntry(
                        solAddress,
                        "SOL",
                        "#22d3ee",
                        List.of(NetworkId.SOLANA)
                )),
                List.of(),
                List.of(),
                true,
                true
        );

        sessionSettingsCommandService.overwriteSessionSettings("session-sol", request);

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getWallets()).hasSize(1);
        assertThat(captor.getValue().getWallets().get(0).getAddress()).isEqualTo(solAddress);
    }

    @Test
    @DisplayName("overwriteSessionSettings updates wallet flags and preserves existing Bybit secret when credentials are omitted")
    void overwriteSessionSettings_preservesExistingBybitSecret() {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setWallets(new ArrayList<>(List.of(wallet(
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                "Main",
                "#22d3ee",
                List.of(NetworkId.ETHEREUM)
        ))));
        UserSession.SessionSettings settings = new UserSession.SessionSettings();
        settings.setHideSmallAssets(Boolean.TRUE);
        settings.setShowReconciliationWarnings(Boolean.TRUE);
        session.setSettings(settings);

        UserSession.SessionIntegration bybit = new UserSession.SessionIntegration();
        bybit.setIntegrationId("BYBIT-33625378");
        bybit.setProvider(UserSession.IntegrationProvider.BYBIT);
        bybit.setDisplayName("Bybit old");
        UserSession.EncryptedSecret secret = new UserSession.EncryptedSecret();
        secret.setMaskedKey("abcd...1234");
        bybit.setEncryptedCredentials(secret);
        session.setIntegrations(new ArrayList<>(List.of(bybit)));

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        PutSessionSettingsRequest request = new PutSessionSettingsRequest(
                List.of(new PutSessionSettingsRequest.WalletEntry(
                        "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                        "Main",
                        "#22D3EE",
                        List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM)
                )),
                List.of(new PutSessionSettingsRequest.IntegrationEntry(
                        "BYBIT",
                        "Bybit renamed",
                        null,
                        null,
                        null
                )),
                List.of(),
                Boolean.FALSE,
                Boolean.FALSE
        );

        sessionSettingsCommandService.overwriteSessionSettings("session-1", request).orElseThrow();

        ArgumentCaptor<UserSession> savedCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedCaptor.capture());
        UserSession saved = savedCaptor.getValue();
        assertThat(saved.getSettings().getHideSmallAssets()).isFalse();
        assertThat(saved.getSettings().getShowReconciliationWarnings()).isFalse();
        assertThat(saved.getIntegrations()).singleElement().satisfies(integration -> {
            assertThat(integration.getDisplayName()).isEqualTo("Bybit renamed");
            assertThat(integration.getEncryptedCredentials()).isSameAs(secret);
        });

        verify(trackedWalletProjectionService).replaceSessionWallets(any(), any(), any(Instant.class));
        verify(bybitApiClient, never()).validateCredentials(any(), any());
        verify(accountUniverseSyncPlanScheduler).schedule("session-1", saved.getUpdatedAt());
    }

    @Test
    @DisplayName("overwriteSessionSettings creates Bybit integration and plans backfill when credentials are provided")
    void overwriteSessionSettings_createsBybitIntegration() {
        UserSession session = new UserSession();
        session.setId("session-2");
        session.setWallets(new ArrayList<>());
        session.setIntegrations(new ArrayList<>());

        when(userSessionRepository.findById("session-2")).thenReturn(Optional.of(session));

        ObjectNode permissions = JsonNodeFactory.instance.objectNode();
        permissions.put("asset", true);
        when(bybitApiClient.validateCredentials("api-key-1234", "super-secret")).thenReturn(
                new BybitApiClient.CredentialInfo("33625378", true, permissions, "UNIFIED")
        );
        UserSession.EncryptedSecret encryptedSecret = new UserSession.EncryptedSecret();
        encryptedSecret.setMaskedKey("api-...1234");
        when(sessionSecretCryptoService.encrypt(any(), eq("api-...1234"))).thenReturn(encryptedSecret);
        PutSessionSettingsRequest request = new PutSessionSettingsRequest(
                List.of(),
                List.of(new PutSessionSettingsRequest.IntegrationEntry(
                        "BYBIT",
                        "Bybit main",
                        "api-key-1234",
                        "super-secret",
                        null
                )),
                List.of(),
                null,
                null
        );

        sessionSettingsCommandService.overwriteSessionSettings("session-2", request).orElseThrow();

        ArgumentCaptor<UserSession> savedCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedCaptor.capture());
        UserSession saved = savedCaptor.getValue();
        assertThat(saved.getSettings().getHideSmallAssets()).isTrue();
        assertThat(saved.getSettings().getShowReconciliationWarnings()).isTrue();
        assertThat(saved.getIntegrations()).singleElement().satisfies(integration -> {
            assertThat(integration.getIntegrationId()).isEqualTo("BYBIT-33625378");
            assertThat(integration.getAccountRef()).isEqualTo("BYBIT:33625378");
            assertThat(integration.getDisplayName()).isEqualTo("Bybit main");
            assertThat(integration.getEncryptedCredentials()).isSameAs(encryptedSecret);
            assertThat(integration.getStatus()).isEqualTo(UserSession.IntegrationStatus.CONNECTED);
            assertThat(integration.getSyncState()).isNotNull();
        });
        verify(accountUniverseSyncPlanScheduler).schedule("session-2", saved.getUpdatedAt());
    }

    @Test
    @DisplayName("overwriteSessionSettings surfaces Bybit validation failures as invalid request errors")
    void overwriteSessionSettings_mapsBybitValidationFailure() {
        UserSession session = new UserSession();
        session.setId("session-3");
        session.setWallets(new ArrayList<>());
        session.setIntegrations(new ArrayList<>());

        when(userSessionRepository.findById("session-3")).thenReturn(Optional.of(session));
        when(bybitApiClient.validateCredentials("bad-key", "bad-secret"))
                .thenThrow(new IllegalStateException("Bybit API rejected request: invalid api key"));

        PutSessionSettingsRequest request = new PutSessionSettingsRequest(
                List.of(),
                List.of(new PutSessionSettingsRequest.IntegrationEntry(
                        "BYBIT",
                        "Bybit main",
                        "bad-key",
                        "bad-secret",
                        null
                )),
                List.of(),
                null,
                null
        );

        assertThatThrownBy(() -> sessionSettingsCommandService.overwriteSessionSettings("session-3", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bybit credential validation failed");
    }

    @Test
    @DisplayName("overwriteSessionSettings persists external venues and schedules universe re-sync")
    void overwriteSessionSettings_persistsExternalVenues() {
        UserSession session = new UserSession();
        session.setId("session-venue");
        session.setWallets(new ArrayList<>());
        session.setIntegrations(new ArrayList<>());
        when(userSessionRepository.findById("session-venue")).thenReturn(Optional.of(session));

        PutSessionSettingsRequest request = new PutSessionSettingsRequest(
                List.of(),
                List.of(),
                List.of(new PutSessionSettingsRequest.ExternalVenueEntry(
                        "0xParadexDepositAddress",
                        "paradex",
                        "Paradex deposit",
                        List.of(NetworkId.ETHEREUM)
                )),
                Boolean.TRUE,
                Boolean.TRUE
        );

        sessionSettingsCommandService.overwriteSessionSettings("session-venue", request).orElseThrow();

        ArgumentCaptor<UserSession> savedCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedCaptor.capture());
        UserSession saved = savedCaptor.getValue();
        assertThat(saved.getSettings().getExternalVenues()).hasSize(1);
        UserSession.ExternalVenue venue = saved.getSettings().getExternalVenues().getFirst();
        assertThat(venue.getAddress()).isEqualTo("0xparadexdepositaddress");
        assertThat(venue.getProvider()).isEqualTo("PARADEX");
        assertThat(venue.getLabel()).isEqualTo("Paradex deposit");
        assertThat(venue.getNetworks()).containsExactly(NetworkId.ETHEREUM);
        verify(accountUniverseSyncPlanScheduler).schedule(eq("session-venue"), any(Instant.class));
    }

    private UserSession.SessionWallet wallet(
            String address,
            String label,
            String color,
            List<NetworkId> networks
    ) {
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(address);
        wallet.setLabel(label);
        wallet.setColor(color);
        wallet.setNetworks(new ArrayList<>(networks));
        return wallet;
    }
}
