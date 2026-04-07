package com.walletradar.ingestion.adapter.evm.explorer.model;

import org.bson.Document;

/**
 * Explorer transaction payload wrapper.
 */
public record ExplorerTransaction(Document data) implements ExplorerPayload {

    public ExplorerTransaction {
        data = data == null ? new Document() : new Document(data);
    }

    public String hash() {
        String hash = getString("hash");
        if (hash != null) {
            return hash;
        }
        return getString("txhash");
    }

    public String blockNumber() {
        return getString("blockNumber");
    }

    public String timeStamp() {
        return getString("timeStamp");
    }
}
