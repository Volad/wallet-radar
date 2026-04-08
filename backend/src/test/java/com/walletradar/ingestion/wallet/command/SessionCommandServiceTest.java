package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionCommandServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private WalletBackfillService walletBackfillService;
    @Mock
    private TrackedWalletProjectionService trackedWalletProjectionService;
    @Mock
    private com.walletradar.session.application.SessionPipelineStateService sessionPipelineStateService;
    @Mock
    private com.walletradar.session.application.AccountingUniverseSyncService accountingUniverseSyncService;

    @InjectMocks
    private SessionCommandService sessionCommandService;

    @Test
    @DisplayName("addSession replaces existing session wallets and triggers backfill")
    void addSession_replacesAndTriggersBackfill() {
        UserSession existing = new UserSession();
        existing.setId("549b0aba-a9af-4789-b125-ebb86314a3f1");
        existing.setCreatedAt(Instant.parse("2026-03-04T10:00:00Z"));
        UserSession.SessionWallet oldWallet = new UserSession.SessionWallet();
        oldWallet.setAddress("0xold");
        oldWallet.setLabel("Old");
        oldWallet.setColor("#ffffff");
        oldWallet.setNetworks(List.of(NetworkId.ETHEREUM));
        existing.setWallets(List.of(oldWallet));

        when(userSessionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        List<SessionCommandService.SessionWalletPayload> payload = List.of(
                new SessionCommandService.SessionWalletPayload(
                        "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                        "Wallet 1",
                        "#22D3EE",
                        List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM)
                )
        );

        SessionCommandService.SessionCommandResult response = sessionCommandService.addSession(existing.getId(), payload);

        assertThat(response.sessionId()).isEqualTo(existing.getId());
        assertThat(response.message()).isEqualTo("Session saved, backfill started");

        ArgumentCaptor<UserSession> savedCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedCaptor.capture());
        UserSession saved = savedCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getWallets()).hasSize(1);
        assertThat(saved.getIntegrations()).isEmpty();
        assertThat(saved.getSettings()).isNotNull();
        assertThat(saved.getWallets().get(0).getAddress()).isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(saved.getWallets().get(0).getLabel()).isEqualTo("Wallet 1");
        assertThat(saved.getWallets().get(0).getColor()).isEqualTo("#22d3ee");
        assertThat(saved.getWallets().get(0).getNetworks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-03-04T10:00:00Z"));
        assertThat(saved.getUpdatedAt()).isNotNull();

        verify(trackedWalletProjectionService).replaceSessionWallets(
                argThat(wallets -> wallets.size() == 1 && "0xold".equals(wallets.get(0).getAddress())),
                argThat(wallets -> wallets.size() == 1
                        && "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f".equals(wallets.get(0).getAddress())),
                any(Instant.class)
        );
        verify(walletBackfillService).addWallet(
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM));
    }

    @Test
    @DisplayName("addSession merges duplicate wallet addresses and triggers one backfill call per wallet")
    void addSession_mergesDuplicateAddresses() {
        when(userSessionRepository.findById("session-1")).thenReturn(Optional.empty());

        List<SessionCommandService.SessionWalletPayload> payload = List.of(
                new SessionCommandService.SessionWalletPayload(
                        "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                        "Wallet 1",
                        "#22d3ee",
                        List.of(NetworkId.ETHEREUM)
                ),
                new SessionCommandService.SessionWalletPayload(
                        "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                        "Wallet 1 updated",
                        "#34D399",
                        List.of(NetworkId.ARBITRUM, NetworkId.ETHEREUM)
                )
        );

        sessionCommandService.addSession("session-1", payload);

        ArgumentCaptor<UserSession> savedCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedCaptor.capture());
        UserSession saved = savedCaptor.getValue();
        assertThat(saved.getWallets()).hasSize(1);
        assertThat(saved.getIntegrations()).isEmpty();
        assertThat(saved.getSettings()).isNotNull();
        UserSession.SessionWallet wallet = saved.getWallets().get(0);
        assertThat(wallet.getAddress()).isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(wallet.getLabel()).isEqualTo("Wallet 1 updated");
        assertThat(wallet.getColor()).isEqualTo("#34d399");
        assertThat(wallet.getNetworks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);

        verify(trackedWalletProjectionService).replaceSessionWallets(anyList(), anyList(), any(Instant.class));
        verify(walletBackfillService, times(1))
                .addWallet("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", wallet.getNetworks());
    }

    @Test
    @DisplayName("addSession allows empty wallet list and creates empty session without backfill")
    void addSession_allowsEmptyWalletList() {
        when(userSessionRepository.findById("session-empty")).thenReturn(Optional.empty());

        SessionCommandService.SessionCommandResult response = sessionCommandService.addSession("session-empty", List.of());

        assertThat(response.sessionId()).isEqualTo("session-empty");
        assertThat(response.message()).isEqualTo("Session created");

        ArgumentCaptor<UserSession> savedCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedCaptor.capture());
        UserSession saved = savedCaptor.getValue();
        assertThat(saved.getWallets()).isEmpty();
        assertThat(saved.getIntegrations()).isEmpty();
        assertThat(saved.getSettings()).isNotNull();

        verify(trackedWalletProjectionService).replaceSessionWallets(anyList(), anyList(), any(Instant.class));
        verify(walletBackfillService, never()).addWallet(any(), anyList());
        verify(sessionPipelineStateService).markStageComplete(
                "session-empty",
                UserSession.PipelineStage.BACKFILL,
                "Empty session created"
        );
    }
}
