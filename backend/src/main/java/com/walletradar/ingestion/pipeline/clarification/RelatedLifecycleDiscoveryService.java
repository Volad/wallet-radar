package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.ingestion.pipeline.onchain.support.RawOrderingMetadataResolver;
import com.walletradar.ingestion.pipeline.onchain.support.ResolvedRawOrderingMetadata;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers missing real protocol lifecycle txs from same-source explorer evidence and
 * normalizes them immediately so clarification does not require a second rerun cycle.
 */
@Service
@RequiredArgsConstructor
public class RelatedLifecycleDiscoveryService {

    private final OnChainClarificationProperties clarificationProperties;
    private final ReceiptClarificationGateway clarificationGateway;
    private final RawTransactionRepository rawTransactionRepository;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder normalizedTransactionBuilder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;
    private final OnChainLifecycleLinkService onChainLifecycleLinkService;

    public int discoverAndNormalize(RawTransaction anchorRawTransaction, OnChainClassificationResult anchorClassification) {
        if (!shouldDiscover(anchorRawTransaction, anchorClassification)) {
            return 0;
        }
        OnChainClarificationProperties.RelatedDiscovery settings = clarificationProperties.getRelatedDiscovery();
        if (settings == null || !settings.isEnabled()) {
            return 0;
        }

        OnChainRawTransactionView anchorView = OnChainRawTransactionView.wrap(anchorRawTransaction);
        Long anchorBlockNumber = anchorRawTransaction.getBlockNumber();
        if (anchorBlockNumber == null || anchorBlockNumber <= 0) {
            return 0;
        }

        long toBlock = anchorBlockNumber + Math.max(1L, settings.getForwardBlockWindow());
        List<String> candidateHashes = clarificationGateway.findWalletRelatedTransactionHashes(
                anchorRawTransaction.getWalletAddress(),
                anchorView.networkId(),
                anchorRawTransaction.getSyncMethod(),
                anchorBlockNumber,
                toBlock,
                settings.getMaxPages()
        );
        if (candidateHashes.isEmpty()) {
            return 0;
        }

        Set<String> saved = new LinkedHashSet<>();
        for (String candidateHash : candidateHashes) {
            if (candidateHash == null
                    || candidateHash.isBlank()
                    || candidateHash.equalsIgnoreCase(anchorRawTransaction.getTxHash())) {
                continue;
            }
            String candidateId = candidateHash.toLowerCase(Locale.ROOT) + ":"
                    + anchorRawTransaction.getNetworkId() + ":"
                    + anchorRawTransaction.getWalletAddress().toLowerCase(Locale.ROOT);
            if (rawTransactionRepository.existsById(candidateId)) {
                continue;
            }

            Optional<RawTransaction> candidateRawOptional = clarificationGateway.fetchRawTransactionByHash(
                    candidateHash,
                    anchorView.networkId(),
                    anchorRawTransaction.getWalletAddress(),
                    anchorRawTransaction.getSyncMethod()
            );
            if (candidateRawOptional.isEmpty()) {
                continue;
            }

            RawTransaction candidateRaw = candidateRawOptional.get();
            prepareOrdering(candidateRaw);
            OnChainClassificationResult candidateClassification = onChainClassifier.classify(candidateRaw);
            if (!isRelevantDiscoveredLifecycle(candidateClassification)) {
                continue;
            }

            Instant now = Instant.now();
            var normalized = normalizedTransactionStore.upsert(
                    normalizedTransactionBuilder.build(candidateRaw, candidateClassification, now)
            );
            onChainLifecycleLinkService.link(candidateRaw, normalized);
            candidateRaw.setNormalizationStatus(NormalizationStatus.COMPLETE);
            candidateRaw.setRetryCount(0);
            candidateRaw.setLastError(null);
            candidateRaw.setNextRetryAt(null);
            rawTransactionRepository.save(candidateRaw);
            saved.add(candidateId);
        }
        return saved.size();
    }

    private boolean shouldDiscover(
            RawTransaction anchorRawTransaction,
            OnChainClassificationResult anchorClassification
    ) {
        if (anchorRawTransaction == null || anchorClassification == null) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(anchorRawTransaction);
        if (view.networkId() == null || view.walletAddress() == null) {
            return false;
        }
        if (!"GMX".equalsIgnoreCase(anchorClassification.protocolName())) {
            return false;
        }
        return switch (anchorClassification.type()) {
            case DERIVATIVE_ORDER_REQUEST,
                    DERIVATIVE_ORDER_EXECUTION,
                    DERIVATIVE_ORDER_CANCEL,
                    DERIVATIVE_POSITION_INCREASE,
                    DERIVATIVE_POSITION_DECREASE,
                    LP_ENTRY_REQUEST,
                    LP_ENTRY_SETTLEMENT,
                    LP_EXIT_REQUEST,
                    LP_EXIT_SETTLEMENT -> true;
            default -> false;
        };
    }

    private boolean isRelevantDiscoveredLifecycle(OnChainClassificationResult classificationResult) {
        if (classificationResult == null || !"GMX".equalsIgnoreCase(classificationResult.protocolName())) {
            return false;
        }
        if (classificationResult.status() == NormalizedTransactionStatus.NEEDS_REVIEW) {
            return false;
        }
        return switch (classificationResult.type()) {
            case DERIVATIVE_ORDER_REQUEST,
                    DERIVATIVE_ORDER_EXECUTION,
                    DERIVATIVE_ORDER_CANCEL,
                    DERIVATIVE_POSITION_INCREASE,
                    DERIVATIVE_POSITION_DECREASE,
                    LP_ENTRY_REQUEST,
                    LP_ENTRY_SETTLEMENT,
                    LP_EXIT_REQUEST,
                    LP_EXIT_SETTLEMENT -> true;
            default -> false;
        };
    }

    private void prepareOrdering(RawTransaction rawTransaction) {
        RawOrderingMetadataResolver.canonicalizeTopLevel(rawTransaction);
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        if (view.transactionIndex() != null && view.blockTimestamp() != null) {
            return;
        }
        explorerRawOrderingRepairGateway.fetch(view.txHash(), view.networkId())
                .ifPresent(repaired -> applyRepairedOrdering(rawTransaction, repaired));
    }

    private void applyRepairedOrdering(
            RawTransaction rawTransaction,
            ResolvedRawOrderingMetadata repaired
    ) {
        ResolvedRawOrderingMetadata existing = RawOrderingMetadataResolver.resolve(rawTransaction);
        ResolvedRawOrderingMetadata merged = new ResolvedRawOrderingMetadata(
                existing.epochSeconds() != null ? existing.epochSeconds() : repaired.epochSeconds(),
                existing.transactionIndex() != null ? existing.transactionIndex() : repaired.transactionIndex()
        );
        RawOrderingMetadataResolver.canonicalizeTopLevel(rawTransaction, merged);
    }
}
