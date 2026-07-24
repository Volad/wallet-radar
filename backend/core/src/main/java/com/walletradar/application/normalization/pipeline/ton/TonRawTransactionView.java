package com.walletradar.application.normalization.pipeline.ton;

import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Safe typed accessor over the TON Center v3 BSON payload stored in
 * {@code rawData.transaction} and {@code rawData.jettonTransfers}.
 *
 * <p>Layout written by {@code TonNetworkAdapter}:</p>
 * <ul>
 *   <li>{@code rawData.transaction} — full TON Center v3 transaction object</li>
 *   <li>{@code rawData.jettonTransfers} — list of jetton-transfer objects for this tx</li>
 *   <li>{@code rawData.source} = {@code "TONCENTER_V3"}</li>
 * </ul>
 */
public final class TonRawTransactionView {

    private static final String TONCENTER_V3_SOURCE = "TONCENTER_V3";

    private final RawTransaction rawTransaction;
    private final Document transaction;

    private TonRawTransactionView(RawTransaction rawTransaction, Document transaction) {
        this.rawTransaction = rawTransaction;
        this.transaction = transaction;
    }

    public static TonRawTransactionView wrap(RawTransaction rawTransaction) {
        Document rawData = rawTransaction.getRawData();
        Document tx = rawData == null ? null : rawData.get("transaction", Document.class);
        return new TonRawTransactionView(rawTransaction, tx);
    }

    public static boolean isTonCenterPayload(RawTransaction rawTransaction) {
        Document rawData = rawTransaction.getRawData();
        if (rawData == null) {
            return false;
        }
        return TONCENTER_V3_SOURCE.equals(rawData.getString("source"));
    }

    /** TON transaction hash. */
    public String txHash() {
        String hash = rawTransaction.getTxHash();
        if (hash != null && !hash.isBlank()) {
            return hash.trim();
        }
        if (transaction == null) {
            return null;
        }
        String h = transaction.getString("hash");
        return (h == null || h.isBlank()) ? null : h.trim();
    }

    /** Owning wallet address (as stored during ingestion). */
    public String walletAddress() {
        String addr = rawTransaction.getWalletAddress();
        return (addr == null || addr.isBlank()) ? null : addr.trim();
    }

    /**
     * Transaction timestamp from the {@code now} field (Unix epoch seconds).
     * Returns {@code null} when the field is absent or zero.
     */
    public Instant blockTimestamp() {
        if (transaction == null) {
            return null;
        }
        Object nowVal = transaction.get("now");
        long epochSeconds = toLong(nowVal);
        return epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : null;
    }

    /** Logical transaction time (lt) as a String (may be very large). */
    public String lt() {
        if (transaction == null) {
            return null;
        }
        Object val = transaction.get("lt");
        return val == null ? null : val.toString();
    }

    /** Incoming message source address (sender), or {@code null} if not present. */
    public String inMsgSource() {
        Document inMsg = inMsg();
        if (inMsg == null) {
            return null;
        }
        return stringField(inMsg, "source");
    }

    /**
     * Incoming message decoded opcode (e.g. {@code text_comment}, {@code excess},
     * {@code jetton_notify}), or {@code null} when absent. Used to distinguish a plain native-TON
     * value transfer from jetton/DeFi machinery messages that merely forward gas.
     */
    public String inMsgOpcode() {
        Document inMsg = inMsg();
        if (inMsg == null) {
            return null;
        }
        return stringField(inMsg, "decoded_opcode");
    }

    /** Incoming message destination address (this wallet), or {@code null}. */
    public String inMsgDestination() {
        Document inMsg = inMsg();
        if (inMsg == null) {
            return null;
        }
        return stringField(inMsg, "destination");
    }

    /**
     * Incoming message value in nanoTON.
     * Returns {@code 0} when the field is absent or non-numeric.
     */
    public long inMsgValueNano() {
        Document inMsg = inMsg();
        if (inMsg == null) {
            return 0L;
        }
        Object val = inMsg.get("value");
        return toLong(val);
    }

    /** Outgoing messages list; empty when not present. */
    public List<Document> outMsgs() {
        if (transaction == null) {
            return List.of();
        }
        return docList(transaction, "out_msgs");
    }

    /** Jetton transfers associated with this transaction; empty when not present. */
    public List<Document> jettonTransfers() {
        Document rawData = rawTransaction.getRawData();
        if (rawData == null) {
            return List.of();
        }
        return docList(rawData, "jettonTransfers");
    }

    /**
     * Returns {@code true} when the transaction failed (compute phase exit code != 0).
     * Also returns {@code true} when the {@code action_result_code} indicates failure.
     */
    public boolean isFailed() {
        if (transaction == null) {
            return false;
        }
        Document description = transaction.get("description", Document.class);
        if (description == null) {
            return false;
        }
        Document computePh = description.get("compute_ph", Document.class);
        if (computePh != null) {
            Object exitCode = computePh.get("exit_code");
            if (exitCode != null) {
                long code = toLong(exitCode);
                if (code != 0) {
                    return true;
                }
            }
        }
        Document action = description.get("action", Document.class);
        if (action != null) {
            Object resultCode = action.get("result_code");
            if (resultCode != null) {
                long code = toLong(resultCode);
                return code != 0;
            }
        }
        return false;
    }

    /**
     * Total transaction fees in nanoTON ({@code total_fees} field).
     */
    public long totalFeesNano() {
        if (transaction == null) {
            return 0L;
        }
        Object val = transaction.get("total_fees");
        return toLong(val);
    }

    // ---- helpers ----

    private Document inMsg() {
        if (transaction == null) {
            return null;
        }
        return transaction.get("in_msg", Document.class);
    }

    private static String stringField(Document doc, String key) {
        if (doc == null) {
            return null;
        }
        Object val = doc.get(key);
        if (val == null) {
            return null;
        }
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> docList(Document parent, String key) {
        if (parent == null) {
            return List.of();
        }
        Object val = parent.get(key);
        if (val instanceof List<?> list) {
            List<Document> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Document doc) {
                    result.add(doc);
                }
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }
}
