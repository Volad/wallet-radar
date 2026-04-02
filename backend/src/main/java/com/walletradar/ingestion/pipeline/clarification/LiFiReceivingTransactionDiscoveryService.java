package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.ingestion.pipeline.onchain.support.RawOrderingMetadataResolver;
import com.walletradar.ingestion.pipeline.onchain.support.ResolvedRawOrderingMetadata;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded LI.FI receiving-tx discovery by official receiving hash across the tracked wallet universe.
 */
@Service
@RequiredArgsConstructor
public class LiFiReceivingTransactionDiscoveryService {

    private static final Comparator<NormalizedTransaction> DESTINATION_PRIORITY = Comparator
            .comparingInt(LiFiReceivingTransactionDiscoveryService::destinationRank)
            .thenComparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(String::compareTo));

    private final ReceiptClarificationGateway receiptClarificationGateway;
    private final TrackedWalletRepository trackedWalletRepository;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder normalizedTransactionBuilder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;

    public Optional<NormalizedTransaction> findOrDiscover(LiFiBridgeStatus status) {
        if (status == null
                || status.receivingNetworkId() == null
                || blank(status.receivingTxHash())
                || status.isSameTransactionEcho()) {
            return Optional.empty();
        }

        Optional<NormalizedTransaction> existing = existingDestination(status);
        if (existing.isPresent()) {
            return existing;
        }

        for (TrackedWallet trackedWallet : trackedWalletRepository.findAllByOrderByAddressAsc()) {
            if (trackedWallet == null || blank(trackedWallet.getAddress())) {
                continue;
            }
            String walletAddress = trackedWallet.getAddress().toLowerCase(Locale.ROOT);
            String rawId = rawId(status.receivingTxHash(), status.receivingNetworkId().name(), walletAddress);

            Optional<RawTransaction> rawOptional = rawTransactionRepository.findById(rawId);
            RawTransaction rawTransaction = rawOptional.orElseGet(() -> receiptClarificationGateway
                    .fetchRawTransactionByHash(status.receivingTxHash(), status.receivingNetworkId(), walletAddress, null)
                    .orElse(null));
            if (rawTransaction == null || !isWalletRelevant(rawTransaction, walletAddress)) {
                continue;
            }

            prepareOrdering(rawTransaction);
            OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
            Instant now = Instant.now();
            NormalizedTransaction normalized = normalizedTransactionStore.upsert(
                    normalizedTransactionBuilder.build(rawTransaction, classificationResult, now)
            );

            rawTransaction.setNormalizationStatus(NormalizationStatus.COMPLETE);
            rawTransaction.setRetryCount(0);
            rawTransaction.setLastError(null);
            rawTransaction.setNextRetryAt(null);
            rawTransactionRepository.save(rawTransaction);

            return Optional.of(normalized);
        }

        return Optional.empty();
    }

    private Optional<NormalizedTransaction> existingDestination(LiFiBridgeStatus status) {
        return normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                        status.receivingTxHash(),
                        status.receivingNetworkId(),
                        NormalizedTransactionSource.ON_CHAIN
                ).stream()
                .filter(Objects::nonNull)
                .min(DESTINATION_PRIORITY);
    }

    private boolean isWalletRelevant(RawTransaction rawTransaction, String walletAddress) {
        return hasWalletTouchEvidence(rawTransaction, walletAddress);
    }

    static boolean hasWalletTouchEvidence(RawTransaction rawTransaction, String walletAddress) {
        if (rawTransaction == null) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        String normalizedWallet = OnChainRawTransactionView.normalizeAddress(walletAddress);
        if (normalizedWallet == null) {
            return false;
        }
        if (normalizedWallet.equals(view.fromAddress())
                || normalizedWallet.equals(view.toAddress())) {
            return true;
        }
        for (Document transfer : view.explorerTokenTransfers()) {
            if (normalizedWallet.equals(view.tokenTransferFrom(transfer))
                    || normalizedWallet.equals(view.tokenTransferTo(transfer))) {
                return true;
            }
        }
        for (Document transfer : view.explorerInternalTransfers()) {
            if (normalizedWallet.equals(view.internalTransferFrom(transfer))
                    || normalizedWallet.equals(view.internalTransferTo(transfer))) {
                return true;
            }
        }
        return false;
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

    private static int destinationRank(NormalizedTransaction candidate) {
        if (candidate == null) {
            return 99;
        }
        if (candidate.getType() == com.walletradar.domain.transaction.normalized.NormalizedTransactionType.BRIDGE_IN) {
            return 0;
        }
        if (candidate.getType() == com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return 1;
        }
        return 2;
    }

    private String rawId(String txHash, String networkId, String walletAddress) {
        return txHash.toLowerCase(Locale.ROOT) + ":" + networkId + ":" + walletAddress;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
