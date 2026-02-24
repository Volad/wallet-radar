package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * Async AVCO recalculation job (e.g. after override or manual compensating transaction).
 * Persisted in recalc_jobs; optional TTL cleanup (e.g. 24h).
 */
@Document(collection = "recalc_jobs")
public class RecalcJob {

    @Id
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public RecalcStatus getStatus() {
        return status;
    }

    public void setStatus(RecalcStatus status) {
        this.status = status;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }

    public String getAssetSymbol() {
        return assetSymbol;
    }

    public void setAssetSymbol(String assetSymbol) {
        this.assetSymbol = assetSymbol;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecalcJob recalcJob = (RecalcJob) o;
        return Objects.equals(id, recalcJob.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
