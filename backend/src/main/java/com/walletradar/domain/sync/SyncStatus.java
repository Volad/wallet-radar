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
 * Source-level raw sync progress. On-chain sources use walletAddress+networkId.
 * Integration sources additionally populate integrationId/provider/accountRef
 * while reusing walletAddress/networkId as a stable unique source key.
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
    private SourceKind sourceKind;
    private String walletAddress;
    private String networkId;
    private String integrationId;
    private String provider;
    private String accountRef;
    private SyncStatusValue status;
    private Integer progressPct;
    private Long lastBlockSynced;
    private Instant lastSyncedAt;
    private Long windowFromBlock;
    private Long windowToBlock;
    private Instant windowFromTime;
    private Instant windowToTime;
    private String syncBannerMessage;
    private boolean backfillComplete;
    /** Raw fetch complete for this wallet×network. */
    private boolean rawFetchComplete;
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

    public enum SourceKind {
        ONCHAIN,
        INTEGRATION
    }
}
