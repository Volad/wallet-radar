package com.walletradar.application.normalization.pipeline.onchain.support;

import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves canonical raw ordering metadata from top-level raw fields, explorer.tx, or unanimous nested explorer evidence.
 */
public final class RawOrderingMetadataResolver {

    private RawOrderingMetadataResolver() {
    }

    public static ResolvedRawOrderingMetadata resolve(RawTransaction rawTransaction) {
        return rawTransaction == null ? new ResolvedRawOrderingMetadata(null, null) : resolve(rawTransaction.getRawData());
    }

    public static ResolvedRawOrderingMetadata resolve(Document rawData) {
        if (rawData == null) {
            return new ResolvedRawOrderingMetadata(null, null);
        }

        Document explorerTx = explorerTx(rawData);
        Long epochSeconds = firstNonNull(
                parseFlexibleLong(rawData.get("timeStamp")),
                parseFlexibleLong(explorerTx == null ? null : explorerTx.get("timeStamp")),
                unanimousLong(explorerEvidenceDocuments(rawData), "timeStamp")
        );
        Integer transactionIndex = firstNonNull(
                parseFlexibleInteger(rawData.get("transactionIndex")),
                parseFlexibleInteger(explorerTx == null ? null : explorerTx.get("transactionIndex")),
                unanimousInteger(explorerEvidenceDocuments(rawData), "transactionIndex")
        );
        return new ResolvedRawOrderingMetadata(epochSeconds, transactionIndex);
    }

    public static boolean canonicalizeTopLevel(RawTransaction rawTransaction) {
        return canonicalizeTopLevel(rawTransaction, resolve(rawTransaction));
    }

    public static boolean canonicalizeTopLevel(
            RawTransaction rawTransaction,
            ResolvedRawOrderingMetadata resolved
    ) {
        if (rawTransaction == null) {
            return false;
        }
        Document rawData = rawTransaction.getRawData();
        if (rawData == null || resolved == null) {
            return false;
        }
        boolean changed = false;
        if (resolved.epochSeconds() != null) {
            String canonicalTimeStamp = Long.toString(resolved.epochSeconds());
            Object current = rawData.get("timeStamp");
            if (!canonicalTimeStamp.equals(stringify(current))) {
                rawData.put("timeStamp", canonicalTimeStamp);
                changed = true;
            }
        }
        if (resolved.transactionIndex() != null) {
            String canonicalTransactionIndex = Integer.toString(resolved.transactionIndex());
            Object current = rawData.get("transactionIndex");
            if (!canonicalTransactionIndex.equals(stringify(current))) {
                rawData.put("transactionIndex", canonicalTransactionIndex);
                changed = true;
            }
        }
        return changed;
    }

    public static Integer parseFlexibleInteger(Object value) {
        BigInteger parsed = parseFlexibleBigInteger(value);
        return parsed == null ? null : parsed.intValue();
    }

    public static Long parseFlexibleLong(Object value) {
        BigInteger parsed = parseFlexibleBigInteger(value);
        return parsed == null ? null : parsed.longValue();
    }

    private static BigInteger parseFlexibleBigInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigInteger.valueOf(number.longValue());
        }
        String text = stringify(value);
        if (text == null) {
            return null;
        }
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return new BigInteger(text.substring(2), 16);
            }
            return new BigInteger(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Document explorerTx(Document rawData) {
        Object explorer = rawData.get("explorer");
        if (!(explorer instanceof Document explorerDocument)) {
            return null;
        }
        Object tx = explorerDocument.get("tx");
        return tx instanceof Document txDocument ? txDocument : null;
    }

    private static List<Document> explorerEvidenceDocuments(Document rawData) {
        Object explorer = rawData.get("explorer");
        if (!(explorer instanceof Document explorerDocument)) {
            return List.of();
        }

        Set<Document> documents = new LinkedHashSet<>();
        collectDocuments(documents, explorerDocument.get("tokenTransfers"));
        collectDocuments(documents, explorerDocument.get("internalTransfers"));
        return List.copyOf(documents);
    }

    private static void collectDocuments(Set<Document> sink, Object value) {
        if (!(value instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            if (item instanceof Document document) {
                sink.add(document);
            }
        }
    }

    private static Long unanimousLong(List<Document> candidates, String fieldName) {
        Set<Long> values = new LinkedHashSet<>();
        for (Document candidate : candidates) {
            Long parsed = parseFlexibleLong(candidate.get(fieldName));
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static Integer unanimousInteger(List<Document> candidates, String fieldName) {
        Set<Integer> values = new LinkedHashSet<>();
        for (Document candidate : candidates) {
            Integer parsed = parseFlexibleInteger(candidate.get(fieldName));
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
