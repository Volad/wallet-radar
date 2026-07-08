package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.session.TrackedWallet;
import com.walletradar.domain.session.TrackedWalletRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassifier;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.normalization.pipeline.onchain.repair.ExplorerRawOrderingRepairGateway;
import com.walletradar.application.normalization.pipeline.onchain.support.RawOrderingMetadataResolver;
import com.walletradar.application.normalization.pipeline.onchain.support.ResolvedRawOrderingMetadata;
import com.walletradar.application.normalization.store.IdempotentNormalizedTransactionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded LI.FI receiving-tx discovery by official receiving hash across the tracked wallet universe.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiFiReceivingTransactionDiscoveryService {

    static final String TERMINAL_LIFI_ERROR = "LiFi discovery: terminal, enrichment not applicable";

    private static final Comparator<NormalizedTransaction> DESTINATION_PRIORITY = Comparator
            .comparingInt(LiFiReceivingTransactionDiscoveryService::destinationRank)
            .thenComparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(String::compareTo));

    private final ReceiptClarificationGateway receiptClarificationGateway;
    private final TrackedWalletRepository trackedWalletRepository;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final ProtocolRegistryService protocolRegistryService;
    private final OnChainClassifier onChainClassifier;
    private final OnChainNormalizedTransactionBuilder normalizedTransactionBuilder;
    private final IdempotentNormalizedTransactionStore normalizedTransactionStore;
    private final ExplorerRawOrderingRepairGateway explorerRawOrderingRepairGateway;

    public Optional<NormalizedTransaction> findOrDiscover(LiFiBridgeStatus status) {
        return findOrDiscover(status, null);
    }

    public Optional<NormalizedTransaction> findOrDiscover(
            LiFiBridgeStatus status,
            NormalizedTransaction sourceHint
    ) {
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

        Optional<String> preferredWallet = LiFiDestinationDiscoverySupport.resolvePreferredSourceWallet(
                status,
                sourceHint,
                normalizedTransactionRepository
        );
        List<String> trackedAddresses = trackedWalletRepository.findAllByOrderByAddressAsc().stream()
                .filter(Objects::nonNull)
                .map(TrackedWallet::getAddress)
                .filter(address -> !blank(address))
                .map(address -> address.toLowerCase(Locale.ROOT))
                .toList();
        List<String> walletScanOrder = LiFiDestinationDiscoverySupport.orderedTrackedWalletAddresses(
                trackedAddresses,
                preferredWallet.orElse(null)
        );

        for (String walletAddress : walletScanOrder) {
            Optional<NormalizedTransaction> discovered = discoverForWallet(status, walletAddress, sourceHint);
            if (discovered.isPresent()) {
                return discovered;
            }
        }

        return Optional.empty();
    }

    private Optional<NormalizedTransaction> discoverForWallet(
            LiFiBridgeStatus status,
            String walletAddress,
            NormalizedTransaction sourceHint
    ) {
        String rawId = rawId(status.receivingTxHash(), status.receivingNetworkId().name(), walletAddress);

        Optional<RawTransaction> rawOptional = rawTransactionRepository.findById(rawId);
        if (rawOptional.isPresent() && TERMINAL_LIFI_ERROR.equals(rawOptional.get().getLastError())) {
            return Optional.empty();
        }
        boolean freshlyFetched = rawOptional.isEmpty();
        RawTransaction rawTransaction = rawOptional.orElseGet(() -> receiptClarificationGateway
                .fetchRawTransactionByHash(status.receivingTxHash(), status.receivingNetworkId(), walletAddress, null)
                .orElse(null));
        if (rawTransaction == null) {
            return Optional.empty();
        }

        Optional<LiFiDestinationDiscoveryPath> discoveryPath = LiFiDestinationDiscoverySupport.resolveWalletRelevance(
                rawTransaction,
                walletAddress,
                status,
                protocolRegistryService
        );
        if (discoveryPath.isEmpty()) {
            if (freshlyFetched) {
                // Cache the fetched tx so the next linking run skips the RPC call.
                // Without this save the wallet-relevance check always misses in the DB and
                // triggers an identical RPC fetch on every subsequent linking pass.
                log.debug("LIFI_DISCOVERY_NO_WALLET_RELEVANCE txHash={} network={} wallet={} — persisting to avoid repeated RPC",
                        status.receivingTxHash(), status.receivingNetworkId(), walletAddress);
                rawTransactionRepository.save(rawTransaction);
            }
            return Optional.empty();
        }

        boolean enriched = LiFiDestinationDiscoverySupport.enrichCalldataSettlementBeforeClassification(
                rawTransaction,
                walletAddress,
                status,
                discoveryPath.orElseThrow(),
                sourceHint,
                normalizedTransactionRepository,
                protocolRegistryService
        );
        LiFiDestinationDiscoverySupport.stampDiscoveryPath(rawTransaction, discoveryPath.orElseThrow());

        prepareOrdering(rawTransaction);
        OnChainClassificationResult classificationResult = onChainClassifier.classify(rawTransaction);
        if (!isReliableClassification(classificationResult)) {
            log.warn(
                    "LIFI_RECEIVING_TX_DISCOVERY_INCOMPLETE txHash={} network={} wallet={} path={} type={} enriched={}",
                    status.receivingTxHash(),
                    status.receivingNetworkId(),
                    walletAddress,
                    discoveryPath.orElseThrow(),
                    classificationResult == null ? null : classificationResult.type(),
                    enriched
            );
            if (enriched) {
                // Enrichment was applied (native-asset bridge) but classification still failed — allow retry.
                rawTransaction.setNormalizationStatus(NormalizationStatus.PENDING);
                rawTransaction.setLastError("LiFi destination discovery produced unreliable classification");
            } else {
                // No enrichment applicable (e.g., token bridge source): classification is terminal.
                // Mark the raw tx so subsequent calls are short-circuited without touching normalizationStatus.
                rawTransaction.setLastError(TERMINAL_LIFI_ERROR);
            }
            rawTransactionRepository.save(rawTransaction);
            return Optional.empty();
        }

        Instant now = Instant.now();
        NormalizedTransaction normalized = normalizedTransactionStore.upsert(
                normalizedTransactionBuilder.build(rawTransaction, classificationResult, now)
        );

        rawTransaction.setNormalizationStatus(NormalizationStatus.COMPLETE);
        rawTransaction.setRetryCount(0);
        rawTransaction.setLastError(null);
        rawTransaction.setNextRetryAt(null);
        rawTransactionRepository.save(rawTransaction);

        log.info(
                "LIFI_RECEIVING_TX_DISCOVERED txHash={} network={} wallet={} path={} type={}",
                status.receivingTxHash(),
                status.receivingNetworkId(),
                walletAddress,
                discoveryPath.orElseThrow(),
                normalized.getType()
        );

        return Optional.of(normalized);
    }

    private Optional<NormalizedTransaction> existingDestination(LiFiBridgeStatus status) {
        return normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                        status.receivingTxHash(),
                        status.receivingNetworkId(),
                        NormalizedTransactionSource.ON_CHAIN
                ).stream()
                .filter(Objects::nonNull)
                .filter(this::isReliableDiscoveredDestination)
                .min(DESTINATION_PRIORITY);
    }

    private boolean isReliableDiscoveredDestination(NormalizedTransaction candidate) {
        if (candidate == null) {
            return false;
        }
        return isReliableInboundType(candidate.getType())
                && candidate.getFlows() != null
                && !candidate.getFlows().isEmpty();
    }

    private boolean isReliableClassification(OnChainClassificationResult classificationResult) {
        if (classificationResult == null
                || classificationResult.flows() == null
                || classificationResult.flows().isEmpty()) {
            return false;
        }
        return isReliableInboundType(classificationResult.type());
    }

    private static boolean isReliableInboundType(
            com.walletradar.domain.transaction.normalized.NormalizedTransactionType type
    ) {
        return type == com.walletradar.domain.transaction.normalized.NormalizedTransactionType.BRIDGE_IN
                || type == com.walletradar.domain.transaction.normalized.NormalizedTransactionType.EXTERNAL_TRANSFER_IN;
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
