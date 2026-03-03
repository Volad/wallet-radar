package com.walletradar.ingestion.adapter.evm.explorer.model;

import org.bson.Document;

/**
 * Explorer transaction-details payload in normalized common form.
 * For Blockscout this maps to /api/v2/transactions/{hash}; for Etherscan-family it falls back to proxy tx-by-hash.
 */
public record ExplorerTransactionDetails(Document data) implements ExplorerPayload {

    public ExplorerTransactionDetails {
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
}

