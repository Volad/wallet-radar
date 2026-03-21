package com.walletradar.ingestion.adapter.evm.explorer.model;

import org.bson.Document;

/**
 * Explorer internal transfer payload wrapper.
 */
public record ExplorerInternalTransfer(Document data) implements ExplorerPayload {

    public ExplorerInternalTransfer {
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
