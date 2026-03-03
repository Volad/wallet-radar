package com.walletradar.costbasis.override;

import com.walletradar.costbasis.engine.AvcoEngine;
import com.walletradar.domain.accounting.AssetPosition;
import com.walletradar.domain.accounting.AssetPositionRepository;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.accounting.RecalcJob;
import com.walletradar.domain.accounting.RecalcJobRepository;
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
 * Override + async recalc integration on canonical normalized transactions.
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
    NormalizedTransactionRepository normalizedTransactionRepository;
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

        NormalizedTransaction buy = tx("tx-ov-1", wallet, Instant.parse("2025-01-01T10:00:00Z"),
                leg(NormalizedLegRole.BUY, assetSymbol, assetContract, new BigDecimal("100"), new BigDecimal("0.99"), 1));
        NormalizedTransaction sell = tx("tx-ov-2", wallet, Instant.parse("2025-01-02T10:00:00Z"),
                leg(NormalizedLegRole.SELL, assetSymbol, assetContract, new BigDecimal("-50"), new BigDecimal("1.01"), 1));

        normalizedTransactionRepository.saveAll(List.of(buy, sell));
        String buyEventId = buy.getId() + ":0";

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
        assertThat(costBasisOverrideRepository.findByNormalizedLegIdAndActiveTrue(buyEventId)).isPresent();

        RecalcJob job = pollUntilComplete(jobId, 120);
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

    private static NormalizedTransaction tx(String txHash, String wallet, Instant timestamp, NormalizedTransaction.Flow leg) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(txHash);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setWalletAddress(wallet);
        tx.setBlockTimestamp(timestamp);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setFlows(List.of(leg));
        return tx;
    }

    private static NormalizedTransaction.Flow leg(
            NormalizedLegRole role,
            String symbol,
            String contract,
            BigDecimal qty,
            BigDecimal price,
            Integer logIndex
    ) {
        NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
        leg.setRole(role);
        leg.setAssetSymbol(symbol);
        leg.setAssetContract(contract);
        leg.setQuantityDelta(qty);
        leg.setUnitPriceUsd(price);
        leg.setLogIndex(logIndex);
        return leg;
    }
}
