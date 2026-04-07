package com.walletradar.ingestion.pipeline.classification.onchain.protocol.cow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CowSwapProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ETH_FLOW = "0xba3cb449bd2b4adddbc894d8697f5170800eadec";
    private static final String GPV2_SETTLEMENT = "0x9008d19f58aabd9ed0d60971565aa8510560ab41";

    private final CowSwapProtocolSemanticClassifier classifier =
            new CowSwapProtocolSemanticClassifier(new ProtocolResourceLoader(new ObjectMapper()));
    private final MovementLegExtractor movementLegExtractor = new MovementLegExtractor(new NativeAssetSymbolResolver());

    @Test
    void ethFlowRequestEmitsDexOrderRequestHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105");
        rawTransaction.getRawData().put("to", ETH_FLOW);
        rawTransaction.getRawData().put("methodId", "0x322bba21");
        rawTransaction.getRawData().put("functionName", "createOrder((address,address,uint256,uint256,bytes32,uint256,uint32,bool,int64))");
        rawTransaction.getRawData().put("input", cowEthFlowCreateOrderInput());
        rawTransaction.getRawData().put("value", "27638811423349461");
        String expectedCorrelation = com.walletradar.ingestion.pipeline.classification.support.CowSwapSupport
                .resolveEthFlowCorrelationId(OnChainRawTransactionView.wrap(rawTransaction));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(CowSwapProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(CowSwapProtocolSemanticClassifier.SEMANTIC_DEX_ORDER_REQUEST);
            assertThat(hint.protocolName()).isEqualTo("CoW Swap");
            assertThat(hint.protocolVersion()).isEqualTo("EthFlow");
            assertThat(hint.correlationId()).isEqualTo(expectedCorrelation);
        });
    }

    @Test
    void settlementTradeEventEmitsDexOrderSettlementHint() {
        RawTransaction request = baseRaw();
        request.getRawData().put("to", ETH_FLOW);
        request.getRawData().put("methodId", "0x322bba21");
        request.getRawData().put("input", cowEthFlowCreateOrderInput());
        request.getRawData().put("value", "27638811423349461");
        String expectedCorrelation = com.walletradar.ingestion.pipeline.classification.support.CowSwapSupport
                .resolveEthFlowCorrelationId(OnChainRawTransactionView.wrap(request));

        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0");
        rawTransaction.getRawData().put("to", GPV2_SETTLEMENT);
        rawTransaction.getRawData().put("methodId", "0x13d79a0b");
        rawTransaction.getRawData().put("functionName", "settle(bytes32[][],uint256[],bytes32[],bytes)");
        rawTransaction.getRawData().put("clarificationEvidence", new Document("fullReceipt", new Document("logs", List.of(
                new Document("address", GPV2_SETTLEMENT).append("topics", List.of(
                        "0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17"
                )).append("data", cowTradeLogData(expectedCorrelation))
        ))));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(CowSwapProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(CowSwapProtocolSemanticClassifier.SEMANTIC_DEX_ORDER_SETTLEMENT);
            assertThat(hint.protocolName()).isEqualTo("CoW Swap");
            assertThat(hint.protocolVersion()).isEqualTo("GPv2");
            assertThat(hint.correlationId()).isEqualTo(expectedCorrelation);
        });
    }

    private ProtocolSemanticContext context(RawTransaction rawTransaction) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<RawLeg> movementLegs = movementLegExtractor.extract(view);
        return new ProtocolSemanticContext(view, ProtocolDiscoveryResult.empty(), movementLegs);
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

    private static String cowEthFlowCreateOrderInput() {
        return "0x322bba21"
                + paddedAddress("0x5979d7b546e38e414f7e9822514be443a4800529")
                + paddedAddress(WALLET)
                + paddedUint("27638811423349461")
                + paddedUint("22628189600680790")
                + paddedBytes32("0xd7e27923e5a18d36851057738a3970e4ddd2905e85b5fc19436a160647863fff")
                + paddedUint("0")
                + paddedUint("1760524229")
                + paddedBool(false)
                + paddedUint("58228845");
    }

    private static String cowTradeLogData(String orderDigest) {
        return "0x"
                + paddedAddress("0x0000000000000000000000000000000000000001")
                + paddedAddress("0x5979d7b546e38e414f7e9822514be443a4800529")
                + paddedUint("27638811423349461")
                + paddedUint("22742145033450122")
                + paddedUint("0")
                + paddedUint("192")
                + paddedUint("32")
                + strip0x(orderDigest);
    }

    private static String paddedAddress(String address) {
        return "%064x".formatted(new BigInteger(strip0x(address), 16));
    }

    private static String paddedUint(String value) {
        return "%064x".formatted(new BigInteger(value));
    }

    private static String paddedBytes32(String value) {
        String normalized = strip0x(value);
        return "0".repeat(Math.max(0, 64 - normalized.length())) + normalized;
    }

    private static String paddedBool(boolean value) {
        return value ? "0".repeat(63) + "1" : "0".repeat(64);
    }

    private static String strip0x(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }
}
