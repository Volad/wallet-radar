package com.walletradar.application.lending.application;

import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;

import java.util.Optional;

/**
 * SPI for reading a lending market's <em>live</em> supply/borrow/reward rates. One implementation per
 * protocol/network family; the market-rate refresh service iterates the registered readers and
 * dispatches by {@link #supports(String, String)} — there is no hardcoded protocol filter (WS-3).
 *
 * <p>Per-market granularity (contrast {@link com.walletradar.application.lending.spi.LendingLivePositionReader},
 * which is per-wallet): a reserve/vault rate read is fetched once and shared across every wallet in
 * that market. The reader returns a fully-populated {@link LendingMarketRateSnapshot} (supply/borrow
 * APY + provenance) so no rate field is lost across protocols; the refresh service only persists it.</p>
 *
 * <p>Background-only, idempotent, best-effort: a failure resolves to an {@code UNAVAILABLE} snapshot
 * or {@link Optional#empty()}, never a thrown exception.</p>
 */
public interface LendingMarketRateReader {

    /**
     * @param protocolKey display protocol key of the market (e.g. {@code Aave}, {@code Jupiter Lend})
     * @param networkId   network id (upper-case name, e.g. {@code SOLANA})
     * @return true when this reader can read rates for the given protocol on the given network
     */
    boolean supports(String protocolKey, String networkId);

    /**
     * Reads the live rate snapshot for one active market.
     *
     * @param market discovered active market to refresh
     * @return the rate snapshot (possibly {@code UNAVAILABLE}) when applicable, else {@link Optional#empty()}
     */
    Optional<LendingMarketRateSnapshot> collect(LendingActiveMarketDiscoveryService.ActiveMarket market);
}
