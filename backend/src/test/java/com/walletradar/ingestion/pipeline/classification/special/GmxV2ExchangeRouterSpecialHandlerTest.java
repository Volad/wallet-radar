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

class GmxV2ExchangeRouterSpecialHandlerTest {

    private final GmxV2ExchangeRouterSpecialHandler handler = new GmxV2ExchangeRouterSpecialHandler();

    @Test
    @DisplayName("createOrder is not classified by the generic special handler")
    void createOrderIsNotClassifiedByGenericSpecialHandler() {
        SpecialHandlerResult result = handler.classify(entry(), view("0x0ad58d2f", "createOrder(CreateOrderParams)"), legs());
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.missingDataReasons()).contains("HANDLER_UNSUPPORTED_METHOD");
    }

    @Test
    @DisplayName("createDeposit becomes LP_ENTRY")
    void createDepositBecomesLpEntry() {
        assertType("0x2e7eff49", "createDeposit(CreateDepositParams)", NormalizedTransactionType.LP_ENTRY);
    }

    @Test
    @DisplayName("createWithdrawal becomes LP_EXIT")
    void createWithdrawalBecomesLpExit() {
        assertType("0x87d66368", "createWithdrawal(CreateWithdrawalParams)", NormalizedTransactionType.LP_EXIT);
    }

    private void assertType(String methodId, String functionName, NormalizedTransactionType expectedType) {
        SpecialHandlerResult result = handler.classify(entry(), view(methodId, functionName), legs());
        assertThat(result.type()).isEqualTo(expectedType);
    }

    private ProtocolRegistryEntry entry() {
        return new ProtocolRegistryEntry(
                "0x7c68c7866a64fa2160f78eeae12217ffbf871fa8",
                Set.of(NetworkId.ARBITRUM),
                ProtocolRegistryFamily.PERP,
                ProtocolRegistryRole.EXCHANGE_ROUTER,
                null,
                ConfidenceLevel.HIGH,
                "GMX",
                "V2.2",
                true,
                ProtocolRegistrySpecialHandlerType.GMX_V2_EXCHANGE_ROUTER
        );
    }

    private OnChainRawTransactionView view(String methodId, String functionName) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId("ARBITRUM");
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
                RawLeg.asset("0xtoken", "USDC", new BigDecimal("-1000.0")),
                RawLeg.fee("ETH", new BigDecimal("-0.001"))
        );
    }
}
