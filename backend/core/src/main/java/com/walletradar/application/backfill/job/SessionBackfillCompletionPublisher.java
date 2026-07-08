package com.walletradar.application.backfill.job;

import com.walletradar.platform.common.config.SchedulerConfig;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.event.WalletNetworkBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.application.session.application.SessionPipelineStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * Promotes wallet×network raw completion into session-scoped backfill completion signals.
 *
 * <p>A short debounce window (DEBOUNCE_DELAY) is applied so that rapid sequential completions
 * from multiple network segments within a single refresh cycle collapse into a single
 * {@link SessionBackfillCompletedEvent}. Without debouncing, each network's segment fires the
 * event independently, triggering redundant full-pipeline runs (linking, replay, snapshot).</p>
 */
@Component
@Slf4j
public class SessionBackfillCompletionPublisher {

    /** How long to wait after the last segment completion before firing the pipeline event. */
    private static final Duration DEBOUNCE_DELAY = Duration.ofSeconds(8);

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineStateService sessionPipelineStateService;
    private final TaskScheduler taskScheduler;

    /** Tracks the pending debounced fire per session. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingFires = new ConcurrentHashMap<>();

    public SessionBackfillCompletionPublisher(
            UserSessionRepository userSessionRepository,
            SyncStatusRepository syncStatusRepository,
            BackfillSegmentRepository backfillSegmentRepository,
            ApplicationEventPublisher applicationEventPublisher,
            SessionPipelineStateService sessionPipelineStateService,
            @Qualifier(SchedulerConfig.SCHEDULER_POOL) TaskScheduler taskScheduler
    ) {
        this.userSessionRepository = userSessionRepository;
        this.syncStatusRepository = syncStatusRepository;
        this.backfillSegmentRepository = backfillSegmentRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.sessionPipelineStateService = sessionPipelineStateService;
        this.taskScheduler = taskScheduler;
    }

    @EventListener
    public void onWalletNetworkBackfillCompleted(WalletNetworkBackfillCompletedEvent event) {
        if (event == null || event.walletAddress() == null || event.walletAddress().isBlank()) {
            return;
        }

        List<UserSession> sessions = userSessionRepository.findAllByWalletsAddress(event.walletAddress());
        for (UserSession session : sessions) {
            maybePublishSessionCompletion(session);
        }
    }

    public void maybePublishSessionCompletionBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        userSessionRepository.findById(sessionId.trim()).ifPresent(this::maybePublishSessionCompletion);
    }

    private void maybePublishSessionCompletion(UserSession session) {
        if (session == null || session.getId() == null || targetCount(session) == 0) {
            return;
        }

        List<UserSession.SessionWallet> wallets = session.getWallets() == null ? List.of() : session.getWallets();
        List<String> addresses = wallets.stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();
        Map<String, SyncStatus> syncStatusByPair = syncStatusRepository.findByWalletAddressIn(addresses).stream()
                .filter(status -> status.getSourceKind() == null || status.getSourceKind() == SyncStatus.SourceKind.ONCHAIN)
                .filter(status -> status.getWalletAddress() != null && status.getNetworkId() != null)
                .collect(Collectors.toMap(
                        status -> pairKey(status.getWalletAddress(), status.getNetworkId()),
                        status -> status,
                        (left, right) -> right
                ));

        for (UserSession.SessionWallet wallet : wallets) {
            for (var network : wallet.getNetworks()) {
                SyncStatus status = syncStatusByPair.get(pairKey(wallet.getAddress(), network.name()));
                if (!isOnChainBackfillComplete(status)) {
                    return;
                }
            }
        }

        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            if (!isIntegrationBackfillComplete(integration)) {
                return;
            }
        }

        // All segments currently visible are complete. Schedule the pipeline event with a short
        // debounce so that remaining networks whose segments are still being planned (and will
        // complete seconds later) don't each trigger a separate full-pipeline run.
        scheduleDebounced(session);
    }

    private void scheduleDebounced(UserSession session) {
        String sessionId = session.getId();
        List<UserSession.SessionWallet> wallets = session.getWallets() == null ? List.of() : session.getWallets();
        int targetCount = targetCount(session);

        ScheduledFuture<?> newFire = taskScheduler.schedule(
                () -> fireSessionCompletion(sessionId, wallets, targetCount),
                Instant.now().plus(DEBOUNCE_DELAY)
        );

        ScheduledFuture<?> existing = pendingFires.put(sessionId, newFire);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
            log.debug(
                    "Backfill completion debounce reset: sessionId={}, delay={}s",
                    sessionId,
                    DEBOUNCE_DELAY.getSeconds()
            );
        } else {
            log.debug(
                    "Backfill completion debounce armed: sessionId={}, delay={}s",
                    sessionId,
                    DEBOUNCE_DELAY.getSeconds()
            );
        }
    }

    private void fireSessionCompletion(String sessionId, List<UserSession.SessionWallet> wallets, int targetCount) {
        pendingFires.remove(sessionId);
        applicationEventPublisher.publishEvent(new SessionBackfillCompletedEvent(
                sessionId,
                wallets.size(),
                targetCount
        ));
        sessionPipelineStateService.markStageComplete(
                sessionId,
                UserSession.PipelineStage.BACKFILL,
                "Raw backfill complete"
        );
        log.info(
                "Live session raw backfill complete: sessionId={}, wallets={}, integrations={}, targets={}",
                sessionId,
                wallets.size(),
                "-",
                targetCount
        );
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(integration -> integration != null
                        && integration.getStatus() != UserSession.IntegrationStatus.DISABLED
                        && integration.getIntegrationId() != null
                        && !integration.getIntegrationId().isBlank())
                .toList();
    }

    private boolean isIntegrationBackfillComplete(UserSession.SessionIntegration integration) {
        SyncStatus integrationStatus = syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()).orElse(null);
        if (integrationStatus != null) {
            return integrationStatus.isBackfillComplete();
        }
        long totalSegments = backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
        if (totalSegments <= 0) {
            return integration.getStatus() == UserSession.IntegrationStatus.READY;
        }
        long completedSegments = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.COMPLETE
        );
        return completedSegments >= totalSegments;
    }

    /**
     * Robustness net: a wallet×network counts as backfill-complete when its boolean flag is set OR the
     * sync_status reached terminal {@code COMPLETE} status. The terminal status is authoritative and cannot
     * coexist with RUNNING/PENDING/FAILED segments, so this never advances a source whose fetch is genuinely
     * still in flight while still rescuing a source whose completion boolean was left stale.
     */
    private static boolean isOnChainBackfillComplete(SyncStatus status) {
        if (status == null) {
            return false;
        }
        return status.isBackfillComplete() || status.getStatus() == SyncStatus.SyncStatusValue.COMPLETE;
    }

    private int targetCount(UserSession session) {
        int walletTargets = session.getWallets() == null ? 0 : session.getWallets().stream()
                .mapToInt(wallet -> wallet.getNetworks() == null ? 0 : wallet.getNetworks().size())
                .sum();
        return walletTargets + enabledIntegrations(session).size();
    }

    private static String pairKey(String walletAddress, String networkId) {
        return walletAddress + "|" + networkId;
    }
}
