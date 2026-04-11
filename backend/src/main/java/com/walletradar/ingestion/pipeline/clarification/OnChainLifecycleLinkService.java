package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.pipeline.classification.support.GmxEventTopicSupport;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Materializes deterministic same-source lifecycle linkage during the dedicated LINKING phase.
 */
@Service
public class OnChainLifecycleLinkService {

    private static final String GMX_ORDER_CANCELLED_EVENT = GmxEventTopicSupport.topicHash("OrderCancelled");
    private static final String GMX_ORDER_EXECUTED_EVENT = GmxEventTopicSupport.topicHash("OrderExecuted");

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    @Nullable
    private final RawTransactionRepository rawTransactionRepository;
    @Nullable
    private final MongoOperations mongoOperations;

    @Autowired
    public OnChainLifecycleLinkService(
            NormalizedTransactionRepository normalizedTransactionRepository,
            @Nullable RawTransactionRepository rawTransactionRepository,
            @Nullable MongoOperations mongoOperations
    ) {
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.rawTransactionRepository = rawTransactionRepository;
        this.mongoOperations = mongoOperations;
    }

    public OnChainLifecycleLinkService(NormalizedTransactionRepository normalizedTransactionRepository) {
        this(normalizedTransactionRepository, null, null);
    }

    public int processNextBatch(int batchSize) {
        if (mongoOperations == null || rawTransactionRepository == null) {
            return 0;
        }
        int changed = 0;
        for (NormalizedTransaction candidate : loadCandidateBatch(batchSize)) {
            RawTransaction rawTransaction = rawTransactionRepository.findById(candidate.getId()).orElse(null);
            if (rawTransaction != null && link(rawTransaction, candidate)) {
                changed++;
            }
        }
        return changed;
    }

    public boolean link(RawTransaction rawTransaction, NormalizedTransaction normalizedTransaction) {
        if (rawTransaction == null || normalizedTransaction == null) {
            return false;
        }
        Set<String> correlationIds = relatedCorrelationIds(rawTransaction, normalizedTransaction);
        if (correlationIds.isEmpty()
                || normalizedTransaction.getWalletAddress() == null
                || normalizedTransaction.getNetworkId() == null) {
            return false;
        }

        List<NormalizedTransaction> related = normalizedTransactionRepository
                .findAllByCorrelationIdInAndSourceAndWalletAddressAndNetworkId(
                        correlationIds,
                        NormalizedTransactionSource.ON_CHAIN,
                        normalizedTransaction.getWalletAddress(),
                        normalizedTransaction.getNetworkId()
                );
        if (related == null || related.isEmpty()) {
            return false;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> updates = new ArrayList<>();
        List<NormalizedTransaction> peers = related.stream()
                .filter(candidate -> candidate != null && !normalizedTransaction.getId().equals(candidate.getId()))
                .toList();
        NormalizedTransaction primaryCounterparty = selectPrimaryCounterparty(normalizedTransaction, peers);
        if (primaryCounterparty != null
                && hasText(primaryCounterparty.getTxHash())
                && !sameHash(normalizedTransaction.getMatchedCounterparty(), primaryCounterparty.getTxHash())) {
            normalizedTransaction.setMatchedCounterparty(primaryCounterparty.getTxHash());
            normalizedTransaction.setUpdatedAt(now);
            updates.add(normalizedTransaction);
        }

        for (NormalizedTransaction candidate : peers) {
            String linkedCounterparty = resolveCandidateCounterparty(normalizedTransaction, candidate, primaryCounterparty);
            if (!hasText(linkedCounterparty) || sameHash(candidate.getMatchedCounterparty(), linkedCounterparty)) {
                continue;
            }
            candidate.setMatchedCounterparty(linkedCounterparty);
            candidate.setUpdatedAt(now);
            updates.add(candidate);
        }
        if (!updates.isEmpty()) {
            normalizedTransactionRepository.saveAll(updates);
            return true;
        }
        return false;
    }

    private List<NormalizedTransaction> loadCandidateBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").in("CONFIRMED", "PENDING_PRICE"),
                Criteria.where("walletAddress").exists(true).ne(null),
                Criteria.where("networkId").exists(true).ne(null),
                Criteria.where("txHash").exists(true).ne(""),
                missingCounterpartyCriteria(),
                new Criteria().orOperator(
                        asyncLifecycleCandidateCriteria(),
                        gmxLifecycleCandidateCriteria()
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

    private Criteria asyncLifecycleCandidateCriteria() {
        return new Criteria().andOperator(
                Criteria.where("type").in(
                        NormalizedTransactionType.LP_ENTRY_REQUEST,
                        NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                        NormalizedTransactionType.LP_EXIT_REQUEST,
                        NormalizedTransactionType.LP_EXIT_SETTLEMENT,
                        NormalizedTransactionType.DEX_ORDER_REQUEST,
                        NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                        NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST,
                        NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                        NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
                        NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
                        NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE
                ),
                Criteria.where("correlationId").exists(true).ne("")
        );
    }

    private Criteria gmxLifecycleCandidateCriteria() {
        return new Criteria().andOperator(
                Criteria.where("protocolName").regex("^GMX$", "i"),
                Criteria.where("type").in(
                        NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                        NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL,
                        NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE,
                        NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE,
                        NormalizedTransactionType.LP_ENTRY_REQUEST,
                        NormalizedTransactionType.LP_ENTRY_SETTLEMENT,
                        NormalizedTransactionType.LP_EXIT_REQUEST,
                        NormalizedTransactionType.LP_EXIT_SETTLEMENT
                )
        );
    }

    private Criteria missingCounterpartyCriteria() {
        return new Criteria().orOperator(
                Criteria.where("matchedCounterparty").exists(false),
                Criteria.where("matchedCounterparty").is(null),
                Criteria.where("matchedCounterparty").is("")
        );
    }

    private NormalizedTransaction selectPrimaryCounterparty(
            NormalizedTransaction normalizedTransaction,
            List<NormalizedTransaction> peers
    ) {
        if (peers == null || peers.isEmpty()) {
            return null;
        }
        return peers.stream()
                .filter(candidate -> sameCorrelation(candidate.getCorrelationId(), normalizedTransaction.getCorrelationId()))
                .sorted(primaryCounterpartyComparator(normalizedTransaction))
                .findFirst()
                .orElse(null);
    }

    private Comparator<NormalizedTransaction> primaryCounterpartyComparator(NormalizedTransaction normalizedTransaction) {
        return Comparator
                .comparingInt((NormalizedTransaction candidate) -> asyncCounterpartyRank(normalizedTransaction, candidate))
                .thenComparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Instant::compareTo))
                .thenComparing(NormalizedTransaction::getTransactionIndex, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(NormalizedTransaction::getTxHash, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private int asyncCounterpartyRank(
            NormalizedTransaction normalizedTransaction,
            NormalizedTransaction candidate
    ) {
        if (isExactAsyncCounterparty(normalizedTransaction.getType(), candidate.getType())) {
            return 0;
        }
        if (isDerivativeTerminalFamily(normalizedTransaction.getType())
                && candidate.getType() == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST) {
            return 1;
        }
        return 2;
    }

    private String resolveCandidateCounterparty(
            NormalizedTransaction normalizedTransaction,
            NormalizedTransaction candidate,
            NormalizedTransaction primaryCounterparty
    ) {
        if (candidate == null || !hasText(normalizedTransaction.getTxHash())) {
            return null;
        }
        if (sameCorrelation(candidate.getCorrelationId(), normalizedTransaction.getCorrelationId())
                && isExactAsyncCounterparty(normalizedTransaction.getType(), candidate.getType())) {
            return normalizedTransaction.getTxHash();
        }
        if (isAcceptedAsymmetricDerivativeLink(normalizedTransaction, candidate)) {
            return normalizedTransaction.getTxHash();
        }
        if (primaryCounterparty != null
                && hasText(primaryCounterparty.getTxHash())
                && sameHash(candidate.getTxHash(), primaryCounterparty.getTxHash())
                && sameCorrelation(candidate.getCorrelationId(), normalizedTransaction.getCorrelationId())) {
            return normalizedTransaction.getTxHash();
        }
        return null;
    }

    private Set<String> relatedCorrelationIds(
            RawTransaction rawTransaction,
            NormalizedTransaction normalizedTransaction
    ) {
        LinkedHashSet<String> correlationIds = new LinkedHashSet<>();
        if (hasText(normalizedTransaction.getCorrelationId())) {
            correlationIds.add(normalizedTransaction.getCorrelationId().toLowerCase(Locale.ROOT));
        }
        if (normalizedTransaction.getProtocolName() == null
                || !"GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName())
                || !isGmxTerminalFamily(normalizedTransaction.getType())) {
            return correlationIds;
        }

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        for (Document log : view.persistedLogs()) {
            if (!GmxEventTopicSupport.EVENT_EMITTER_TOPIC.equals(topicAt(log, 0))) {
                continue;
            }
            String eventTopic = topicAt(log, 1);
            if (!GMX_ORDER_CANCELLED_EVENT.equals(eventTopic) && !GMX_ORDER_EXECUTED_EVENT.equals(eventTopic)) {
                continue;
            }
            String correlationId = topicAt(log, 2);
            if (hasText(correlationId)) {
                correlationIds.add(correlationId.toLowerCase(Locale.ROOT));
            }
        }
        return correlationIds;
    }

    private boolean isGmxTerminalFamily(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION
                || type == NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL
                || type == NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE
                || type == NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE
                || type == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                || type == NormalizedTransactionType.LP_EXIT_SETTLEMENT;
    }

    private boolean isDerivativeTerminalFamily(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION
                || type == NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL
                || type == NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE
                || type == NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE;
    }

    private boolean isAcceptedAsymmetricDerivativeLink(
            NormalizedTransaction normalizedTransaction,
            NormalizedTransaction candidate
    ) {
        return isDerivativeTerminalFamily(normalizedTransaction.getType())
                && candidate.getType() == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST;
    }

    private boolean isExactAsyncCounterparty(
            NormalizedTransactionType left,
            NormalizedTransactionType right
    ) {
        if (left == null || right == null) {
            return false;
        }
        return (left == NormalizedTransactionType.LP_ENTRY_REQUEST && right == NormalizedTransactionType.LP_ENTRY_SETTLEMENT)
                || (left == NormalizedTransactionType.LP_ENTRY_SETTLEMENT && right == NormalizedTransactionType.LP_ENTRY_REQUEST)
                || (left == NormalizedTransactionType.LP_EXIT_REQUEST && right == NormalizedTransactionType.LP_EXIT_SETTLEMENT)
                || (left == NormalizedTransactionType.LP_EXIT_SETTLEMENT && right == NormalizedTransactionType.LP_EXIT_REQUEST)
                || (left == NormalizedTransactionType.DEX_ORDER_REQUEST && right == NormalizedTransactionType.DEX_ORDER_SETTLEMENT)
                || (left == NormalizedTransactionType.DEX_ORDER_SETTLEMENT && right == NormalizedTransactionType.DEX_ORDER_REQUEST)
                || (left == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST && isDerivativeTerminalFamily(right))
                || (right == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST && isDerivativeTerminalFamily(left));
    }

    private String topicAt(Document log, int index) {
        if (log == null) {
            return null;
        }
        Object topicsObject = log.get("topics");
        if (!(topicsObject instanceof List<?> topics) || index < 0 || index >= topics.size()) {
            return null;
        }
        Object topic = topics.get(index);
        return topic == null ? null : topic.toString().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean sameCorrelation(String left, String right) {
        return hasText(left) && hasText(right) && left.equalsIgnoreCase(right);
    }

    private boolean sameHash(String left, String right) {
        return hasText(left) && hasText(right) && left.equalsIgnoreCase(right);
    }
}
