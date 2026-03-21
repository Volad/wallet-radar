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

class BalancerVaultSpecialHandlerTest {

    private final BalancerVaultSpecialHandler handler = new BalancerVaultSpecialHandler();

    @Test
    @DisplayName("joinPool becomes LP_ENTRY")
    void joinPoolBecomesLpEntry() {
        SpecialHandlerResult result = handler.classify(entry(), view("joinPool(bytes32,address,address,(address[],uint256[],bytes,bool))"), legs());

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
    }

    @Test
    @DisplayName("exitPool becomes LP_EXIT")
    void exitPoolBecomesLpExit() {
        SpecialHandlerResult result = handler.classify(entry(), view("exitPool(bytes32,address,address,(address[],uint256[],bytes,bool))"), legs());

        assertThat(result.type()).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    private ProtocolRegistryEntry entry() {
        return new ProtocolRegistryEntry(
                "0xba12222222228d8ba445958a75a0704d566bf2c8",
                Set.of(NetworkId.ETHEREUM),
                ProtocolRegistryFamily.DEX,
                ProtocolRegistryRole.VAULT,
                null,
                ConfidenceLevel.HIGH,
                "Balancer",
                "V2",
                true,
                ProtocolRegistrySpecialHandlerType.BALANCER_VAULT
        );
    }

    private OnChainRawTransactionView view(String functionName) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress("0x1111111111111111111111111111111111111111");
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("functionName", functionName));
        return OnChainRawTransactionView.wrap(rawTransaction);
    }

    private List<RawLeg> legs() {
        return List.of(
                RawLeg.asset("0xtoken", "USDC", new BigDecimal("-100.0")),
                RawLeg.asset("0xlp", "BPT", new BigDecimal("99.0"))
        );
    }
}
