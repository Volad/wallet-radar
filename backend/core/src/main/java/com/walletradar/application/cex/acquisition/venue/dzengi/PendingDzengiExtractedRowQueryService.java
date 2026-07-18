package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads pending Dzengi extracted rows for normalization batches.
 */
@Service
@RequiredArgsConstructor
public class PendingDzengiExtractedRowQueryService {

    private final MongoOperations mongoOperations;

    public List<DzengiExtractedEvent> loadNextBatch(int batchSize) {
        return loadNextBatch(batchSize, null);
    }

    public List<DzengiExtractedEvent> loadNextBatch(int batchSize, String sessionId) {
        Criteria criteria = Criteria.where("status").is(DzengiExtractedEventStatus.RAW)
                .and("basisRelevant").is(Boolean.TRUE)
                .orOperator(
                        Criteria.where("outOfScope").exists(false),
                        Criteria.where("outOfScope").is(Boolean.FALSE)
                );
        if (sessionId != null && !sessionId.isBlank()) {
            criteria = criteria.and("sessionId").is(sessionId.trim());
        }
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.asc("timeUtc"), Sort.Order.asc("_id")))
                .limit(Math.max(1, batchSize));
        return mongoOperations.find(query, DzengiExtractedEvent.class);
    }
}
