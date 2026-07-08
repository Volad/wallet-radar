package com.walletradar.application.pipeline.pipeline.support;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalizes BSON-like map/list structures into canonical BSON Documents.
 */
public final class BsonCoercionSupport {

    private BsonCoercionSupport() {
    }

    public static Document asDocument(Object value) {
        if (value instanceof Document document) {
            return copyDocument(document);
        }
        if (value instanceof Map<?, ?> map) {
            return documentFromMap(map);
        }
        return null;
    }

    public static List<Document> asDocumentList(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>(items.size());
        for (Object item : items) {
            Document document = asDocument(item);
            if (document != null) {
                documents.add(document);
            }
        }
        return documents.isEmpty() ? List.of() : List.copyOf(documents);
    }

    public static Document copyDocument(Document document) {
        if (document == null) {
            return null;
        }
        Document copy = new Document();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            copy.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return copy;
    }

    private static Document documentFromMap(Map<?, ?> map) {
        Document document = new Document();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            document.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return document;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Document document) {
            return copyDocument(document);
        }
        if (value instanceof Map<?, ?> map) {
            return documentFromMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeValue(item));
            }
            return List.copyOf(normalized);
        }
        return value;
    }
}
