package com.walletradar.application.lending.spi;

import java.util.Optional;

/**
 * SPI for reading a wallet's <em>live</em> lending position (collateral, debt, health factor,
 * liquidation threshold) directly from a lending protocol. One implementation per protocol/network
 * family; the refresh services iterate the registered readers and dispatch by
 * {@link #supports(String, String)} — there is no hardcoded protocol filter (WS-3).
 *
 * <p>Per-wallet granularity (contrast {@link LendingMarketRateReader}, which is per-market and shares
 * a reserve read across wallets). Implementations:</p>
 * <ul>
 *   <li>are <strong>background-only</strong> — must never be invoked on a GET/read path (they perform
 *       network I/O); the GET path reads persisted snapshots;</li>
 *   <li>are idempotent, retry-safe and best-effort — a routine failure (rate limit, timeout, empty
 *       position) resolves to {@link Optional#empty()}, never a thrown exception, so callers keep the
 *       last snapshot marked stale rather than fabricating a value.</li>
 * </ul>
 */
public interface LendingLivePositionReader {

    /**
     * @param protocolKey display protocol key of the borrow group (e.g. {@code Aave}, {@code Jupiter Lend})
     * @param networkId   network id (upper-case name, e.g. {@code SOLANA})
     * @return true when this reader can read the given protocol on the given network
     */
    boolean supports(String protocolKey, String networkId);

    /**
     * Reads the live position for one wallet.
     *
     * @param request the borrow group identity to refresh
     * @return the live position when resolvable, else {@link Optional#empty()}
     */
    Optional<LiveLendingPosition> read(LivePositionRequest request);
}
