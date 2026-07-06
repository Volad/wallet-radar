package com.walletradar.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Clears stale PRICE_UNRESOLVABLE flags once all replay-required flows are now priced.
 */
@Service
@RequiredArgsConstructor
public class StalePriceUnresolvedRepairService {

    private final MongoOperations mongoOperations;

    public int repairNextBatch(int batchSize) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("missingDataReasons").in(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON),
                Criteria.where("status").ne(com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus.PENDING_PRICE)
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));

        List<NormalizedTransaction> rows = mongoOperations.find(query, NormalizedTransaction.class);
        if (rows.isEmpty()) {
            return 0;
        }

        int repaired = 0;
        Instant now = Instant.now();
        for (NormalizedTransaction row : rows) {
            if (PriceableFlowPolicy.hasReplayRelevantUnresolvedPrice(row)) {
                continue;
            }
            Set<String> reasons = new LinkedHashSet<>(row.getMissingDataReasons() == null
                    ? List.of()
                    : row.getMissingDataReasons());
            if (!reasons.remove(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON)) {
                continue;
            }
            row.setMissingDataReasons(new ArrayList<>(reasons));
            row.setUpdatedAt(now);
            mongoOperations.save(row);
            repaired++;
        }
        return repaired;
    }
}
