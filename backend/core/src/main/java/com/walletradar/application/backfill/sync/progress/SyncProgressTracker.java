package com.walletradar.application.backfill.sync.progress;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.WalletNetworkBackfillCompletedEvent;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.application.backfill.config.BackfillProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Updates sync_status during raw backfill execution.
 */
@Component
@RequiredArgsConstructor
public class SyncProgressTracker {

    private final SyncStatusRepository syncStatusRepository;
    private final BackfillProperties backfillProperties;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Set status to RUNNING and optional banner. Idempotent if already RUNNING.
     */
    public void setRunning(String walletAddress, String networkId, Integer progressPct, Long lastBlockSynced, String syncBannerMessage) {
        SyncStatus status = syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .orElse(new SyncStatus());
        if (status.getSourceKind() == null) {
            status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        }
        if (status.getId() == null) {
            status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
            status.setWalletAddress(walletAddress);
            status.setNetworkId(networkId);
        }
        status.setStatus(SyncStatus.SyncStatusValue.RUNNING);
        if (progressPct != null) {
            status.setProgressPct(progressPct);
        }
        if (lastBlockSynced != null) {
            status.setLastBlockSynced(lastBlockSynced);
        }
        status.setSyncBannerMessage(syncBannerMessage);
        status.setBackfillComplete(false);
        status.setUpdatedAt(Instant.now());
        syncStatusRepository.save(status);
    }

    /**
     * Set raw fetch complete for a wallet×network.
     */
    public void setRawFetchComplete(String walletAddress, String networkId, Long lastBlockSynced) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    if (s.getSourceKind() == null) {
                        s.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
                    }
                    s.setRawFetchComplete(true);
                    s.setLastBlockSynced(lastBlockSynced);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }

    /**
     * Set status to COMPLETE, clear banner, and mark the source terminally complete.
     *
     * <p>Reaching {@code COMPLETE} is a terminal state for a wallet×network window: every executable
     * segment finished (or there was nothing left to fetch). It is therefore authoritative — both
     * {@code rawFetchComplete} and {@code backfillComplete} are flipped to {@code true}. This prevents
     * a window that completed through a "no executable segments" / empty-segment / adapter-skip path
     * from being persisted as {@code COMPLETE} while the completion booleans stay {@code false}, which
     * would otherwise strand the session-level backfill completion gate forever.
     */
    public void setComplete(String walletAddress, String networkId) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    if (s.getSourceKind() == null) {
                        s.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
                    }
                    s.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
                    s.setProgressPct(100);
                    s.setSyncBannerMessage(null);
                    s.setRawFetchComplete(true);
                    s.setBackfillComplete(true);
                    s.setRetryCount(0);
                    s.setNextRetryAfter(null);
                    s.setLastSyncedAt(s.getWindowToTime() == null ? Instant.now() : s.getWindowToTime());
                    clearWindow(s);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                    publishBackfillCompletion(walletAddress, networkId, s.isBackfillComplete());
                });
    }

    /**
     * Set status to FAILED, increment retryCount, compute nextRetryAfter with exponential backoff + jitter.
     */
    public void setFailed(String walletAddress, String networkId, String syncBannerMessage) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    if (s.getSourceKind() == null) {
                        s.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
                    }
                    int newRetryCount = s.getRetryCount() + 1;
                    s.setStatus(SyncStatus.SyncStatusValue.FAILED);
                    s.setRetryCount(newRetryCount);
                    s.setSyncBannerMessage(syncBannerMessage);
                    s.setNextRetryAfter(calculateNextRetry(newRetryCount));
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }

    /**
     * Exponential backoff: min(base * 2^(retryCount-1), max) + 25% jitter.
     */
    Instant calculateNextRetry(int retryCount) {
        long baseMinutes = backfillProperties.getRetryBaseDelayMinutes();
        long maxMinutes = backfillProperties.getRetryMaxDelayMinutes();
        long delayMinutes = Math.min(baseMinutes * (1L << Math.min(retryCount - 1, 30)), maxMinutes);
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0, Math.max(1, delayMinutes * 15));
        return Instant.now().plus(Duration.ofMinutes(delayMinutes)).plusSeconds(jitterSeconds);
    }

    private void publishBackfillCompletion(String walletAddress, String networkId, boolean backfillComplete) {
        if (!backfillComplete) {
            return;
        }
        try {
            applicationEventPublisher.publishEvent(
                    new WalletNetworkBackfillCompletedEvent(walletAddress, NetworkId.valueOf(networkId))
            );
        } catch (IllegalArgumentException ignored) {
            // Unknown network ids should not block backfill completion persistence.
        }
    }

    private void clearWindow(SyncStatus status) {
        status.setWindowFromBlock(null);
        status.setWindowToBlock(null);
        status.setWindowFromTime(null);
        status.setWindowToTime(null);
    }
}
