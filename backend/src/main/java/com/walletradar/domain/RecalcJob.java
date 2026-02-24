package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Async AVCO recalculation job (e.g. after override or manual compensating transaction).
 * Persisted in recalc_jobs; optional TTL cleanup (e.g. 24h).
 * Recalc is full-wallet for now: executor runs AvcoEngine.recalculateForWallet(walletAddress) for all (network, asset) pairs of that wallet; networkId/assetContract are not stored on the job.
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
    private String assetSymbol;
    private Instant createdAt;
    private Instant completedAt;

    public enum RecalcStatus {
        PENDING,
        COMPLETE,
        FAILED
    }
}
