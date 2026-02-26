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
 * Sync progress per (walletAddress, networkId). Updated during backfill and incremental sync.
 */
@Document(collection = "sync_status")
@CompoundIndex(name = "wallet_network", def = "{'walletAddress': 1, 'networkId': 1}", unique = true)
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SyncStatus {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private String walletAddress;
    private String networkId;
    private SyncStatusValue status;
    private Integer progressPct;
    private Long lastBlockSynced;
    private String syncBannerMessage;
    private boolean backfillComplete;
    /** Phase 1 done: raw fetch complete for this wallet×network (ADR-020). */
    private boolean rawFetchComplete;
    /** Phase 2 done: classification complete (ADR-020). */
    private boolean classificationComplete;
    private Instant updatedAt;
    private int retryCount;
    private Instant nextRetryAfter;

    public enum SyncStatusValue {
        PENDING,
        RUNNING,
        COMPLETE,
        PARTIAL,
        FAILED,
        ABANDONED
    }
}
