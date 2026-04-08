package com.walletradar.domain.sync;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persistent status of one backfill segment for a wallet×network sync.
 * Linked to sync_status via syncStatusId.
 */
@Document(collection = "backfill_segments")
@CompoundIndex(name = "sync_segment_unique", def = "{'syncStatusId': 1, 'segmentIndex': 1}", unique = true)
@CompoundIndex(name = "sync_status_updated", def = "{'syncStatusId': 1, 'status': 1, 'updatedAt': 1}")
@CompoundIndex(name = "wallet_network_status", def = "{'walletAddress': 1, 'networkId': 1, 'status': 1}")
@CompoundIndex(name = "integration_segment_status_idx", def = "{'sourceKind': 1, 'integrationId': 1, 'status': 1, 'updatedAt': 1}")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BackfillSegment {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String sessionId;
    private SourceKind sourceKind;
    private SegmentKind segmentKind;
    private String syncStatusId;
    private String walletAddress;
    private String networkId;
    private String integrationId;
    private String provider;
    private String accountRef;
    private String stream;

    private Integer segmentIndex;
    private Long fromBlock;
    private Long toBlock;
    private Instant fromTime;
    private Instant toTime;
    private String cursor;

    private SegmentStatus status;
    private Integer progressPct;
    private Long processedCount;
    private Long lastProcessedBlock;
    private String errorMessage;
    private Integer retryCount;

    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;

    public enum SegmentStatus {
        PENDING,
        RUNNING,
        COMPLETE,
        FAILED
    }

    public enum SourceKind {
        ONCHAIN,
        INTEGRATION
    }

    public enum SegmentKind {
        BLOCK_RANGE,
        TIME_RANGE,
        CURSOR_PAGE
    }
}
