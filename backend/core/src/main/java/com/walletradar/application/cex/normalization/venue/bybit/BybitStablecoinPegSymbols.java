package com.walletradar.application.cex.normalization.venue.bybit;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of the Bybit normalization stablecoin <em>peg</em> set — symbols treated as pegged
 * to $1.00 during Bybit canonical normalization (orphan-trade pricing, bot-transfer cost basis,
 * counterparty stable-flow detection, mapped-row pricing). Previously this identical set was copied
 * across four Bybit normalization classes (Wave W10 consolidation).
 *
 * <p><strong>Deliberately separate</strong> from {@code BybitVenueDescriptor.STABLECOIN_SYMBOLS},
 * which is the external-capital (NEC) FUND-inflow eligibility set. The two intentionally diverge:
 * the NEC set includes bare {@code USD} and excludes {@code DAI/FDUSD/PYUSD/TUSD/USD1}, because NEC
 * gates fiat capital injection whereas this set gates $1 pricing. Merging them would change both
 * behaviors, so they are kept apart.</p>
 */
final class BybitStablecoinPegSymbols {

    private static final Set<String> SYMBOLS = Set.of(
            "USDT", "USDC", "USDE", "USDS", "USDD", "DAI", "FDUSD", "PYUSD", "TUSD", "USD1"
    );

    private BybitStablecoinPegSymbols() {
    }

    /**
     * Case-insensitive membership test with the exact null/blank guard the four former call sites
     * used (blank symbols return {@code false}).
     */
    static boolean isPegged(String assetSymbol) {
        return assetSymbol != null
                && !assetSymbol.isBlank()
                && SYMBOLS.contains(assetSymbol.trim().toUpperCase(Locale.ROOT));
    }

    static Set<String> symbols() {
        return SYMBOLS;
    }
}
