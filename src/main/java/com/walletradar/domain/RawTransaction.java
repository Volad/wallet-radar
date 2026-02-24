package com.walletradar.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Immutable on-chain transaction data as fetched from RPC. Never mutated after ingestion (INV-02).
 * Schema varies per network; rawData holds the native payload (BSON Document).
 */
@Document(collection = "raw_transactions")
@CompoundIndex(name = "txHash_networkId", def = "{'txHash': 1, 'networkId': 1}", unique = true)
public class RawTransaction {

    @Id
    private String id;
    private String txHash;
    private String networkId;
    private org.bson.Document rawData;

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

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public org.bson.Document getRawData() {
        return rawData;
    }

    public void setRawData(org.bson.Document rawData) {
        this.rawData = rawData;
    }
}
