package com.walletradar.config;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.common.PriceSource;
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
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xabc123");
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-01-15T10:00:00Z"));
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setConfidence(new BigDecimal("0.95"));

        NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
        leg.setRole(NormalizedLegRole.BUY);
        leg.setAssetSymbol("ETH");
        leg.setAssetContract("0x0000000000000000000000000000000000000000");
        leg.setQuantityDelta(new BigDecimal("1.5"));
        leg.setUnitPriceUsd(new BigDecimal("2500.123456789"));
        leg.setPriceSource(PriceSource.SWAP_DERIVED);
        leg.setValueUsd(new BigDecimal("3750.1851851835"));
        leg.setRealisedPnlUsd(new BigDecimal("2.50"));
        tx.setFlows(java.util.List.of(leg));

        mongoTemplate.save(tx, "normalized_transactions");
        assertThat(tx.getId()).isNotNull();

        NormalizedTransaction read = mongoTemplate.findById(tx.getId(), NormalizedTransaction.class, "normalized_transactions");
        assertThat(read).isNotNull();
        assertThat(read.getFlows()).hasSize(1);
        assertThat(read.getFlows().get(0).getQuantityDelta()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(read.getFlows().get(0).getUnitPriceUsd()).isEqualByComparingTo(new BigDecimal("2500.123456789"));
        assertThat(read.getFlows().get(0).getValueUsd()).isEqualByComparingTo(new BigDecimal("3750.1851851835"));
        assertThat(read.getFlows().get(0).getRealisedPnlUsd()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(read.getNetworkId()).isEqualTo(NetworkId.ETHEREUM);
    }
}
