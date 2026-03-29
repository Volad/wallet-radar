package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRawStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads due Bybit raw rows in deterministic source order.
 */
@Service
@RequiredArgsConstructor
public class PendingExternalLedgerRowQueryService {

    private final MongoOperations mongoOperations;

    public List<ExternalLedgerRaw> loadNextBatch(int batchSize) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(ExternalLedgerRawStatus.RAW),
                Criteria.where("basisRelevant").is(Boolean.TRUE),
                new Criteria().orOperator(
                        Criteria.where("outOfScope").exists(false),
                        Criteria.where("outOfScope").is(Boolean.FALSE)
                ),
                new Criteria().orOperator(
                        Criteria.where("canonicalType").exists(false),
                        Criteria.where("canonicalType").ne("UNKNOWN_CEX")
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("timeUtc"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, ExternalLedgerRaw.class);
    }
}
