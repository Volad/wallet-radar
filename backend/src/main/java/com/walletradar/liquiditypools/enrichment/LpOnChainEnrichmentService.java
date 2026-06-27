package com.walletradar.liquiditypools.enrichment;

import com.walletradar.liquiditypools.application.LpPositionSnapshotService;
import com.walletradar.liquiditypools.persistence.LpPositionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LpOnChainEnrichmentService {

    private final List<LpPositionReader> readers;
    private final List<LpSnapshotEnricher> enrichers;
    private final LpPositionSnapshotService snapshotService;

    public EnrichmentResult enrich(LpPositionContext context) {
        if (context == null || context.closed()) {
            return EnrichmentResult.empty();
        }
        String lastError = null;
        for (LpPositionReader reader : readers) {
            if (!reader.supports(context)) {
                continue;
            }
            try {
                Optional<LpPositionSnapshot> snapshot = reader.read(context);
                if (snapshot.isPresent()) {
                    LpPositionSnapshot result = snapshot.get();
                    runEnrichers(context, result);
                    // Carry over existing liquidity bins if the reader could not refresh them
                    // (e.g. batch RPC failed). Bins are refreshed on the next successful read.
                    if (result.getLiquidityBins() == null || result.getLiquidityBins().isEmpty()) {
                        snapshotService.findByCorrelationId(context.correlationId()).ifPresent(existing -> {
                            if (existing.getLiquidityBins() != null && !existing.getLiquidityBins().isEmpty()) {
                                result.setLiquidityBins(existing.getLiquidityBins());
                                result.setLiquidityBinsAt(existing.getLiquidityBinsAt());
                            }
                        });
                    }
                    return EnrichmentResult.success(result);
                }
            } catch (Exception error) {
                lastError = reader.getClass().getSimpleName() + ": " + error;
                log.warn("LP enrichment failed correlationId={} reader={} error={}",
                        context.correlationId(), reader.getClass().getSimpleName(), error.toString());
            }
        }
        Optional<LpPositionSnapshot> existing = snapshotService.findByCorrelationId(context.correlationId());
        if (existing.isPresent()) {
            LpPositionSnapshot stale = existing.get();
            stale.setSnapshotStale(true);
            if (lastError != null) {
                stale.setUnavailableReason(lastError);
            }
            return EnrichmentResult.stale(stale);
        }
        if (lastError != null) {
            return EnrichmentResult.failed(lastError);
        }
        return EnrichmentResult.empty();
    }

    private void runEnrichers(LpPositionContext context, LpPositionSnapshot snapshot) {
        for (LpSnapshotEnricher enricher : enrichers) {
            if (!enricher.supports(context)) {
                continue;
            }
            try {
                enricher.enrich(context, snapshot);
            } catch (Exception e) {
                log.warn("LP snapshot enricher failed correlationId={} enricher={} error={}",
                        context.correlationId(), enricher.getClass().getSimpleName(), e.toString());
            }
        }
    }

    public record EnrichmentResult(
            Optional<LpPositionSnapshot> snapshot,
            boolean fresh,
            String failureReason
    ) {
        static EnrichmentResult success(LpPositionSnapshot snapshot) {
            return new EnrichmentResult(Optional.of(snapshot), true, null);
        }

        static EnrichmentResult stale(LpPositionSnapshot snapshot) {
            return new EnrichmentResult(Optional.of(snapshot), false, null);
        }

        static EnrichmentResult failed(String reason) {
            return new EnrichmentResult(Optional.empty(), false, reason);
        }

        static EnrichmentResult empty() {
            return new EnrichmentResult(Optional.empty(), false, null);
        }
    }
}
