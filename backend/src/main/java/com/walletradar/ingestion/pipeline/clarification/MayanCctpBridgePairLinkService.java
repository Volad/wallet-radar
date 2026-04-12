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
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes fee-bearing Mayan/CCTP bridge continuity from official source-driven confirmation.
 */
@Service
@RequiredArgsConstructor
public class MayanCctpBridgePairLinkService {

    private static final String MAYAN_BRIDGE_SELECTOR = "0x30c48952";
    private static final String MAYAN_SETTLEMENT_SELECTOR = "0xe2de2a03";
    private static final String MAYAN_STATUS_KEY = "mayanStatus";
    private static final Comparator<NormalizedTransaction> DESTINATION_SELECTION_ORDER = Comparator
            .comparingInt(MayanCctpBridgePairLinkService::destinationSelectionRank)
            .thenComparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(String::compareTo));
    private static final Comparator<NormalizedTransaction> SOURCE_SELECTION_ORDER = Comparator
            .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(String::compareTo));

    private final MayanStatusGateway mayanStatusGateway;
    private final PendingMayanBridgeSourceQueryService pendingMayanBridgeSourceQueryService;
    private final MayanReceivingTransactionDiscoveryService mayanReceivingTransactionDiscoveryService;
    private final RawTransactionClarificationEnricher rawTransactionClarificationEnricher;
    private final RawTransactionRepository rawTransactionRepository;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcileOutstandingSources(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction candidate : pendingMayanBridgeSourceQueryService.loadNextBatch(batchSize)) {
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
        if (isMayanSourceCandidate(rawTransaction, normalizedTransaction)) {
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

        if (!isPotentialMayanDestination(rawTransaction, normalizedTransaction)) {
            return false;
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
        Optional<MayanBridgeStatus> statusOptional = readExistingStatus(rawTransaction);
        if (statusOptional.isEmpty()) {
            statusOptional = mayanStatusGateway.fetchBridgeStatus(source.getTxHash());
            statusOptional.ifPresent(status -> persistStatusEvidence(rawTransaction, status));
        }
        if (statusOptional.isEmpty()) {
            return Optional.empty();
        }

        MayanBridgeStatus status = statusOptional.get();
        if (!status.isSettled() || status.isSameTransactionEcho()) {
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
                .filter(destination -> status.destinationWalletAddress() == null
                        || status.destinationWalletAddress().equalsIgnoreCase(destination.getWalletAddress()))
                .sorted(DESTINATION_SELECTION_ORDER)
                .findFirst();
        if (existingDestination.isPresent()) {
            return existingDestination;
        }
        return mayanReceivingTransactionDiscoveryService.findOrDiscover(status);
    }

    private void materializePair(NormalizedTransaction source, NormalizedTransaction destination) {
        Instant now = Instant.now();
        String correlationId = hasText(source.getCorrelationId())
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
        if (destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN && isInboundOnly(destination)) {
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
                .filter(candidate -> hasText(candidate.getCorrelationId())
                        && candidate.getCorrelationId().toLowerCase(Locale.ROOT).startsWith("bridge:mayan:"))
                .filter(candidate -> !sameHash(candidate.getTxHash(), destination.getTxHash()))
                .sorted(SOURCE_SELECTION_ORDER)
                .findFirst();
    }

    private boolean supportsPlainMoveBasis(NormalizedTransaction source, NormalizedTransaction destination) {
        List<NormalizedTransaction.Flow> sourcePrincipal = principalFlows(source, -1);
        List<NormalizedTransaction.Flow> destinationPrincipal = principalFlows(destination, 1);
        if (sourcePrincipal.size() != 1 || destinationPrincipal.size() != 1) {
            return false;
        }
        String sourceAsset = BridgeAssetFamilySupport.continuityIdentity(sourcePrincipal.getFirst());
        String destinationAsset = BridgeAssetFamilySupport.continuityIdentity(destinationPrincipal.getFirst());
        if (sourceAsset == null || !sourceAsset.equals(destinationAsset)) {
            return false;
        }
        return sourcePrincipal.getFirst().getQuantityDelta() != null
                && destinationPrincipal.getFirst().getQuantityDelta() != null;
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
        return destination.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN && isInboundOnly(destination);
    }

    private void retagInboundFlowsAsBridgeTransfer(NormalizedTransaction transaction) {
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            flow.setRole(NormalizedLegRole.TRANSFER);
        }
    }

    private Optional<MayanBridgeStatus> readExistingStatus(RawTransaction rawTransaction) {
        if (rawTransaction == null || rawTransaction.getClarificationEvidence() == null) {
            return Optional.empty();
        }
        return MayanBridgeStatus.fromDocument(rawTransaction.getClarificationEvidence().get(MAYAN_STATUS_KEY, Document.class));
    }

    private void persistStatusEvidence(RawTransaction rawTransaction, MayanBridgeStatus status) {
        rawTransactionClarificationEnricher.mergeClarificationEvidence(rawTransaction, MAYAN_STATUS_KEY, status.toDocument());
        rawTransactionRepository.save(rawTransaction);
    }

    private boolean isMayanSourceCandidate(RawTransaction rawTransaction, NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction.getType() != NormalizedTransactionType.BRIDGE_OUT) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        return MAYAN_BRIDGE_SELECTOR.equals(view.methodId())
                || containsAny(view.functionName(), "swapandstartbridgetokensviamayan")
                || containsAny(normalizedTransaction.getProtocolName(), "mayan");
    }

    private boolean isPotentialMayanDestination(RawTransaction rawTransaction, NormalizedTransaction normalizedTransaction) {
        if (!isMaterializableDestination(normalizedTransaction)) {
            return false;
        }
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        return MAYAN_SETTLEMENT_SELECTOR.equals(view.methodId())
                || containsAny(view.functionName(), "redeemwithfee");
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
                ? "bridge:mayan:" + sourceTxHash.toLowerCase(Locale.ROOT)
                : null;
    }

    private boolean containsAny(String value, String... needles) {
        if (!hasText(value) || needles == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
}
