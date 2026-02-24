package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * Sync progress per (walletAddress, networkId). Updated during backfill and incremental sync.
 */
@Document(collection = "sync_status")
@CompoundIndex(name = "wallet_network", def = "{'walletAddress': 1, 'networkId': 1}", unique = true)
public class SyncStatus {

    @Id
    private String id;
    private String walletAddress;
    private String networkId;
    private SyncStatusValue status;
    private Integer progressPct;
    private Long lastBlockSynced;
    private String syncBannerMessage;
    private boolean backfillComplete;
    private Instant updatedAt;

    public enum SyncStatusValue {
        PENDING,
        RUNNING,
        COMPLETE,
        PARTIAL,
        FAILED
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public SyncStatusValue getStatus() {
        return status;
    }

    public void setStatus(SyncStatusValue status) {
        this.status = status;
    }

    public Integer getProgressPct() {
        return progressPct;
    }

    public void setProgressPct(Integer progressPct) {
        this.progressPct = progressPct;
    }

    public Long getLastBlockSynced() {
        return lastBlockSynced;
    }

    public void setLastBlockSynced(Long lastBlockSynced) {
        this.lastBlockSynced = lastBlockSynced;
    }

    public String getSyncBannerMessage() {
        return syncBannerMessage;
    }

    public void setSyncBannerMessage(String syncBannerMessage) {
        this.syncBannerMessage = syncBannerMessage;
    }

    public boolean isBackfillComplete() {
        return backfillComplete;
    }

    public void setBackfillComplete(boolean backfillComplete) {
        this.backfillComplete = backfillComplete;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncStatus that = (SyncStatus) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
