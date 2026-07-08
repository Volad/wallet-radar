package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.CowSwapSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Links misclassified GPv2 CoW Swap settlement inflows ({@code EXTERNAL_TRANSFER_IN}) to the
 * preceding Eth Flow {@code DEX_ORDER_REQUEST} once both rows exist in normalized output.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CowSwapEthFlowSettlementLinkService {

    private static final Duration MAX_REQUEST_AGE = Duration.ofDays(7);

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int linkOutstandingSettlements(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction settlement : loadCandidateBatch(batchSize)) {
            if (link(settlement)) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("CowSwapEthFlowSettlementLink: linked={}", changed);
        }
        return changed;
    }

    boolean link(NormalizedTransaction settlement) {
        if (!isSettlementCandidate(settlement)) {
            return false;
        }
        List<NormalizedTransaction> requests = loadOutstandingRequests(settlement);
        if (requests.size() != 1) {
            return false;
        }
        NormalizedTransaction request = requests.getFirst();
        if (request.getCorrelationId() == null || request.getCorrelationId().isBlank()) {
            return false;
        }
        if (settlementAlreadyLinked(request.getCorrelationId(), settlement)) {
            return false;
        }
        return applyLinkage(settlement, request);
    }

    private List<NormalizedTransaction> loadCandidateBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                Criteria.where("walletAddress").exists(true).ne(""),
                Criteria.where("networkId").exists(true),
                Criteria.where("blockTimestamp").exists(true),
                new Criteria().orOperator(
                        Criteria.where("counterpartyAddress").is(CowSwapSupport.GPV2_SETTLEMENT),
                        Criteria.where("counterpartyAddress").is(CowSwapSupport.GPV2_SETTLEMENT.toLowerCase(Locale.ROOT))
                ),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is(""),
                        Criteria.where("protocolName").exists(false),
                        Criteria.where("protocolName").is(null),
                        Criteria.where("protocolName").is("")
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::isSettlementCandidate)
                .toList();
    }

    private List<NormalizedTransaction> loadOutstandingRequests(NormalizedTransaction settlement) {
        Instant earliest = settlement.getBlockTimestamp().minus(MAX_REQUEST_AGE);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.DEX_ORDER_REQUEST),
                Criteria.where("protocolName").regex("^CoW Swap$", "i"),
                Criteria.where("walletAddress").is(settlement.getWalletAddress()),
                Criteria.where("networkId").is(settlement.getNetworkId()),
                Criteria.where("blockTimestamp").gte(earliest).lte(settlement.getBlockTimestamp()),
                Criteria.where("correlationId").exists(true).ne("")
        ));
        query.with(Sort.by(
                Sort.Order.desc("blockTimestamp"),
                Sort.Order.desc("transactionIndex"),
                Sort.Order.desc("_id")
        ));
        query.limit(8);
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(request -> !settlementAlreadyLinked(request.getCorrelationId(), settlement))
                .toList();
    }

    private boolean settlementAlreadyLinked(String correlationId, NormalizedTransaction settlement) {
        if (correlationId == null || correlationId.isBlank()) {
            return true;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.DEX_ORDER_SETTLEMENT),
                Criteria.where("correlationId").is(correlationId),
                Criteria.where("walletAddress").is(settlement.getWalletAddress()),
                Criteria.where("networkId").is(settlement.getNetworkId()),
                Criteria.where("_id").ne(settlement.getId())
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private boolean applyLinkage(NormalizedTransaction settlement, NormalizedTransaction request) {
        Instant now = Instant.now();
        boolean changed = false;
        if (settlement.getType() != NormalizedTransactionType.DEX_ORDER_SETTLEMENT) {
            settlement.setType(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
            changed = true;
        }
        if (!CowSwapSupport.PROTOCOL_NAME.equalsIgnoreCase(
                settlement.getProtocolName() == null ? "" : settlement.getProtocolName())) {
            settlement.setProtocolName(CowSwapSupport.PROTOCOL_NAME);
            changed = true;
        }
        if (!Objects.equals(settlement.getCorrelationId(), request.getCorrelationId())) {
            settlement.setCorrelationId(request.getCorrelationId());
            changed = true;
        }
        if (settlement.getClassifiedBy() == null) {
            settlement.setClassifiedBy(ClassificationSource.HEURISTIC);
            changed = true;
        }
        if (!sameHash(settlement.getMatchedCounterparty(), request.getTxHash())) {
            settlement.setMatchedCounterparty(request.getTxHash());
            changed = true;
        }
        if (!sameHash(request.getMatchedCounterparty(), settlement.getTxHash())) {
            request.setMatchedCounterparty(settlement.getTxHash());
            request.setUpdatedAt(now);
            normalizedTransactionRepository.save(request);
        }
        if (changed) {
            settlement.setUpdatedAt(now);
            normalizedTransactionRepository.save(settlement);
        }
        return changed;
    }

    private boolean isSettlementCandidate(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.ON_CHAIN
                || transaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getWalletAddress() == null
                || transaction.getNetworkId() == null
                || transaction.getBlockTimestamp() == null) {
            return false;
        }
        if (!isGpv2SettlementCounterparty(transaction.getCounterpartyAddress())) {
            return false;
        }
        return principalFlows(transaction).size() == 1;
    }

    private static boolean isGpv2SettlementCounterparty(String counterpartyAddress) {
        if (counterpartyAddress == null || counterpartyAddress.isBlank()) {
            return false;
        }
        return CowSwapSupport.GPV2_SETTLEMENT.equalsIgnoreCase(counterpartyAddress.trim());
    }

    private List<NormalizedTransaction.Flow> principalFlows(NormalizedTransaction transaction) {
        if (transaction.getFlows() == null) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0)
                .toList();
    }

    private static boolean sameHash(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }
}
