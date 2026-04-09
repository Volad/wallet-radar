package com.walletradar.ingestion.job.clarification;

import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-shot repair for historical canonical rows that predate clarification-time protocolName enrichment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProtocolNameRepairJob {

    private final OnChainClarificationProperties properties;
    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            return;
        }
        int enriched = protocolNameEnrichmentService.processRepairSweep(properties.getBatchSize());
        if (enriched > 0) {
            log.info("Protocol name repair complete: updated={}", enriched);
        }
    }
}
