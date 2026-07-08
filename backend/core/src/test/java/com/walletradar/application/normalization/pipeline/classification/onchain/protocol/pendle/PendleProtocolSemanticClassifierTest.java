package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.pendle;

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

class PendleProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0x00000000005bbb0ef59571e58418f9a4357b68a0";

    private final PendleProtocolSemanticClassifier classifier =
            new PendleProtocolSemanticClassifier(new ProtocolResourceLoader(new ObjectMapper()));

    @Test
    void swapMethodEmitsSwapHint() {
        assertType("0x4e7ed11c", "swapExactTokenForPt(...)", NormalizedTransactionType.SWAP);
    }

    @Test
    void addLiquidityMethodEmitsLpEntryHint() {
        assertType("0xb0c7e3f8", "addLiquiditySingleToken(...)", NormalizedTransactionType.LP_ENTRY);
    }

    @Test
    void removeLiquidityMethodEmitsLpExitHint() {
        assertType("0x1ef4b0d8", "removeLiquiditySingleToken(...)", NormalizedTransactionType.LP_EXIT);
    }

    private void assertType(String methodId, String functionName, NormalizedTransactionType expectedType) {
        List<ProtocolSemanticHint> hints = classifier.classify(context(methodId, functionName));
        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.suggestedType()).isEqualTo(expectedType);
            assertThat(hint.protocolName()).isEqualTo("Pendle");
        });
    }

    private ProtocolSemanticContext context(String methodId, String functionName) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("to", ROUTER)
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("methodId", methodId)
                .append("functionName", functionName)
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return new ProtocolSemanticContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                new ProtocolDiscoveryResult(List.of(new ProtocolMatch(
                        "Pendle",
                        "V3",
                        ProtocolRegistryFamily.YIELD,
                        ProtocolRegistryRole.ROUTER,
                        ConfidenceLevel.HIGH,
                        ROUTER,
                        ROUTER,
                        "TO_ADDRESS",
                        null,
                        ProtocolRegistrySpecialHandlerType.PENDLE_ROUTER
                )), null),
                List.of()
        );
    }
}
