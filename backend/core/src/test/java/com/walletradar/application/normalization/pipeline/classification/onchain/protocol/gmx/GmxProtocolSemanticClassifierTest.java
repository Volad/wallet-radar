package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.gmx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryEventType;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.application.normalization.pipeline.classification.support.GmxEventTopicSupport;
import com.walletradar.application.normalization.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GmxProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    private final ProtocolRegistryService protocolRegistryService = mock(ProtocolRegistryService.class);
    private final GmxProtocolSemanticClassifier classifier = new GmxProtocolSemanticClassifier(
            protocolRegistryService,
            new ProtocolResourceLoader(new ObjectMapper())
    );
    private final MovementLegExtractor movementLegExtractor = new MovementLegExtractor(new NativeAssetSymbolResolver(NetworkTestFixtures.registry()));

    @Test
    void gmxOrderRequestEmitsDerivativeOrderRequestHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7");
        rawTransaction.getRawData().put("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d800000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000003000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000000000018000000000000000000000000000000000000000000000000000000000000000447d39aaf100000000000000000000000031ef83a530fde1b38ee9a18093a333d8bbbc40d5000000000000000000000000000000000000000000000000000033e67d14a680000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000064e6d66ac8000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000031ef83a530fde1b38ee9a18093a333d8bbbc40d500000000000000000000000000000000000000000000000000000000007a12000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003046996807b000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000001e000000000000000000000000000000000000011449b266f7daf4020a0de846000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006dec442830813000000000000000000000000000000000000000000000000000033e67d14a680000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000111111111111111111111111111111111111111100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000ff0000000000000000000000000000000000000100000000000000000000000070d95587d40a2caf56bd97485ab3eec10bee6336000000000000000000000000af88d065e77c8cc2239327c5edb3a432268e583100000000000000000000000000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x31eF83a530Fde1B38EE9A18093A333D8Bbbc40D5")
                        .append("value", "8000000")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        "0x01",
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x4f7264657243726561746564")
                ))));

        when(protocolRegistryService.lookup(eq(NetworkId.ARBITRUM), eq("0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5")))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5",
                        Set.of(NetworkId.ARBITRUM),
                        ProtocolRegistryFamily.PERP,
                        ProtocolRegistryRole.ORDER_VAULT,
                        ProtocolRegistryEventType.PROTOCOL_CUSTODY_DEPOSIT,
                        ConfidenceLevel.HIGH,
                        "GMX",
                        "V2",
                        false,
                        null
                )));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(GmxProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(GmxProtocolSemanticClassifier.SEMANTIC_DERIVATIVE_ORDER_REQUEST);
            assertThat(hint.correlationId()).isEqualTo("0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106");
        });
    }

    @Test
    void clarifiedExecuteOrderEmitsDerivativePositionIncreaseHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7");
        rawTransaction.getRawData().put("from", "0x3333333333333333333333333333333333333333");
        rawTransaction.getRawData().put("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336");
        rawTransaction.getRawData().put("methodId", "0x7ebc83f7");
        rawTransaction.getRawData().put("functionName", "executeOrder(bytes32 key,tuple oracleParams)");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        GmxEventTopicSupport.topicHash("OrderExecuted"),
                                        "0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                                .append("data", "0x"),
                        new Document("address", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("topics", List.of(
                                        "0x137a44067c8961cd7e1d876f4754a5a3a75989b4552f1843fc69c3b372def160"
                                ))
                                .append("data", "0x506f736974696f6e496e637265617365")
                ))));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(GmxProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(GmxProtocolSemanticClassifier.SEMANTIC_DERIVATIVE_POSITION_INCREASE);
            assertThat(hint.correlationId()).isEqualTo("0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106");
        });
    }

    @Test
    void gmxDepositRequestEmitsLpEntryRequestHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28");
        rawTransaction.getRawData().put("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41");
        rawTransaction.getRawData().put("methodId", "0xac9650d8");
        rawTransaction.getRawData().put("functionName", "multicall(bytes[] data)");
        rawTransaction.getRawData().put("input", "0xac9650d8000000007d39aaf100000000e6d66ac8000000004c82aa41");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenDecimal", "6")
                        .append("from", WALLET)
                        .append("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                        .append("value", "1000000000")
        )).append("internalTransfers", List.of()));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(GmxProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(GmxProtocolSemanticClassifier.SEMANTIC_LP_ENTRY_REQUEST);
            assertThat(hint.correlationId()).isNull();
        });
    }

    @Test
    void gmxGlvDepositSettlementEmitsLpEntrySettlementHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0x9fab1650749416a4fcf94f02cf16abd99b80f3ec1f1a18851c6f891a21391579");
        rawTransaction.getRawData().put("from", "0x3333333333333333333333333333333333333333");
        rawTransaction.getRawData().put("to", "0x7eadee3f226a0d2be54a333a2588cc94292ffd7f");
        rawTransaction.getRawData().put("methodId", "0x5ee8ec8f");
        rawTransaction.getRawData().put("functionName", "executeGlvDeposit(bytes32 key,tuple oracleParams)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xca9feeefa77f6e76087de229da0e0bd3f2b6d2bc")
                        .append("tokenSymbol", "GLV [WETH-USDC]")
                        .append("tokenDecimal", "18")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "8047029766125422803")
        )).append("internalTransfers", List.of()));
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                .append("topics", List.of(
                                        GmxEventTopicSupport.EVENT_EMITTER_TOPIC,
                                        "0x168af62e3da2e23e63dfeb41b97ea0feef3c7a45e72ebc59e924f19ae915f14e",
                                        "0xcae9309eacbae0ae8fb295bce2293b08c0b0c80624f60b2929d14a4a0176ff6f",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111"
                                ))
                ))));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(GmxProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(GmxProtocolSemanticClassifier.SEMANTIC_LP_ENTRY_SETTLEMENT);
            assertThat(hint.correlationId()).isEqualTo("0xcae9309eacbae0ae8fb295bce2293b08c0b0c80624f60b2929d14a4a0176ff6f");
        });
    }

    @Test
    void exchangeRouterCreateDepositEmitsLpEntryHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0xexchange1");
        rawTransaction.getRawData().put("to", "0x7c68c7866a64fa2160f78eeae12217ffbf871fa8");
        rawTransaction.getRawData().put("methodId", "0x2e7eff49");
        rawTransaction.getRawData().put("functionName", "createDeposit(CreateDepositParams)");

        List<ProtocolSemanticHint> hints = classifier.classify(context(
                rawTransaction,
                new ProtocolDiscoveryResult(List.of(new ProtocolMatch(
                        "GMX",
                        "V2.2",
                        ProtocolRegistryFamily.PERP,
                        ProtocolRegistryRole.EXCHANGE_ROUTER,
                        ConfidenceLevel.HIGH,
                        "0x7c68c7866a64fa2160f78eeae12217ffbf871fa8",
                        "0x7c68c7866a64fa2160f78eeae12217ffbf871fa8",
                        "TO_ADDRESS",
                        null,
                        ProtocolRegistrySpecialHandlerType.GMX_V2_EXCHANGE_ROUTER
                )), null)
        ));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.semanticType()).isEqualTo(GmxProtocolSemanticClassifier.SEMANTIC_LP_ENTRY_REQUEST);
            assertThat(hint.suggestedType()).isEqualTo(com.walletradar.domain.transaction.normalized.NormalizedTransactionType.LP_ENTRY);
        });
    }

    private ProtocolSemanticContext context(RawTransaction rawTransaction) {
        return context(rawTransaction, ProtocolDiscoveryResult.empty());
    }

    private ProtocolSemanticContext context(
            RawTransaction rawTransaction,
            ProtocolDiscoveryResult discoveryResult
    ) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<RawLeg> movementLegs = movementLegExtractor.extract(view);
        return new ProtocolSemanticContext(view, discoveryResult, movementLegs);
    }

    private RawTransaction baseRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return rawTransaction;
    }
}
