package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.session.application.SourceSyncPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletBackfillServiceTest {

    @Mock
    private SourceSyncPlanner sourceSyncPlanner;
    @Mock
    private WalletBackfillPlanner backfillJobPlanner;

    @InjectMocks
    private WalletBackfillService walletBackfillService;

    @Test
    @DisplayName("addWallet with explicit networks delegates to sync planner and segment planner")
    void addWallet_explicitNetworks() {
        List<NetworkId> networks = List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
        when(sourceSyncPlanner.planStandaloneInitialOnChain(
                org.mockito.ArgumentMatchers.eq("0x742d35Cc6634C0532925a3b844Bc454e4438f44e"),
                org.mockito.ArgumentMatchers.eq(networks),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(2);

        walletBackfillService.addWallet("0x742d35Cc6634C0532925a3b844Bc454e4438f44e", networks);

        verify(sourceSyncPlanner).planStandaloneInitialOnChain(
                org.mockito.ArgumentMatchers.eq("0x742d35Cc6634C0532925a3b844Bc454e4438f44e"),
                org.mockito.ArgumentMatchers.eq(networks),
                org.mockito.ArgumentMatchers.any()
        );
        verify(backfillJobPlanner).planPendingOnChainSources("0x742d35Cc6634C0532925a3b844Bc454e4438f44e", networks);
    }

    @Test
    @DisplayName("addWallet with empty networks expands to all supported networks")
    void addWallet_emptyNetworks_usesAllNetworks() {
        List<NetworkId> allNetworks = List.of(NetworkId.values());
        when(sourceSyncPlanner.planStandaloneInitialOnChain(
                org.mockito.ArgumentMatchers.eq("0xabc"),
                org.mockito.ArgumentMatchers.eq(allNetworks),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(allNetworks.size());

        walletBackfillService.addWallet("0xabc", Collections.emptyList());

        verify(sourceSyncPlanner).planStandaloneInitialOnChain(
                org.mockito.ArgumentMatchers.eq("0xabc"),
                org.mockito.ArgumentMatchers.eq(allNetworks),
                org.mockito.ArgumentMatchers.any()
        );
        verify(backfillJobPlanner).planPendingOnChainSources("0xabc", allNetworks);
    }

    @Test
    @DisplayName("addWallet does not invoke planner when source planner found nothing to schedule")
    void addWallet_skipsPlannerWhenNothingScheduled() {
        List<NetworkId> networks = List.of(NetworkId.BASE);
        when(sourceSyncPlanner.planStandaloneInitialOnChain(
                org.mockito.ArgumentMatchers.eq("0xabc"),
                org.mockito.ArgumentMatchers.eq(networks),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(0);

        walletBackfillService.addWallet("0xabc", networks);

        verify(backfillJobPlanner, never()).planPendingOnChainSources("0xabc", networks);
    }

    @Test
    @DisplayName("scheduleIncrementalBackfill plans refresh window and delegates segment planning")
    void scheduleIncrementalBackfill_plansRefreshWindow() {
        List<NetworkId> networks = List.of(NetworkId.BASE);
        when(sourceSyncPlanner.planStandaloneRefreshOnChain(
                org.mockito.ArgumentMatchers.eq("0xabc"),
                org.mockito.ArgumentMatchers.eq(networks),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(1);

        walletBackfillService.scheduleIncrementalBackfill("0xabc", networks);

        verify(sourceSyncPlanner).planStandaloneRefreshOnChain(
                org.mockito.ArgumentMatchers.eq("0xabc"),
                org.mockito.ArgumentMatchers.eq(networks),
                org.mockito.ArgumentMatchers.any()
        );
        verify(backfillJobPlanner).planPendingOnChainSources("0xabc", networks);
    }

    @Test
    @DisplayName("scheduleIncrementalBackfill ignores empty network list")
    void scheduleIncrementalBackfill_ignoresEmptyNetworkList() {
        walletBackfillService.scheduleIncrementalBackfill("0xabc", List.of());

        verify(sourceSyncPlanner, never()).planStandaloneRefreshOnChain(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(backfillJobPlanner, never()).planPendingOnChainSources(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }
}
