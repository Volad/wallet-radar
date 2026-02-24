package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Central domain object: normalised financial event. All monetary fields are BigDecimal (INV-06).
 * Manual compensating events have txHash=null and use clientId for idempotency.
 */
@Document(collection = "economic_events")
@CompoundIndexes({
    @CompoundIndex(name = "txHash_networkId", def = "{'txHash': 1, 'networkId': 1}", unique = true, sparse = true),
    @CompoundIndex(name = "wallet_network_block", def = "{'walletAddress': 1, 'networkId': 1, 'blockTimestamp': 1}"),
    @CompoundIndex(name = "wallet_asset_block", def = "{'walletAddress': 1, 'assetSymbol': 1, 'blockTimestamp': 1}")
})
public class EconomicEvent {

    @Id
    private String id;
    private String txHash;
    private NetworkId networkId;
    private String walletAddress;
    private Instant blockTimestamp;
    private EconomicEventType eventType;
    private String assetSymbol;
    private String assetContract;
    private BigDecimal quantityDelta;
    private BigDecimal priceUsd;
    private PriceSource priceSource;
    private BigDecimal totalValueUsd;
    private BigDecimal gasCostUsd;
    private boolean gasIncludedInBasis;
    private BigDecimal realisedPnlUsd;
    private BigDecimal avcoAtTimeOfSale;
    private FlagCode flagCode;
    private boolean flagResolved;
    private String counterpartyAddress;
    private boolean isInternalTransfer;
    private String protocolName;
    private String clientId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public NetworkId getNetworkId() {
        return networkId;
    }

    public void setNetworkId(NetworkId networkId) {
        this.networkId = networkId;
    }

    public String getWalletAddress() {
        return walletAddress;
    }

    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }

    public Instant getBlockTimestamp() {
        return blockTimestamp;
    }

    public void setBlockTimestamp(Instant blockTimestamp) {
        this.blockTimestamp = blockTimestamp;
    }

    public EconomicEventType getEventType() {
        return eventType;
    }

    public void setEventType(EconomicEventType eventType) {
        this.eventType = eventType;
    }

    public String getAssetSymbol() {
        return assetSymbol;
    }

    public void setAssetSymbol(String assetSymbol) {
        this.assetSymbol = assetSymbol;
    }

    public String getAssetContract() {
        return assetContract;
    }

    public void setAssetContract(String assetContract) {
        this.assetContract = assetContract;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public void setQuantityDelta(BigDecimal quantityDelta) {
        this.quantityDelta = quantityDelta;
    }

    public BigDecimal getPriceUsd() {
        return priceUsd;
    }

    public void setPriceUsd(BigDecimal priceUsd) {
        this.priceUsd = priceUsd;
    }

    public PriceSource getPriceSource() {
        return priceSource;
    }

    public void setPriceSource(PriceSource priceSource) {
        this.priceSource = priceSource;
    }

    public BigDecimal getTotalValueUsd() {
        return totalValueUsd;
    }

    public void setTotalValueUsd(BigDecimal totalValueUsd) {
        this.totalValueUsd = totalValueUsd;
    }

    public BigDecimal getGasCostUsd() {
        return gasCostUsd;
    }

    public void setGasCostUsd(BigDecimal gasCostUsd) {
        this.gasCostUsd = gasCostUsd;
    }

    public boolean isGasIncludedInBasis() {
        return gasIncludedInBasis;
    }

    public void setGasIncludedInBasis(boolean gasIncludedInBasis) {
        this.gasIncludedInBasis = gasIncludedInBasis;
    }

    public BigDecimal getRealisedPnlUsd() {
        return realisedPnlUsd;
    }

    public void setRealisedPnlUsd(BigDecimal realisedPnlUsd) {
        this.realisedPnlUsd = realisedPnlUsd;
    }

    public BigDecimal getAvcoAtTimeOfSale() {
        return avcoAtTimeOfSale;
    }

    public void setAvcoAtTimeOfSale(BigDecimal avcoAtTimeOfSale) {
        this.avcoAtTimeOfSale = avcoAtTimeOfSale;
    }

    public FlagCode getFlagCode() {
        return flagCode;
    }

    public void setFlagCode(FlagCode flagCode) {
        this.flagCode = flagCode;
    }

    public boolean isFlagResolved() {
        return flagResolved;
    }

    public void setFlagResolved(boolean flagResolved) {
        this.flagResolved = flagResolved;
    }

    public String getCounterpartyAddress() {
        return counterpartyAddress;
    }

    public void setCounterpartyAddress(String counterpartyAddress) {
        this.counterpartyAddress = counterpartyAddress;
    }

    public boolean isInternalTransfer() {
        return isInternalTransfer;
    }

    public void setInternalTransfer(boolean internalTransfer) {
        isInternalTransfer = internalTransfer;
    }

    public String getProtocolName() {
        return protocolName;
    }

    public void setProtocolName(String protocolName) {
        this.protocolName = protocolName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EconomicEvent that = (EconomicEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
