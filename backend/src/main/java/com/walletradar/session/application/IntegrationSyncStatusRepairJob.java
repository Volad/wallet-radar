package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps integration-backed sync_status rows present and aligned with
 * backfill_segments for sessions created before the sync_status unification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationSyncStatusRepairJob {

    private final UserSessionRepository userSessionRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final IntegrationSyncStatusService integrationSyncStatusService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        repair();
    }

    @Scheduled(fixedDelayString = "${walletradar.integration.sync-status-repair-interval-ms:60000}")
    public void repair() {
        int updated = 0;
        for (UserSession session : userSessionRepository.findAll()) {
            if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
                continue;
            }
            for (UserSession.SessionIntegration integration : session.getIntegrations()) {
                if (integration == null || integration.getIntegrationId() == null || integration.getIntegrationId().isBlank()) {
                    continue;
                }
                int total = (int) backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
                int completed = (int) backfillSegmentRepository.countByIntegrationIdAndStatus(
                        integration.getIntegrationId(),
                        BackfillSegment.SegmentStatus.COMPLETE
                );
                int failed = (int) backfillSegmentRepository.countByIntegrationIdAndStatus(
                        integration.getIntegrationId(),
                        BackfillSegment.SegmentStatus.FAILED
                );
                integrationSyncStatusService.update(integration, total, completed, failed, integration.getLastError());
                updated++;
            }
        }
        if (updated > 0) {
            log.info("Integration sync_status reconciliation complete: updated={}", updated);
        }
    }
}
