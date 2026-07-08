package com.walletradar.platform.networks.evm.explorer.model;

import org.bson.Document;

/**
 * Explorer token transfer payload wrapper.
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
