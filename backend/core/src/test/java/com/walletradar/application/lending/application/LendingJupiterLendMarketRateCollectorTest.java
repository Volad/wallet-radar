package com.walletradar.application.lending.application;

import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
import com.walletradar.platform.networks.solana.jupiter.lend.JupiterLendClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Jupiter Lend market-rate reader matches a discovered market to its Borrow API vault by
 * side + canonical underlying symbol (WSOL == SOL) and publishes the protocol supply/borrow APY so the
 * lending page can render Net APY. Rates are venue-sourced (API_SNAPSHOT), never fabricated.
 */
class LendingJupiterLendMarketRateCollectorTest {

    private static final String WSOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    private static final JupiterLendClient.BorrowVault SOL_USDC_VAULT = new JupiterLendClient.BorrowVault(
            1,
            new JupiterLendClient.VaultToken(WSOL_MINT, "WSOL", 9),
            new JupiterLendClient.VaultToken(USDC_MINT, "USDC", 6),
            new BigDecimal("0.80"),
            new BigDecimal("0.85"),
            new BigDecimal("4.46"),
            new BigDecimal("4.58")
    );

    @Test
    void supportsJupiterOnSolanaOnly() {
        LendingJupiterLendMarketRateCollector collector =
                new LendingJupiterLendMarketRateCollector(client(List.of(SOL_USDC_VAULT)));
        assertThat(collector.supports("Jupiter Lend", "SOLANA")).isTrue();
        assertThat(collector.supports("Aave", "SOLANA")).isFalse();
        assertThat(collector.supports("Jupiter Lend", "BASE")).isFalse();
    }

    @Test
    void supplyMarketPublishesProtocolApy() {
        LendingJupiterLendMarketRateCollector collector =
                new LendingJupiterLendMarketRateCollector(client(List.of(SOL_USDC_VAULT)));

        Optional<LendingMarketRateSnapshot> result = collector.collect(market("SUPPLY", "SOL"));

        assertThat(result).isPresent();
        LendingMarketRateSnapshot snapshot = result.get();
        assertThat(snapshot.getRateStatus()).isEqualTo(LendingMarketRateStatus.API_SNAPSHOT);
        assertThat(snapshot.getRateSource()).isEqualTo("JUPITER_LEND_BORROW_API");
        assertThat(snapshot.getSupplyAprPct()).isEqualByComparingTo("4.46");
        assertThat(snapshot.getBorrowAprPct()).isEqualByComparingTo("4.58");
        assertThat(snapshot.getSupplyApyPct()).isNotNull();
        assertThat(snapshot.getBorrowApyPct()).isNotNull();
        // APY (per-second compounding) is at least the nominal APR.
        assertThat(snapshot.getSupplyApyPct()).isGreaterThanOrEqualTo(new BigDecimal("4.46"));
    }

    @Test
    void borrowMarketPublishesProtocolApy() {
        LendingJupiterLendMarketRateCollector collector =
                new LendingJupiterLendMarketRateCollector(client(List.of(SOL_USDC_VAULT)));

        Optional<LendingMarketRateSnapshot> result = collector.collect(market("BORROW", "USDC"));

        assertThat(result).isPresent();
        LendingMarketRateSnapshot snapshot = result.get();
        assertThat(snapshot.getRateStatus()).isEqualTo(LendingMarketRateStatus.API_SNAPSHOT);
        assertThat(snapshot.getBorrowAprPct()).isEqualByComparingTo("4.58");
    }

    @Test
    void marketNotFoundIsUnavailable() {
        LendingJupiterLendMarketRateCollector collector =
                new LendingJupiterLendMarketRateCollector(client(List.of(SOL_USDC_VAULT)));

        Optional<LendingMarketRateSnapshot> result = collector.collect(market("SUPPLY", "ETH"));

        assertThat(result).isPresent();
        assertThat(result.get().getRateStatus()).isEqualTo(LendingMarketRateStatus.UNAVAILABLE);
        assertThat(result.get().getUnavailableReason()).isEqualTo("JUPITER_LEND_MARKET_NOT_FOUND");
    }

    @Test
    void emptyVaultsIsUnavailable() {
        LendingJupiterLendMarketRateCollector collector =
                new LendingJupiterLendMarketRateCollector(client(List.of()));

        Optional<LendingMarketRateSnapshot> result = collector.collect(market("SUPPLY", "SOL"));

        assertThat(result).isPresent();
        assertThat(result.get().getRateStatus()).isEqualTo(LendingMarketRateStatus.UNAVAILABLE);
        assertThat(result.get().getUnavailableReason()).isEqualTo("JUPITER_LEND_VAULTS_UNAVAILABLE");
    }

    private static LendingActiveMarketDiscoveryService.ActiveMarket market(String side, String underlying) {
        return new LendingActiveMarketDiscoveryService.ActiveMarket(
                "session-1",
                "Jupiter Lend",
                "SOLANA",
                "wallet-1",
                "Jupiter Lend:SOLANA:ACCOUNT-POOL",
                side,
                underlying,
                underlying,
                "receipt-contract"
        );
    }

    private static JupiterLendClient client(List<JupiterLendClient.BorrowVault> vaults) {
        return new JupiterLendClient() {
            @Override
            public List<BorrowVault> fetchBorrowVaults() {
                return vaults;
            }

            @Override
            public List<BorrowPosition> fetchBorrowPositions(String walletAddress) {
                return List.of();
            }
        };
    }
}
