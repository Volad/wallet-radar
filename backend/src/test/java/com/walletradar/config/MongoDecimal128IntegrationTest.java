package com.walletradar.config;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
@Testcontainers
@Import(MongoConfig.class)
class MongoDecimal128IntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    MongoTemplate mongoTemplate;

    @Test
    @DisplayName("persist and read document with Decimal128 monetary fields")
    void persistAndReadWithDecimal128() {
        EconomicEvent event = new EconomicEvent();
        event.setTxHash("0xabc123");
        event.setNetworkId(NetworkId.ETHEREUM);
        event.setWalletAddress("0xwallet");
        event.setBlockTimestamp(Instant.parse("2025-01-15T10:00:00Z"));
        event.setEventType(EconomicEventType.SWAP_BUY);
        event.setAssetSymbol("ETH");
        event.setAssetContract("0x0000000000000000000000000000000000000000");
        event.setQuantityDelta(new BigDecimal("1.5"));
        event.setPriceUsd(new BigDecimal("2500.123456789"));
        event.setPriceSource(PriceSource.SWAP_DERIVED);
        event.setTotalValueUsd(new BigDecimal("3750.1851851835"));
        event.setGasCostUsd(new BigDecimal("2.50"));
        event.setGasIncludedInBasis(true);

        mongoTemplate.save(event, "economic_events");
        assertThat(event.getId()).isNotNull();

        EconomicEvent read = mongoTemplate.findById(event.getId(), EconomicEvent.class, "economic_events");
        assertThat(read).isNotNull();
        assertThat(read.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(read.getPriceUsd()).isEqualByComparingTo(new BigDecimal("2500.123456789"));
        assertThat(read.getTotalValueUsd()).isEqualByComparingTo(new BigDecimal("3750.1851851835"));
        assertThat(read.getGasCostUsd()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(read.getNetworkId()).isEqualTo(NetworkId.ETHEREUM);
    }
}
