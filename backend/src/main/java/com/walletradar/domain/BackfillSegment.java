package com.walletradar.domain;

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
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BackfillSegment {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String syncStatusId;
    private String walletAddress;
    private String networkId;

    private Integer segmentIndex;
    private Long fromBlock;
    private Long toBlock;

    private SegmentStatus status;
    private Integer progressPct;
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
}

