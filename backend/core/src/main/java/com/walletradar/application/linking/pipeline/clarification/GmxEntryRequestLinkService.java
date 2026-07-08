package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Links GMX LP_ENTRY_REQUEST rows (raw hex event-key correlationIds) to the pool-slug
 * correlationId of their corresponding LP_ENTRY_SETTLEMENT.
 *
 * <p>GMX processes deposit requests asynchronously: a user submits a deposit request
 * (LP_ENTRY_REQUEST) containing USDC and an execution fee, and a keeper settles it
 * within seconds by minting GM/GLV tokens (LP_ENTRY_SETTLEMENT). Because the classifier
 * assigns the raw deposit-key as the request's correlationId and the pool slug as the
 * settlement's, they appear as separate positions in the accumulator.
 *
 * <p>Matching strategy: same wallet address + settlement timestamp within
 * {@value #SETTLEMENT_WINDOW_SECONDS} seconds after the request. On Arbitrum, keepers
 * settle in 1–3 seconds, so a 10-minute window is conservative yet safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmxEntryRequestLinkService {

    private static final String GMX_LP_PREFIX = "gmx-lp:";
    private static final long SETTLEMENT_WINDOW_SECONDS = 600;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int linkOutstandingRequests(int batchSize) {
        List<NormalizedTransaction> requests = loadUnlinkedRequests(batchSize);
        if (requests.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (NormalizedTransaction request : requests) {
            String wallet = request.getWalletAddress();
            Instant requestTime = request.getBlockTimestamp();
            if (wallet == null || wallet.isBlank() || requestTime == null) {
                continue;
            }

            NormalizedTransaction settlement = findMatchingSettlement(wallet, requestTime);
            if (settlement == null || settlement.getCorrelationId() == null) {
                continue;
            }

            log.info("GMX_ENTRY_REQUEST_LINK corrId={} -> pool={} (settlement={})",
                    request.getCorrelationId(), settlement.getCorrelationId(), settlement.getTxHash());
            request.setCorrelationId(settlement.getCorrelationId());
            request.setUpdatedAt(now);
            dirty.add(request);
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("GMX_ENTRY_REQUEST_LINK linked={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadUnlinkedRequests(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.LP_ENTRY_REQUEST),
                Criteria.where("protocolName").regex("^gmx", "i"),
                Criteria.where("correlationId").exists(true).ne(null).ne(""),
                Criteria.where("correlationId").not().regex("^" + GMX_LP_PREFIX)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private NormalizedTransaction findMatchingSettlement(String walletAddress, Instant requestTime) {
        Instant windowEnd = requestTime.plusSeconds(SETTLEMENT_WINDOW_SECONDS);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.LP_ENTRY_SETTLEMENT),
                Criteria.where("walletAddress").is(walletAddress),
                Criteria.where("correlationId").regex("^" + GMX_LP_PREFIX),
                Criteria.where("blockTimestamp").gte(requestTime).lte(windowEnd)
        ));
        query.with(Sort.by(Sort.Direction.ASC, "blockTimestamp")).limit(1);
        return mongoOperations.findOne(query, NormalizedTransaction.class);
    }
}
