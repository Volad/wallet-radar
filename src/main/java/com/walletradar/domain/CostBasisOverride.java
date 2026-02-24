package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * User-supplied cost price replacing system-derived price for an on-chain event.
 * Stored in cost_basis_overrides; isActive=true supersedes original priceUsd in AVCO replay (INV-08).
 */
@Document(collection = "cost_basis_overrides")
public class CostBasisOverride {

    @Id
    private String id;
    @Indexed
    private String economicEventId;
    private BigDecimal priceUsd;
    private boolean isActive;
    private String note;
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEconomicEventId() {
        return economicEventId;
    }

    public void setEconomicEventId(String economicEventId) {
        this.economicEventId = economicEventId;
    }

    public BigDecimal getPriceUsd() {
        return priceUsd;
    }

    public void setPriceUsd(BigDecimal priceUsd) {
        this.priceUsd = priceUsd;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CostBasisOverride that = (CostBasisOverride) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
