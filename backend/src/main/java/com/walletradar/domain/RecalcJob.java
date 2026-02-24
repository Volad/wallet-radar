package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Async AVCO recalculation job (e.g. after override or manual compensating transaction).
 * Persisted in recalc_jobs; optional TTL cleanup (e.g. 24h).
 * Executor runs AvcoEngine.replayFromBeginning(walletAddress, networkId, assetContract) for the affected (wallet, asset).
 */
@Document(collection = "recalc_jobs")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RecalcJob {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private RecalcStatus status;
    private String walletAddress;
    private String networkId;
    private String assetContract;
    private String assetSymbol;
    private Instant createdAt;
    private Instant completedAt;
    /** Set when status=COMPLETE; returned by GET /recalc/status/{jobId}. */
    private BigDecimal newPerWalletAvco;

    public enum RecalcStatus {
        PENDING,
        RUNNING,
        COMPLETE,
        FAILED
    }
}
