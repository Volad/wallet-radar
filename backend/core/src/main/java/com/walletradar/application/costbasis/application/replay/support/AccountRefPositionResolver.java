package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared, coverage-and-existence-gated resolver that honours an explicit {@code flow.accountRef}
 * naming a Bybit sub-account ({@code …:FUND} / {@code …:UTA} / {@code …:EARN}) when routing a
 * drain / disposal target during replay (ADR-042).
 *
 * <p><b>Why.</b> {@code BybitCanonicalTransactionBuilder} collapses a sub-account
 * {@code walletAddress} to the UID umbrella (e.g. {@code BYBIT:33625378}) while
 * {@code finalizeBybitFlows} still stamps the originating sub-account onto {@code flow.accountRef}
 * (e.g. {@code BYBIT:33625378:FUND}). The replay drain / disposal resolvers historically keyed on
 * {@code walletAddress} plus max-quantity / coverage heuristics and discarded {@code accountRef}, so
 * {@code :FUND}-funded principal was drained from the umbrella and the sub-account kept a phantom
 * residual.
 *
 * <p><b>Rule.</b> When a flow carries an explicit {@code accountRef} naming a sub-position that
 * <em>(a)</em> already EXISTS as a position and <em>(b)</em> holds enough inventory to COVER the
 * leg, redirect the drain / disposal <em>target</em> to that named sub-position. The
 * umbrella-collapsed position KEY is unchanged — only the target is redirected. When the named
 * sub-position does not exist or cannot cover, the caller's default (umbrella) is returned unchanged.
 *
 * <p><b>Determinism / safety.</b> The decision is a pure function of {@code accountRef} + the named
 * sub-position's inventory — never of counterparty, transaction type, or correlationId. This keeps
 * self-funded corridor-outs (RC-9) structurally immune: they already drain their {@code :FUND} pool,
 * so the redirect returns the same position they would have used. The store is only peeked (never
 * mutated) so no phantom sub-position is ever materialised.
 */
public final class AccountRefPositionResolver {

    private static final MathContext MC = MathContext.DECIMAL128;

    private static final List<String> SUB_ACCOUNT_SUFFIXES = List.of(":FUND", ":UTA", ":EARN");

    private AccountRefPositionResolver() {
    }

    /**
     * Returns the sub-account {@link AssetKey} named by {@code accountRef} when a position with that
     * key already EXISTS in {@code positions} and holds enough inventory to COVER
     * {@code coverQuantity}; otherwise returns {@code defaultKey} unchanged.
     *
     * @param defaultKey    the caller's collapsed (umbrella) position key; supplies the network /
     *                      contract / symbol / identity used to build the sub-account candidate
     * @param accountRef    the flow's {@code accountRef} (full sub-account wallet, e.g.
     *                      {@code BYBIT:33625378:FUND}); may be {@code null}
     * @param positions     replay position store map, peeked (never mutated)
     * @param coverQuantity absolute leg quantity the sub-position must cover; when {@code null} or
     *                      non-positive any non-empty sub-position covers
     */
    public static AssetKey resolveInventoryBearingAccountRefKey(
            AssetKey defaultKey,
            String accountRef,
            Map<AssetKey, PositionState> positions,
            BigDecimal coverQuantity
    ) {
        if (defaultKey == null || positions == null || positions.isEmpty()) {
            return defaultKey;
        }
        String subWallet = subAccountWallet(accountRef);
        if (subWallet == null) {
            return defaultKey;
        }
        String defaultWallet = defaultKey.walletAddress();
        if (defaultWallet != null && subWallet.equalsIgnoreCase(defaultWallet)) {
            return defaultKey;
        }
        AssetKey subKey = new AssetKey(
                subWallet,
                defaultKey.networkId(),
                defaultKey.assetContract(),
                defaultKey.assetSymbol(),
                defaultKey.assetIdentity()
        );
        if (subKey.equals(defaultKey)) {
            return defaultKey;
        }
        PositionState subPosition = positions.get(subKey);
        if (positionCoversQuantity(subPosition, coverQuantity)) {
            return subKey;
        }
        return defaultKey;
    }

    /**
     * Returns {@code accountRef} trimmed when it names a Bybit sub-account
     * ({@code …:FUND}/{@code …:UTA}/{@code …:EARN}); {@code null} otherwise. Only these suffixes
     * bound the redirect so no non-Bybit or umbrella-level accountRef ever triggers it.
     */
    private static String subAccountWallet(String accountRef) {
        if (accountRef == null) {
            return null;
        }
        String trimmed = accountRef.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String suffix : SUB_ACCOUNT_SUFFIXES) {
            if (upper.endsWith(suffix)) {
                return trimmed;
            }
        }
        return null;
    }

    private static boolean positionCoversQuantity(PositionState position, BigDecimal coverQuantity) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        if (qty == null || qty.signum() <= 0) {
            return false;
        }
        if (coverQuantity == null || coverQuantity.signum() <= 0) {
            return true;
        }
        return qty.compareTo(coverQuantity.multiply(ReplayToleranceSupport.carrySourceCoverageRatio(), MC)) >= 0;
    }
}
