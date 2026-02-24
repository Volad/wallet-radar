package com.walletradar.ingestion.job;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.domain.WalletAddedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Adds a wallet: upserts sync_status PENDING per network and publishes WalletAddedEvent (T-023).
 */
@Service
@RequiredArgsConstructor
public class WalletBackfillService {

    private final SyncStatusRepository syncStatusRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Upsert sync_status PENDING for each (address, network), then publish WalletAddedEvent.
     * If {@code networks} is null or empty, all supported networks are used.
     */
    public void addWallet(String address, List<NetworkId> networks) {
        List<NetworkId> targetNetworks = (networks == null || networks.isEmpty())
                ? Arrays.asList(NetworkId.values())
                : networks;
        for (NetworkId networkId : targetNetworks) {
            SyncStatus status = syncStatusRepository.findByWalletAddressAndNetworkId(address, networkId.name())
                    .orElse(new SyncStatus());
            if (status.getId() == null) {
                status.setWalletAddress(address);
                status.setNetworkId(networkId.name());
            }
            status.setStatus(SyncStatus.SyncStatusValue.PENDING);
            status.setProgressPct(0);
            status.setLastBlockSynced(null);
            status.setSyncBannerMessage("Backfill queued");
            status.setBackfillComplete(false);
            status.setUpdatedAt(Instant.now());
            syncStatusRepository.save(status);
        }
        applicationEventPublisher.publishEvent(new WalletAddedEvent(address, targetNetworks));
    }
}
