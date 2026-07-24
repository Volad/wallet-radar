package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.jupiter.JupiterProperties;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers symbol↔mint mapping from {@code on_chain_balances}, stablecoin/native skips, largest-qty
 * mint selection for symbol collisions, and never-throw empty behaviour for
 * {@link JupiterPriceLatestPriceProvider}.
 */
class JupiterPriceLatestPriceProviderTest {

    private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String WSOL_MINT = "So11111111111111111111111111111111111111112";
    private static final String MSOL_MINT = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
    private static final String PUMP_MINT_SMALL = "Pump1small1111111111111111111111111111111111";
    private static final String PUMP_MINT_BIG = "Pump2big22222222222222222222222222222222222";
    private static final String GHOST_MINT = "Ghost3333333333333333333333333333333333333";

    private final MongoOperations mongo = mock(MongoOperations.class);
    private final JupiterClient jupiterClient = mock(JupiterClient.class);

    private JupiterPriceLatestPriceProvider provider() {
        return new JupiterPriceLatestPriceProvider(mongo, jupiterClient, new JupiterProperties());
    }

    private static Document balance(String symbol, String contract, String qty) {
        return new Document()
                .append("assetSymbol", symbol)
                .append("assetContract", contract)
                .append("quantity", new Decimal128(new BigDecimal(qty)));
    }

    @SuppressWarnings("unchecked")
    private void stubBalances(List<Document> docs) {
        when(mongo.find(any(Query.class), eq(Document.class), eq("on_chain_balances"))).thenReturn(docs);
    }

    @Test
    void mapsSolanaMintsToSymbolsSkippingNativeAndStablecoins() {
        stubBalances(List.of(
                balance("SOL", "NATIVE:SOLANA", "3"),
                balance("USDC", USDC_MINT, "100"),
                balance("MSOL", MSOL_MINT, "10"),
                balance("PUMP", PUMP_MINT_SMALL, "5"),
                balance("PUMP", PUMP_MINT_BIG, "20"),
                balance("GHOST", GHOST_MINT, "3")
        ));
        when(jupiterClient.fetchPrices(anyList())).thenReturn(Map.of(
                MSOL_MINT, new BigDecimal("214.50"),
                PUMP_MINT_SMALL, new BigDecimal("0.001"),
                PUMP_MINT_BIG, new BigDecimal("0.002")
                // GHOST_MINT intentionally unpriced
        ));

        Map<String, TrackedPriceAssetDocument.Kind> wanted = Map.of(
                "MSOL", TrackedPriceAssetDocument.Kind.CRYPTO,
                "PUMP", TrackedPriceAssetDocument.Kind.CRYPTO,
                "GHOST", TrackedPriceAssetDocument.Kind.CRYPTO
        );

        Map<String, NormalizedLatestQuote> result = provider().fetchAll(wanted);

        assertThat(result).containsOnlyKeys("MSOL", "PUMP");

        NormalizedLatestQuote msol = result.get("MSOL");
        assertThat(msol.priceUsd()).isEqualByComparingTo("214.50");
        assertThat(msol.source()).isEqualTo(PriceSource.JUPITER);
        assertThat(msol.quoteCurrency()).isEqualTo("USD");
        assertThat(msol.sourceSymbol()).isEqualTo(MSOL_MINT);

        // Collision: the mint held in the larger quantity wins.
        NormalizedLatestQuote pump = result.get("PUMP");
        assertThat(pump.sourceSymbol()).isEqualTo(PUMP_MINT_BIG);
        assertThat(pump.priceUsd()).isEqualByComparingTo("0.002");
    }

    @Test
    void skipsWrappedSolMintSoNativeSolIsCexPricedNotJupiterPriced() {
        // WS-6 (B4): wSOL canonicalises to SOL; it must be priced by the CEX venues, never as a
        // distinct SPL via Jupiter (which would create a spurious JUPITER SOL quote).
        stubBalances(List.of(
                balance("SOL", WSOL_MINT, "5"),
                balance("MSOL", MSOL_MINT, "10")
        ));
        when(jupiterClient.fetchPrices(anyList())).thenReturn(Map.of(
                MSOL_MINT, new BigDecimal("214.50")
        ));

        Map<String, NormalizedLatestQuote> result = provider().fetchAll(Map.of(
                "SOL", TrackedPriceAssetDocument.Kind.CRYPTO,
                "MSOL", TrackedPriceAssetDocument.Kind.CRYPTO
        ));

        // wSOL mint is skipped → SOL is not priced by Jupiter; only MSOL resolves.
        assertThat(result).containsOnlyKeys("MSOL");
    }

    @Test
    void restrictsToWantedSymbols() {
        stubBalances(List.of(balance("MSOL", MSOL_MINT, "10")));
        // MSOL is not wanted this cycle → no price fetch, empty result.
        Map<String, NormalizedLatestQuote> result = provider().fetchAll(
                Map.of("BONK", TrackedPriceAssetDocument.Kind.CRYPTO));
        assertThat(result).isEmpty();
    }

    @Test
    void emptyWhenNoSolanaBalances() {
        stubBalances(List.of());
        assertThat(provider().fetchAll(Map.of("MSOL", TrackedPriceAssetDocument.Kind.CRYPTO))).isEmpty();
    }

    @Test
    void emptyWhenDisabled() {
        JupiterProperties disabled = new JupiterProperties();
        disabled.setEnabled(false);
        JupiterPriceLatestPriceProvider disabledProvider =
                new JupiterPriceLatestPriceProvider(mongo, jupiterClient, disabled);
        assertThat(disabledProvider.fetchAll(Map.of("MSOL", TrackedPriceAssetDocument.Kind.CRYPTO))).isEmpty();
    }

    @Test
    void emptyForNullOrEmptyWanted() {
        assertThat(provider().fetchAll(null)).isEmpty();
        assertThat(provider().fetchAll(Map.of())).isEmpty();
    }

    @Test
    void priorityIsBelowCexVenues() {
        assertThat(provider().priority()).isGreaterThan(2);
        assertThat(provider().source()).isEqualTo(PriceSource.JUPITER);
    }
}
