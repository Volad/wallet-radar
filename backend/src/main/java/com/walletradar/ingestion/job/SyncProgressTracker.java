package com.walletradar.ingestion.job;

import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Updates sync_status during backfill and incremental sync (T-009, T-011).
 */
@Component
@RequiredArgsConstructor
public class SyncProgressTracker {

    private final SyncStatusRepository syncStatusRepository;

    /**
     * Set status to RUNNING and optional banner. Idempotent if already RUNNING.
     */
    public void setRunning(String walletAddress, String networkId, Integer progressPct, Long lastBlockSynced, String syncBannerMessage) {
        SyncStatus status = syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .orElse(new SyncStatus());
        if (status.getId() == null) {
            status.setWalletAddress(walletAddress);
            status.setNetworkId(networkId);
        }
        status.setStatus(SyncStatus.SyncStatusValue.RUNNING);
        status.setProgressPct(progressPct);
        status.setLastBlockSynced(lastBlockSynced);
        status.setSyncBannerMessage(syncBannerMessage);
        status.setBackfillComplete(false);
        status.setUpdatedAt(Instant.now());
        syncStatusRepository.save(status);
    }

    /**
     * Set status to COMPLETE, clear banner, set backfillComplete=true.
     */
    public void setComplete(String walletAddress, String networkId) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    s.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
                    s.setProgressPct(100);
                    s.setSyncBannerMessage(null);
                    s.setBackfillComplete(true);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }

    /**
     * Set status to FAILED and optional message.
     */
    public void setFailed(String walletAddress, String networkId, String syncBannerMessage) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    s.setStatus(SyncStatus.SyncStatusValue.FAILED);
                    s.setSyncBannerMessage(syncBannerMessage);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }
}
