package com.walletradar.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Point-in-time asset entry within a portfolio snapshot. All monetary fields are BigDecimal (INV-06).
 */
public class AssetSnapshot {

    private String assetSymbol;
    private BigDecimal quantity;
    private BigDecimal perWalletAvco;
    private BigDecimal spotPriceUsd;
    private BigDecimal valueUsd;
    private BigDecimal unrealisedPnlUsd;
    private boolean isResolved;

    public String getAssetSymbol() {
        return assetSymbol;
    }

    public void setAssetSymbol(String assetSymbol) {
        this.assetSymbol = assetSymbol;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPerWalletAvco() {
        return perWalletAvco;
    }

    public void setPerWalletAvco(BigDecimal perWalletAvco) {
        this.perWalletAvco = perWalletAvco;
    }

    public BigDecimal getSpotPriceUsd() {
        return spotPriceUsd;
    }

    public void setSpotPriceUsd(BigDecimal spotPriceUsd) {
        this.spotPriceUsd = spotPriceUsd;
    }

    public BigDecimal getValueUsd() {
        return valueUsd;
    }

    public void setValueUsd(BigDecimal valueUsd) {
        this.valueUsd = valueUsd;
    }

    public BigDecimal getUnrealisedPnlUsd() {
        return unrealisedPnlUsd;
    }

    public void setUnrealisedPnlUsd(BigDecimal unrealisedPnlUsd) {
        this.unrealisedPnlUsd = unrealisedPnlUsd;
    }

    public boolean isResolved() {
        return isResolved;
    }

    public void setResolved(boolean resolved) {
        isResolved = resolved;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssetSnapshot that = (AssetSnapshot) o;
        return Objects.equals(assetSymbol, that.assetSymbol)
                && Objects.equals(quantity, that.quantity)
                && Objects.equals(perWalletAvco, that.perWalletAvco);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetSymbol, quantity, perWalletAvco);
    }
}
