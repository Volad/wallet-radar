package com.walletradar.application.lending.spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Protocol-authoritative live snapshot of a single wallet's leveraged lending position, read
 * directly from the protocol (on-chain state / protocol API) rather than reconstructed from the
 * accounting ledger.
 *
 * <p>This is the single-authority record for the position (WS-3): the collateral it reports is a
 * <em>carry</em> (an already-owned asset locked in the protocol, valued at market using the SOL/asset
 * AVCO already in the ledger — never a fresh basis acquisition), and the debt it reports supersedes
 * the classification-derived {@code borrow_liabilities.qtyOpen} (WS-4 SET/override, never stack).</p>
 *
 * @param collateral            supplied/locked collateral legs (carry), one per asset
 * @param debt                  outstanding debt legs (incl. accrued interest), one per asset
 * @param healthFactor          Aave-style health factor ({@code liquidationThreshold × collatUsd / debtUsd})
 * @param liquidationThreshold  liquidation threshold as a fraction (e.g. {@code 0.85} for 85%)
 * @param loanToValue           current loan-to-value as a fraction (e.g. {@code 0.564} for 56.4%)
 * @param source               provenance marker persisted on the snapshot (e.g. {@code LIVE_PROTOCOL})
 * @param blockNumber          optional chain block/slot the read was anchored to
 * @param rawRef               optional opaque reference (vault id / position id) for auditability
 */
public record LiveLendingPosition(
        List<LiveLendingAssetAmount> collateral,
        List<LiveLendingAssetAmount> debt,
        BigDecimal healthFactor,
        BigDecimal liquidationThreshold,
        BigDecimal loanToValue,
        String source,
        Long blockNumber,
        String rawRef
) {
    public LiveLendingPosition {
        collateral = collateral == null ? List.of() : List.copyOf(collateral);
        debt = debt == null ? List.of() : List.copyOf(debt);
    }
}
