package com.walletradar.application.backfill.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.session.application.SourceSyncPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone wallet-entry helper for the legacy wallet API. Session-owned
 * flows use {@link com.walletradar.session.application.AccountUniverseSyncPlanScheduler} /
 * {@link com.walletradar.session.application.AccountUniverseSyncPlannerService} instead of this service.
 */
@Service
@RequiredArgsConstructor
public class WalletBackfillService {

    private final SourceSyncPlanner sourceSyncPlanner;
    private final WalletBackfillPlanner backfillJobPlanner;

    public void addWallet(String address, List<NetworkId> networks) {
        List<NetworkId> targetNetworks = (networks == null || networks.isEmpty())
                ? Arrays.asList(NetworkId.values())
                : networks;
        int scheduledTargets = sourceSyncPlanner.planStandaloneInitialOnChain(address, targetNetworks, Instant.now());
        if (scheduledTargets > 0) {
            backfillJobPlanner.planPendingOnChainSources(address, targetNetworks);
        }
    }

    public void scheduleIncrementalBackfill(String address, List<NetworkId> networks) {
        List<NetworkId> targetNetworks = (networks == null || networks.isEmpty())
                ? List.of()
                : networks;
        if (targetNetworks.isEmpty()) {
            return;
        }
        int scheduledTargets = sourceSyncPlanner.planStandaloneRefreshOnChain(address, targetNetworks, Instant.now());
        if (scheduledTargets > 0) {
            backfillJobPlanner.planPendingOnChainSources(address, targetNetworks);
        }
    }
}
