package com.walletradar.ingestion.store;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "walletradar.ingestion.network.ETHEREUM.urls[0]=https://eth.llamarpc.com",
        "walletradar.ingestion.network.ETHEREUM.batch-block-size=2000"
})
@Testcontainers
class IdempotentEventStoreIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    IdempotentEventStore store;
    @Autowired
    EconomicEventRepository repository;

    @Test
    @DisplayName("double write same txHash+networkId results in single event")
    void doubleWriteSameTx_singleEvent() {
        EconomicEvent event = new EconomicEvent();
        event.setTxHash("0xidem123");
        event.setNetworkId(NetworkId.ETHEREUM);
        event.setWalletAddress("0xwallet");
        event.setBlockTimestamp(Instant.parse("2025-01-15T10:00:00Z"));
        event.setEventType(EconomicEventType.SWAP_BUY);
        event.setAssetSymbol("ETH");
        event.setAssetContract("0x0000000000000000000000000000000000000000");
        event.setQuantityDelta(new BigDecimal("1"));
        event.setPriceUsd(new BigDecimal("2500"));
        event.setPriceSource(PriceSource.SWAP_DERIVED);
        event.setTotalValueUsd(new BigDecimal("2500"));
        event.setGasCostUsd(BigDecimal.ZERO);
        event.setGasIncludedInBasis(true);

        EconomicEvent first = store.upsert(event);
        assertThat(first.getId()).isNotNull();

        EconomicEvent event2 = new EconomicEvent();
        event2.setTxHash("0xidem123");
        event2.setNetworkId(NetworkId.ETHEREUM);
        event2.setWalletAddress("0xwallet2");
        event2.setBlockTimestamp(Instant.parse("2025-01-15T11:00:00Z"));
        event2.setEventType(EconomicEventType.SWAP_SELL);
        event2.setAssetSymbol("ETH");
        event2.setAssetContract("0x0000000000000000000000000000000000000000");
        event2.setQuantityDelta(new BigDecimal("-0.5"));
        event2.setPriceUsd(new BigDecimal("2600"));
        event2.setPriceSource(PriceSource.SWAP_DERIVED);
        event2.setTotalValueUsd(new BigDecimal("1300"));
        event2.setGasCostUsd(BigDecimal.ZERO);
        event2.setGasIncludedInBasis(false);

        EconomicEvent second = store.upsert(event2);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getWalletAddress()).isEqualTo("0xwallet2");
        assertThat(second.getEventType()).isEqualTo(EconomicEventType.SWAP_SELL);

        assertThat(repository.findByTxHashAndNetworkId("0xidem123", NetworkId.ETHEREUM)).isPresent();
    }

    @Test
    @DisplayName("MANUAL_COMPENSATING with same clientId returns existing event (idempotent)")
    void manualCompensating_sameClientId_idempotent() {
        EconomicEvent manual = new EconomicEvent();
        manual.setTxHash(null);
        manual.setNetworkId(NetworkId.ETHEREUM);
        manual.setWalletAddress("0xwallet");
        manual.setBlockTimestamp(Instant.now());
        manual.setEventType(EconomicEventType.MANUAL_COMPENSATING);
        manual.setAssetSymbol("USDC");
        manual.setAssetContract("0xusdc");
        manual.setQuantityDelta(new BigDecimal("100"));
        manual.setPriceUsd(new BigDecimal("1"));
        manual.setPriceSource(PriceSource.MANUAL);
        manual.setTotalValueUsd(new BigDecimal("100"));
        manual.setGasCostUsd(BigDecimal.ZERO);
        manual.setGasIncludedInBasis(false);
        manual.setClientId("client-uuid-456");

        EconomicEvent first = store.upsert(manual);
        assertThat(first.getId()).isNotNull();

        EconomicEvent manual2 = new EconomicEvent();
        manual2.setTxHash(null);
        manual2.setNetworkId(NetworkId.ARBITRUM);
        manual2.setWalletAddress("0xother");
        manual2.setBlockTimestamp(Instant.now());
        manual2.setEventType(EconomicEventType.MANUAL_COMPENSATING);
        manual2.setAssetSymbol("USDC");
        manual2.setClientId("client-uuid-456");

        EconomicEvent second = store.upsert(manual2);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getWalletAddress()).isEqualTo("0xwallet");

        assertThat(repository.findByClientId("client-uuid-456")).isPresent();
    }
}
