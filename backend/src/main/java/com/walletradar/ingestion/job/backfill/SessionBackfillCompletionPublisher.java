package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.event.WalletNetworkBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Promotes wallet×network raw completion into session-scoped backfill completion signals.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionBackfillCompletionPublisher {

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineStateService sessionPipelineStateService;

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
                if (status == null || !status.isBackfillComplete()) {
                    return;
                }
            }
        }

        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            if (!isIntegrationBackfillComplete(integration)) {
                return;
            }
        }

        int targetCount = targetCount(session);

        applicationEventPublisher.publishEvent(new SessionBackfillCompletedEvent(
                session.getId(),
                wallets.size(),
                targetCount
        ));
        sessionPipelineStateService.markStageComplete(
                session.getId(),
                UserSession.PipelineStage.BACKFILL,
                "Raw backfill complete"
        );
        log.info(
                "Live session raw backfill complete: sessionId={}, wallets={}, integrations={}, targets={}",
                session.getId(),
                wallets.size(),
                enabledIntegrations(session).size(),
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
