package com.walletradar.ingestion.adapter.evm.explorer.model;

import org.bson.Document;

/**
 * Explorer receipt in normalized common form.
 */
public record ExplorerReceipt(Document data) implements ExplorerPayload {

    public ExplorerReceipt {
        data = data == null ? new Document() : new Document(data);
    }

    public String blockNumber() {
        return getString("blockNumber");
    }

    public boolean hasLogsField() {
        Document source = data();
        return source != null && source.containsKey("logs") && source.get("logs") != null;
    }
}

