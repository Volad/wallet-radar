package com.walletradar.api.controller;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.NormalizedLegRole;
import com.walletradar.domain.NormalizedTransaction;
import com.walletradar.domain.NormalizedTransactionRepository;
import com.walletradar.domain.NormalizedTransactionStatus;
import com.walletradar.domain.NormalizedTransactionType;
import com.walletradar.domain.PriceSource;
import com.walletradar.ingestion.job.backfill.BackfillJobRunner;
import com.walletradar.ingestion.sync.balance.BalanceRefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@SpringBootTest(properties = {
        "walletradar.ingestion.network.ETHEREUM.urls[0]=https://eth.llamarpc.com",
        "walletradar.ingestion.network.ETHEREUM.batch-block-size=2000"
})
@AutoConfigureWebTestClient
@Testcontainers
class TransactionControllerIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    WebTestClient webTestClient;
    @Autowired
    NormalizedTransactionRepository normalizedTransactionRepository;

    @MockBean
    BackfillJobRunner backfillJobRunner;
    @MockBean
    BalanceRefreshService balanceRefreshService;

    @Test
    @DisplayName("history endpoint returns only CONFIRMED normalized transactions by default")
    void returnsOnlyConfirmedByDefault() {
        normalizedTransactionRepository.saveAll(List.of(
                tx("0xconfirmed", NormalizedTransactionStatus.CONFIRMED, Instant.parse("2025-01-02T10:00:00Z"), new BigDecimal("-16")),
                tx("0xpending", NormalizedTransactionStatus.PENDING_PRICE, Instant.parse("2025-01-03T10:00:00Z"), new BigDecimal("-20"))
        ));

        webTestClient.get()
                .uri("/api/v1/assets/USDC/transactions?limit=10&direction=DESC")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].txHash").isEqualTo("0xconfirmed")
                .jsonPath("$.items[0].status").isEqualTo("CONFIRMED");
    }

    private static NormalizedTransaction tx(String txHash, NormalizedTransactionStatus status, Instant ts, BigDecimal qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(txHash);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(ts);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(status);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());

        NormalizedTransaction.Leg leg = new NormalizedTransaction.Leg();
        leg.setRole(NormalizedLegRole.SELL);
        leg.setAssetContract("0xaf88");
        leg.setAssetSymbol("USDC");
        leg.setQuantityDelta(qty);
        leg.setUnitPriceUsd(BigDecimal.ONE);
        leg.setValueUsd(qty.abs());
        leg.setPriceSource(PriceSource.STABLECOIN);
        tx.setLegs(List.of(leg));
        return tx;
    }
}
