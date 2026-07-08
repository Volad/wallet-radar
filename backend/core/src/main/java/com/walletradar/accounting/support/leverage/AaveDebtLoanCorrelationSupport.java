package com.walletradar.accounting.support.leverage;

import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic synthetic loan correlation id for on-chain Aave-style debt lifecycles (ADR-012
 * §D8, F-3/F-4).
 *
 * <p>On-chain {@code BORROW} / {@code REPAY} transactions have no exchange-issued {@code orderId},
 * so the {@link com.walletradar.costbasis.application.BorrowLiabilityTracker} cannot match a repay
 * to its opening borrow. This support derives a stable {@code evm:&lt;network&gt;:&lt;debtContract&gt;:&lt;wallet&gt;}
 * key from the {@code variableDebt}/{@code stableDebt} leg that is present in both the borrow
 * (mint) and the repay (burn) of the same revolving Aave debt position.</p>
 *
 * <p>The {@code evm:} prefix guarantees the key never collides with Bybit numeric {@code orderId}s,
 * keeping the two liability namespaces disjoint inside {@code borrow_liabilities}.</p>
 */
public final class AaveDebtLoanCorrelationSupport {

    /** Namespace prefix — distinct from all Bybit numeric orderId-derived composite ids. */
    public static final String SYNTHETIC_LOAN_PREFIX = "evm:";

    /**
     * Namespace prefix for an <em>inferred</em> on-chain leverage borrow (ADR-028). A leveraged
     * buy embeds the borrow inside a SWAP and has no explicit {@code variableDebt}/{@code stableDebt}
     * marker leg, so its synthetic gap liability is keyed on the acquired collateral contract. The
     * distinct {@code evm-lev:} prefix keeps it disjoint from both Bybit numeric orderIds and the
     * {@code evm:} Aave debt-marker namespace, so it can never collide with an explicit BORROW/REPAY
     * lifecycle that F-4 already owns.
     */
    public static final String LEVERAGE_LOAN_PREFIX = "evm-lev:";

    private AaveDebtLoanCorrelationSupport() {
    }

    /**
     * Deterministic synthetic correlation id for an inferred leverage borrow (ADR-028).
     *
     * <p>When a leveraged buy exposes a debt contract (Aave-style {@code Borrow(...)} log), the key
     * reuses the {@code evm:&lt;network&gt;:&lt;debtContract&gt;:&lt;wallet&gt;} namespace so any later
     * on-chain repay can match via the existing F-4 machinery. Otherwise the synthetic-gap key is
     * {@code evm-lev:&lt;network&gt;:&lt;collateralContract&gt;:&lt;wallet&gt;}, derived from the acquired
     * collateral. Returns {@code null} when neither a debt contract nor a collateral contract is
     * available — the caller must then route to clarification rather than fabricate a liability.</p>
     */
    public static String leverageLoanCorrelationId(
            NetworkId networkId,
            String debtContract,
            String collateralContract,
            String walletAddress
    ) {
        String debtKey = normalizeContract(debtContract);
        if (debtKey != null) {
            return SYNTHETIC_LOAN_PREFIX
                    + networkSegment(networkId) + ":"
                    + debtKey + ":"
                    + walletSegment(walletAddress);
        }
        String collateralKey = normalizeContract(collateralContract);
        if (collateralKey != null) {
            return LEVERAGE_LOAN_PREFIX
                    + networkSegment(networkId) + ":"
                    + collateralKey + ":"
                    + walletSegment(walletAddress);
        }
        return null;
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isLoanLifecycleType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.BORROW || type == NormalizedTransactionType.REPAY;
    }

    /**
     * Returns the deterministic synthetic loan correlation id for a {@code BORROW}/{@code REPAY}
     * transaction that carries an Aave-style debt-marker leg, or {@code null} when the transaction
     * is not a debt-token loan lifecycle (e.g. a Compound/Fluid model with no debt receipt, or a
     * transaction that already has an authoritative correlation id from another source).
     */
    public static String syntheticLoanCorrelationId(NormalizedTransaction transaction) {
        if (transaction == null || !isLoanLifecycleType(transaction.getType())) {
            return null;
        }
        NormalizedTransaction.Flow debtLeg = debtMarkerLeg(transaction.getFlows());
        if (debtLeg == null) {
            return null;
        }
        String debtKey = debtIdentityKey(debtLeg);
        if (debtKey == null) {
            return null;
        }
        return SYNTHETIC_LOAN_PREFIX
                + networkSegment(transaction.getNetworkId()) + ":"
                + debtKey + ":"
                + walletSegment(transaction.getWalletAddress());
    }

    private static NormalizedTransaction.Flow debtMarkerLeg(List<NormalizedTransaction.Flow> flows) {
        if (flows == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow != null && AccountingAssetIdentitySupport.isDebtIdentity(flow.getAssetSymbol())) {
                return flow;
            }
        }
        return null;
    }

    private static String debtIdentityKey(NormalizedTransaction.Flow debtLeg) {
        String contract = debtLeg.getAssetContract();
        if (contract != null && !contract.isBlank()) {
            return contract.trim().toLowerCase(Locale.ROOT);
        }
        String symbol = debtLeg.getAssetSymbol();
        if (symbol != null && !symbol.isBlank()) {
            return "symbol:" + symbol.trim().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static String networkSegment(NetworkId networkId) {
        return networkId == null ? "UNKNOWN" : networkId.name();
    }

    private static String walletSegment(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.trim().toLowerCase(Locale.ROOT);
    }
}
