package com.walletradar.application.pricing.latest;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.descriptor.NetworkRegistry;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers WS-6 registry behaviour: native majors (notably non-EVM SOL/TON) are seeded before the
 * build regardless of balance population, and xStock equity-backed symbols are stamped EQUITY.
 */
class TrackedAssetRegistryMaintainerTest {

    private final MongoOperations mongo = mock(MongoOperations.class);
    private final TrackedPriceAssetRepository repository = mock(TrackedPriceAssetRepository.class);
    private final LatestPriceProperties properties = new LatestPriceProperties();
    private final NetworkRegistry networkRegistry = mock(NetworkRegistry.class);

    private TrackedAssetRegistryMaintainer maintainer() {
        return new TrackedAssetRegistryMaintainer(mongo, repository, properties, networkRegistry);
    }

    @SuppressWarnings("unchecked")
    private void stubEmptyCollections() {
        when(mongo.find(any(Query.class), eq(Document.class), any(String.class))).thenReturn(List.of());
    }

    private void stubNetworks() {
        when(networkRegistry.walletSupportedNetworks())
                .thenReturn(Set.of(NetworkId.SOLANA, NetworkId.TON, NetworkId.ETHEREUM));
        when(networkRegistry.nativeSymbol(NetworkId.SOLANA)).thenReturn("SOL");
        when(networkRegistry.nativeSymbol(NetworkId.TON)).thenReturn("TON");
        when(networkRegistry.nativeSymbol(NetworkId.ETHEREUM)).thenReturn("ETH");
    }

    private Map<String, String> captureUpsertKindsBySymbol() {
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongo, atLeastOnce()).upsert(any(Query.class), updateCaptor.capture(),
                eq(TrackedPriceAssetDocument.class));
        Map<String, String> kinds = new LinkedHashMap<>();
        for (Update update : updateCaptor.getAllValues()) {
            Document set = update.getUpdateObject().get("$set", Document.class);
            if (set != null) {
                kinds.put(set.getString("symbol"), set.getString("kind"));
            }
        }
        return kinds;
    }

    @Test
    void seedsNativeMajorsEvenWhenNoBalancesPopulated() {
        stubNetworks();
        stubEmptyCollections();

        int upserted = maintainer().rebuild();

        Map<String, String> kinds = captureUpsertKindsBySymbol();
        assertThat(kinds).containsKeys("SOL", "TON", "ETH");
        assertThat(kinds.get("SOL")).isEqualTo("CRYPTO");
        assertThat(kinds.get("TON")).isEqualTo("CRYPTO");
        assertThat(upserted).isGreaterThanOrEqualTo(3);
    }

    @Test
    void stampsXStockEquityJettonsAsEquityKind() {
        stubNetworks();
        // Only on_chain_balances returns a row; the other collections are empty.
        when(mongo.find(any(Query.class), eq(Document.class), eq("on_chain_balances")))
                .thenReturn(List.of(new Document()
                        .append("assetSymbol", "AMZNx")
                        .append("quantity", new Decimal128(new BigDecimal("5")))));
        when(mongo.find(any(Query.class), eq(Document.class), eq("bybit_live_balances"))).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class), eq("dzengi_live_balances"))).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class), eq("lp_position_snapshots"))).thenReturn(List.of());

        maintainer().rebuild();

        Map<String, String> kinds = captureUpsertKindsBySymbol();
        // AMZNx canonicalises to AMZN and must be tracked as EQUITY (Dzengi equity ticker match).
        assertThat(kinds).containsEntry("AMZN", "EQUITY");
    }
}
