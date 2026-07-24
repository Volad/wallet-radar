package com.walletradar.application.lending.application;

import com.walletradar.application.lending.spi.LiveLendingAssetAmount;
import com.walletradar.application.lending.spi.LiveLendingPosition;
import com.walletradar.application.lending.spi.LivePositionRequest;
import com.walletradar.application.pricing.latest.CurrentPriceReadService;
import com.walletradar.application.pricing.latest.ResolvedPrice;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.platform.networks.solana.jupiter.lend.JupiterLendClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS-3 acceptance: the live Jupiter Lend reader reproduces the audited ground truth for Solana wallet
 * {@code 9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG} — collateral 5.4225 SOL, debt 233.38 USDT,
 * CF 0.80 / LT 0.85 ⇒ HF ≈ 1.51, LTV ≈ 56.4% — and counts the position exactly once.
 */
class JupiterLendLivePositionReaderTest {

    private static final String WSOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";

    private final JupiterLendClient client = mock(JupiterLendClient.class);
    private final CurrentPriceReadService priceService = mock(CurrentPriceReadService.class);

    private JupiterLendLivePositionReader reader() {
        return new JupiterLendLivePositionReader(client, priceService);
    }

    private static LivePositionRequest request() {
        return new LivePositionRequest("session-1", "Jupiter Lend", "SOLANA", WALLET);
    }

    private void stubGroundTruthVaultAndPosition() {
        JupiterLendClient.BorrowVault vault = new JupiterLendClient.BorrowVault(
                2,
                new JupiterLendClient.VaultToken(WSOL_MINT, "WSOL", 9),
                new JupiterLendClient.VaultToken(USDT_MINT, "USDT", 6),
                new BigDecimal("0.80"),
                new BigDecimal("0.85"),
                new BigDecimal("4.50"),
                new BigDecimal("4.56"));
        when(client.fetchBorrowVaults()).thenReturn(List.of(vault));
        // Real Jupiter Lend API shape for this wallet: supply 5.422710801 SOL (9dp),
        // borrow 233.387312 USDT (6dp, authoritative debt incl. accrued interest — matches the app's
        // "Debt 233.38 USDT"), dustBorrow 0.050159 USDT (6dp, negligible sub-unit residual, NOT debt).
        when(client.fetchBorrowPositions(WALLET)).thenReturn(List.of(new JupiterLendClient.BorrowPosition(
                101L, 2, new BigInteger("5422710801"), new BigInteger("233387312"), new BigInteger("50159"))));
        when(priceService.resolveOne(eq("SOL")))
                .thenReturn(Optional.of(new ResolvedPrice(new BigDecimal("76.27"), PriceSource.BYBIT, Instant.now(), false)));
        when(priceService.resolveOne(eq("USDT")))
                .thenReturn(Optional.of(new ResolvedPrice(BigDecimal.ONE, PriceSource.STABLECOIN, Instant.now(), false)));
    }

    @Test
    void supportsJupiterLendOnSolanaOnly() {
        JupiterLendLivePositionReader reader = reader();
        assertThat(reader.supports("Jupiter Lend", "SOLANA")).isTrue();
        assertThat(reader.supports("JupiterLend", "solana")).isTrue();
        assertThat(reader.supports("Aave", "ARBITRUM")).isFalse();
        assertThat(reader.supports("Jupiter Lend", "ETHEREUM")).isFalse();
        assertThat(reader.supports(null, "SOLANA")).isFalse();
    }

    @Test
    void readsGroundTruthCollateralDebtHealthFactorAndLtv() {
        stubGroundTruthVaultAndPosition();

        LiveLendingPosition position = reader().read(request()).orElseThrow();

        // Collateral: 5.4227 SOL, counted once, booked as native SOL identity (merges with free SOL).
        assertThat(position.collateral()).hasSize(1);
        LiveLendingAssetAmount collateral = position.collateral().get(0);
        assertThat(collateral.assetSymbol()).isEqualTo("SOL");
        assertThat(collateral.assetContract()).isEqualTo("NATIVE:SOLANA");
        assertThat(collateral.quantity()).isEqualByComparingTo("5.422710801");

        // Debt: 233.387312 USDT (from `borrow`, incl. accrued interest — NOT the 0.05 dustBorrow),
        // counted once, marked at $1.
        assertThat(position.debt()).hasSize(1);
        LiveLendingAssetAmount debt = position.debt().get(0);
        assertThat(debt.assetSymbol()).isEqualTo("USDT");
        assertThat(debt.quantity()).isEqualByComparingTo("233.387312");
        assertThat(debt.marketValueUsd()).isEqualByComparingTo("233.387312");

        assertThat(position.liquidationThreshold()).isEqualByComparingTo("0.85");
        // HF = 0.85 × (5.422710801 × 76.27) / 233.387312 ≈ 1.506
        assertThat(position.healthFactor()).isBetween(new BigDecimal("1.50"), new BigDecimal("1.52"));
        // LTV = debtUsd / collateralUsd ≈ 0.564
        assertThat(position.loanToValue()).isBetween(new BigDecimal("0.560"), new BigDecimal("0.568"));
        assertThat(position.source()).isEqualTo("LIVE_PROTOCOL");
    }

    @Test
    void emptyWhenWalletHasNoPositions() {
        when(client.fetchBorrowPositions(WALLET)).thenReturn(List.of());
        assertThat(reader().read(request())).isEmpty();
    }
}
