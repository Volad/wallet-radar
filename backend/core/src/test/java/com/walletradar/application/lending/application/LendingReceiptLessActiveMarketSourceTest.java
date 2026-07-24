package com.walletradar.application.lending.application;

import com.walletradar.application.lending.application.LendingActiveMarketDiscoveryService.ActiveMarket;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshot;
import com.walletradar.application.lending.persistence.LendingLivePositionSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the receipt-less discovery source emits an {@link ActiveMarket} per collateral leg (SUPPLY)
 * and debt leg (BORROW) of the freshest live-position snapshot, keyed so the emitted marketKey /
 * underlyingSymbol / side match the built lending position lookup (Jupiter Lend SOL/USDC loop).
 */
class LendingReceiptLessActiveMarketSourceTest {

    private static final String SESSION = "df5e69cc-a0c0-4910-8b7d-74488fa266e2";
    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
    private static final String NATIVE_SOL = "NATIVE:SOLANA";
    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    private final LendingLivePositionSnapshotRepository repository =
            mock(LendingLivePositionSnapshotRepository.class);
    private final LendingReceiptLessActiveMarketSource source =
            new LendingReceiptLessActiveMarketSource(repository);

    @Test
    void emitsSupplySolAndBorrowUsdcMarketsFromLiveSnapshot() {
        when(repository.findBySessionIdInAndCapturedAtGreaterThanEqual(anyCollection(), any()))
                .thenReturn(List.of(jupiterLoopSnapshot(Instant.now())));

        List<ActiveMarket> markets = source.discover(List.of(SESSION));

        assertThat(markets).hasSize(2);
        ActiveMarket supply = market(markets, "SUPPLY");
        assertThat(supply.protocol()).isEqualTo("Jupiter Lend");
        assertThat(supply.networkId()).isEqualTo("SOLANA");
        assertThat(supply.marketKey()).isEqualTo("Jupiter Lend:SOLANA:ACCOUNT-POOL");
        assertThat(supply.underlyingSymbol()).isEqualTo("SOL");
        assertThat(supply.walletAddress()).isEqualTo(WALLET);
        assertThat(supply.assetContract()).isEqualTo(NATIVE_SOL);

        ActiveMarket borrow = market(markets, "BORROW");
        assertThat(borrow.marketKey()).isEqualTo("Jupiter Lend:SOLANA:ACCOUNT-POOL");
        assertThat(borrow.underlyingSymbol()).isEqualTo("USDC");
    }

    @Test
    void keepsOnlyLatestSnapshotPerGroup() {
        LendingLivePositionSnapshot stale = jupiterLoopSnapshot(Instant.now().minusSeconds(120));
        stale.getCollateral().get(0).setAssetSymbol("STALE");
        LendingLivePositionSnapshot fresh = jupiterLoopSnapshot(Instant.now());
        when(repository.findBySessionIdInAndCapturedAtGreaterThanEqual(anyCollection(), any()))
                .thenReturn(List.of(stale, fresh));

        List<ActiveMarket> markets = source.discover(List.of(SESSION));

        assertThat(markets).extracting(ActiveMarket::underlyingSymbol)
                .containsExactlyInAnyOrder("SOL", "USDC");
    }

    @Test
    void skipsZeroQuantityLegs() {
        LendingLivePositionSnapshot snapshot = jupiterLoopSnapshot(Instant.now());
        snapshot.getDebt().get(0).setQuantity(BigDecimal.ZERO);
        when(repository.findBySessionIdInAndCapturedAtGreaterThanEqual(anyCollection(), any()))
                .thenReturn(List.of(snapshot));

        List<ActiveMarket> markets = source.discover(List.of(SESSION));

        assertThat(markets).extracting(ActiveMarket::side).containsExactly("SUPPLY");
    }

    @Test
    void emptySessionsReturnsEmpty() {
        assertThat(source.discover(List.of())).isEmpty();
    }

    private static ActiveMarket market(List<ActiveMarket> markets, String side) {
        return markets.stream().filter(m -> side.equals(m.side())).findFirst().orElseThrow();
    }

    private static LendingLivePositionSnapshot jupiterLoopSnapshot(Instant capturedAt) {
        return new LendingLivePositionSnapshot()
                .setId("snap:" + capturedAt.toEpochMilli())
                .setSessionId(SESSION)
                .setProtocolKey("Jupiter Lend")
                .setNetworkId("SOLANA")
                .setWalletAddress(WALLET)
                .setCapturedAt(capturedAt)
                .setCollateral(List.of(leg("SOL", NATIVE_SOL, new BigDecimal("5.42"))))
                .setDebt(List.of(leg("USDC", USDC_MINT, new BigDecimal("233.39"))));
    }

    private static LendingLivePositionSnapshot.Leg leg(String symbol, String contract, BigDecimal quantity) {
        return new LendingLivePositionSnapshot.Leg()
                .setAssetSymbol(symbol)
                .setAssetContract(contract)
                .setQuantity(quantity);
    }
}
