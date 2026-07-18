package com.walletradar.application.cex.normalization.venue.bybit;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave W10 — pins the consolidated Bybit normalization stablecoin peg set (previously copied across
 * four classes) and its case/blank behavior, and documents its intentional divergence from the
 * venue NEC eligibility set.
 */
class BybitStablecoinPegSymbolsTest {

    private static final Set<String> EXPECTED = Set.of(
            "USDT", "USDC", "USDE", "USDS", "USDD", "DAI", "FDUSD", "PYUSD", "TUSD", "USD1"
    );

    @Test
    void pegSetMatchesHistoricalMembership() {
        assertThat(BybitStablecoinPegSymbols.symbols())
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void isPeggedIsCaseInsensitiveForEveryMember() {
        for (String symbol : EXPECTED) {
            assertThat(BybitStablecoinPegSymbols.isPegged(symbol)).as(symbol).isTrue();
            assertThat(BybitStablecoinPegSymbols.isPegged(symbol.toLowerCase())).as(symbol).isTrue();
            assertThat(BybitStablecoinPegSymbols.isPegged("  " + symbol + "  ")).as(symbol).isTrue();
        }
    }

    @Test
    void isPeggedRejectsNullBlankAndNonMembers() {
        assertThat(BybitStablecoinPegSymbols.isPegged(null)).isFalse();
        assertThat(BybitStablecoinPegSymbols.isPegged("")).isFalse();
        assertThat(BybitStablecoinPegSymbols.isPegged("   ")).isFalse();
        assertThat(BybitStablecoinPegSymbols.isPegged("ETH")).isFalse();
        assertThat(BybitStablecoinPegSymbols.isPegged("USD")).isFalse();
    }

    @Test
    void pegSetDivergesFromNecEligibilitySet() {
        // The NEC (FUND capital) set gates fiat injection: it accepts bare USD and rejects the
        // DeFi stablecoins that are still $1-pegged for pricing. Guard the divergence so a future
        // "consolidation" cannot silently merge them.
        assertThat(BybitStablecoinPegSymbols.symbols()).contains("DAI", "FDUSD", "PYUSD", "TUSD", "USD1");
        assertThat(BybitStablecoinPegSymbols.symbols()).doesNotContain("USD");
    }
}
