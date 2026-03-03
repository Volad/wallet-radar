package com.walletradar.ingestion.adapter.evm.explorer.model;

import org.bson.Document;

/**
 * Common normalized explorer payload wrapper used by explorer adapters.
 */
public interface ExplorerPayload {

    Document data();

    default Document asDocument() {
        Document source = data();
        return source == null ? new Document() : new Document(source);
    }

    default String getString(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Document source = data();
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        String asText = value.toString();
        return asText.isBlank() ? null : asText;
    }
}

