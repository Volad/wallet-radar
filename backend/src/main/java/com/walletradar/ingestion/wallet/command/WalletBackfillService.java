package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.event.WalletAddedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Adds a wallet by creating pending sync_status entries and publishing a backfill event.
 */
@Service
@RequiredArgsConstructor
public class WalletBackfillService {

    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Upsert sync_status PENDING for each (address, network), then publish WalletAddedEvent.
     * If {@code networks} is null or empty, all supported networks are used.
     */
    public void addWallet(String address, List<NetworkId> networks) {
        List<NetworkId> targetNetworks = (networks == null || networks.isEmpty())
                ? Arrays.asList(NetworkId.values())
                : networks;
        List<NetworkId> networksNeedingBackfill = new ArrayList<>();
        for (NetworkId networkId : targetNetworks) {
            SyncStatus status = syncStatusRepository.findByWalletAddressAndNetworkId(address, networkId.name())
                    .orElse(new SyncStatus());
            if (status.isBackfillComplete()) {
                continue;
            }
            if (status.getSourceKind() == null) {
                status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
            }
            if (status.getId() == null) {
                status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
                status.setWalletAddress(address);
                status.setNetworkId(networkId.name());
            }
            status.setStatus(SyncStatus.SyncStatusValue.PENDING);
            status.setProgressPct(0);
            status.setLastBlockSynced(null);
            status.setSyncBannerMessage("Backfill queued");
            status.setBackfillComplete(false);
            status.setRetryCount(0);
            status.setNextRetryAfter(null);
            status.setUpdatedAt(Instant.now());
            syncStatusRepository.save(status);
            networksNeedingBackfill.add(networkId);
        }
        if (!networksNeedingBackfill.isEmpty()) {
            applicationEventPublisher.publishEvent(new WalletAddedEvent(address, networksNeedingBackfill));
        }
    }

    /**
     * Reuses the existing sync_status row for a bounded delta cycle.
     * Historical raw/canonical rows stay untouched; only orchestration segments
     * are replaced so the next worker pass computes a fresh delta window.
     */
    public void scheduleIncrementalBackfill(String address, List<NetworkId> networks) {
        List<NetworkId> targetNetworks = (networks == null || networks.isEmpty())
                ? List.of()
                : networks;
        if (targetNetworks.isEmpty()) {
            return;
        }
        List<NetworkId> scheduledNetworks = new ArrayList<>();
        for (NetworkId networkId : targetNetworks) {
            SyncStatus status = syncStatusRepository.findByWalletAddressAndNetworkId(address, networkId.name())
                    .orElse(new SyncStatus());
            if (status.getSourceKind() == null) {
                status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
            }
            if (status.getId() == null) {
                status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
                status.setWalletAddress(address);
                status.setNetworkId(networkId.name());
            } else {
                backfillSegmentRepository.deleteBySyncStatusId(status.getId());
            }
            status.setStatus(SyncStatus.SyncStatusValue.PENDING);
            status.setProgressPct(0);
            status.setSyncBannerMessage("Refresh queued");
            status.setBackfillComplete(false);
            status.setRawFetchComplete(false);
            status.setRetryCount(0);
            status.setNextRetryAfter(null);
            status.setUpdatedAt(Instant.now());
            syncStatusRepository.save(status);
            scheduledNetworks.add(networkId);
        }
        if (!scheduledNetworks.isEmpty()) {
            applicationEventPublisher.publishEvent(new WalletAddedEvent(address, scheduledNetworks));
        }
    }
}
