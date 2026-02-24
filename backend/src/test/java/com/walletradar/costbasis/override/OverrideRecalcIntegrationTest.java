package com.walletradar.costbasis.override;

import com.walletradar.costbasis.engine.AvcoEngine;
import com.walletradar.domain.AssetPosition;
import com.walletradar.domain.AssetPositionRepository;
import com.walletradar.domain.CostBasisOverrideRepository;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RecalcJob;
import com.walletradar.domain.RecalcJobRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-017 DoD: integration test — PUT override → poll recalc status → COMPLETE and positions updated.
 */
@SpringBootTest(properties = {
        "walletradar.ingestion.network.ETHEREUM.urls[0]=https://eth.llamarpc.com",
        "walletradar.ingestion.network.ETHEREUM.batch-block-size=2000"
})
@Testcontainers
class OverrideRecalcIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    OverrideService overrideService;
    @Autowired
    AvcoEngine avcoEngine;
    @Autowired
    EconomicEventRepository economicEventRepository;
    @Autowired
    CostBasisOverrideRepository costBasisOverrideRepository;
    @Autowired
    RecalcJobRepository recalcJobRepository;
    @Autowired
    AssetPositionRepository assetPositionRepository;

    @Test
    @DisplayName("PUT override → poll recalc status → COMPLETE and positions updated")
    void putOverride_pollRecalc_completeAndPositionsUpdated() {
        String wallet = "0xoverrideWallet";
        String assetContract = "0x0000000000000000000000000000000000000001";
        String assetSymbol = "USDC";

        EconomicEvent buy = new EconomicEvent();
        buy.setTxHash("0xtxOverride1");
        buy.setNetworkId(NetworkId.ETHEREUM);
        buy.setWalletAddress(wallet);
        buy.setBlockTimestamp(Instant.parse("2025-01-01T10:00:00Z"));
        buy.setEventType(EconomicEventType.SWAP_BUY);
        buy.setAssetSymbol(assetSymbol);
        buy.setAssetContract(assetContract);
        buy.setQuantityDelta(new BigDecimal("100"));
        buy.setPriceUsd(new BigDecimal("0.99"));
        buy.setTotalValueUsd(new BigDecimal("99"));
        buy.setGasCostUsd(BigDecimal.ZERO);
        buy.setGasIncludedInBasis(true);

        EconomicEvent sell = new EconomicEvent();
        sell.setTxHash("0xtxOverride2");
        sell.setNetworkId(NetworkId.ETHEREUM);
        sell.setWalletAddress(wallet);
        sell.setBlockTimestamp(Instant.parse("2025-01-02T10:00:00Z"));
        sell.setEventType(EconomicEventType.SWAP_SELL);
        sell.setAssetSymbol(assetSymbol);
        sell.setAssetContract(assetContract);
        sell.setQuantityDelta(new BigDecimal("-50"));
        sell.setPriceUsd(new BigDecimal("1.01"));
        sell.setTotalValueUsd(new BigDecimal("50.5"));
        sell.setGasCostUsd(BigDecimal.ZERO);
        sell.setGasIncludedInBasis(false);

        economicEventRepository.saveAll(List.of(buy, sell));
        String buyEventId = buy.getId();
        assertThat(buyEventId).isNotNull();

        // Initial AVCO: 0.99, after sell remaining 50 @ 0.99, realisedPnl = (1.01-0.99)*50 = 1.00
        avcoEngine.replayFromBeginning(wallet, NetworkId.ETHEREUM, assetContract);

        AssetPosition beforeOverride = assetPositionRepository
                .findByWalletAddressAndNetworkIdAndAssetContract(wallet, "ETHEREUM", assetContract)
                .orElseThrow();
        assertThat(beforeOverride.getPerWalletAvco()).isEqualByComparingTo("0.99");
        assertThat(beforeOverride.getTotalRealisedPnlUsd()).isEqualByComparingTo("1.00");

        // Override BUY price to 1.00 → new AVCO 1.00, realisedPnl = (1.01-1.00)*50 = 0.50
        String jobId = overrideService.setOverride(buyEventId, new BigDecimal("1.00"), "Override to 1.00");

        assertThat(jobId).isNotNull();
        assertThat(costBasisOverrideRepository.findByEconomicEventIdAndActiveTrue(buyEventId)).isPresent();

        // Poll until COMPLETE (async recalc runs on recalc-executor)
        RecalcJob job = pollUntilComplete(jobId, 100);
        assertThat(job.getStatus()).isEqualTo(RecalcJob.RecalcStatus.COMPLETE);
        assertThat(job.getNewPerWalletAvco()).isEqualByComparingTo("1.00");
        assertThat(job.getCompletedAt()).isNotNull();

        AssetPosition afterOverride = assetPositionRepository
                .findByWalletAddressAndNetworkIdAndAssetContract(wallet, "ETHEREUM", assetContract)
                .orElseThrow();
        assertThat(afterOverride.getPerWalletAvco()).isEqualByComparingTo("1.00");
        assertThat(afterOverride.getTotalRealisedPnlUsd()).isEqualByComparingTo("0.50");
    }

    private RecalcJob pollUntilComplete(String jobId, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            RecalcJob job = recalcJobRepository.findById(jobId).orElse(null);
            if (job != null && (job.getStatus() == RecalcJob.RecalcStatus.COMPLETE || job.getStatus() == RecalcJob.RecalcStatus.FAILED)) {
                return job;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("RecalcJob " + jobId + " did not complete within " + maxAttempts + " attempts");
    }
}
