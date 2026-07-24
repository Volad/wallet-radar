package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Safe typed accessor over the Helius-parsed BSON payload stored in
 * {@code rawData.heliusParsed} for Solana raw transactions.
 *
 * <p>The Helius Enhanced Transactions API stores the full parsed payload at
 * {@code rawData.source = "HELIUS_ENHANCED"} and {@code rawData.heliusParsed = <Document>}.</p>
 */
public final class SolanaRawTransactionView {

    private final RawTransaction rawTransaction;
    private final Document heliusParsed;

    private SolanaRawTransactionView(RawTransaction rawTransaction, Document heliusParsed) {
        this.rawTransaction = rawTransaction;
        this.heliusParsed = heliusParsed;
    }

    public static SolanaRawTransactionView wrap(RawTransaction rawTransaction) {
        Document rawData = rawTransaction.getRawData();
        Document heliusParsed = rawData == null ? null : rawData.get("heliusParsed", Document.class);
        return new SolanaRawTransactionView(rawTransaction, heliusParsed);
    }

    public static boolean isHeliusPayload(RawTransaction rawTransaction) {
        Document rawData = rawTransaction.getRawData();
        if (rawData == null) {
            return false;
        }
        String source = rawData.getString("source");
        return "HELIUS_ENHANCED".equals(source);
    }

    public String signature() {
        String txHash = rawTransaction.getTxHash();
        if (txHash != null && !txHash.isBlank()) {
            return txHash.trim();
        }
        if (heliusParsed == null) {
            return null;
        }
        String sig = heliusParsed.getString("signature");
        return sig == null || sig.isBlank() ? null : sig.trim();
    }

    public long slot() {
        if (rawTransaction.getSlot() != null) {
            return rawTransaction.getSlot();
        }
        if (heliusParsed == null) {
            return 0L;
        }
        Object slotVal = heliusParsed.get("slot");
        if (slotVal instanceof Number num) {
            return num.longValue();
        }
        return 0L;
    }

    public Instant blockTimestamp() {
        if (heliusParsed == null) {
            return null;
        }
        Object tsVal = heliusParsed.get("timestamp");
        if (tsVal instanceof Number num) {
            long epochSeconds = num.longValue();
            return epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : null;
        }
        return null;
    }

    public String walletAddress() {
        String addr = rawTransaction.getWalletAddress();
        return addr == null || addr.isBlank() ? null : addr.trim();
    }

    public String feePayer() {
        if (heliusParsed == null) {
            return null;
        }
        String fp = heliusParsed.getString("feePayer");
        return fp == null || fp.isBlank() ? null : fp.trim();
    }

    /**
     * Helius high-level type label (e.g. "SWAP", "TRANSFER", "ADD_LIQUIDITY", "UNKNOWN").
     */
    public String heliusType() {
        if (heliusParsed == null) {
            return "UNKNOWN";
        }
        String t = heliusParsed.getString("type");
        return t == null || t.isBlank() ? "UNKNOWN" : t.trim().toUpperCase();
    }

    /**
     * Helius source label (e.g. "JUPITER", "METEORA", "RAYDIUM", "UNKNOWN").
     */
    public String heliusSource() {
        if (heliusParsed == null) {
            return "UNKNOWN";
        }
        String s = heliusParsed.getString("source");
        return s == null || s.isBlank() ? "UNKNOWN" : s.trim().toUpperCase();
    }

    /**
     * Transaction fee in lamports paid by the fee payer (the raw meta.fee).
     */
    public long feeInLamports() {
        if (heliusParsed == null) {
            return 0L;
        }
        Object feeVal = heliusParsed.get("fee");
        if (feeVal instanceof Number num) {
            return num.longValue();
        }
        return 0L;
    }

    /**
     * Transaction fee in lamports attributable to the TRACKED wallet: the full meta.fee when this
     * wallet is the fee payer, otherwise 0. Helius reports meta.fee for every transaction the wallet
     * appears in — including bot- or protocol-managed transactions (e.g. Hawksight Meteora
     * rebalances) whose fee payer is a third party. Only the fee payer actually spends the fee, and
     * the wallet's {@code nativeBalanceChange} embeds the fee only in that case, so all fee-scoped
     * accounting (the FEE leg and the native-delta add-back) must use this rather than
     * {@link #feeInLamports()}.
     */
    public long walletFeeInLamports() {
        String payer = feePayer();
        String wallet = walletAddress();
        if (payer == null || wallet == null || !payer.equals(wallet)) {
            return 0L;
        }
        return feeInLamports();
    }

    @SuppressWarnings("unchecked")
    public List<Document> tokenTransfers() {
        return docList(heliusParsed, "tokenTransfers");
    }

    @SuppressWarnings("unchecked")
    public List<Document> nativeTransfers() {
        return docList(heliusParsed, "nativeTransfers");
    }

    @SuppressWarnings("unchecked")
    public List<Document> accountData() {
        return docList(heliusParsed, "accountData");
    }

    /**
     * All instructions (top-level and nested inner instructions) in traversal order. Each returned
     * {@link Document} retains its {@code programId} and {@code accounts} list, so callers can locate
     * program-specific instructions (e.g. Meteora DLMM add/remove-liquidity) and read their account
     * layout for deterministic position-identity resolution.
     */
    public List<Document> flattenedInstructions() {
        List<Document> result = new ArrayList<>();
        collectInstructions(docList(heliusParsed, "instructions"), result);
        return Collections.unmodifiableList(result);
    }

    private static void collectInstructions(List<Document> instructions, List<Document> sink) {
        for (Document instruction : instructions) {
            if (instruction == null) {
                continue;
            }
            sink.add(instruction);
            collectInstructions(docList(instruction, "innerInstructions"), sink);
        }
    }

    /**
     * The ordered {@code accounts} list of a single instruction {@link Document}, or an empty list
     * when absent. Non-string entries are skipped defensively.
     */
    public static List<String> instructionAccounts(Document instruction) {
        if (instruction == null) {
            return List.of();
        }
        Object val = instruction.get("accounts");
        if (!(val instanceof List<?> list)) {
            return List.of();
        }
        List<String> accounts = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                accounts.add(s.trim());
            }
        }
        return Collections.unmodifiableList(accounts);
    }

    /**
     * All unique program IDs from the top-level {@code instructions} array.
     */
    public List<String> programIds() {
        List<Document> instructions = docList(heliusParsed, "instructions");
        Set<String> ids = new LinkedHashSet<>();
        for (Document instruction : instructions) {
            String pid = instruction.getString("programId");
            if (pid != null && !pid.isBlank()) {
                ids.add(pid.trim());
            }
            // Also collect inner instruction program IDs
            List<Document> inner = docList(instruction, "innerInstructions");
            for (Document innerIx : inner) {
                String innerPid = innerIx.getString("programId");
                if (innerPid != null && !innerPid.isBlank()) {
                    ids.add(innerPid.trim());
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }

    /**
     * The {@code events.swap} object from Helius, or {@code null} if not present.
     */
    public Document swapEvent() {
        if (heliusParsed == null) {
            return null;
        }
        Document events = heliusParsed.get("events", Document.class);
        if (events == null) {
            return null;
        }
        return events.get("swap", Document.class);
    }

    /**
     * Returns {@code true} if the given program ID appears in the instructions list.
     */
    public boolean hasProgramId(String programId) {
        if (programId == null) {
            return false;
        }
        return programIds().contains(programId);
    }

    public boolean isValid() {
        return signature() != null && walletAddress() != null && heliusParsed != null;
    }

    // --- private helpers ---

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
