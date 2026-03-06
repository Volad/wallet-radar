package com.walletradar.api.controller;

import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
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

    @MockBean
    private BackfillJobRunner backfillJobRunner;
    @MockBean
    private BalanceRefreshService balanceRefreshService;

    @BeforeEach
    void cleanCollections() {
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
}
