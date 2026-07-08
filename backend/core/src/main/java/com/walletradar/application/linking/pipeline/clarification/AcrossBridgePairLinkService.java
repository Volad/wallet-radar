package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.BridgeAssetFamilySupport;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Materializes high-confidence same-wallet Across bridge continuity once both source and
 * destination rows already exist in normalized on-chain output.
 */
@Service
@RequiredArgsConstructor
public class AcrossBridgePairLinkService {

    private static final Duration MAX_TIME_DELTA = Duration.ofSeconds(60);
    private static final BigDecimal MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.25");
    private static final int CANDIDATE_LIMIT = 12;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcileOutstandingSources(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction source : loadOutstandingSources(batchSize)) {
            if (link(source)) {
                changed++;
            } else if (seedOrphanSourceCorrelation(source)) {
                changed++;
            }
        }
        return changed;
    }

    public boolean link(@Nullable NormalizedTransaction transaction) {
        if (transaction == null || transaction.getSource() != NormalizedTransactionSource.ON_CHAIN) {
            return false;
        }
        if (isAcrossSourceCandidate(transaction)) {
            return resolveDestination(transaction)
                    .map(destination -> materializePair(transaction, destination))
                    .orElse(false);
        }
        if (isDestinationCandidate(transaction)) {
            return resolveSource(transaction)
                    .map(source -> materializePair(source, transaction))
                    .orElse(false);
        }
        return false;
    }

    private List<NormalizedTransaction> loadOutstandingSources(int batchSize) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("protocolName").regex("across", "i"),
                Criteria.where("walletAddress").ne(null),
                Criteria.where("blockTimestamp").ne(null),
                new Criteria().orOperator(
                        Criteria.where("correlationId").is(null),
                        Criteria.where("matchedCounterparty").is(null)
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private Optional<NormalizedTransaction> resolveDestination(NormalizedTransaction source) {
        if (!isAcrossSourceCandidate(source)) {
            return Optional.empty();
        }
        List<NormalizedTransaction> candidates = deduplicateById(loadDestinationCandidates(source));
        List<NormalizedTransaction> accepted = candidates.stream()
                .filter(candidate -> isStrongDestinationCandidate(source, candidate))
                .toList();
        if (accepted.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(accepted.getFirst());
    }

    private Optional<NormalizedTransaction> resolveSource(NormalizedTransaction destination) {
        if (!isDestinationCandidate(destination)) {
            return Optional.empty();
        }
        List<NormalizedTransaction> candidates = deduplicateById(loadSourceCandidates(destination));
        List<NormalizedTransaction> accepted = candidates.stream()
                .filter(candidate -> isStrongDestinationCandidate(candidate, destination))
                .toList();
        if (accepted.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(accepted.getFirst());
    }

    private List<NormalizedTransaction> loadDestinationCandidates(NormalizedTransaction source) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN)
                ),
                Criteria.where("walletAddress").is(source.getWalletAddress()),
                Criteria.where("blockTimestamp").gte(source.getBlockTimestamp().minus(MAX_TIME_DELTA))
                        .lte(source.getBlockTimestamp().plus(MAX_TIME_DELTA)),
                Criteria.where("txHash").ne(source.getTxHash())
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(CANDIDATE_LIMIT);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private List<NormalizedTransaction> loadSourceCandidates(NormalizedTransaction destination) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("protocolName").regex("across", "i"),
                Criteria.where("walletAddress").is(destination.getWalletAddress()),
                Criteria.where("blockTimestamp").gte(destination.getBlockTimestamp().minus(MAX_TIME_DELTA))
                        .lte(destination.getBlockTimestamp().plus(MAX_TIME_DELTA)),
                Criteria.where("txHash").ne(destination.getTxHash())
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(CANDIDATE_LIMIT);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private boolean isStrongDestinationCandidate(NormalizedTransaction source, NormalizedTransaction destination) {
        if (!isAcrossSourceCandidate(source) || !isDestinationCandidate(destination)) {
            return false;
        }
        if (!sameWallet(source, destination) || sameNetwork(source, destination) || sameHash(source.getTxHash(), destination.getTxHash())) {
            return false;
        }
        if (!isPairingStateCompatible(source.getMatchedCounterparty(), destination.getTxHash())
                || !isPairingStateCompatible(destination.getMatchedCounterparty(), source.getTxHash())) {
            return false;
        }

        String expectedCorrelationId = correlationId(source.getTxHash());
        if (!isCorrelationCompatible(source.getCorrelationId(), expectedCorrelationId)
                || !isCorrelationCompatible(destination.getCorrelationId(), expectedCorrelationId)) {
            return false;
        }

        Optional<NormalizedTransaction.Flow> sourcePrincipal = BridgePairLinkSupport.selectPrimaryPrincipalFlow(source, -1);
        Optional<NormalizedTransaction.Flow> destinationPrincipal = BridgePairLinkSupport.selectPrimaryPrincipalFlow(destination, 1);
        if (sourcePrincipal.isEmpty() || destinationPrincipal.isEmpty()) {
            return false;
        }
        NormalizedTransaction.Flow sourceFlow = sourcePrincipal.orElseThrow();
        NormalizedTransaction.Flow destinationFlow = destinationPrincipal.orElseThrow();
        if (!BridgePairLinkSupport.supportsBridgeContinuity(sourceFlow, destinationFlow)) {
            return false;
        }

        long deltaSeconds = Math.abs(Duration.between(source.getBlockTimestamp(), destination.getBlockTimestamp()).toSeconds());
        if (deltaSeconds > MAX_TIME_DELTA.toSeconds()) {
            return false;
        }

        return relativeQuantityDiff(sourceFlow, destinationFlow)
                .compareTo(MAX_RELATIVE_QTY_DIFF) <= 0;
    }

    private boolean seedOrphanSourceCorrelation(NormalizedTransaction source) {
        if (!isAcrossSourceCandidate(source) || hasText(source.getCorrelationId())) {
            return false;
        }
        source.setCorrelationId(correlationId(source.getTxHash()));
        source.setUpdatedAt(Instant.now());
        normalizedTransactionRepository.save(source);
        return true;
    }

    private boolean materializePair(NormalizedTransaction source, NormalizedTransaction destination) {
        String correlationId = hasText(source.getCorrelationId()) ? source.getCorrelationId() : correlationId(source.getTxHash());
        boolean continuityCandidate = BridgePairLinkSupport.supportsPlainMoveBasis(source, destination);
        Instant now = Instant.now();

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
            if (!hasText(destination.getProtocolName()) && hasText(source.getProtocolName())) {
                destination.setProtocolName(source.getProtocolName());
            }
            if (!hasText(destination.getProtocolVersion()) && hasText(source.getProtocolVersion())) {
                destination.setProtocolVersion(source.getProtocolVersion());
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
            destination.setMatchedCounterparty(source.getTxHash());
            destination.setUpdatedAt(now);
            updates.add(destination);
        }

        if (updates.isEmpty()) {
            return false;
        }
        normalizedTransactionRepository.saveAll(deduplicateById(updates));
        return true;
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

    private boolean isAcrossSourceCandidate(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getSource() == NormalizedTransactionSource.ON_CHAIN
                && transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                && hasText(transaction.getWalletAddress())
                && transaction.getBlockTimestamp() != null
                && isAcrossProtocol(transaction.getProtocolName());
    }

    private boolean isDestinationCandidate(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getSource() == NormalizedTransactionSource.ON_CHAIN
                && isMaterializableDestination(transaction)
                && hasText(transaction.getWalletAddress())
                && transaction.getBlockTimestamp() != null;
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

    private void retagInboundFlowsAsBridgeTransfer(NormalizedTransaction transaction) {
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            flow.setRole(NormalizedLegRole.TRANSFER);
        }
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

    private boolean isPairingStateCompatible(String currentValue, String expectedValue) {
        return !hasText(currentValue) || sameHash(currentValue, expectedValue);
    }

    private boolean isCorrelationCompatible(String currentValue, String expectedValue) {
        return !hasText(currentValue) || sameCorrelation(currentValue, expectedValue);
    }

    private boolean isAcrossProtocol(String protocolName) {
        return hasText(protocolName) && protocolName.toLowerCase(Locale.ROOT).contains("across");
    }

    private String correlationId(String sourceTxHash) {
        return hasText(sourceTxHash)
                ? "bridge:across:" + sourceTxHash.toLowerCase(Locale.ROOT)
                : null;
    }

    private List<NormalizedTransaction> deduplicateById(List<NormalizedTransaction> candidates) {
        Map<String, NormalizedTransaction> deduplicated = new LinkedHashMap<>();
        for (NormalizedTransaction candidate : candidates) {
            if (candidate == null || !hasText(candidate.getId())) {
                continue;
            }
            deduplicated.putIfAbsent(candidate.getId(), candidate);
        }
        return List.copyOf(deduplicated.values());
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
}
