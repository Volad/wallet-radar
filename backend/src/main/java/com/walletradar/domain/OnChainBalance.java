package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Current on-chain asset quantity for (wallet, network, asset). Updated by balance poll; used for reconciliation.
 */
@Document(collection = "on_chain_balances")
@CompoundIndex(name = "wallet_network_asset", def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1}", unique = true)
public class OnChainBalance {

    @Id
    private String id;
    private String walletAddress;
    private String networkId;
    private String assetContract;
    private BigDecimal quantity;
    private Instant capturedAt;

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

    public String getAssetContract() {
        return assetContract;
    }

    public void setAssetContract(String assetContract) {
        this.assetContract = assetContract;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OnChainBalance that = (OnChainBalance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
