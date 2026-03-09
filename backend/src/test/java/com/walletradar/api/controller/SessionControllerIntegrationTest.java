package com.walletradar.api.controller;

import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.transaction.session.SessionTransactionRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.job.backfill.BackfillJobRunner;
import com.walletradar.ingestion.sync.balance.BalanceRefreshService;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "walletradar.ingestion.backfill.window-blocks=1",
        "walletradar.ingestion.network.ETHEREUM.urls[0]=https://eth.llamarpc.com"
})
@AutoConfigureWebTestClient
@Testcontainers
class SessionControllerIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private UserSessionRepository userSessionRepository;
    @Autowired
    private SyncStatusRepository syncStatusRepository;
    @Autowired
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Autowired
    private SessionTransactionRepository sessionTransactionRepository;

    @MockBean
    private BackfillJobRunner backfillJobRunner;
    @MockBean
    private BalanceRefreshService balanceRefreshService;

    @BeforeEach
    void cleanCollections() {
        sessionTransactionRepository.deleteAll();
        normalizedTransactionRepository.deleteAll();
        syncStatusRepository.deleteAll();
        userSessionRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /sessions returns 202 and session is readable with backfill status")
    void postSessions_thenGetSessionAndBackfillStatus() {
        String sessionId = "549b0aba-a9af-4789-b125-ebb86314a3f1";
        String body = """
                {
                  "sessionId":"549b0aba-a9af-4789-b125-ebb86314a3f1",
                  "wallets":[
                    {
                      "address":"0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                      "label":"Wallet 1",
                      "color":"#22d3ee",
                      "networks":["ETHEREUM","ARBITRUM"]
                    }
                  ]
                }
                """;

        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(sessionId)
                .jsonPath("$.message").isEqualTo("Session saved, backfill started");

        webTestClient.get().uri("/api/v1/sessions/" + sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(sessionId)
                .jsonPath("$.wallets[0].address").isEqualTo("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                .jsonPath("$.wallets[0].label").isEqualTo("Wallet 1")
                .jsonPath("$.wallets[0].color").isEqualTo("#22d3ee")
                .jsonPath("$.wallets[0].networks.length()").isEqualTo(2);

        webTestClient.get().uri("/api/v1/sessions/" + sessionId + "/backfill-status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(sessionId)
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.overallProgressPct").isEqualTo(0)
                .jsonPath("$.totalTargets").isEqualTo(2)
                .jsonPath("$.wallets[0].networks[0].status").exists();
    }

    @Test
    @DisplayName("POST /sessions with same sessionId replaces persisted wallets")
    void postSessions_replaceBySessionId() {
        String sessionId = "31bd3303-b39d-4842-b92f-a28c8fbf944b";
        String first = """
                {
                  "sessionId":"31bd3303-b39d-4842-b92f-a28c8fbf944b",
                  "wallets":[
                    {
                      "address":"0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                      "label":"Wallet 1",
                      "color":"#22d3ee",
                      "networks":["ETHEREUM"]
                    }
                  ]
                }
                """;
        String second = """
                {
                  "sessionId":"31bd3303-b39d-4842-b92f-a28c8fbf944b",
                  "wallets":[
                    {
                      "address":"0x68bc3b81c853338eaaa21552F57437Dfd7bf5b7f",
                      "label":"Wallet 2",
                      "color":"#34d399",
                      "networks":["ARBITRUM","BASE"]
                    }
                  ]
                }
                """;

        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(first)
                .exchange()
                .expectStatus().isAccepted();

        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(second)
                .exchange()
                .expectStatus().isAccepted();

        webTestClient.get().uri("/api/v1/sessions/" + sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.wallets.length()").isEqualTo(1)
                .jsonPath("$.wallets[0].address").isEqualTo("0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                .jsonPath("$.wallets[0].label").isEqualTo("Wallet 2")
                .jsonPath("$.wallets[0].networks.length()").isEqualTo(2);
    }

    @Test
    @DisplayName("POST /sessions is all-or-nothing when any wallet is invalid")
    void postSessions_invalidOneWallet_returns400AndNoWrite() {
        String body = """
                {
                  "sessionId":"58c1bf5f-cf1e-4062-a355-94b466f4adf0",
                  "wallets":[
                    {
                      "address":"invalid",
                      "label":"Wallet 1",
                      "color":"#22d3ee",
                      "networks":["ETHEREUM"]
                    },
                    {
                      "address":"0x68bc3b81c853338eaaa21552F57437Dfd7bf5b7f",
                      "label":"Wallet 2",
                      "color":"#34d399",
                      "networks":["ARBITRUM"]
                    }
                  ]
                }
                """;

        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_ADDRESS");

        assertThat(userSessionRepository.count()).isEqualTo(0);
        assertThat(syncStatusRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /sessions/{sessionId}/backfill-status aggregates sync_status progress")
    void getBackfillStatus_aggregatesProgress() {
        String sessionId = "e15347b2-f9b8-4db3-bd43-5f3f752c95a7";
        String body = """
                {
                  "sessionId":"e15347b2-f9b8-4db3-bd43-5f3f752c95a7",
                  "wallets":[
                    {
                      "address":"0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                      "label":"Wallet 1",
                      "color":"#22d3ee",
                      "networks":["ETHEREUM","ARBITRUM"]
                    }
                  ]
                }
                """;
        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted();

        List<SyncStatus> statuses = syncStatusRepository.findByWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        assertThat(statuses).hasSize(2);
        for (SyncStatus status : statuses) {
            if ("ETHEREUM".equals(status.getNetworkId())) {
                status.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
                status.setProgressPct(100);
                status.setBackfillComplete(true);
                status.setSyncBannerMessage(null);
            } else {
                status.setStatus(SyncStatus.SyncStatusValue.RUNNING);
                status.setProgressPct(40);
                status.setBackfillComplete(false);
                status.setSyncBannerMessage("Backfill running");
            }
        }
        syncStatusRepository.saveAll(statuses);

        webTestClient.get().uri("/api/v1/sessions/" + sessionId + "/backfill-status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.overallProgressPct").isEqualTo(70)
                .jsonPath("$.completedTargets").isEqualTo(1)
                .jsonPath("$.totalTargets").isEqualTo(2);
    }

    @Test
    @DisplayName("GET /sessions/{sessionId} returns 404 when session does not exist")
    void getSession_notFound() {
        webTestClient.get().uri("/api/v1/sessions/3dc99fbe-f44e-4ede-8c43-f7f4a20ea0e8")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("OPTIONS /sessions preflight is allowed for local frontend origin")
    void optionsSessions_preflightAllowed() {
        WebTestClient corsClient = webTestClient.mutate().baseUrl("http://localhost").build();

        corsClient.options().uri("/api/v1/sessions")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:4200")
                .expectHeader().value("Access-Control-Allow-Methods", methods -> assertThat(methods).contains("POST"));
    }

    @Test
    @DisplayName("POST /sessions/{sessionId}/transactions/rebuild projects confirmed normalized rows")
    void rebuildSessionTransactions_projectsRows() {
        String sessionId = "c29fbf34-4276-4ca5-b815-cc17f1f42034";
        String address = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

        String body = """
                {
                  "sessionId":"c29fbf34-4276-4ca5-b815-cc17f1f42034",
                  "wallets":[
                    {
                      "address":"0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                      "label":"Wallet 1",
                      "color":"#22d3ee",
                      "networks":["BSC"]
                    }
                  ]
                }
                """;
        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted();

        NormalizedTransaction older = confirmedTx(
                "n-1",
                "0xaaa",
                address,
                Instant.parse("2026-03-01T10:00:00Z"),
                BigDecimal.ZERO);
        NormalizedTransaction newer = confirmedTx(
                "n-2",
                "0xbbb",
                address,
                Instant.parse("2026-03-02T10:00:00Z"),
                new BigDecimal("12.34"));
        normalizedTransactionRepository.saveAll(List.of(older, newer));

        webTestClient.post().uri("/api/v1/sessions/" + sessionId + "/transactions/rebuild")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(sessionId)
                .jsonPath("$.projectedTransactions").isEqualTo(2)
                .jsonPath("$.message").isEqualTo("Session transactions rebuilt");

        webTestClient.get().uri("/api/v1/sessions/" + sessionId + "/transactions?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(sessionId)
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].txHash").isEqualTo("0xbbb")
                .jsonPath("$.items[0].realisedPnlUsdTotal").isEqualTo(12.34)
                .jsonPath("$.items[1].txHash").isEqualTo("0xaaa");
    }

    @Test
    @DisplayName("GET /sessions/{sessionId}/transactions returns 404 for unknown session")
    void getSessionTransactions_notFound() {
        webTestClient.get().uri("/api/v1/sessions/6ba96356-484a-4d0d-82f9-46bbf198f818/transactions")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("rebuild marks uniquely paired cross-network transfers as matched bridges")
    void rebuildSessionTransactions_matchesBridgeLifecycle() {
        String sessionId = "f2f0e53e-31df-40d5-8fb2-6fc95d004f89";
        String sharedAddress = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String destinationAddress = "0xf03b52e8686b962e051a6075a06b96cb8a663021";

        String body = """
                {
                  "sessionId":"f2f0e53e-31df-40d5-8fb2-6fc95d004f89",
                  "wallets":[
                    {
                      "address":"0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                      "label":"Wallet 1",
                      "color":"#22d3ee",
                      "networks":["ETHEREUM","ARBITRUM"]
                    },
                    {
                      "address":"0xf03b52e8686b962e051a6075a06b96cb8a663021",
                      "label":"Wallet 2",
                      "color":"#a78bfa",
                      "networks":["ETHEREUM","ARBITRUM"]
                    }
                  ]
                }
                """;
        webTestClient.post().uri("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted();

        NormalizedTransaction bridgeOut = confirmedTransferTx(
                "bridge-out",
                "0xbridgeout",
                sharedAddress,
                NetworkId.ETHEREUM,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                Instant.parse("2026-03-02T10:00:00Z"),
                new BigDecimal("-500"),
                new BigDecimal("-500"));
        NormalizedTransaction bridgeIn = confirmedTransferTx(
                "bridge-in",
                "0xbridgein",
                destinationAddress,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.EXTERNAL_INBOUND,
                Instant.parse("2026-03-02T10:06:00Z"),
                new BigDecimal("499"),
                new BigDecimal("499"));
        normalizedTransactionRepository.saveAll(List.of(bridgeOut, bridgeIn));

        webTestClient.post().uri("/api/v1/sessions/" + sessionId + "/transactions/rebuild")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.projectedTransactions").isEqualTo(2);

        webTestClient.get().uri("/api/v1/sessions/" + sessionId + "/transactions?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].txHash").isEqualTo("0xbridgein")
                .jsonPath("$.items[0].bridgeStatus").isEqualTo("MATCHED")
                .jsonPath("$.items[1].txHash").isEqualTo("0xbridgeout")
                .jsonPath("$.items[1].bridgeStatus").isEqualTo("MATCHED");
    }

    private static NormalizedTransaction confirmedTx(String id, String txHash, String walletAddress,
                                                     Instant blockTimestamp, BigDecimal realisedPnlUsd) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(txHash);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(NetworkId.BSC);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setCreatedAt(blockTimestamp);
        tx.setUpdatedAt(blockTimestamp);

        NormalizedTransaction.Flow sell = new NormalizedTransaction.Flow();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetContract("0xasset");
        sell.setAssetSymbol("USDC");
        sell.setQuantityDelta(new BigDecimal("-1"));
        sell.setRealisedPnlUsd(realisedPnlUsd);
        sell.setLogIndex(7);
        tx.setFlows(List.of(sell));
        return tx;
    }

    private static NormalizedTransaction confirmedTransferTx(
            String id,
            String txHash,
            String walletAddress,
            NetworkId networkId,
            NormalizedTransactionType type,
            Instant blockTimestamp,
            BigDecimal quantityDelta,
            BigDecimal valueUsd
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(txHash);
        tx.setWalletAddress(walletAddress);
        tx.setNetworkId(networkId);
        tx.setBlockTimestamp(blockTimestamp);
        tx.setType(type);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setCreatedAt(blockTimestamp);
        tx.setUpdatedAt(blockTimestamp);

        NormalizedTransaction.Flow transfer = new NormalizedTransaction.Flow();
        transfer.setRole(NormalizedLegRole.TRANSFER);
        transfer.setAssetContract("0xasset");
        transfer.setAssetSymbol("USDC");
        transfer.setQuantityDelta(quantityDelta);
        transfer.setValueUsd(valueUsd);
        transfer.setLogIndex(4);

        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetContract("0xfee");
        fee.setAssetSymbol("ETH");
        fee.setQuantityDelta(new BigDecimal("-0.0001"));
        fee.setLogIndex(5);

        tx.setFlows(List.of(transfer, fee));
        return tx;
    }
}
