package com.walletradar.api.controller;

import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.job.BackfillJobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-009, T-023 DoD: POST wallets → 202; GET status; POST sync/refresh → 202.
 */
@SpringBootTest(properties = {
        "walletradar.ingestion.backfill.window-blocks=1",
        "walletradar.ingestion.network.ETHEREUM.urls[0]=https://eth.llamarpc.com"
})
@AutoConfigureWebTestClient
@Testcontainers
class WalletAndSyncIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    WebTestClient webTestClient;
    @Autowired
    SyncStatusRepository syncStatusRepository;

    @MockBean
    BackfillJobRunner backfillJobRunner;

    @Test
    @DisplayName("POST /wallets returns 202 and GET status returns 200")
    void postWalletsThenGetStatus() {
        String body = """
                {"address":"0x742d35Cc6634C0532925a3b844Bc454e4438f44e","networks":["ETHEREUM"]}
                """;
        webTestClient.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Backfill started")
                .jsonPath("$.syncId").exists();

        webTestClient.get().uri("/api/v1/wallets/0x742d35Cc6634C0532925a3b844Bc454e4438f44e/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.walletAddress").isEqualTo("0x742d35Cc6634C0532925a3b844Bc454e4438f44e")
                .jsonPath("$.networks[0].networkId").isEqualTo("ETHEREUM")
                .jsonPath("$.networks[0].status").exists();
    }

    @Test
    @DisplayName("POST /sync/refresh returns 202")
    void postSyncRefresh() {
        String body = """
                {"wallets":["0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"],"networks":["ETHEREUM","ARBITRUM"]}
                """;
        webTestClient.post().uri("/api/v1/sync/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Incremental sync triggered");
    }

    @Test
    @DisplayName("GET status with invalid address returns 404 when no data")
    void getStatusNotFoundWhenNoSyncStatus() {
        webTestClient.get().uri("/api/v1/wallets/0x0000000000000000000000000000000000000001/status")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /wallets with invalid address returns 400")
    void postWalletsInvalidAddress() {
        String body = """
                {"address":"invalid","networks":["ETHEREUM"]}
                """;
        webTestClient.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_ADDRESS");
    }
}
