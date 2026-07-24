package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkStablecoinContracts;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.platform.networks.ton.price.TonPriceClient;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers symbol↔master mapping from {@code on_chain_balances} (networkId=TON), native/stablecoin
 * skips, xStock canonicalisation, largest-qty master selection, and never-throw empty behaviour for
 * {@link TonJettonLatestPriceProvider}.
 */
class TonJettonLatestPriceProviderTest {

    private static final String STON_MASTER = "0:3690254dc15b2297610cda60744a45f2b710aa4234b89adb630e99d79b01bd4f";
    private static final String XAUT_MASTER = "0:3547f2ee4022c794c80ea354b81bb63b5b571dd05ac091b035d19abbadd74ac6";
    private static final String USDT_MASTER = "0:b113a994b5024a16719f69139328eb759596c38a25f59028b146fecdc3621dfe";
    private static final String GHOST_MASTER = "0:0000000000000000000000000000000000000000000000000000000000000abc";

    private final MongoOperations mongo = mock(MongoOperations.class);
    private final TonPriceClient tonPriceClient = mock(TonPriceClient.class);

    @BeforeAll
    static void bindStablecoins() {
        NetworkStablecoinContracts.bind(id -> id == NetworkId.TON ? Set.of(USDT_MASTER) : Set.of());
    }

    private TonJettonLatestPriceProvider provider() {
        return new TonJettonLatestPriceProvider(mongo, tonPriceClient);
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
    void mapsTonMastersToSymbolsSkippingNativeAndStablecoins() {
        stubBalances(List.of(
                balance("TON", "TONCOIN", "100"),
                balance("USDT", USDT_MASTER, "60"),
                balance("STON", STON_MASTER, "200"),
                balance("XAUt0", XAUT_MASTER, "5"),
                balance("GHOST", GHOST_MASTER, "3")
        ));
        when(tonPriceClient.fetchAllPrices()).thenReturn(Map.of(
                STON_MASTER, new BigDecimal("0.60"),
                XAUT_MASTER, new BigDecimal("2600.00")
                // GHOST intentionally unpriced
        ));

        Map<String, TrackedPriceAssetDocument.Kind> wanted = Map.of(
                "STON", TrackedPriceAssetDocument.Kind.CRYPTO,
                "XAUT", TrackedPriceAssetDocument.Kind.CRYPTO,
                "GHOST", TrackedPriceAssetDocument.Kind.CRYPTO
        );

        Map<String, NormalizedLatestQuote> result = provider().fetchAll(wanted);

        // Native TON + USDT (stablecoin) skipped; GHOST unpriced. XAUt0 canonicalises to XAUT.
        assertThat(result).containsOnlyKeys("STON", "XAUT");

        NormalizedLatestQuote ston = result.get("STON");
        assertThat(ston.priceUsd()).isEqualByComparingTo("0.60");
        assertThat(ston.source()).isEqualTo(PriceSource.STON_FI);
        assertThat(ston.quoteCurrency()).isEqualTo("USD");
        assertThat(ston.sourceSymbol()).isEqualTo(STON_MASTER);

        NormalizedLatestQuote xaut = result.get("XAUT");
        assertThat(xaut.priceUsd()).isEqualByComparingTo("2600.00");
        assertThat(xaut.sourceSymbol()).isEqualTo(XAUT_MASTER);
    }

    @Test
    void restrictsToWantedSymbols() {
        stubBalances(List.of(balance("STON", STON_MASTER, "200")));
        Map<String, NormalizedLatestQuote> result = provider().fetchAll(
                Map.of("NOTGE", TrackedPriceAssetDocument.Kind.CRYPTO));
        assertThat(result).isEmpty();
    }

    @Test
    void emptyWhenNoTonBalances() {
        stubBalances(List.of());
        assertThat(provider().fetchAll(Map.of("STON", TrackedPriceAssetDocument.Kind.CRYPTO))).isEmpty();
    }

    @Test
    void emptyForNullOrEmptyWanted() {
        assertThat(provider().fetchAll(null)).isEmpty();
        assertThat(provider().fetchAll(Map.of())).isEmpty();
    }

    @Test
    void priorityIsBelowCexVenues() {
        assertThat(provider().priority()).isGreaterThan(2);
        assertThat(provider().source()).isEqualTo(PriceSource.STON_FI);
    }
}
