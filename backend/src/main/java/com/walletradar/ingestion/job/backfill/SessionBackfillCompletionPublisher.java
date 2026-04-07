package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.event.WalletNetworkBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
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

    private void maybePublishSessionCompletion(UserSession session) {
        if (session == null || session.getId() == null || session.getWallets() == null || session.getWallets().isEmpty()) {
            return;
        }

        List<String> addresses = session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();
        Map<String, SyncStatus> syncStatusByPair = syncStatusRepository.findByWalletAddressIn(addresses).stream()
                .filter(status -> status.getWalletAddress() != null && status.getNetworkId() != null)
                .collect(Collectors.toMap(
                        status -> pairKey(status.getWalletAddress(), status.getNetworkId()),
                        status -> status,
                        (left, right) -> right
                ));

        int targetCount = 0;
        for (UserSession.SessionWallet wallet : session.getWallets()) {
            for (var network : wallet.getNetworks()) {
                targetCount++;
                SyncStatus status = syncStatusByPair.get(pairKey(wallet.getAddress(), network.name()));
                if (status == null || !status.isBackfillComplete()) {
                    return;
                }
            }
        }

        applicationEventPublisher.publishEvent(new SessionBackfillCompletedEvent(
                session.getId(),
                session.getWallets().size(),
                targetCount
        ));
        sessionPipelineStateService.markStageComplete(
                session.getId(),
                UserSession.PipelineStage.BACKFILL,
                "Raw backfill complete"
        );
        log.info(
                "Live session raw backfill complete: sessionId={}, wallets={}, targets={}",
                session.getId(),
                session.getWallets().size(),
                targetCount
        );
    }

    private static String pairKey(String walletAddress, String networkId) {
        return walletAddress + "|" + networkId;
    }
}
