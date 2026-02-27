package com.walletradar.config;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.domain.PriceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest(properties = "spring.data.mongodb.auto-index-creation=true")
@Testcontainers
@Import(MongoConfig.class)
class NormalizedTransactionMongoIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    MongoTemplate mongoTemplate;

    @Test
    @DisplayName("persist and read normalized transaction with Decimal128 leg fields")
    void persistAndReadNormalizedTransaction() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xnorm123");
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-10-06T09:11:09Z"));
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());
        tx.setMissingDataReasons(List.of());

        NormalizedTransaction.Leg sell = new NormalizedTransaction.Leg();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetContract("0xaaa");
        sell.setAssetSymbol("USDC");
        sell.setQuantityDelta(new BigDecimal("-16"));
        sell.setUnitPriceUsd(new BigDecimal("1.000000000000000000"));
        sell.setValueUsd(new BigDecimal("16.000000000000000000"));
        sell.setPriceSource(PriceSource.STABLECOIN);

        NormalizedTransaction.Leg buy = new NormalizedTransaction.Leg();
        buy.setRole(NormalizedLegRole.BUY);
        buy.setAssetContract("0xbbb");
        buy.setAssetSymbol("ETH");
        buy.setQuantityDelta(new BigDecimal("0.004"));
        buy.setUnitPriceUsd(new BigDecimal("4000.000000000000000000"));
        buy.setValueUsd(new BigDecimal("16.000000000000000000"));
        buy.setPriceSource(PriceSource.SWAP_DERIVED);

        tx.setLegs(List.of(sell, buy));

        mongoTemplate.save(tx, "normalized_transactions");
        assertThat(tx.getId()).isNotNull();

        NormalizedTransaction read = mongoTemplate.findById(tx.getId(), NormalizedTransaction.class, "normalized_transactions");
        assertThat(read).isNotNull();
        assertThat(read.getLegs()).hasSize(2);
        assertThat(read.getLegs().get(0).getQuantityDelta()).isEqualByComparingTo("-16");
        assertThat(read.getLegs().get(0).getUnitPriceUsd()).isEqualByComparingTo("1.000000000000000000");
        assertThat(read.getLegs().get(1).getQuantityDelta()).isEqualByComparingTo("0.004");
        assertThat(read.getLegs().get(1).getUnitPriceUsd()).isEqualByComparingTo("4000.000000000000000000");
    }

    @Test
    @DisplayName("normalized_transactions indexes are created")
    void indexesCreated() {
        List<IndexInfo> indexes = mongoTemplate.indexOps("normalized_transactions").getIndexInfo();
        List<String> names = indexes.stream().map(IndexInfo::getName).toList();

        assertThat(names).contains("tx_network_wallet_uniq");
        assertThat(names).contains("wallet_network_status_block");
        assertThat(names).contains("legs_asset_contract");
    }
}
