package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.wallet.command.WalletBackfillService;
import com.walletradar.integration.IntegrationBackfillPlanningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Schedules a bounded refresh cycle without clearing historical raw or
 * canonical history. The same sync_status rows are reused; only orchestration
 * segments are replaced for sources that actually have a delta window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionRefreshCommandService {

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final WalletBackfillService walletBackfillService;
    private final IntegrationBackfillPlanningService integrationBackfillPlanningService;
    private final SessionPipelineStateService sessionPipelineStateService;
    private final List<BlockHeightResolver> blockHeightResolvers;

    public Optional<SessionRefreshResult> refresh(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::scheduleRefresh);
    }

    private SessionRefreshResult scheduleRefresh(UserSession session) {
        ensureRefreshAllowed(session);

        Instant now = Instant.now();
        Instant refreshAnchor = now.truncatedTo(ChronoUnit.SECONDS);
        Map<NetworkId, Long> latestHeads = new HashMap<>();
        int scheduledTargets = 0;
        int skippedTargets = 0;
        boolean sessionChanged = false;

        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            List<NetworkId> networksToSchedule = new ArrayList<>();
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                SyncStatus syncStatus = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                                SyncStatus.SourceKind.ONCHAIN,
                                wallet.getAddress(),
                                networkId.name()
                        )
                        .orElse(null);
                Long checkpoint = resolveOnChainCheckpoint(syncStatus);
                if (checkpoint != null) {
                    long currentHead = latestHeads.computeIfAbsent(networkId, this::resolveCurrentBlock);
                    if (currentHead <= checkpoint) {
                        skippedTargets++;
                        continue;
                    }
                }
                networksToSchedule.add(networkId);
            }
            if (!networksToSchedule.isEmpty()) {
                walletBackfillService.scheduleIncrementalBackfill(wallet.getAddress(), networksToSchedule);
                scheduledTargets += networksToSchedule.size();
                sessionChanged = true;
            }
        }

        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            Instant lowerBound = resolveIntegrationCheckpoint(integration);
            if (lowerBound != null && !lowerBound.isBefore(refreshAnchor)) {
                skippedTargets++;
                continue;
            }
            UserSession.IntegrationSyncState syncState = lowerBound == null
                    ? integrationBackfillPlanningService.replanInitialBackfill(session.getId(), integration)
                    : integrationBackfillPlanningService.replanIncrementalBackfill(
                            session.getId(),
                            integration,
                            lowerBound,
                            refreshAnchor
                    );
            if (syncState.getTotalSegments() == null || syncState.getTotalSegments() <= 0) {
                skippedTargets++;
                continue;
            }
            integration.setSyncState(syncState);
            integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
            integration.setUpdatedAt(now);
            integration.setLastError(null);
            scheduledTargets++;
            sessionChanged = true;
        }

        if (!sessionChanged) {
            return new SessionRefreshResult(
                    session.getId(),
                    RefreshStatus.UP_TO_DATE,
                    0,
                    skippedTargets,
                    "Session is already up to date"
            );
        }

        session.setUpdatedAt(now);
        userSessionRepository.save(session);
        sessionPipelineStateService.markStageRunning(
                session.getId(),
                UserSession.PipelineStage.BACKFILL,
                "Incremental refresh queued"
        );
        log.info(
                "Session refresh scheduled: sessionId={}, scheduledTargets={}, skippedTargets={}",
                session.getId(),
                scheduledTargets,
                skippedTargets
        );
        return new SessionRefreshResult(
                session.getId(),
                RefreshStatus.SCHEDULED,
                scheduledTargets,
                skippedTargets,
                "Incremental refresh queued"
        );
    }

    private void ensureRefreshAllowed(UserSession session) {
        if (session == null || session.getId() == null) {
            throw new RefreshConflictException("Session refresh requires a persisted session");
        }
        UserSession.PipelineState pipelineState = session.getPipelineState();
        if (pipelineState != null && pipelineState.getStatus() == UserSession.PipelineStatus.RUNNING) {
            throw new RefreshConflictException("Refresh is unavailable while the pipeline is running");
        }
        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                                SyncStatus.SourceKind.ONCHAIN,
                                wallet.getAddress(),
                                networkId.name()
                        )
                        .orElse(null);
                if (status == null || !status.isBackfillComplete()) {
                    throw new RefreshConflictException("Refresh is unavailable until the initial backfill is complete");
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            SyncStatus latest = syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()).orElse(null);
            boolean complete = latest != null
                    ? latest.isBackfillComplete()
                    : integration.getStatus() == UserSession.IntegrationStatus.READY;
            if (!complete) {
                throw new RefreshConflictException("Refresh is unavailable until the initial integration backfill is complete");
            }
        }
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(Objects::nonNull)
                .filter(integration -> integration.getStatus() != UserSession.IntegrationStatus.DISABLED)
                .filter(integration -> integration.getIntegrationId() != null && !integration.getIntegrationId().isBlank())
                .toList();
    }

    private Long resolveOnChainCheckpoint(SyncStatus syncStatus) {
        if (syncStatus == null) {
            return null;
        }
        if (syncStatus.getLastBlockSynced() != null) {
            return syncStatus.getLastBlockSynced();
        }
        if (syncStatus.getId() == null) {
            return null;
        }
        return backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(syncStatus.getId()).stream()
                .filter(segment -> segment.getStatus() == BackfillSegment.SegmentStatus.COMPLETE)
                .map(BackfillSegment::getToBlock)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
    }

    private Instant resolveIntegrationCheckpoint(UserSession.SessionIntegration integration) {
        if (integration.getLastSyncAt() != null) {
            return integration.getLastSyncAt().truncatedTo(ChronoUnit.SECONDS);
        }
        Instant latestSegmentUpperBound = backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(
                        integration.getIntegrationId()
                ).stream()
                .map(BackfillSegment::getToTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        if (latestSegmentUpperBound != null) {
            return latestSegmentUpperBound.truncatedTo(ChronoUnit.SECONDS);
        }
        return syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId())
                .map(SyncStatus::getUpdatedAt)
                .map(timestamp -> timestamp.truncatedTo(ChronoUnit.SECONDS))
                .orElse(null);
    }

    private long resolveCurrentBlock(NetworkId networkId) {
        BlockHeightResolver resolver = blockHeightResolvers.stream()
                .filter(candidate -> candidate.supports(networkId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No block-height resolver registered for " + networkId.name()));
        return resolver.getCurrentBlock(networkId);
    }

    public record SessionRefreshResult(
            String sessionId,
            RefreshStatus status,
            int scheduledTargets,
            int skippedTargets,
            String message
    ) {
    }

    public enum RefreshStatus {
        SCHEDULED,
        UP_TO_DATE
    }

    public static class RefreshConflictException extends RuntimeException {
        public RefreshConflictException(String message) {
            super(message);
        }
    }
}
