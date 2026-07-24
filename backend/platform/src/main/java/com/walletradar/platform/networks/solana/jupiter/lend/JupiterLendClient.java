package com.walletradar.platform.networks.solana.jupiter.lend;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Free/keyed Jupiter Lend (Solana) Borrow API client — reads borrow vault config and per-wallet
 * borrow positions. Transport-only (platform layer); all accounting/valuation lives in
 * {@code core.application.lending}.
 *
 * <p>Best-effort by contract: implementations must never throw. Any venue error (rate limit,
 * timeout, 4xx/5xx, malformed body) resolves to an empty list so callers keep the last snapshot
 * marked stale rather than fabricating a value.</p>
 *
 * <p>Endpoints (see developers.jup.ag Borrow API):</p>
 * <ul>
 *   <li>{@code GET /lend/v1/borrow/vaults} → vault config (supply/borrow token, risk params, rates)</li>
 *   <li>{@code GET /lend/v1/borrow/positions?users={wallet}} → per-wallet positions (supply/borrow)</li>
 * </ul>
 */
public interface JupiterLendClient {

    /** @return every borrow vault's config; empty on any failure or when disabled. */
    List<BorrowVault> fetchBorrowVaults();

    /**
     * @param walletAddress base58 wallet (case-sensitive)
     * @return the wallet's borrow positions; empty on any failure, when disabled, or none open.
     */
    List<BorrowPosition> fetchBorrowPositions(String walletAddress);

    /**
     * A borrow vault (market). Risk params are expressed as fractions (e.g. {@code 0.85}), already
     * divided from the protocol's per-mille integer encoding ({@code 850 → 0.85}).
     *
     * @param vaultId               protocol vault id ({@code id} field)
     * @param supplyToken           collateral token identity
     * @param borrowToken           debt token identity
     * @param collateralFactor      max LTV fraction (e.g. {@code 0.80})
     * @param liquidationThreshold  liquidation threshold fraction (e.g. {@code 0.85})
     * @param supplyRatePct         supply APR/APY percent, or null when absent
     * @param borrowRatePct         borrow APR/APY percent, or null when absent
     */
    record BorrowVault(
            int vaultId,
            VaultToken supplyToken,
            VaultToken borrowToken,
            BigDecimal collateralFactor,
            BigDecimal liquidationThreshold,
            BigDecimal supplyRatePct,
            BigDecimal borrowRatePct
    ) {
    }

    /** A vault token identity. */
    record VaultToken(String mint, String symbol, Integer decimals) {
    }

    /**
     * A user's borrow position (NFT).
     *
     * @param nftId      position NFT id
     * @param vaultId    owning vault id
     * @param supply     collateral in supply-token base units
     * @param borrow     outstanding debt (incl. accrued interest) in borrow-token base units
     * @param dustBorrow negligible sub-unit rounding residual in borrow-token base units, or null;
     *                   NOT the outstanding debt (that is {@link #borrow()})
     */
    record BorrowPosition(
            long nftId,
            int vaultId,
            BigInteger supply,
            BigInteger borrow,
            BigInteger dustBorrow
    ) {
    }
}
