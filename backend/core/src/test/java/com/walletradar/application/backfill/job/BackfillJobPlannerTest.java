package com.walletradar.application.backfill.job;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.application.backfill.config.BackfillProperties;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import com.walletradar.integration.IntegrationBackfillPlanningService;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SourceSyncPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackfillJobPlannerTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private BackfillProperties backfillProperties;
    @Mock
    private IngestionNetworkProperties ingestionNetworkProperties;
    @Mock
    private IntegrationBackfillPlanningService integrationBackfillPlanningService;
    @Mock
    private SourceSyncPlanner sourceSyncPlanner;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    @InjectMocks
    private BackfillJobPlanner backfillJobPlanner;

    @Test
    void skipsOnChainSegmentsWhenBackfillDisabled() {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setAccountingUniverseId("session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG");
        wallet.setNetworks(List.of(NetworkId.SOLANA));
        session.setWallets(List.of(wallet));

        SyncStatus status = new SyncStatus();
        status.setId("sync-solana");
        status.setStatus(SyncStatus.SyncStatusValue.PENDING);
        status.setWalletAddress(wallet.getAddress());
        status.setNetworkId(NetworkId.SOLANA.name());
        status.setWindowFromBlock(1L);
        status.setWindowToBlock(10L);

        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN,
                wallet.getAddress(),
                NetworkId.SOLANA.name()
        )).thenReturn(Optional.of(status));
        when(accountingUniverseService.isBackfillEnabled(
                eq("session-1"),
                eq(wallet.getAddress()),
                eq(NetworkId.SOLANA)
        )).thenReturn(false);

        int planned = backfillJobPlanner.planPendingSessionSources(session);

        assertThat(planned).isZero();
        verify(sourceSyncPlanner, never()).repairOnChainBlockWindowIfMissing(any(), any());
        verify(backfillSegmentRepository, never()).saveAll(any());
    }
}
