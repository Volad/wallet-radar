package com.walletradar.costbasis.engine;

import com.walletradar.domain.accounting.AssetPosition;
import com.walletradar.domain.accounting.AssetPositionRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
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

@SpringBootTest(properties = {
        "walletradar.ingestion.network.ETHEREUM.urls[0]=https://eth.llamarpc.com",
        "walletradar.ingestion.network.ETHEREUM.batch-block-size=2000"
})
@Testcontainers
class AvcoEngineIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    AvcoEngine avcoEngine;
    @Autowired
    NormalizedTransactionRepository normalizedTransactionRepository;
    @Autowired
    AssetPositionRepository assetPositionRepository;

    @Test
    @DisplayName("persist confirmed normalized tx then run engine then verify positions")
    void persistNormalized_runEngine_verifyPositions() {
        String wallet = "0xintWallet";
        String assetContract = "0x0000000000000000000000000000000000000000";
        String assetSymbol = "ETH";

        NormalizedTransaction buy = tx("0xtx1", wallet, Instant.parse("2025-01-01T10:00:00Z"),
                leg(NormalizedLegRole.BUY, assetSymbol, assetContract, new BigDecimal("3"), new BigDecimal("2000"), 1));
        NormalizedTransaction sell = tx("0xtx2", wallet, Instant.parse("2025-01-02T10:00:00Z"),
                leg(NormalizedLegRole.SELL, assetSymbol, assetContract, new BigDecimal("-1"), new BigDecimal("2500"), 1));

        normalizedTransactionRepository.saveAll(List.of(buy, sell));

        avcoEngine.replayFromBeginning(wallet, NetworkId.ETHEREUM, assetContract);

        AssetPosition position = assetPositionRepository
                .findByWalletAddressAndNetworkIdAndAssetContract(wallet, "ETHEREUM", assetContract)
                .orElseThrow();
        assertThat(position.getQuantity()).isEqualByComparingTo("2");
        assertThat(position.getPerWalletAvco()).isEqualByComparingTo("2000");
        assertThat(position.getTotalCostBasisUsd()).isEqualByComparingTo("4000");
        assertThat(position.getTotalRealisedPnlUsd()).isEqualByComparingTo("500"); // (2500-2000)*1
        assertThat(position.isHasIncompleteHistory()).isFalse();

        NormalizedTransaction sellReloaded = normalizedTransactionRepository.findById(sell.getId()).orElseThrow();
        assertThat(sellReloaded.getFlows().get(0).getAvcoAtTimeOfSale()).isEqualByComparingTo("2000");
        assertThat(sellReloaded.getFlows().get(0).getRealisedPnlUsd()).isEqualByComparingTo("500");
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
