package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.ingestion.pipeline.clarification.RelatedLifecycleDiscoveryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Shared rebuild/save flow for clarification-driven reclassification paths.
 */
@Component
final class ClarificationReclassificationHandler {

    private final OnChainNormalizedTransactionBuilder builder;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    @Nullable
    private final RelatedLifecycleDiscoveryService relatedLifecycleDiscoveryService;
    @Nullable
    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;

    @Autowired
    ClarificationReclassificationHandler(
            OnChainNormalizedTransactionBuilder builder,
            NormalizedTransactionRepository normalizedTransactionRepository,
            @Nullable RelatedLifecycleDiscoveryService relatedLifecycleDiscoveryService,
            @Nullable ProtocolNameEnrichmentService protocolNameEnrichmentService
    ) {
        this.builder = builder;
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.relatedLifecycleDiscoveryService = relatedLifecycleDiscoveryService;
        this.protocolNameEnrichmentService = protocolNameEnrichmentService;
    }

    ClarificationReclassificationHandler(
            OnChainNormalizedTransactionBuilder builder,
            NormalizedTransactionRepository normalizedTransactionRepository
    ) {
        this(builder, normalizedTransactionRepository, null, null);
    }

    ClarificationReclassificationHandler(
            OnChainNormalizedTransactionBuilder builder,
            NormalizedTransactionRepository normalizedTransactionRepository,
            @Nullable RelatedLifecycleDiscoveryService relatedLifecycleDiscoveryService
    ) {
        this(builder, normalizedTransactionRepository, relatedLifecycleDiscoveryService, null);
    }

    NormalizedTransaction persistReclassification(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        NormalizedTransaction reclassified = builder.rebuildAfterReclassification(
                normalizedTransaction,
                rawTransaction,
                classificationResult,
                now
        );
        return normalizedTransactionRepository.save(reclassified);
    }

    NormalizedTransaction persistMetadataClarification(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        NormalizedTransaction clarified = builder.rebuildAfterClarification(
                normalizedTransaction,
                rawTransaction,
                classificationResult,
                now
        );
        NormalizedTransaction saved = normalizedTransactionRepository.save(clarified);
        enrichProtocolName(saved, rawTransaction, now);
        return saved;
    }

    NormalizedTransaction persistReceiptClarification(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            OnChainClassificationResult classificationResult,
            Instant now
    ) {
        NormalizedTransaction reclassified = persistReclassification(
                normalizedTransaction,
                rawTransaction,
                classificationResult,
                now
        );
        enrichProtocolName(reclassified, rawTransaction, now);
        if (relatedLifecycleDiscoveryService != null) {
            relatedLifecycleDiscoveryService.discoverAndNormalize(rawTransaction, classificationResult);
        }
        return reclassified;
    }

    private void enrichProtocolName(
            NormalizedTransaction normalizedTransaction,
            RawTransaction rawTransaction,
            Instant now
    ) {
        if (protocolNameEnrichmentService == null) {
            return;
        }
        protocolNameEnrichmentService.enrich(normalizedTransaction, rawTransaction, now);
    }
}
