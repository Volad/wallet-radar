package com.walletradar.costbasis.application.replay.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;

import java.util.Locale;

/**
 * Deterministic synthetic loan correlation id for Bybit UTA pledge-loan lifecycles
 * (ADR-012 §D2–D3, R-4 — Bybit extension of the on-chain F-3/F-4 work).
 *
 * <p>Bybit pledge {@code BORROW} rows are sourced from {@code TRANSACTION_LOG} with an
 * exchange order UUID, while the matching {@code REPAY} rows arrive on a different stream with a
 * server-issued {@code uta_pledge-loan-server_<numericId>} id. The two legs therefore never share a
 * correlation id, so {@link com.walletradar.costbasis.application.BorrowLiabilityTracker} cannot
 * match a repay to its opening borrow and the repay falls through to a market-priced disposal that
 * fabricates realised PnL.</p>
 *
 * <p>Bybit's pledge loan is a single revolving credit line per UID and per borrowed asset (you
 * borrow MNT and repay MNT against one pledge account), so the deterministic key is
 * {@code bybit-pledge:<uid>:<canonicalAsset>}. Both legs of the same asset's revolving line resolve
 * to the same key, letting the existing tracker net borrows against repays and book matched repays
 * at ~$0.</p>
 *
 * <p>The {@code bybit-pledge:} prefix is disjoint from the on-chain {@code evm:} namespace
 * ({@link com.walletradar.ingestion.pipeline.classification.support.AaveDebtLoanCorrelationSupport})
 * and from raw numeric Bybit {@code orderId}s, so the three liability namespaces never collide
 * inside {@code borrow_liabilities}.</p>
 */
public final class BybitPledgeLoanCorrelationSupport {

    /** Namespace prefix — distinct from on-chain {@code evm:} and raw Bybit numeric orderIds. */
    public static final String SYNTHETIC_LOAN_PREFIX = "bybit-pledge:";

    private BybitPledgeLoanCorrelationSupport() {
    }

    /**
     * Returns the deterministic per-(uid, asset) revolving pledge-loan correlation id for a Bybit
     * {@code BORROW}/{@code REPAY} flow, or {@code null} when the transaction is not a Bybit
     * pledge-loan lifecycle (wrong source, wrong type, or no resolvable uid/asset).
     */
    public static String syntheticLoanCorrelationId(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT
                || !isLoanLifecycleType(transaction.getType())) {
            return null;
        }
        String uid = extractUid(flow.getAccountRef());
        if (uid == null) {
            uid = extractUid(transaction.getWalletAddress());
        }
        String asset = canonicalAsset(flow.getAssetSymbol());
        if (uid == null || asset == null) {
            return null;
        }
        return SYNTHETIC_LOAN_PREFIX + uid + ":" + asset;
    }

    private static boolean isLoanLifecycleType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.BORROW || type == NormalizedTransactionType.REPAY;
    }

    private static String canonicalAsset(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return null;
        }
        return CanonicalAssetCatalog.canonicalMarketSymbol(assetSymbol);
    }

    /**
     * Extracts the Bybit UID from a {@code BYBIT:<uid>[:SUB]} wallet reference. Returns {@code null}
     * for non-Bybit references so on-chain {@code evm:} account refs never produce a pledge key.
     */
    private static String extractUid(String reference) {
        if (reference == null) {
            return null;
        }
        String trimmed = reference.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = trimmed.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        String uid = colon > 0 ? without.substring(0, colon) : without;
        return uid.isBlank() ? null : uid;
    }
}
