package com.walletradar.ingestion.pipeline.clarification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Releases stale clarification leases held by a previously crashed process.
 *
 * <p>When the backend restarts after a crash the PENDING_CLARIFICATION rows that were claimed by
 * the previous process retain their {@code clarificationLeaseUntil} timestamps (up to 300 s in the
 * future). The watchdog fires a clarification resume immediately, but the batch query skips all
 * leased rows and returns 0 — leading to empty clarification cycles until the lease TTL expires.
 *
 * <p>By releasing all future leases at startup we make every PENDING_CLARIFICATION row immediately
 * claimable so the very first clarification batch picks up all outstanding work at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClarificationLeaseRecoveryStartup {

    private final PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService;

    @EventListener(ApplicationReadyEvent.class)
    public void releaseStaleLeases() {
        int released = pendingReceiptClarificationQueryService.releaseAllStaleLeases();
        if (released > 0) {
            log.info("Clarification lease recovery: released {} stale leases from previous process", released);
        } else {
            log.debug("Clarification lease recovery: no stale leases found");
        }
    }
}
