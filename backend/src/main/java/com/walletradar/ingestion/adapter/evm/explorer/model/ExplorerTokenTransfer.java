package com.walletradar.ingestion.adapter.evm.explorer.model;

import org.bson.Document;

/**
 * Explorer token transfer in normalized common form.
 */
public record ExplorerTokenTransfer(Document data) implements ExplorerPayload {

    public ExplorerTokenTransfer {
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

