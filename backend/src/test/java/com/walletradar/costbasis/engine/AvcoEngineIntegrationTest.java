package com.walletradar.costbasis.engine;

import com.walletradar.domain.AssetPosition;
import com.walletradar.domain.AssetPositionRepository;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.NetworkId;
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
    EconomicEventRepository economicEventRepository;
    @Autowired
    AssetPositionRepository assetPositionRepository;

    @Test
    @DisplayName("persist events then run engine then verify positions")
    void persistEvents_runEngine_verifyPositions() {
        String wallet = "0xintWallet";
        String assetContract = "0x0000000000000000000000000000000000000000";
        String assetSymbol = "ETH";

        EconomicEvent buy = new EconomicEvent();
        buy.setTxHash("0xtx1");
        buy.setNetworkId(NetworkId.ETHEREUM);
        buy.setWalletAddress(wallet);
        buy.setBlockTimestamp(Instant.parse("2025-01-01T10:00:00Z"));
        buy.setEventType(EconomicEventType.SWAP_BUY);
        buy.setAssetSymbol(assetSymbol);
        buy.setAssetContract(assetContract);
        buy.setQuantityDelta(new BigDecimal("3"));
        buy.setPriceUsd(new BigDecimal("2000"));
        buy.setTotalValueUsd(new BigDecimal("6000"));
        buy.setGasCostUsd(BigDecimal.ZERO);
        buy.setGasIncludedInBasis(true);

        EconomicEvent sell = new EconomicEvent();
        sell.setTxHash("0xtx2");
        sell.setNetworkId(NetworkId.ETHEREUM);
        sell.setWalletAddress(wallet);
        sell.setBlockTimestamp(Instant.parse("2025-01-02T10:00:00Z"));
        sell.setEventType(EconomicEventType.SWAP_SELL);
        sell.setAssetSymbol(assetSymbol);
        sell.setAssetContract(assetContract);
        sell.setQuantityDelta(new BigDecimal("-1"));
        sell.setPriceUsd(new BigDecimal("2500"));
        sell.setTotalValueUsd(new BigDecimal("2500"));
        sell.setGasCostUsd(BigDecimal.ZERO);
        sell.setGasIncludedInBasis(false);

        economicEventRepository.saveAll(List.of(buy, sell));

        avcoEngine.replayFromBeginning(wallet, NetworkId.ETHEREUM, assetContract);

        AssetPosition position = assetPositionRepository
                .findByWalletAddressAndNetworkIdAndAssetContract(wallet, "ETHEREUM", assetContract)
                .orElseThrow();
        assertThat(position.getQuantity()).isEqualByComparingTo("2");
        assertThat(position.getPerWalletAvco()).isEqualByComparingTo("2000");
        assertThat(position.getTotalCostBasisUsd()).isEqualByComparingTo("4000");
        assertThat(position.getTotalRealisedPnlUsd()).isEqualByComparingTo("500"); // (2500-2000)*1
        assertThat(position.isHasIncompleteHistory()).isFalse();

        EconomicEvent sellReloaded = economicEventRepository.findAll().stream()
                .filter(e -> "0xtx2".equals(e.getTxHash()))
                .findFirst()
                .orElseThrow();
        assertThat(sellReloaded.getAvcoAtTimeOfSale()).isEqualByComparingTo("2000");
        assertThat(sellReloaded.getRealisedPnlUsd()).isEqualByComparingTo("500");
    }
}
