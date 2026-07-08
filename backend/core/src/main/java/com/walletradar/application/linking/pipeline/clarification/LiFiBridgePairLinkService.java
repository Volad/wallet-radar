package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.BridgeAssetFamilySupport;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.LiFiRouteSupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Materializes LI.FI / Jumper destination-side bridge pairs once official receiving-tx evidence is available.
 */
@Service
@RequiredArgsConstructor
public class LiFiBridgePairLinkService {

    private static final Duration ROUTED_BRIDGE_FALLBACK_MAX_TIME_DELTA = Duration.ofSeconds(180);
    private static final BigDecimal ROUTED_BRIDGE_FALLBACK_MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.15");
    private static final Duration ROUTED_BRIDGE_GENERIC_MAX_TIME_DELTA = Duration.ofSeconds(90);
    private static final BigDecimal ROUTED_BRIDGE_GENERIC_MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.02");
    private static final Duration STATUS_MISS_RETRY_DELAY = Duration.ofHours(6);
    private static final int ROUTED_BRIDGE_FALLBACK_CANDIDATE_LIMIT = 12;
    private static final int STATUS_LOOKUP_LANES = 4;
    private static final Set<String> USD_STABLE_SYMBOLS = Set.of(
            "USDC",
            "USDT",
            "USD₮0",
            "USDT0",
            "USDB",
            "USDBC",
            "USDE",
            "DEUSD",
            "GHO",
            "AUSD"
    );
    private static final Comparator<NormalizedTransaction> DESTINATION_SELECTION_ORDER = Comparator
            .comparingInt(LiFiBridgePairLinkService::destinationSelectionRank)
            .thenComparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(String::compareTo));
    private static final Comparator<NormalizedTransaction> SOURCE_SELECTION_ORDER = Comparator
            .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(String::compareTo));

    private final LiFiStatusGateway liFiStatusGateway;
    private final PendingLiFiBridgeSourceQueryService pendingLiFiBridgeSourceQueryService;
    private final LiFiReceivingTransactionDiscoveryService liFiReceivingTransactionDiscoveryService;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final MongoOperations mongoOperations;
    private final ProtocolRegistryService protocolRegistryService;

    public int reconcileOutstandingSources(int batchSize) {
        int changed = 0;
        List<SourceContext> statusLookupCandidates = new ArrayList<>();
        for (NormalizedTransaction candidate : pendingLiFiBridgeSourceQueryService.loadNextBatch(batchSize)) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Optional<RawTransaction> rawOptional = rawTransactionRepository.findById(candidate.getId());
            if (rawOptional.isEmpty()) {
                continue;
            }
            RawTransaction rawTransaction = rawOptional.get();
            if (linkInternal(rawTransaction, candidate, false)) {
                changed++;
            } else if (needsStatusLookup(rawTransaction, candidate)) {
                statusLookupCandidates.add(new SourceContext(rawTransaction, candidate));
            }
        }
        for (SourceContext context : fetchStatuses(statusLookupCandidates)) {
            if (linkInternal(context.rawTransaction(), context.normalizedTransaction(), false)) {
                changed++;
            }
        }
        for (NormalizedTransaction candidate : pendingLiFiBridgeSourceQueryService.loadAnchoredWithoutInboundBatch(batchSize)) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Optional<RawTransaction> rawOptional = rawTransactionRepository.findById(candidate.getId());
            if (rawOptional.isEmpty()) {
                continue;
            }
            if (linkInternal(rawOptional.get(), candidate, false)) {
                changed++;
            }
        }
        return changed;
    }

    public void link(@Nullable RawTransaction rawTransaction, @Nullable NormalizedTransaction normalizedTransaction) {
        linkInternal(rawTransaction, normalizedTransaction, true);
    }

    private boolean linkInternal(
            @Nullable RawTransaction rawTransaction,
            @Nullable NormalizedTransaction normalizedTransaction,
            boolean allowStatusLookup
    ) {
        if (rawTransaction == null || normalizedTransaction == null) {
            return false;
        }
        if (normalizedTransaction.getSource() != NormalizedTransactionSource.ON_CHAIN) {
            return false;
        }

        Optional<NormalizedTransaction> pairedDestination = Optional.empty();
        if (isLiFiSourceCandidate(rawTransaction, normalizedTransaction)) {
            pairedDestination = seedSourceCounterparty(rawTransaction, normalizedTransaction, allowStatusLookup);
        }

        String anchorBefore = pairingState(normalizedTransaction);
        if (pairedDestination.isPresent()) {
            NormalizedTransaction destination = pairedDestination.get();
            String destinationBefore = pairingState(destination);
            materializePair(normalizedTransaction, destination);
            return !Objects.equals(anchorBefore, pairingState(normalizedTransaction))
                    || !Objects.equals(destinationBefore, pairingState(destination));
        }
        NormalizedTransaction materializedSource = findSourceByReceivingTx(normalizedTransaction).orElse(null);
        if (materializedSource == null) {
            return !Objects.equals(anchorBefore, pairingState(normalizedTransaction));
        }
        if (!hasText(materializedSource.getMatchedCounterparty())
                || !sameHash(materializedSource.getMatchedCounterparty(), normalizedTransaction.getTxHash())) {
            return false;
        }
        String sourceBefore = pairingState(materializedSource);
        materializePair(materializedSource, normalizedTransaction);
        return !Objects.equals(sourceBefore, pairingState(materializedSource))
                || !Objects.equals(anchorBefore, pairingState(normalizedTransaction));
    }

    private Optional<NormalizedTransaction> seedSourceCounterparty(
            RawTransaction rawTransaction,
            NormalizedTransaction source,
            boolean allowStatusLookup
    ) {
        Optional<LiFiBridgeStatus> statusOptional = readExistingStatus(rawTransaction);
        if (statusOptional.isEmpty()) {
            Optional<NormalizedTransaction> localDestination = resolveRoutedBridgeFallbackDestination(rawTransaction, source);
            if (localDestination.isPresent()) {
                return localDestination;
            }
        }
        if (statusOptional.isEmpty() && allowStatusLookup && !isStatusMissRetryDeferred(rawTransaction, Instant.now())) {
            statusOptional = liFiStatusGateway.fetchBridgeStatus(source.getTxHash());
            statusOptional.ifPresent(status -> persistStatusEvidence(rawTransaction, status));
            if (statusOptional.isEmpty()) {
                persistStatusMiss(rawTransaction, Instant.now());
            }
        }
        if (statusOptional.isEmpty()) {
            return Optional.empty();
        }

        LiFiBridgeStatus status = statusOptional.get();
        if (status.isSameTransactionEcho()) {
            clearSelfLinkIfPresent(source);
            return Optional.empty();
        }
        List<NormalizedTransaction> currentDestinations = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                status.receivingTxHash(),
                status.receivingNetworkId(),
                NormalizedTransactionSource.ON_CHAIN
        );
        Optional<NormalizedTransaction> existingDestination = currentDestinations.stream()
                .filter(Objects::nonNull)
                .filter(this::isMaterializableDestination)
                .sorted(DESTINATION_SELECTION_ORDER)
                .findFirst();
        if (existingDestination.isPresent()) {
            return existingDestination;
        }
        Optional<NormalizedTransaction> discoveredDestination = liFiReceivingTransactionDiscoveryService.findOrDiscover(status, source);
        if (discoveredDestination.isPresent()) {
            return discoveredDestination;
        }
        seedSourceAnchorFromStatus(source, status);
        return Optional.empty();
    }

    private boolean needsStatusLookup(RawTransaction rawTransaction, NormalizedTransaction normalizedTransaction) {
        return isLiFiSourceCandidate(rawTransaction, normalizedTransaction)
                && readExistingStatus(rawTransaction).isEmpty()
                && !isStatusMissRetryDeferred(rawTransaction, Instant.now());
    }

    private List<SourceContext> fetchStatuses(List<SourceContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        List<Callable<StatusLookupResult>> tasks = contexts.stream()
                .map(context -> (Callable<StatusLookupResult>) () -> new StatusLookupResult(
                        context,
                        liFiStatusGateway.fetchBridgeStatus(context.normalizedTransaction().getTxHash())
                ))
                .toList();
        int lanes = Math.max(1, Math.min(STATUS_LOOKUP_LANES, tasks.size()));
        ExecutorService executor = Executors.newFixedThreadPool(lanes);
        ArrayList<SourceContext> withStatus = new ArrayList<>();
        try {
            List<Future<StatusLookupResult>> futures = executor.invokeAll(tasks);
            for (Future<StatusLookupResult> future : futures) {
                try {
                    StatusLookupResult result = future.get();
                    if (result.status().isPresent()) {
                        persistStatusEvidence(result.context().rawTransaction(), result.status().orElseThrow());
                        withStatus.add(result.context());
                    } else {
                        persistStatusMiss(result.context().rawTransaction(), Instant.now());
                    }
                } catch (ExecutionException ignored) {
                    // Local matching already ran; leave the row unresolved for a later retry.
                }
            }
            return List.copyOf(withStatus);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            executor.shutdownNow();
        }
    }

    private void materializePair(NormalizedTransaction source, NormalizedTransaction destination) {
        RawTransaction destinationRaw = rawTransactionRepository.findById(destination.getId()).orElse(null);
        materializePair(source, destination, destinationRaw);
    }

    private void materializePair(
            NormalizedTransaction source,
            NormalizedTransaction destination,
            @Nullable RawTransaction destinationRaw
    ) {
        if (shouldPreserveExistingDestinationPairing(source, destination)) {
            materializeSupplementalSourceAnchor(source, destination);
            return;
        }
        Instant now = Instant.now();
        String correlationId = source.getCorrelationId() != null && !source.getCorrelationId().isBlank()
                ? source.getCorrelationId()
                : correlationId(source.getTxHash());

        boolean continuityCandidate = supportsPlainMoveBasis(source, destination);
        List<NormalizedTransaction> updates = new ArrayList<>();

        // Simple cross-asset bridge swaps (cc=false AND exactly 1:1 flow pair with different families)
        // must NOT carry matchedCounterparty on the source leg: a set counterparty routes CARRY_OUT
        // through bridgeSettlementKey() ("bridge-settlement:") while the BRIDGE_IN drains only
        // "bridge:" keys — they can never match, leaving the CARRY_OUT orphaned (~$968 guard breach).
        // Multi-flow bridges (cc=false because there are ≥2 source or destination principal flows)
        // retain the counterparty because hasSinglePrincipalTransferFlow() prevents bridgeSettlementKey()
        // from firing, and the UI still needs the link.
        boolean simpleCrossAssetSwap = !continuityCandidate
                && principalFlows(source, -1).size() == 1
                && principalFlows(destination, 1).size() == 1;
        String targetSourceCounterparty = simpleCrossAssetSwap ? null : destination.getTxHash();
        boolean sourceCounterpartyMismatch = simpleCrossAssetSwap
                ? hasText(source.getMatchedCounterparty())
                : !sameHash(source.getMatchedCounterparty(), destination.getTxHash());
        if (sourceCounterpartyMismatch
                || !sameCorrelation(source.getCorrelationId(), correlationId)
                || !Objects.equals(source.getContinuityCandidate(), continuityCandidate)) {
            source.setMatchedCounterparty(targetSourceCounterparty);
            source.setCorrelationId(correlationId);
            source.setContinuityCandidate(continuityCandidate);
            source.setUpdatedAt(now);
            updates.add(source);
        }

        boolean destinationChanged = false;
        if (isMaterializableDestination(destination) && isInboundOnly(destination)) {
            if (destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
                destination.setType(NormalizedTransactionType.BRIDGE_IN);
                destinationChanged = true;
            }
            if (alignDestinationInboundRolesForBridgeSettlement(source, destination)) {
                destinationChanged = true;
            }
            if (destination.getClassifiedBy() == null) {
                destination.setClassifiedBy(ClassificationSource.HEURISTIC);
            }
            if (destination.getConfidence() == null || destination.getConfidence() == ConfidenceLevel.LOW) {
                destination.setConfidence(ConfidenceLevel.MEDIUM);
            }
        }
        if (!hasText(destination.getProtocolName()) && destinationRaw != null) {
            Optional<ProtocolRegistryEntry> settlementEntry = resolveRoutedSettlementEntry(destinationRaw, destination);
            if (settlementEntry.isPresent()) {
                ProtocolRegistryEntry entry = settlementEntry.get();
                destination.setProtocolName(entry.protocolName());
                destination.setProtocolVersion(entry.protocolVersion());
                destinationChanged = true;
            }
        }
        if (!sameHash(destination.getMatchedCounterparty(), source.getTxHash())) {
            destination.setMatchedCounterparty(source.getTxHash());
            destinationChanged = true;
        }
        if (!sameCorrelation(destination.getCorrelationId(), correlationId)) {
            destination.setCorrelationId(correlationId);
            destinationChanged = true;
        }
        if (!Objects.equals(destination.getContinuityCandidate(), continuityCandidate)) {
            destination.setContinuityCandidate(continuityCandidate);
            destinationChanged = true;
        }
        if (continuityCandidate) {
            if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(source, now)) {
                if (!updates.contains(source)) {
                    updates.add(source);
                }
            }
            if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(destination, now)) {
                destinationChanged = true;
            }
            if (BridgePairLinkSupport.applyLinkedBridgeCounterparty(source, destination, now)) {
                destinationChanged = true;
            }
        }
        if (destinationChanged) {
            destination.setUpdatedAt(now);
            updates.add(destination);
        }

        if (!updates.isEmpty()) {
            normalizedTransactionRepository.saveAll(deduplicate(updates));
        }
    }

    private boolean shouldPreserveExistingDestinationPairing(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        return source != null
                && destination != null
                && hasText(destination.getMatchedCounterparty())
                && hasText(destination.getCorrelationId())
                && !sameHash(destination.getMatchedCounterparty(), source.getTxHash());
    }

    /**
     * Anchors a supplemental LI.FI source to an already-paired destination without overwriting
     * the principal reciprocal pair on the destination header.
     */
    private void materializeSupplementalSourceAnchor(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        RawTransaction sourceRaw = rawTransactionRepository.findById(source.getId()).orElse(null);
        Optional<LiFiBridgeStatus> statusOptional = readExistingStatus(sourceRaw);
        if (statusOptional.isEmpty()) {
            return;
        }
        LiFiBridgeStatus status = statusOptional.get();
        if (status.apiStatus() == null
                || !"DONE".equalsIgnoreCase(status.apiStatus())
                || !sameHash(status.receivingTxHash(), destination.getTxHash())) {
            return;
        }

        List<NormalizedTransaction.Flow> inboundFlows = destination.getFlows() == null
                ? List.of()
                : destination.getFlows().stream()
                        .filter(Objects::nonNull)
                        .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                        .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0)
                        .toList();
        Optional<SupplementalBridgeLegMatch> supplementalMatch = selectSupplementalBridgeLegMatch(
                source,
                destination,
                inboundFlows
        );
        if (supplementalMatch.isEmpty()) {
            return;
        }
        NormalizedTransaction.Flow matchedInbound = supplementalMatch.orElseThrow().inbound();
        NormalizedTransaction.Flow matchedOutbound = supplementalMatch.orElseThrow().outbound();

        Instant now = Instant.now();
        String correlationId = correlationId(source.getTxHash());
        boolean continuityCandidate = BridgePairLinkSupport.supportsSupplementalMoveBasis(
                matchedOutbound,
                matchedInbound
        );
        List<NormalizedTransaction> updates = new ArrayList<>();
        boolean sourceChanged = false;

        // Apply the same cross-asset guard as materializePair: suppress matchedCounterparty on the
        // source only for cc=false single-outbound-flow supplemental anchors.
        boolean supplementalSimpleCrossAsset = !continuityCandidate && principalFlows(source, -1).size() == 1;
        if (!supplementalSimpleCrossAsset) {
            if (!sameHash(source.getMatchedCounterparty(), destination.getTxHash())) {
                source.setMatchedCounterparty(destination.getTxHash());
                sourceChanged = true;
            }
        } else if (hasText(source.getMatchedCounterparty())) {
            source.setMatchedCounterparty(null);
            sourceChanged = true;
        }
        if (!sameCorrelation(source.getCorrelationId(), correlationId)) {
            source.setCorrelationId(correlationId);
            sourceChanged = true;
        }
        if (!Objects.equals(source.getContinuityCandidate(), continuityCandidate)) {
            source.setContinuityCandidate(continuityCandidate);
            sourceChanged = true;
        }
        if (continuityCandidate) {
            if (BridgePairLinkSupport.retagSupplementalOutboundFlow(source, matchedOutbound, now)) {
                sourceChanged = true;
            }
            if (BridgePairLinkSupport.retagSupplementalInboundFlow(
                    destination,
                    matchedInbound,
                    source.getTxHash(),
                    now
            )) {
                updates.add(destination);
            }
        }
        if (sourceChanged) {
            source.setUpdatedAt(now);
            updates.add(source);
        }
        if (!updates.isEmpty()) {
            normalizedTransactionRepository.saveAll(deduplicate(updates));
        }
    }

    private Optional<NormalizedTransaction> findSourceByReceivingTx(NormalizedTransaction destination) {
        if (!hasText(destination.getTxHash())) {
            return Optional.empty();
        }
        return normalizedTransactionRepository.findAllByMatchedCounterpartyAndSource(
                        destination.getTxHash(),
                        NormalizedTransactionSource.ON_CHAIN
                ).stream()
                .filter(candidate -> candidate != null && candidate.getType() == NormalizedTransactionType.BRIDGE_OUT)
                .filter(candidate -> !sameHash(candidate.getTxHash(), destination.getTxHash()))
                .sorted(SOURCE_SELECTION_ORDER)
                .findFirst();
    }

    private Optional<NormalizedTransaction> resolveRoutedBridgeFallbackDestination(
            RawTransaction sourceRaw,
            NormalizedTransaction source
    ) {
        if (!isRoutedBridgeFallbackSource(sourceRaw, source)) {
            return Optional.empty();
        }
        List<NormalizedTransaction> accepted = loadRoutedBridgeFallbackDestinationCandidates(source).stream()
                .filter(Objects::nonNull)
                .filter(candidate -> isStrongRoutedBridgeFallbackDestination(source, candidate))
                .toList();
        if (accepted.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(accepted.getFirst());
    }

    private List<NormalizedTransaction> loadRoutedBridgeFallbackDestinationCandidates(NormalizedTransaction source) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("walletAddress").is(source.getWalletAddress()),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN)
                ),
                Criteria.where("blockTimestamp")
                        .gte(source.getBlockTimestamp().minus(ROUTED_BRIDGE_FALLBACK_MAX_TIME_DELTA))
                        .lte(source.getBlockTimestamp().plus(ROUTED_BRIDGE_FALLBACK_MAX_TIME_DELTA)),
                Criteria.where("txHash").ne(source.getTxHash())
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(ROUTED_BRIDGE_FALLBACK_CANDIDATE_LIMIT);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private boolean isRoutedBridgeFallbackSource(
            RawTransaction rawTransaction,
            NormalizedTransaction normalizedTransaction
    ) {
        if (!isLiFiSourceCandidate(rawTransaction, normalizedTransaction)) {
            return false;
        }
        return normalizedTransaction.getWalletAddress() != null
                && normalizedTransaction.getBlockTimestamp() != null
                && principalFlows(normalizedTransaction, -1).size() == 1;
    }

    private boolean isStrongRoutedBridgeFallbackDestination(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        if (!isMaterializableDestination(destination)) {
            return false;
        }
        if (!sameWallet(source, destination)
                || sameNetwork(source, destination)
                || sameHash(source.getTxHash(), destination.getTxHash())) {
            return false;
        }
        String expectedCorrelationId = correlationId(source.getTxHash());
        if (!isPairingStateCompatible(source.getMatchedCounterparty(), destination.getTxHash())
                || !isPairingStateCompatible(destination.getMatchedCounterparty(), source.getTxHash())) {
            return false;
        }
        if (!isCorrelationCompatible(source.getCorrelationId(), expectedCorrelationId)
                || !isCorrelationCompatible(destination.getCorrelationId(), expectedCorrelationId)) {
            return false;
        }
        RawTransaction destinationRaw = rawTransactionRepository.findById(destination.getId()).orElse(null);
        boolean hasTrustedSettlementEvidence = resolveRoutedSettlementEntry(destinationRaw, destination).isPresent();
        List<NormalizedTransaction.Flow> sourcePrincipal = principalFlows(source, -1);
        List<NormalizedTransaction.Flow> destinationPrincipal = principalFlows(destination, 1);
        if (sourcePrincipal.size() != 1 || destinationPrincipal.size() != 1) {
            return false;
        }
        NormalizedTransaction.Flow sourceFlow = sourcePrincipal.getFirst();
        NormalizedTransaction.Flow destinationFlow = destinationPrincipal.getFirst();
        if (!supportsBridgeContinuity(sourceFlow, destinationFlow)) {
            return false;
        }
        if (sourceFlow.getQuantityDelta() == null || destinationFlow.getQuantityDelta() == null) {
            return false;
        }
        if (destinationFlow.getQuantityDelta().abs().compareTo(sourceFlow.getQuantityDelta().abs()) > 0) {
            return false;
        }
        long deltaSeconds = Math.abs(Duration.between(source.getBlockTimestamp(), destination.getBlockTimestamp()).toSeconds());
        if (deltaSeconds > ROUTED_BRIDGE_FALLBACK_MAX_TIME_DELTA.toSeconds()) {
            return false;
        }
        BigDecimal relativeQuantityDiff = relativeQuantityDiff(sourceFlow, destinationFlow);
        if (relativeQuantityDiff.compareTo(ROUTED_BRIDGE_FALLBACK_MAX_RELATIVE_QTY_DIFF) > 0) {
            return false;
        }
        if (!hasTrustedSettlementEvidence) {
            if (deltaSeconds > ROUTED_BRIDGE_GENERIC_MAX_TIME_DELTA.toSeconds()) {
                return false;
            }
            if (relativeQuantityDiff.compareTo(ROUTED_BRIDGE_GENERIC_MAX_RELATIVE_QTY_DIFF) > 0) {
                return false;
            }
        }
        return true;
    }

    private Optional<ProtocolRegistryEntry> resolveRoutedSettlementEntry(
            @Nullable RawTransaction rawTransaction,
            NormalizedTransaction destination
    ) {
        if (isAcrossProtocol(destination.getProtocolName())) {
            return Optional.of(new ProtocolRegistryEntry(
                    "",
                    java.util.Set.of(destination.getNetworkId()),
                    ProtocolRegistryFamily.BRIDGE,
                    ProtocolRegistryRole.BRIDGE_ENTRY,
                    null,
                    ConfidenceLevel.MEDIUM,
                    destination.getProtocolName(),
                    destination.getProtocolVersion(),
                    false,
                    null
            ));
        }
        if (rawTransaction == null) {
            return Optional.empty();
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        Optional<ProtocolRegistryEntry> relayPayout = resolveRelayPayoutEntry(view, destination);
        if (relayPayout.isPresent()) {
            return relayPayout;
        }
        String walletAddress = destination.getWalletAddress();
        for (Document transfer : view.explorerInternalTransfers()) {
            if (view.internalTransferErrored(transfer)) {
                continue;
            }
            String recipient = view.internalTransferTo(transfer);
            if (walletAddress != null && recipient != null && !walletAddress.equalsIgnoreCase(recipient)) {
                continue;
            }
            Optional<ProtocolRegistryEntry> entry = protocolRegistryService.lookup(
                    destination.getNetworkId(),
                    view.internalTransferFrom(transfer)
            );
            if (entry.filter(this::isAcrossBridgeEntry).isPresent()) {
                return entry;
            }
        }
        return Optional.empty();
    }

    private Optional<ProtocolRegistryEntry> resolveRelayPayoutEntry(
            OnChainRawTransactionView view,
            NormalizedTransaction destination
    ) {
        String walletAddress = destination.getWalletAddress();
        String topLevelRecipient = view.toAddress();
        if (walletAddress != null && topLevelRecipient != null && !walletAddress.equalsIgnoreCase(topLevelRecipient)) {
            return Optional.empty();
        }
        return protocolRegistryService.lookup(destination.getNetworkId(), view.fromAddress())
                .filter(this::isRelayPayoutEntry);
    }

    private boolean supportsPlainMoveBasis(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        List<NormalizedTransaction.Flow> sourcePrincipal = principalFlows(source, -1);
        List<NormalizedTransaction.Flow> destinationPrincipal = principalFlows(destination, 1);
        if (sourcePrincipal.size() != 1 || destinationPrincipal.size() != 1) {
            return false;
        }
        NormalizedTransaction.Flow sourceFlow = sourcePrincipal.getFirst();
        NormalizedTransaction.Flow destinationFlow = destinationPrincipal.getFirst();
        if (!supportsBridgeContinuity(sourceFlow, destinationFlow)) {
            return false;
        }
        return sourceFlow.getQuantityDelta() != null && destinationFlow.getQuantityDelta() != null;
    }

    private boolean supportsBridgeContinuity(
            NormalizedTransaction.Flow sourceFlow,
            NormalizedTransaction.Flow destinationFlow
    ) {
        String sourceAsset = BridgeAssetFamilySupport.continuityIdentity(sourceFlow);
        String destinationAsset = BridgeAssetFamilySupport.continuityIdentity(destinationFlow);
        if (hasText(sourceAsset) && sourceAsset.equals(destinationAsset)) {
            return true;
        }
        return supportsCanonicalBridgeAlias(sourceAsset, destinationAsset, sourceFlow, destinationFlow);
    }

    private boolean supportsCanonicalBridgeAlias(
            String sourceIdentity,
            String destinationIdentity,
            NormalizedTransaction.Flow sourceFlow,
            NormalizedTransaction.Flow destinationFlow
    ) {
        if (isFamilyIdentity(sourceIdentity) || isFamilyIdentity(destinationIdentity)) {
            return false;
        }
        return CanonicalAssetCatalog.sameCanonicalSymbol(
                sourceFlow == null ? null : sourceFlow.getAssetSymbol(),
                destinationFlow == null ? null : destinationFlow.getAssetSymbol()
        );
    }

    private boolean isFamilyIdentity(String identity) {
        return hasText(identity) && identity.startsWith("FAMILY:");
    }

    private List<NormalizedTransaction.Flow> principalFlows(NormalizedTransaction transaction, int direction) {
        if (transaction == null || transaction.getFlows() == null) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null && Integer.signum(flow.getQuantityDelta().signum()) == direction)
                .toList();
    }

    private boolean isInboundOnly(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return false;
        }
        boolean hasInbound = transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .anyMatch(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0);
        boolean hasOutbound = transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .anyMatch(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0);
        return hasInbound && !hasOutbound;
    }

    private boolean isMaterializableDestination(NormalizedTransaction destination) {
        if (destination == null) {
            return false;
        }
        if (destination.getType() == NormalizedTransactionType.BRIDGE_IN) {
            return true;
        }
        return destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && isInboundOnly(destination);
    }

    private void retagInboundFlowsAsBridgeTransfer(NormalizedTransaction transaction) {
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            flow.setRole(NormalizedLegRole.TRANSFER);
        }
    }

    private boolean alignDestinationInboundRolesForBridgeSettlement(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        if (destination == null || destination.getFlows() == null || destination.getFlows().isEmpty()) {
            return false;
        }
        List<NormalizedTransaction.Flow> inboundFlows = destination.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0)
                .toList();
        if (inboundFlows.isEmpty()) {
            return false;
        }
        if (inboundFlows.size() == 1) {
            boolean alreadySingleTransfer = destination.getFlows().stream()
                    .filter(Objects::nonNull)
                    .filter(flow -> flow.getRole() == NormalizedLegRole.TRANSFER)
                    .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() > 0)
                    .count() == 1;
            if (alreadySingleTransfer) {
                return false;
            }
            retagInboundFlowsAsBridgeTransfer(destination);
            return true;
        }

        Optional<NormalizedTransaction.Flow> primaryFlow = selectPrimaryInboundBridgeFlow(source, destination, inboundFlows);
        if (primaryFlow.isEmpty()) {
            return false;
        }
        NormalizedTransaction.Flow primary = primaryFlow.orElseThrow();
        boolean changed = false;
        for (NormalizedTransaction.Flow flow : destination.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            NormalizedLegRole targetRole = flow == primary ? NormalizedLegRole.TRANSFER : NormalizedLegRole.BUY;
            if (flow.getRole() != targetRole) {
                flow.setRole(targetRole);
                changed = true;
            }
        }
        return changed;
    }

    private Optional<NormalizedTransaction.Flow> selectPrimaryInboundBridgeFlow(
            NormalizedTransaction source,
            NormalizedTransaction destination,
            List<NormalizedTransaction.Flow> inboundFlows
    ) {
        List<NormalizedTransaction.Flow> sourcePrincipal = principalFlows(source, -1);
        if (sourcePrincipal.size() != 1 || inboundFlows == null || inboundFlows.isEmpty()) {
            return Optional.empty();
        }
        return selectPrimaryInboundBridgeFlowForSourcePrincipal(
                source,
                sourcePrincipal.getFirst(),
                destination,
                inboundFlows
        );
    }

    private Optional<SupplementalBridgeLegMatch> selectSupplementalBridgeLegMatch(
            NormalizedTransaction source,
            NormalizedTransaction destination,
            List<NormalizedTransaction.Flow> inboundFlows
    ) {
        if (source == null || destination == null || inboundFlows == null || inboundFlows.isEmpty()) {
            return Optional.empty();
        }
        SupplementalBridgeLegMatch bestMatch = null;
        BigDecimal bestRelativeDiff = null;
        int bestStableRank = Integer.MIN_VALUE;
        int matchCount = 0;
        for (NormalizedTransaction.Flow sourceFlow : principalFlows(source, -1)) {
            Optional<NormalizedTransaction.Flow> inboundOptional = selectPrimaryInboundBridgeFlowForSourcePrincipal(
                    source,
                    sourceFlow,
                    destination,
                    inboundFlows
            );
            if (inboundOptional.isEmpty()) {
                continue;
            }
            matchCount++;
            NormalizedTransaction.Flow inbound = inboundOptional.orElseThrow();
            BigDecimal relativeDiff = sourceFlow.getQuantityDelta() == null || inbound.getQuantityDelta() == null
                    ? BigDecimal.ONE
                    : relativeQuantityDiff(sourceFlow, inbound);
            int stableRank = stableSettlementRank(source, sourceFlow, destination, inbound);
            if (bestMatch == null
                    || stableRank > bestStableRank
                    || stableRank == bestStableRank && relativeDiff.compareTo(bestRelativeDiff) < 0) {
                bestMatch = new SupplementalBridgeLegMatch(sourceFlow, inbound);
                bestRelativeDiff = relativeDiff;
                bestStableRank = stableRank;
            }
        }
        if (bestMatch == null || matchCount != 1) {
            return Optional.empty();
        }
        return Optional.of(bestMatch);
    }

    private Optional<NormalizedTransaction.Flow> selectPrimaryInboundBridgeFlowForSourcePrincipal(
            NormalizedTransaction source,
            NormalizedTransaction.Flow sourceFlow,
            NormalizedTransaction destination,
            List<NormalizedTransaction.Flow> inboundFlows
    ) {
        if (sourceFlow == null || inboundFlows == null || inboundFlows.isEmpty()) {
            return Optional.empty();
        }

        NormalizedTransaction.Flow best = null;
        BigDecimal bestRelativeDiff = null;
        int bestStableRank = Integer.MIN_VALUE;
        Set<NormalizedTransaction.Flow> tied = new HashSet<>();

        for (NormalizedTransaction.Flow candidate : inboundFlows) {
            if (candidate == null || !supportsSettlementPrincipalCompatibility(source, sourceFlow, destination, candidate)) {
                continue;
            }
            BigDecimal relativeDiff = sourceFlow.getQuantityDelta() == null || candidate.getQuantityDelta() == null
                    ? BigDecimal.ONE
                    : relativeQuantityDiff(sourceFlow, candidate);
            int stableRank = stableSettlementRank(source, sourceFlow, destination, candidate);
            if (best == null
                    || stableRank > bestStableRank
                    || stableRank == bestStableRank && relativeDiff.compareTo(bestRelativeDiff) < 0) {
                best = candidate;
                bestRelativeDiff = relativeDiff;
                bestStableRank = stableRank;
                tied.clear();
                tied.add(candidate);
                continue;
            }
            if (stableRank == bestStableRank && relativeDiff.compareTo(bestRelativeDiff) == 0) {
                tied.add(candidate);
            }
        }

        if (best == null || tied.size() > 1) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private record SupplementalBridgeLegMatch(
            NormalizedTransaction.Flow outbound,
            NormalizedTransaction.Flow inbound
    ) {
    }

    private boolean supportsSettlementPrincipalCompatibility(
            NormalizedTransaction source,
            NormalizedTransaction.Flow sourceFlow,
            NormalizedTransaction destination,
            NormalizedTransaction.Flow destinationFlow
    ) {
        return supportsBridgeContinuity(sourceFlow, destinationFlow)
                || stableSettlementRank(source, sourceFlow, destination, destinationFlow) > 0;
    }

    private int stableSettlementRank(
            NormalizedTransaction source,
            NormalizedTransaction.Flow sourceFlow,
            NormalizedTransaction destination,
            NormalizedTransaction.Flow destinationFlow
    ) {
        boolean sourceUsdStable = isUsdStable(source, sourceFlow);
        boolean destinationUsdStable = isUsdStable(destination, destinationFlow);
        if (sourceUsdStable && destinationUsdStable) {
            return 2;
        }
        boolean sourceEuroStable = isEuroStable(sourceFlow);
        boolean destinationEuroStable = isEuroStable(destinationFlow);
        if (sourceEuroStable && destinationEuroStable) {
            return 1;
        }
        return 0;
    }

    private boolean isUsdStable(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return false;
        }
        if (CanonicalAssetCatalog.isUsdStablecoin(
                transaction.getNetworkId(),
                flow.getAssetContract(),
                flow.getAssetSymbol(),
                transaction.getSource()
        )) {
            return true;
        }
        return USD_STABLE_SYMBOLS.contains(CanonicalAssetCatalog.normalizeSymbol(flow.getAssetSymbol()));
    }

    private boolean isEuroStable(NormalizedTransaction.Flow flow) {
        return flow != null && CanonicalAssetCatalog.isEuroStablecoin(flow.getAssetSymbol());
    }

    private Optional<LiFiBridgeStatus> readExistingStatus(RawTransaction rawTransaction) {
        if (rawTransaction == null || rawTransaction.getClarificationEvidence() == null) {
            return Optional.empty();
        }
        return LiFiBridgeStatus.fromDocument(rawTransaction.getClarificationEvidence().get("protocolStatus", Document.class));
    }

    private boolean isStatusMissRetryDeferred(RawTransaction rawTransaction, Instant now) {
        Document status = rawTransaction == null || rawTransaction.getClarificationEvidence() == null
                ? null
                : rawTransaction.getClarificationEvidence().get("protocolStatus", Document.class);
        if (status == null || !"LIFI".equalsIgnoreCase(status.getString("provider"))) {
            return false;
        }
        String apiStatus = status.getString("apiStatus");
        if (!"UNAVAILABLE".equalsIgnoreCase(apiStatus)) {
            return false;
        }
        Instant nextRetryAt = parseInstant(status.get("nextRetryAt"));
        return nextRetryAt != null && nextRetryAt.isAfter(now);
    }

    private void persistStatusEvidence(RawTransaction rawTransaction, LiFiBridgeStatus status) {
        rawTransactionClarificationEnricher.mergeProtocolStatus(rawTransaction, status.toDocument());
        rawTransactionRepository.save(rawTransaction);
    }

    private void persistStatusMiss(RawTransaction rawTransaction, Instant now) {
        if (rawTransaction == null) {
            return;
        }
        Document status = new Document("provider", "LIFI")
                .append("apiStatus", "UNAVAILABLE")
                .append("checkedAt", now.toString())
                .append("nextRetryAt", now.plus(STATUS_MISS_RETRY_DELAY).toString());
        rawTransactionClarificationEnricher.mergeProtocolStatus(rawTransaction, status);
        rawTransactionRepository.save(rawTransaction);
    }

    private Instant parseInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text.trim());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private void seedSourceAnchorFromStatus(
            NormalizedTransaction source,
            LiFiBridgeStatus status
    ) {
        if (source == null || status == null || !hasText(status.receivingTxHash())) {
            return;
        }
        boolean changed = false;
        String correlationId = correlationId(source.getTxHash());
        if (!sameHash(source.getMatchedCounterparty(), status.receivingTxHash())) {
            source.setMatchedCounterparty(status.receivingTxHash());
            changed = true;
        }
        if (!sameCorrelation(source.getCorrelationId(), correlationId)) {
            source.setCorrelationId(correlationId);
            changed = true;
        }
        if (!changed) {
            return;
        }
        source.setUpdatedAt(Instant.now());
        normalizedTransactionRepository.save(source);
    }

    private boolean isLiFiSourceCandidate(
            RawTransaction rawTransaction,
            NormalizedTransaction normalizedTransaction
    ) {
        if (normalizedTransaction.getType() != NormalizedTransactionType.BRIDGE_OUT) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        return readExistingStatus(rawTransaction)
                .filter(status -> hasText(status.receivingTxHash()))
                .isPresent()
                || LiFiRouteSupport.hasRouteTag(view)
                || hasText(normalizedTransaction.getProtocolName())
                && isLiFiProtocol(normalizedTransaction.getProtocolName());
    }

    private BigDecimal relativeQuantityDiff(NormalizedTransaction.Flow sourceFlow, NormalizedTransaction.Flow destinationFlow) {
        BigDecimal left = sourceFlow.getQuantityDelta().abs();
        BigDecimal right = destinationFlow.getQuantityDelta().abs();
        BigDecimal denominator = left.max(right);
        if (denominator.signum() == 0) {
            return BigDecimal.ONE;
        }
        return left.subtract(right).abs().divide(denominator, MathContext.DECIMAL64);
    }

    private boolean sameWallet(NormalizedTransaction left, NormalizedTransaction right) {
        return left != null
                && right != null
                && hasText(left.getWalletAddress())
                && left.getWalletAddress().equalsIgnoreCase(right.getWalletAddress());
    }

    private boolean sameNetwork(NormalizedTransaction left, NormalizedTransaction right) {
        return left != null && right != null && left.getNetworkId() == right.getNetworkId();
    }

    private boolean isAcrossBridgeEntry(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.family() == ProtocolRegistryFamily.BRIDGE
                && (entry.role() == ProtocolRegistryRole.BRIDGE_ENTRY
                || entry.role() == ProtocolRegistryRole.ROUTER)
                && isAcrossProtocol(entry.protocolName());
    }

    private boolean isRelayPayoutEntry(ProtocolRegistryEntry entry) {
        return entry != null
                && entry.role() == ProtocolRegistryRole.GAS_PAYER
                && isRelayProtocol(entry.protocolName());
    }

    private boolean isLiFiProtocol(String protocolName) {
        return normalizedProtocolName(protocolName).contains("lifi");
    }

    private boolean isAcrossProtocol(String protocolName) {
        return normalizedProtocolName(protocolName).contains("across");
    }

    private boolean isRelayProtocol(String protocolName) {
        return normalizedProtocolName(protocolName).contains("relay");
    }

    private String normalizedProtocolName(String protocolName) {
        if (!hasText(protocolName)) {
            return "";
        }
        return protocolName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private List<NormalizedTransaction> deduplicate(List<NormalizedTransaction> updates) {
        Set<String> seen = new LinkedHashSet<>();
        List<NormalizedTransaction> deduplicated = new ArrayList<>();
        for (NormalizedTransaction candidate : updates) {
            if (candidate == null || !seen.add(candidate.getId())) {
                continue;
            }
            deduplicated.add(candidate);
        }
        return deduplicated;
    }

    private String correlationId(String sourceTxHash) {
        return hasText(sourceTxHash)
                ? "bridge:lifi:" + sourceTxHash.toLowerCase(Locale.ROOT)
                : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean sameHash(String left, String right) {
        return hasText(left) && hasText(right) && left.equalsIgnoreCase(right);
    }

    private boolean sameCorrelation(String left, String right) {
        return hasText(left) && hasText(right) && left.equalsIgnoreCase(right);
    }

    private boolean isPairingStateCompatible(String existing, String expected) {
        return !hasText(existing) || sameHash(existing, expected);
    }

    private boolean isCorrelationCompatible(String existing, String expected) {
        return !hasText(existing) || sameCorrelation(existing, expected);
    }

    private void clearSelfLinkIfPresent(NormalizedTransaction source) {
        if (source == null) {
            return;
        }
        if (sameHash(source.getMatchedCounterparty(), source.getTxHash())) {
            source.setMatchedCounterparty(null);
            source.setUpdatedAt(Instant.now());
            normalizedTransactionRepository.save(source);
        }
    }

    private int destinationRank(NormalizedTransaction destination) {
        if (destination == null) {
            return 99;
        }
        if (destination.getType() == NormalizedTransactionType.BRIDGE_IN) {
            return 0;
        }
        if (destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN && isInboundOnly(destination)) {
            return 1;
        }
        return 2;
    }

    private static int destinationSelectionRank(NormalizedTransaction destination) {
        if (destination == null) {
            return 99;
        }
        if (destination.getType() == NormalizedTransactionType.BRIDGE_IN) {
            return 0;
        }
        if (destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return 1;
        }
        return 2;
    }

    private String pairingState(NormalizedTransaction transaction) {
        if (transaction == null) {
            return "";
        }
        StringBuilder state = new StringBuilder();
        state.append(transaction.getType()).append('|')
                .append(transaction.getMatchedCounterparty()).append('|')
                .append(transaction.getCorrelationId()).append('|')
                .append(transaction.getContinuityCandidate());
        if (transaction.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                if (flow == null) {
                    continue;
                }
                state.append('|')
                        .append(flow.getRole()).append(':')
                        .append(flow.getAssetContract()).append(':')
                        .append(flow.getAssetSymbol()).append(':')
                        .append(flow.getQuantityDelta());
            }
        }
        return state.toString();
    }

    private record SourceContext(
            RawTransaction rawTransaction,
            NormalizedTransaction normalizedTransaction
    ) {
    }

    private record StatusLookupResult(
            SourceContext context,
            Optional<LiFiBridgeStatus> status
    ) {
    }
}
