package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Persistence for segment-level backfill progress.
 */
public interface BackfillSegmentRepository extends MongoRepository<BackfillSegment, String> {

    boolean existsBySyncStatusId(String syncStatusId);

    List<BackfillSegment> findBySyncStatusIdOrderBySegmentIndexAsc(String syncStatusId);

    List<BackfillSegment> findBySyncStatusIdAndStatusInOrderBySegmentIndexAsc(
            String syncStatusId,
            Collection<BackfillSegment.SegmentStatus> statuses);

    List<BackfillSegment> findBySyncStatusIdAndStatusAndUpdatedAtBefore(
            String syncStatusId,
            BackfillSegment.SegmentStatus status,
            Instant updatedAt);

    long countBySyncStatusId(String syncStatusId);

    long countBySyncStatusIdAndStatus(String syncStatusId, BackfillSegment.SegmentStatus status);
}

