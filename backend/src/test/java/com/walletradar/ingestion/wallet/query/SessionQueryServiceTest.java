package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import org.junit.jupiter.api.DisplayName;
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
class SessionQueryServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;

    @InjectMocks
    private SessionQueryService sessionQueryService;

    @Test
    @DisplayName("findBackfillStatus treats missing sync rows as PENDING with 0 progress")
    void findBackfillStatus_missingSyncRowsAsPending() {
        UserSession session = session(
                "s-1",
                wallet("0xabc", "Wallet 1", "#22d3ee", List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM))
        );
        when(userSessionRepository.findById("s-1")).thenReturn(Optional.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of());

        Optional<SessionQueryService.SessionBackfillStatusView> response = sessionQueryService.findBackfillStatus("s-1");

        assertThat(response).isPresent();
        SessionQueryService.SessionBackfillStatusView status = response.orElseThrow();
        assertThat(status.status()).isEqualTo("RUNNING");
        assertThat(status.overallProgressPct()).isEqualTo(0);
        assertThat(status.totalTargets()).isEqualTo(2);
        assertThat(status.completedTargets()).isEqualTo(0);
        assertThat(status.wallets()).hasSize(1);
        assertThat(status.wallets().get(0).networks()).hasSize(2);
        assertThat(status.wallets().get(0).networks().get(0).status()).isEqualTo("PENDING");
        assertThat(status.wallets().get(0).networks().get(0).progressPct()).isEqualTo(0);
    }

    @Test
    @DisplayName("findBackfillStatus aggregates progress and running state from sync_status")
    void findBackfillStatus_aggregatesProgress() {
        UserSession session = session(
                "s-2",
                wallet("0xabc", "Wallet 1", "#22d3ee", List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM))
        );
        when(userSessionRepository.findById("s-2")).thenReturn(Optional.of(session));

        SyncStatus completeEth = new SyncStatus();
        completeEth.setWalletAddress("0xabc");
        completeEth.setNetworkId(NetworkId.ETHEREUM.name());
        completeEth.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        completeEth.setProgressPct(100);
        completeEth.setBackfillComplete(true);
        completeEth.setUpdatedAt(Instant.parse("2026-03-04T10:00:00Z"));

        SyncStatus runningArb = new SyncStatus();
        runningArb.setWalletAddress("0xabc");
        runningArb.setNetworkId(NetworkId.ARBITRUM.name());
        runningArb.setStatus(SyncStatus.SyncStatusValue.RUNNING);
        runningArb.setProgressPct(50);
        runningArb.setBackfillComplete(false);
        runningArb.setUpdatedAt(Instant.parse("2026-03-04T10:01:00Z"));

        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc")))
                .thenReturn(List.of(completeEth, runningArb));

        SessionQueryService.SessionBackfillStatusView status = sessionQueryService.findBackfillStatus("s-2").orElseThrow();

        assertThat(status.status()).isEqualTo("RUNNING");
        assertThat(status.overallProgressPct()).isEqualTo(75);
        assertThat(status.totalTargets()).isEqualTo(2);
        assertThat(status.completedTargets()).isEqualTo(1);
    }

    @Test
    @DisplayName("findBackfillStatus returns FAILED when no running targets and at least one failed")
    void findBackfillStatus_failedAggregate() {
        UserSession session = session(
                "s-3",
                wallet("0xabc", "Wallet 1", "#22d3ee", List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM))
        );
        when(userSessionRepository.findById("s-3")).thenReturn(Optional.of(session));

        SyncStatus failed = new SyncStatus();
        failed.setWalletAddress("0xabc");
        failed.setNetworkId(NetworkId.ETHEREUM.name());
        failed.setStatus(SyncStatus.SyncStatusValue.FAILED);
        failed.setProgressPct(20);
        failed.setBackfillComplete(false);

        SyncStatus complete = new SyncStatus();
        complete.setWalletAddress("0xabc");
        complete.setNetworkId(NetworkId.ARBITRUM.name());
        complete.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        complete.setProgressPct(100);
        complete.setBackfillComplete(true);

        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc")))
                .thenReturn(List.of(failed, complete));

        SessionQueryService.SessionBackfillStatusView status = sessionQueryService.findBackfillStatus("s-3").orElseThrow();
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.overallProgressPct()).isEqualTo(60);
        assertThat(status.completedTargets()).isEqualTo(1);
    }

    @Test
    @DisplayName("findSession maps persisted wallets and networks")
    void findSession_mapsResponse() {
        UserSession session = session(
                "s-4",
                wallet("0xabc", "Wallet 1", "#22d3ee", List.of(NetworkId.ETHEREUM, NetworkId.BASE))
        );
        when(userSessionRepository.findById("s-4")).thenReturn(Optional.of(session));

        SessionQueryService.SessionView response = sessionQueryService.findSession("s-4").orElseThrow();
        assertThat(response.sessionId()).isEqualTo("s-4");
        assertThat(response.wallets()).hasSize(1);
        assertThat(response.wallets().get(0).networks()).containsExactly(NetworkId.ETHEREUM, NetworkId.BASE);
    }

    private static UserSession session(String id, UserSession.SessionWallet wallet) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setWallets(List.of(wallet));
        return session;
    }

    private static UserSession.SessionWallet wallet(String address, String label, String color, List<NetworkId> networks) {
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(address);
        wallet.setLabel(label);
        wallet.setColor(color);
        wallet.setNetworks(networks);
        return wallet;
    }
}
