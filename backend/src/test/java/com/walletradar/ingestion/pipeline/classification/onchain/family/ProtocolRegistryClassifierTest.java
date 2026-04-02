package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LendingRegistryClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String AAVE_POOL = "0x794a61358d6845594f94dc1db02a252b5b4814ad";

    @Test
    @DisplayName("resource markers can classify lending pool tx before direct method fallback")
    void resourceMarkersClassifyLendingPoolBeforeDirectMethodFallback() {
        ProtocolRegistryService protocolRegistryService = mock(ProtocolRegistryService.class);
        when(protocolRegistryService.lookup(NetworkId.AVALANCHE, AAVE_POOL))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        AAVE_POOL,
                        Set.of(NetworkId.AVALANCHE),
                        ProtocolRegistryFamily.LENDING,
                        ProtocolRegistryRole.POOL,
                        ProtocolRegistryEventType.LENDING_DEPOSIT,
                        ConfidenceLevel.HIGH,
                        "Aave",
                        "V3",
                        false,
                        null
                )));

        ProtocolResourceCatalog protocolResourceCatalog = (protocolName, protocolVersion) -> Optional.of(
                new ProtocolResourceDefinition(
                        "aave",
                        "Aave",
                        "v3",
                        List.of("Aave", "Aave V3"),
                        List.of("BORROW_REPAY"),
                        List.of("LENDING"),
                        List.of("FULL_RECEIPT"),
                        new ProtocolResourceDefinition.ProtocolResourceMarkers(
                                Map.of("lendingDeposit", List.of("0xfeedbeef")),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of()
                        )
                )
        );

        LendingRegistryClassifier classifier = new LendingRegistryClassifier(
                protocolRegistryService,
                protocolResourceCatalog
        );

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xabc")
                .setNetworkId(NetworkId.AVALANCHE.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", AAVE_POOL)
                        .append("from", WALLET)
                        .append("methodId", "0xfeedbeef")
                        .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));

        OnChainClassificationContext context = new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of()
        );

        Optional<ClassificationDecision> result = classifier.classify(context);

        assertThat(result)
                .isPresent()
                .get()
                .satisfies(decision -> {
                    assertThat(decision.type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
                    assertThat(decision.classifiedBy()).isEqualTo(ClassificationSource.PROTOCOL_REGISTRY);
                    assertThat(decision.protocolName()).isEqualTo("Aave");
                });
    }
}
