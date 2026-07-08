package com.walletradar.application.normalization.pipeline.classification.onchain.protocol.init;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.application.normalization.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InitCapitalSemanticClassifierTest {

    private static final String WALLET = "0xabcdef1234567890abcdef1234567890abcdef12";
    private static final String POSITION_MANAGER = "0xf82cbcab75c1138a8f1f20179613e7c0c8337346";
    private static final String COLLATERAL_POOL = "0x6cc1039746803bc325ec6eb7262def3a672ae243";
    private static final String BORROW_POOL = "0x00a55649e597d463fd212fbe48a3b40f0e227d06";
    private static final String INIT_METHOD_ID = "0x247d4981";

    private final InitCapitalSemanticClassifier classifier = new InitCapitalSemanticClassifier();
    private final MovementLegExtractor movementLegExtractor =
            new MovementLegExtractor(new NativeAssetSymbolResolver(NetworkTestFixtures.registry()));

    @Test
    void patternA_depositCollateralAndBorrow_classifiedAsBorrow() {
        // Tx A: cmETH leaves wallet → collateral pool, USDC arrives wallet ← borrow pool
        RawTransaction raw = buildRaw(INIT_METHOD_ID, List.of(
                tokenTransfer(WALLET, COLLATERAL_POOL, "cmETH", "8615000000000000000"),
                tokenTransfer(BORROW_POOL, WALLET, "USDC", "900000000")
        ));

        List<ProtocolSemanticHint> hints = classifier.classify(context(raw));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.BORROW);
            assertThat(hint.protocolName()).isEqualTo("INIT Capital");
            assertThat(hint.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        });
    }

    @Test
    void patternB_pureBorrow_classifiedAsBorrow() {
        // Tx B: only USDC arrives from borrow pool (no collateral deposit in this tx)
        RawTransaction raw = buildRaw(INIT_METHOD_ID, List.of(
                tokenTransfer(BORROW_POOL, WALLET, "USDC", "100000000")
        ));

        List<ProtocolSemanticHint> hints = classifier.classify(context(raw));

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.BORROW));
    }

    @Test
    void patternC_repayAndWithdrawCollateral_classifiedAsLendingWithdraw() {
        // Tx C: USDC leaves wallet → PositionManager (repayment), cmETH arrives ← collateral pool
        RawTransaction raw = buildRaw(INIT_METHOD_ID, List.of(
                tokenTransfer(WALLET, POSITION_MANAGER, "USDC", "1005300000"),
                tokenTransfer(COLLATERAL_POOL, WALLET, "cmETH", "861500000000000000")
        ));

        List<ProtocolSemanticHint> hints = classifier.classify(context(raw));

        assertThat(hints).singleElement().satisfies(hint -> {
            assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
            assertThat(hint.protocolName()).isEqualTo("INIT Capital");
            assertThat(hint.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        });
    }

    @Test
    void nonInitMethodId_notClassified() {
        RawTransaction raw = buildRaw("0x12345678", List.of(
                tokenTransfer(BORROW_POOL, WALLET, "USDC", "100000000")
        ));

        List<ProtocolSemanticHint> hints = classifier.classify(context(raw));

        assertThat(hints).isEmpty();
    }

    @Test
    void initMethodIdButNoMatchingTransferPattern_notClassified() {
        // Transfer from unknown address — not a recognized INIT Capital pattern
        RawTransaction raw = buildRaw(INIT_METHOD_ID, List.of(
                tokenTransfer("0x1111111111111111111111111111111111111111", WALLET, "DAI", "100000000000000000000")
        ));

        List<ProtocolSemanticHint> hints = classifier.classify(context(raw));

        assertThat(hints).isEmpty();
    }

    @Test
    void registryMatchIdentifiesInitCapital_withoutMethodId() {
        // Even without the execute() method ID, if the registry identifies the toAddress as
        // INIT Capital, the classifier should fire.
        RawTransaction raw = buildRaw("0x00000000", List.of(
                tokenTransfer(BORROW_POOL, WALLET, "USDC", "100000000")
        ));

        ProtocolDiscoveryResult discovery = new ProtocolDiscoveryResult(List.of(
                new ProtocolMatch(
                        "INIT Capital",
                        "V1",
                        ProtocolRegistryFamily.LENDING,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        ConfidenceLevel.HIGH,
                        POSITION_MANAGER,
                        POSITION_MANAGER,
                        "TO_ADDRESS",
                        null,
                        null
                )
        ), null);

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        ProtocolSemanticContext ctx = new ProtocolSemanticContext(
                view, discovery, movementLegExtractor.extract(view));

        List<ProtocolSemanticHint> hints = classifier.classify(ctx);

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.BORROW));
    }

    private ProtocolSemanticContext context(RawTransaction raw) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        return new ProtocolSemanticContext(
                view,
                ProtocolDiscoveryResult.empty(),
                movementLegExtractor.extract(view)
        );
    }

    private RawTransaction buildRaw(String methodId, List<Document> transfers) {
        RawTransaction tx = new RawTransaction();
        tx.setTxHash("0x" + "a".repeat(64));
        tx.setNetworkId(NetworkId.MANTLE.name());
        tx.setWalletAddress(WALLET);
        tx.setRawData(new Document()
                .append("to", POSITION_MANAGER)
                .append("timeStamp", "1718600000")
                .append("transactionIndex", "1")
                .append("methodId", methodId)
                .append("functionName", "execute((uint8,bytes)[] _params)")
                .append("explorer", new Document("tokenTransfers", transfers)
                        .append("internalTransfers", List.of())));
        return tx;
    }

    private Document tokenTransfer(String from, String to, String symbol, String value) {
        return new Document("from", from)
                .append("to", to)
                .append("contractAddress", "0x" + symbol.toLowerCase() + "0000000000000000000000000000000000")
                .append("tokenSymbol", symbol)
                .append("tokenDecimal", "18")
                .append("value", value);
    }
}
