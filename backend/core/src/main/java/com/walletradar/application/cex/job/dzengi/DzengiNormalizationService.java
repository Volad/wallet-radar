package com.walletradar.application.cex.job.dzengi;

import com.walletradar.application.cex.normalization.venue.dzengi.DzengiCanonicalTransactionBuilder;
import com.walletradar.application.cex.acquisition.venue.dzengi.PendingDzengiExtractedRowQueryService;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEventRepository;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEventStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Materializes canonical Dzengi docs from extracted staging rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DzengiNormalizationService {

    private static final String MY_TRADES_PREFIX = ":MY_TRADES:";
    private static final String MY_TRADES_V2_PREFIX = ":MY_TRADES_V2:";

    private final PendingDzengiExtractedRowQueryService pendingDzengiExtractedRowQueryService;
    private final DzengiExtractedEventRepository dzengiExtractedEventRepository;
    private final DzengiCanonicalTransactionBuilder builder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final AccountingUniverseService accountingUniverseService;

    public int processNextBatch(int batchSize) {
        return processNextBatch(batchSize, null);
    }

    public int processNextBatch(int batchSize, String sessionId) {
        bindUniverseIfPresent(sessionId);
        List<DzengiExtractedEvent> batch = pendingDzengiExtractedRowQueryService.loadNextBatch(batchSize, sessionId);
        if (batch.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int processed = 0;
        for (DzengiExtractedEvent row : batch) {
            if (normalize(row, now)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean normalize(DzengiExtractedEvent row, Instant now) {
        if (isSupersededByV2(row)) {
            row.setStatus(DzengiExtractedEventStatus.EXCLUDED);
            dzengiExtractedEventRepository.save(row);
            log.debug("Excluded MY_TRADES event superseded by MY_TRADES_V2: {}", row.getId());
            return false;
        }
        NormalizedTransaction transaction = builder.build(row, now);
        if (transaction == null) {
            row.setStatus(DzengiExtractedEventStatus.EXCLUDED);
            dzengiExtractedEventRepository.save(row);
            return false;
        }
        normalizedTransactionStore.upsert(transaction);
        row.setStatus(DzengiExtractedEventStatus.CONFIRMED);
        dzengiExtractedEventRepository.save(row);
        return true;
    }

    /**
     * Returns true when a MY_TRADES extracted event has a corresponding MY_TRADES_V2 event for the
     * same integration + symbol + trade ID. MY_TRADES_V2 is the authoritative source; the legacy
     * MY_TRADES record must be excluded to prevent double-counting of USD flows.
     */
    private boolean isSupersededByV2(DzengiExtractedEvent row) {
        String id = row.getId();
        if (id == null) {
            return false;
        }
        int idx = id.indexOf(MY_TRADES_PREFIX);
        if (idx < 0 || id.contains(MY_TRADES_V2_PREFIX)) {
            return false;
        }
        String v2Id = id.substring(0, idx) + MY_TRADES_V2_PREFIX + id.substring(idx + MY_TRADES_PREFIX.length());
        return dzengiExtractedEventRepository.existsById(v2Id);
    }

    private void bindUniverseIfPresent(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        accountingUniverseService.bindUniverse(sessionId.trim());
    }
}
