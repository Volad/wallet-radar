package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Maintains sync_status rows for integration-backed raw backfill sources.
 */
@Service
@RequiredArgsConstructor
public class IntegrationSyncStatusService {

    private final SyncStatusRepository syncStatusRepository;

    public void initialize(UserSession.SessionIntegration integration, int totalSegments) {
        SyncStatus status = findOrCreate(integration);
        status.setStatus(totalSegments == 0 ? SyncStatus.SyncStatusValue.COMPLETE : SyncStatus.SyncStatusValue.PENDING);
        status.setProgressPct(totalSegments == 0 ? 100 : 0);
        status.setLastBlockSynced(null);
        status.setSyncBannerMessage(totalSegments == 0 ? null : "Backfill queued");
        status.setBackfillComplete(totalSegments == 0);
        status.setRawFetchComplete(totalSegments == 0);
        status.setRetryCount(0);
        status.setNextRetryAfter(null);
        status.setUpdatedAt(Instant.now());
        syncStatusRepository.save(status);
    }

    public void update(UserSession.SessionIntegration integration, int totalSegments, int completedSegments, int failedSegments, String lastError) {
        SyncStatus status = findOrCreate(integration);
        boolean complete = totalSegments == 0
                ? integration.getStatus() == UserSession.IntegrationStatus.READY
                : completedSegments >= totalSegments;
        boolean failed = totalSegments == 0
                ? integration.getStatus() == UserSession.IntegrationStatus.ERROR
                : failedSegments > 0 && !complete;
        boolean running = !complete && !failed && totalSegments > 0;

        status.setStatus(complete
                ? SyncStatus.SyncStatusValue.COMPLETE
                : failed
                ? SyncStatus.SyncStatusValue.FAILED
                : running
                ? SyncStatus.SyncStatusValue.RUNNING
                : SyncStatus.SyncStatusValue.PENDING);
        status.setProgressPct(totalSegments == 0
                ? (complete ? 100 : 0)
                : Math.max(0, Math.min(100, (int) Math.round((double) completedSegments * 100.0 / totalSegments))));
        status.setSyncBannerMessage(failed
                ? lastError
                : complete
                ? null
                : "Integration backfill: " + completedSegments + "/" + totalSegments + " segments complete");
        status.setBackfillComplete(complete);
        status.setRawFetchComplete(complete);
        status.setUpdatedAt(Instant.now());
        if (failed) {
            status.setRetryCount(status.getRetryCount() + 1);
        } else {
            status.setRetryCount(0);
            status.setNextRetryAfter(null);
        }
        syncStatusRepository.save(status);
    }

    public void delete(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return;
        }
        syncStatusRepository.deleteByIntegrationId(integrationId);
    }

    private SyncStatus findOrCreate(UserSession.SessionIntegration integration) {
        List<SyncStatus> existing = syncStatusRepository.findAllByIntegrationIdOrderByUpdatedAtDescIdDesc(integration.getIntegrationId());
        SyncStatus status = existing.isEmpty() ? new SyncStatus() : existing.get(0);
        if (existing.size() > 1) {
            syncStatusRepository.deleteAll(existing.subList(1, existing.size()));
        }
        status.setSourceKind(SyncStatus.SourceKind.INTEGRATION);
        status.setIntegrationId(integration.getIntegrationId());
        status.setProvider(integration.getProvider() == null ? null : integration.getProvider().name());
        status.setAccountRef(integration.getAccountRef());
        status.setWalletAddress(integration.getAccountRef());
        status.setNetworkId(integration.getProvider() == null ? null : integration.getProvider().name());
        return status;
    }
}
