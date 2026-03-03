package com.walletradar.ingestion.store;

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
class IdempotentNormalizedTransactionStoreIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    IdempotentNormalizedTransactionStore store;
    @Autowired
    NormalizedTransactionRepository repository;

    @Test
    @DisplayName("double write same tx+network+wallet results in single normalized transaction")
    void doubleWriteSameTxSingleNormalizedTransaction() {
        NormalizedTransaction first = tx("0xidem-norm", new BigDecimal("-100"), NormalizedTransactionStatus.PENDING_PRICE);
        NormalizedTransaction saved1 = store.upsert(first);
        assertThat(saved1.getId()).isNotNull();

        NormalizedTransaction second = tx("0xidem-norm", new BigDecimal("-120"), NormalizedTransactionStatus.PENDING_PRICE);
        second.setGroupId("LP_POSITION:BASE:0xwallet:435853");
        NormalizedTransaction saved2 = store.upsert(second);

        assertThat(saved2.getId()).isEqualTo(saved1.getId());
        assertThat(saved2.getFlows().get(0).getQuantityDelta()).isEqualByComparingTo("-120");
        assertThat(saved2.getGroupId()).isEqualTo("LP_POSITION:BASE:0xwallet:435853");
        assertThat(repository.findAll()).hasSize(1);
    }

    private static NormalizedTransaction tx(String txHash, BigDecimal qty, NormalizedTransactionStatus status) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(txHash);
        tx.setNetworkId(NetworkId.ETHEREUM);
        tx.setWalletAddress("0xwallet");
        tx.setBlockTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(status);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());

        NormalizedTransaction.Flow leg = new NormalizedTransaction.Flow();
        leg.setRole(NormalizedLegRole.SELL);
        leg.setAssetContract("0xasset");
        leg.setAssetSymbol("ASSET");
        leg.setQuantityDelta(qty);
        tx.setFlows(List.of(leg));
        return tx;
    }
}
