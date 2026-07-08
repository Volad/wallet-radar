package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.resolv;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    private final ResolvProtocolSemanticClassifier classifier = new ResolvProtocolSemanticClassifier();
    private final MovementLegExtractor movementLegExtractor = new MovementLegExtractor(new NativeAssetSymbolResolver(NetworkTestFixtures.registry()));

    @Test
    void initiateWithdrawalEmitsWithdrawRequestHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0xd446b9d8a4b32b795cc957dc0e8381792bdf283824a8faf979042115f4c961c0");
        rawTransaction.getRawData().put("to", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23");
        rawTransaction.getRawData().put("methodId", "0x12edde5e");
        rawTransaction.getRawData().put("functionName", "initiateWithdrawal(uint256 withdrawAmount)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23")
                        .append("tokenSymbol", "stRESOLV")
                        .append("tokenDecimal", "18")
                        .append("from", WALLET)
                        .append("to", "0x0000000000000000000000000000000000000000")
                        .append("value", "30000000000000000000")
        )).append("internalTransfers", List.of()));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(ResolvProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(ResolvProtocolSemanticClassifier.SEMANTIC_STAKING_WITHDRAW_REQUEST);
            assertThat(hint.correlationId()).isEqualTo("resolv-unstake:" + WALLET + ":30");
        });
    }

    @Test
    void withdrawClaimEmitsWithdrawSettlementHint() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setTxHash("0xde8afcabd1284b6b287eb9550d204e03d9d1c59da518a2d5acbe3f4613f38a5b");
        rawTransaction.getRawData().put("to", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23");
        rawTransaction.getRawData().put("methodId", "0xe1e13847");
        rawTransaction.getRawData().put("functionName", "withdraw(bool withdrawBNB, address token)");
        rawTransaction.getRawData().put("explorer", new Document("tokenTransfers", List.of(
                new Document("contractAddress", "0x259338656198ec7a76c729514d3cb45dfbf768a1")
                        .append("tokenSymbol", "RESOLV")
                        .append("tokenDecimal", "18")
                        .append("from", "0xfe4bce4b3949c35fb17691d8b03c3cadbe2e5e23")
                        .append("to", WALLET)
                        .append("value", "30000000000000000000")
        )).append("internalTransfers", List.of()));

        List<ProtocolSemanticHint> hints = classifier.classify(context(rawTransaction));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.protocolKey()).isEqualTo(ResolvProtocolSemanticClassifier.PROTOCOL_KEY);
            assertThat(hint.semanticType()).isEqualTo(ResolvProtocolSemanticClassifier.SEMANTIC_STAKING_WITHDRAW);
            assertThat(hint.correlationId()).isEqualTo("resolv-unstake:" + WALLET + ":30");
        });
    }

    private ProtocolSemanticContext context(RawTransaction rawTransaction) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<RawLeg> movementLegs = movementLegExtractor.extract(view);
        return new ProtocolSemanticContext(view, ProtocolDiscoveryResult.empty(), movementLegs);
    }

    private RawTransaction baseRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
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
