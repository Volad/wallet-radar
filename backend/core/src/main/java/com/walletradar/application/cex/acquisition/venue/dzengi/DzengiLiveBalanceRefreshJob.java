package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps Dzengi live balance snapshots warm in memory and Mongo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DzengiLiveBalanceRefreshJob {

    private final DzengiLiveBalanceService dzengiLiveBalanceService;
    private final UserSessionRepository userSessionRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${walletradar.integration.dzengi.live-balance-refresh-interval-ms:300000}")
    public void scheduledRefresh() {
        refresh();
    }

    private void refresh() {
        if (!running.compareAndSet(false, true)) {
            log.info("Dzengi live balance refresh skipped because a previous run is still active");
            return;
        }
        try {
            Set<String> integrationIds = discoverActiveDzengiIntegrationIds();
            int refreshed = 0;
            int failed = 0;
            for (String integrationId : integrationIds) {
                try {
                    if (dzengiLiveBalanceService.refresh(integrationId).isPresent()) {
                        refreshed++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn(
                            "Dzengi live balance refresh failed for integration {}: {}",
                            integrationId,
                            ex.getMessage()
                    );
                }
            }
            log.info(
                    "Dzengi live balance refresh complete integrations={} refreshed={} failed={}",
                    integrationIds.size(),
                    refreshed,
                    failed
            );
        } finally {
            running.set(false);
        }
    }

    private Set<String> discoverActiveDzengiIntegrationIds() {
        Set<String> integrationIds = new LinkedHashSet<>();
        for (UserSession session : userSessionRepository.findAll()) {
            if (session.getIntegrations() == null) {
                continue;
            }
            for (UserSession.SessionIntegration integration : session.getIntegrations()) {
                if (integration == null
                        || integration.getIntegrationId() == null
                        || integration.getIntegrationId().isBlank()
                        || integration.getProvider() != UserSession.IntegrationProvider.DZENGI
                        || integration.getStatus() == UserSession.IntegrationStatus.DISABLED) {
                    continue;
                }
                integrationIds.add(integration.getIntegrationId());
            }
        }
        return integrationIds;
    }
}
