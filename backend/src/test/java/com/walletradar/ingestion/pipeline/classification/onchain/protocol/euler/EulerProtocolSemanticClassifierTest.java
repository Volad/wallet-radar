package com.walletradar.ingestion.pipeline.classification.onchain.protocol.euler;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EulerProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0xddcbe30a761edd2e19bba930a977475265f36fa1";

    private final EulerProtocolSemanticClassifier classifier =
            new EulerProtocolSemanticClassifier(new ProtocolResourceLoader(new ObjectMapper()));
    private final MovementLegExtractor movementLegExtractor = new MovementLegExtractor(new NativeAssetSymbolResolver());

    @Test
    void clarifiedBatchDepositEmitsLendingDepositHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df");
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch(tuple[] items)");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", ROUTER)
                                .append("value", "1000000"),
                        new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("tokenSymbol", "eUSDC")
                                .append("tokenDecimal", "6")
                                .append("from", ROUTER)
                                .append("to", WALLET)
                                .append("value", "995000")
                )))
                .append("fullReceipt", new Document("logs", List.of())));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(EulerProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(EulerProtocolSemanticClassifier.SEMANTIC_LENDING_DEPOSIT);
        });
    }

    @Test
    void clarifiedCollateralOpenEmitsLoopOpenHint() {
        String subaccount = "0x1111111111111111111111111111111111111110";
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f");
        rawTransaction.getRawData().put("to", ROUTER);
        rawTransaction.getRawData().put("methodId", "0xc16ae7a4");
        rawTransaction.getRawData().put("functionName", "batch((address targetContract, address onBehalfOfAccount, uint256 value, bytes data)[] items) payable");
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 2)
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x1d45674ec811f8a33c97616790bc5a81d4c9afac")
                                .append("tokenSymbol", "eUSDt-2-DEBT")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", subaccount)
                                .append("value", "1823548898"),
                        new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("tokenSymbol", "eUSDC-2")
                                .append("tokenDecimal", "6")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", subaccount)
                                .append("value", "1774411539"),
                        new Document("contractAddress", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4")
                                .append("to", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("value", "1774411539")
                )))
                .append("fullReceipt", new Document("logs", List.of(
                        new Document("address", ROUTER)
                                .append("topics", List.of(
                                        "0x6e9738e5aa38fe1517adbb480351ec386ece82947737b18badbcad1e911133ec",
                                        "0x0000000000000000000000001111111111111111111111111111111111111111",
                                        "0x1111111111111111111111111111111111111100000000000000000000000000",
                                        "0x000000000000000000000000aba9d2d4b6b93c3dc8976d8eb0690cca56431fe4"
                                ))
                                .append("data", "0x00000000000000000000000011111111111111111111111111111111111111104b3fd14800000000000000000000000000000000000000000000000000000000"),
                        new Document("address", "0xaba9d2d4b6b93c3dc8976d8eb0690cca56431fe4")
                                .append("topics", List.of(
                                        "0xcbc04eca7e9da35cb1393a6135a199ca52e450d5e9251cbd99f7847d33a36750"
                                ))
                                .append("data", "0x01")
                ))));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(EulerProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(EulerProtocolSemanticClassifier.SEMANTIC_LENDING_LOOP_OPEN);
        });
    }

    private ProtocolSemanticContext context(RawTransaction rawTransaction) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<RawLeg> movementLegs = movementLegExtractor.extract(view);
        return new ProtocolSemanticContext(view, ProtocolDiscoveryResult.empty(), movementLegs);
    }

    private RawTransaction baseRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setNetworkId(NetworkId.AVALANCHE.name());
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
