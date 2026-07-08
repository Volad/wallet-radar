package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.balancer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BalancerProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String VAULT = "0xba12222222228d8ba445958a75a0704d566bf2c8";

    private final BalancerProtocolSemanticClassifier classifier =
            new BalancerProtocolSemanticClassifier(new ProtocolResourceLoader(new ObjectMapper()));

    @Test
    void joinPoolEmitsLpEntryHint() {
        List<ProtocolSemanticHint> hints = classifier.classify(context("joinPool(bytes32,address,address,(address[],uint256[],bytes,bool))"));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
            assertThat(hint.protocolName()).isEqualTo("Balancer");
            assertThat(hint.protocolVersion()).isEqualTo("V2");
        });
    }

    @Test
    void exitPoolEmitsLpExitHint() {
        List<ProtocolSemanticHint> hints = classifier.classify(context("exitPool(bytes32,address,address,(address[],uint256[],bytes,bool))"));

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.LP_EXIT));
    }

    @Test
    void swapEmitsSwapHint() {
        List<ProtocolSemanticHint> hints = classifier.classify(context("swap((bytes32,uint8,address,address,uint256,bytes),(address,bool,address,bool),uint256,uint256)"));

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.SWAP));
    }

    private ProtocolSemanticContext context(String functionName) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("to", VAULT)
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("functionName", functionName)
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return new ProtocolSemanticContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                new ProtocolDiscoveryResult(List.of(new ProtocolMatch(
                        "Balancer",
                        "V2",
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.VAULT,
                        ConfidenceLevel.HIGH,
                        VAULT,
                        VAULT,
                        "TO_ADDRESS",
                        null,
                        ProtocolRegistrySpecialHandlerType.BALANCER_VAULT
                )), null),
                List.of()
        );
    }
}
