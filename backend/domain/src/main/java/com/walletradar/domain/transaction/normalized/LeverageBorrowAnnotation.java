package com.walletradar.domain.transaction.normalized;

import org.bson.Document;

/**
 * Reads/writes the inferred leverage-borrow annotation on a {@link NormalizedTransaction}'s
 * {@code metadata.leverage} sub-document (ADR-028).
 *
 * <p>The annotation is written at classification (where borrow evidence and the correlation key are
 * deterministically known) and read at pricing (to force market spot on the collateral leg) and at
 * replay (to size the synthetic borrow against market-priced legs). It lives in the domain layer so
 * the ingestion, pricing, and cost-basis modules can share it without crossing module boundaries.</p>
 */
public final class LeverageBorrowAnnotation {

    public static final String METADATA_KEY = "leverage";

    private static final String CANDIDATE = "candidate";
    private static final String BORROW_EVIDENCE = "borrowEvidence";
    private static final String EVIDENCE_KIND = "evidenceKind";
    private static final String LOAN_CORRELATION_ID = "loanCorrelationId";
    private static final String COLLATERAL_CONTRACT = "collateralContract";
    private static final String COLLATERAL_SYMBOL = "collateralSymbol";

    private LeverageBorrowAnnotation() {
    }

    /** Persists the annotation into {@code transaction.metadata.leverage}, creating metadata if absent. */
    public static void write(
            NormalizedTransaction transaction,
            boolean borrowEvidence,
            String evidenceKind,
            String loanCorrelationId,
            String collateralContract,
            String collateralSymbol
    ) {
        if (transaction == null) {
            return;
        }
        Document metadata = transaction.getMetadata() == null
                ? new Document()
                : new Document(transaction.getMetadata());
        Document leverage = new Document();
        leverage.put(CANDIDATE, Boolean.TRUE);
        leverage.put(BORROW_EVIDENCE, borrowEvidence);
        leverage.put(EVIDENCE_KIND, evidenceKind);
        leverage.put(LOAN_CORRELATION_ID, loanCorrelationId);
        leverage.put(COLLATERAL_CONTRACT, collateralContract);
        leverage.put(COLLATERAL_SYMBOL, collateralSymbol);
        metadata.put(METADATA_KEY, leverage);
        transaction.setMetadata(metadata);
    }

    /** True when a leverage annotation with confirmed borrow evidence and a usable correlation key exists. */
    public static boolean isLeveragedBuy(NormalizedTransaction transaction) {
        Document leverage = leverageDocument(transaction);
        if (leverage == null) {
            return false;
        }
        return Boolean.TRUE.equals(leverage.get(CANDIDATE))
                && Boolean.TRUE.equals(leverage.get(BORROW_EVIDENCE))
                && loanCorrelationId(transaction) != null;
    }

    /** True when the transaction carries any leverage candidate annotation. */
    public static boolean isCandidate(NormalizedTransaction transaction) {
        Document leverage = leverageDocument(transaction);
        return leverage != null && Boolean.TRUE.equals(leverage.get(CANDIDATE));
    }

    public static String loanCorrelationId(NormalizedTransaction transaction) {
        return stringValue(transaction, LOAN_CORRELATION_ID);
    }

    public static String collateralContract(NormalizedTransaction transaction) {
        return stringValue(transaction, COLLATERAL_CONTRACT);
    }

    public static String collateralSymbol(NormalizedTransaction transaction) {
        return stringValue(transaction, COLLATERAL_SYMBOL);
    }

    private static String stringValue(NormalizedTransaction transaction, String key) {
        Document leverage = leverageDocument(transaction);
        if (leverage == null) {
            return null;
        }
        Object value = leverage.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Document leverageDocument(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getMetadata() == null) {
            return null;
        }
        Object value = transaction.getMetadata().get(METADATA_KEY);
        return value instanceof Document document ? document : null;
    }
}
