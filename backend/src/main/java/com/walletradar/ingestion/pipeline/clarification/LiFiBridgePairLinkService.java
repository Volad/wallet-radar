package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.accounting.support.BridgeAssetFamilySupport;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.LiFiRouteSupport;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes LI.FI / Jumper destination-side bridge pairs once official receiving-tx evidence is available.
 */
@Service
@RequiredArgsConstructor
public class LiFiBridgePairLinkService {

    private static final Duration ROUTED_ACROSS_FALLBACK_MAX_TIME_DELTA = Duration.ofSeconds(60);
    private static final BigDecimal ROUTED_ACROSS_FALLBACK_MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.15");
    private static final int ROUTED_ACROSS_FALLBACK_CANDIDATE_LIMIT = 12;

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
        for (NormalizedTransaction candidate : pendingLiFiBridgeSourceQueryService.loadNextBatch(batchSize)) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Optional<RawTransaction> rawOptional = rawTransactionRepository.findById(candidate.getId());
            if (rawOptional.isEmpty()) {
                continue;
            }
            if (linkInternal(rawOptional.get(), candidate)) {
                changed++;
            }
        }
        return changed;
    }

    public void link(@Nullable RawTransaction rawTransaction, @Nullable NormalizedTransaction normalizedTransaction) {
        linkInternal(rawTransaction, normalizedTransaction);
    }

    private boolean linkInternal(@Nullable RawTransaction rawTransaction, @Nullable NormalizedTransaction normalizedTransaction) {
        if (rawTransaction == null || normalizedTransaction == null) {
            return false;
        }
        if (normalizedTransaction.getSource() != NormalizedTransactionSource.ON_CHAIN) {
            return false;
        }

        Optional<NormalizedTransaction> pairedDestination = Optional.empty();
        if (isLiFiSourceCandidate(rawTransaction, normalizedTransaction)) {
            pairedDestination = seedSourceCounterparty(rawTransaction, normalizedTransaction);
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
            return false;
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
            NormalizedTransaction source
    ) {
        Optional<LiFiBridgeStatus> statusOptional = readExistingStatus(rawTransaction);
        if (statusOptional.isEmpty()) {
            statusOptional = liFiStatusGateway.fetchBridgeStatus(source.getTxHash());
            statusOptional.ifPresent(status -> persistStatusEvidence(rawTransaction, status));
        }
        if (statusOptional.isEmpty()) {
            return resolveRoutedAcrossFallbackDestination(rawTransaction, source);
        }

        LiFiBridgeStatus status = statusOptional.get();
        if (status.isSameTransactionEcho()) {
            clearSelfLinkIfPresent(source);
            return Optional.empty();
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
        if (changed) {
            source.setUpdatedAt(Instant.now());
            normalizedTransactionRepository.save(source);
        }

        List<NormalizedTransaction> currentDestinations = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                status.receivingTxHash(),
                status.receivingNetworkId(),
                NormalizedTransactionSource.ON_CHAIN
        );
        Optional<NormalizedTransaction> existingDestination = currentDestinations.stream()
                .filter(Objects::nonNull)
                .filter(this::isMaterializableDestination)
                .sorted((left, right) -> Integer.compare(destinationRank(left), destinationRank(right)))
                .findFirst();
        if (existingDestination.isPresent()) {
            return existingDestination;
        }
        return liFiReceivingTransactionDiscoveryService.findOrDiscover(status);
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
        Instant now = Instant.now();
        String correlationId = source.getCorrelationId() != null && !source.getCorrelationId().isBlank()
                ? source.getCorrelationId()
                : correlationId(source.getTxHash());

        boolean continuityCandidate = supportsPlainMoveBasis(source, destination);
        List<NormalizedTransaction> updates = new ArrayList<>();

        if (!sameHash(source.getMatchedCounterparty(), destination.getTxHash())
                || !sameCorrelation(source.getCorrelationId(), correlationId)
                || !Objects.equals(source.getContinuityCandidate(), continuityCandidate)) {
            source.setMatchedCounterparty(destination.getTxHash());
            source.setCorrelationId(correlationId);
            source.setContinuityCandidate(continuityCandidate);
            source.setUpdatedAt(now);
            updates.add(source);
        }

        boolean destinationChanged = false;
        if (destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && isInboundOnly(destination)) {
            destination.setType(NormalizedTransactionType.BRIDGE_IN);
            retagInboundFlowsAsBridgeTransfer(destination);
            if (destination.getClassifiedBy() == null) {
                destination.setClassifiedBy(ClassificationSource.HEURISTIC);
            }
            if (destination.getConfidence() == null || destination.getConfidence() == ConfidenceLevel.LOW) {
                destination.setConfidence(ConfidenceLevel.MEDIUM);
            }
            destinationChanged = true;
        }
        if (!hasText(destination.getProtocolName()) && destinationRaw != null) {
            Optional<ProtocolRegistryEntry> settlementEntry = resolveAcrossSettlementEntry(destinationRaw, destination);
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
        if (destinationChanged) {
            destination.setUpdatedAt(now);
            updates.add(destination);
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
                .findFirst();
    }

    private Optional<NormalizedTransaction> resolveRoutedAcrossFallbackDestination(
            RawTransaction sourceRaw,
            NormalizedTransaction source
    ) {
        if (!isRoutedAcrossFallbackSource(sourceRaw, source)) {
            return Optional.empty();
        }
        List<NormalizedTransaction> accepted = loadRoutedAcrossFallbackDestinationCandidates(source).stream()
                .filter(Objects::nonNull)
                .filter(candidate -> isStrongRoutedAcrossFallbackDestination(source, candidate))
                .toList();
        if (accepted.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(accepted.getFirst());
    }

    private List<NormalizedTransaction> loadRoutedAcrossFallbackDestinationCandidates(NormalizedTransaction source) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("walletAddress").is(source.getWalletAddress()),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN)
                ),
                Criteria.where("blockTimestamp")
                        .gte(source.getBlockTimestamp().minus(ROUTED_ACROSS_FALLBACK_MAX_TIME_DELTA))
                        .lte(source.getBlockTimestamp().plus(ROUTED_ACROSS_FALLBACK_MAX_TIME_DELTA)),
                Criteria.where("txHash").ne(source.getTxHash())
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(ROUTED_ACROSS_FALLBACK_CANDIDATE_LIMIT);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private boolean isRoutedAcrossFallbackSource(
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

    private boolean isStrongRoutedAcrossFallbackDestination(
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
        RawTransaction destinationRaw = rawTransactionRepository.findById(destination.getId()).orElse(null);
        if (resolveAcrossSettlementEntry(destinationRaw, destination).isEmpty()) {
            return false;
        }
        List<NormalizedTransaction.Flow> sourcePrincipal = principalFlows(source, -1);
        List<NormalizedTransaction.Flow> destinationPrincipal = principalFlows(destination, 1);
        if (sourcePrincipal.size() != 1 || destinationPrincipal.size() != 1) {
            return false;
        }
        NormalizedTransaction.Flow sourceFlow = sourcePrincipal.getFirst();
        NormalizedTransaction.Flow destinationFlow = destinationPrincipal.getFirst();
        String sourceAsset = BridgeAssetFamilySupport.continuityIdentity(sourceFlow);
        String destinationAsset = BridgeAssetFamilySupport.continuityIdentity(destinationFlow);
        if (sourceAsset == null || !sourceAsset.equals(destinationAsset)) {
            return false;
        }
        if (sourceFlow.getQuantityDelta() == null || destinationFlow.getQuantityDelta() == null) {
            return false;
        }
        if (destinationFlow.getQuantityDelta().abs().compareTo(sourceFlow.getQuantityDelta().abs()) > 0) {
            return false;
        }
        long deltaSeconds = Math.abs(Duration.between(source.getBlockTimestamp(), destination.getBlockTimestamp()).toSeconds());
        if (deltaSeconds > ROUTED_ACROSS_FALLBACK_MAX_TIME_DELTA.toSeconds()) {
            return false;
        }
        return relativeQuantityDiff(sourceFlow, destinationFlow)
                .compareTo(ROUTED_ACROSS_FALLBACK_MAX_RELATIVE_QTY_DIFF) <= 0;
    }

    private Optional<ProtocolRegistryEntry> resolveAcrossSettlementEntry(
            @Nullable RawTransaction rawTransaction,
            NormalizedTransaction destination
    ) {
        if (hasText(destination.getProtocolName())
                && destination.getProtocolName().toLowerCase(Locale.ROOT).contains("across")) {
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
        String sourceAsset = BridgeAssetFamilySupport.continuityIdentity(sourceFlow);
        String destinationAsset = BridgeAssetFamilySupport.continuityIdentity(destinationFlow);
        if (sourceAsset == null || !sourceAsset.equals(destinationAsset)) {
            return false;
        }
        return sourceFlow.getQuantityDelta() != null && destinationFlow.getQuantityDelta() != null;
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

    private Optional<LiFiBridgeStatus> readExistingStatus(RawTransaction rawTransaction) {
        if (rawTransaction == null || rawTransaction.getClarificationEvidence() == null) {
            return Optional.empty();
        }
        return LiFiBridgeStatus.fromDocument(rawTransaction.getClarificationEvidence().get("protocolStatus", Document.class));
    }

    private void persistStatusEvidence(RawTransaction rawTransaction, LiFiBridgeStatus status) {
        rawTransactionClarificationEnricher.mergeProtocolStatus(rawTransaction, status.toDocument());
        rawTransactionRepository.save(rawTransaction);
    }

    private boolean isLiFiSourceCandidate(
            RawTransaction rawTransaction,
            NormalizedTransaction normalizedTransaction
    ) {
        if (normalizedTransaction.getType() != NormalizedTransactionType.BRIDGE_OUT) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        return LiFiRouteSupport.hasRouteTag(view)
                || hasText(normalizedTransaction.getProtocolName())
                && normalizedTransaction.getProtocolName().toLowerCase(Locale.ROOT).contains("lifi");
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
                && hasText(entry.protocolName())
                && entry.protocolName().toLowerCase(Locale.ROOT).contains("across");
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
}
