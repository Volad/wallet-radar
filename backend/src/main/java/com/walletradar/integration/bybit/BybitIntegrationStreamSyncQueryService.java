package com.walletradar.integration.bybit;

import com.walletradar.domain.sync.BackfillSegment;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Read-only snapshot of how far each Bybit API stream has been pulled and materialized.
 */
@Service
@RequiredArgsConstructor
public class BybitIntegrationStreamSyncQueryService {

    private static final String BACKFILL_SEGMENTS = "backfill_segments";
    private static final String BYBIT_EXTRACTED_EVENTS = "bybit_extracted_events";

    private final MongoOperations mongoOperations;

    public List<BybitStreamSyncSnapshot> summarize(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return List.of();
        }
        String id = integrationId.trim();
        Map<String, Instant> lastSegmentCompleted = maxInstantByGroup(
                BACKFILL_SEGMENTS,
                Criteria.where("integrationId").is(id)
                        .and("sourceKind").is(BackfillSegment.SourceKind.INTEGRATION)
                        .and("status").is(BackfillSegment.SegmentStatus.COMPLETE)
                        .and("completedAt").ne(null),
                "stream",
                "completedAt"
        );
        Map<String, Instant> newestStoredEvent = maxInstantByGroup(
                BYBIT_EXTRACTED_EVENTS,
                Criteria.where("integrationId").is(id),
                "sourceStream",
                "timeUtc"
        );
        return Stream.of(BybitIntegrationStream.values())
                .map(stream -> new BybitStreamSyncSnapshot(
                        stream.name(),
                        lastSegmentCompleted.get(stream.name()),
                        newestStoredEvent.get(stream.name())
                ))
                .toList();
    }

    private Map<String, Instant> maxInstantByGroup(
            String collection,
            Criteria matchCriteria,
            String groupField,
            String maxField
    ) {
        String alias = "maxVal";
        var pipeline = Aggregation.newAggregation(
                Aggregation.match(matchCriteria),
                Aggregation.group(groupField).max(maxField).as(alias)
        );
        Map<String, Instant> out = new HashMap<>();
        for (Document doc : mongoOperations.aggregate(pipeline, collection, Document.class).getMappedResults()) {
            String stream = doc.getString("_id");
            if (stream == null) {
                continue;
            }
            Instant instant = readInstant(doc.get(alias));
            if (instant != null) {
                out.put(stream, instant);
            }
        }
        return out;
    }

    private static Instant readInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof Date date) {
            return date.toInstant();
        }
        return null;
    }
}
