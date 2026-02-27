package com.walletradar.ingestion.sync.progress;

import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.config.BackfillProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Updates sync_status during backfill and incremental sync (T-009, T-011).
 */
@Component
@RequiredArgsConstructor
public class SyncProgressTracker {

    private final SyncStatusRepository syncStatusRepository;
    private final BackfillProperties backfillProperties;

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
     * Set raw fetch complete (Phase 1 done). ADR-020.
     */
    public void setRawFetchComplete(String walletAddress, String networkId, Long lastBlockSynced) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    s.setRawFetchComplete(true);
                    s.setLastBlockSynced(lastBlockSynced);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }

    /**
     * Set classification complete (Phase 2 done). ADR-020.
     * @deprecated ADR-021: Backfill no longer sets this; classifier is continuous. Kept for backward compat.
     */
    @Deprecated
    public void setClassificationComplete(String walletAddress, String networkId) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    s.setClassificationComplete(true);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }

    /**
     * Set status to COMPLETE, clear banner, set backfillComplete=rawFetchComplete (ADR-021).
     * Does NOT set classificationComplete — classifier is a separate continuous process.
     */
    public void setComplete(String walletAddress, String networkId) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
                    s.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
                    s.setProgressPct(100);
                    s.setSyncBannerMessage(null);
                    s.setBackfillComplete(s.isRawFetchComplete());
                    s.setRetryCount(0);
                    s.setNextRetryAfter(null);
                    s.setUpdatedAt(Instant.now());
                    syncStatusRepository.save(s);
                });
    }

    /**
     * Set status to FAILED, increment retryCount, compute nextRetryAfter with exponential backoff + jitter.
     */
    public void setFailed(String walletAddress, String networkId, String syncBannerMessage) {
        syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkId)
                .ifPresent(s -> {
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
}
