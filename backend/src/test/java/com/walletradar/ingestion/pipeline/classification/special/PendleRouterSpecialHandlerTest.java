package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PendleRouterSpecialHandlerTest {

    private final PendleRouterSpecialHandler handler = new PendleRouterSpecialHandler();

    @Test
    @DisplayName("swap method becomes SWAP")
    void swapMethodBecomesSwap() {
        assertType("0x4e7ed11c", "swapExactTokenForPt(...)", NormalizedTransactionType.SWAP);
    }

    @Test
    @DisplayName("add liquidity method becomes LP_ENTRY")
    void addLiquidityMethodBecomesLpEntry() {
        assertType("0xb0c7e3f8", "addLiquiditySingleToken(...)", NormalizedTransactionType.LP_ENTRY);
    }

    @Test
    @DisplayName("remove liquidity method becomes LP_EXIT")
    void removeLiquidityMethodBecomesLpExit() {
        assertType("0x1ef4b0d8", "removeLiquiditySingleToken(...)", NormalizedTransactionType.LP_EXIT);
    }

    private void assertType(String methodId, String functionName, NormalizedTransactionType expectedType) {
        SpecialHandlerResult result = handler.classify(entry(), view(methodId, functionName), legs());
        assertThat(result.type()).isEqualTo(expectedType);
    }

    private ProtocolRegistryEntry entry() {
        return new ProtocolRegistryEntry(
                "0x00000000005bbb0ef59571e58418f9a4357b68a0",
                Set.of(NetworkId.ETHEREUM),
                ProtocolRegistryFamily.YIELD,
                ProtocolRegistryRole.ROUTER,
                null,
                ConfidenceLevel.HIGH,
                "Pendle",
                "V3",
                true,
                ProtocolRegistrySpecialHandlerType.PENDLE_ROUTER
        );
    }

    private OnChainRawTransactionView view(String methodId, String functionName) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress("0x1111111111111111111111111111111111111111");
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("methodId", methodId)
                .append("functionName", functionName));
        return OnChainRawTransactionView.wrap(rawTransaction);
    }

    private List<RawLeg> legs() {
        return List.of(
                RawLeg.asset("0xtoken", "USDC", new BigDecimal("-100.0")),
                RawLeg.asset("0xpt", "PT", new BigDecimal("95.0"))
        );
    }
}
