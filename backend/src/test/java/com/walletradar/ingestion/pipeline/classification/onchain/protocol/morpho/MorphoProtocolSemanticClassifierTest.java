package com.walletradar.ingestion.pipeline.classification.onchain.protocol.morpho;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolMatch;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticHint;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.MovementLegExtractor;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MorphoProtocolSemanticClassifierTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String BUNDLER = "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13";

    private final MorphoProtocolSemanticClassifier classifier =
            new MorphoProtocolSemanticClassifier(new ProtocolResourceLoader(new ObjectMapper()));
    private final MovementLegExtractor movementLegExtractor = new MovementLegExtractor(new NativeAssetSymbolResolver());

    @Test
    void mintedVaultShareInboundEmitsVaultDepositHint() {
        List<ProtocolSemanticHint> hints = classifier.classify(context(depositRaw()));

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT));
    }

    @Test
    void withdrawCollateralBundleEmitsLendingWithdrawHint() {
        List<ProtocolSemanticHint> hints = classifier.classify(context(withdrawCollateralRaw()));

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW));
    }

    @Test
    void nonShareOneInOneOutFallsBackToSwapHint() {
        List<ProtocolSemanticHint> hints = classifier.classify(context(swapRaw()));

        assertThat(hints).singleElement().satisfies(hint ->
                assertThat(hint.suggestedType()).isEqualTo(NormalizedTransactionType.SWAP));
    }

    private ProtocolSemanticContext context(RawTransaction rawTransaction) {
        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(rawTransaction);
        List<RawLeg> movementLegs = movementLegExtractor.extract(view);
        return new ProtocolSemanticContext(
                view,
                new ProtocolDiscoveryResult(List.of(new ProtocolMatch(
                        "Morpho",
                        "Bundler3",
                        ProtocolRegistryFamily.AGGREGATOR,
                        ProtocolRegistryRole.ROUTER,
                        ConfidenceLevel.HIGH,
                        BUNDLER,
                        BUNDLER,
                        "TO_ADDRESS",
                        null,
                        ProtocolRegistrySpecialHandlerType.MORPHO_BUNDLER
                )), null),
                movementLegs
        );
    }

    private RawTransaction depositRaw() {
        return baseRaw(new Document("tokenTransfers", List.of(
                new Document("from", WALLET)
                        .append("to", "0x9954afb60bb5a222714c478ac86990f221788b88")
                        .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("value", "863000000"),
                new Document("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("contractAddress", "0x7e97fa6893871a2751b5fe961978dccb2c201e65")
                        .append("tokenSymbol", "gtUSDCc")
                        .append("tokenName", "Gauntlet USDC CORE Vault")
                        .append("tokenDecimal", "18")
                        .append("value", "857839932590298984142")
        )));
    }

    private RawTransaction swapRaw() {
        return baseRaw(new Document("tokenTransfers", List.of(
                new Document("from", WALLET)
                        .append("to", "0x2222222222222222222222222222222222222222")
                        .append("contractAddress", "0xaaaa")
                        .append("tokenSymbol", "USDC")
                        .append("tokenName", "USD Coin")
                        .append("tokenDecimal", "6")
                        .append("value", "1000000"),
                new Document("from", "0x3333333333333333333333333333333333333333")
                        .append("to", WALLET)
                        .append("contractAddress", "0xbbbb")
                        .append("tokenSymbol", "DAI")
                        .append("tokenName", "Dai Stablecoin")
                        .append("tokenDecimal", "18")
                        .append("value", "1000000000000000000")
        )));
    }

    private RawTransaction withdrawCollateralRaw() {
        RawTransaction rawTransaction = baseRaw(new Document("tokenTransfers", List.of(
                new Document("from", "0x6c247b1f6182318877311737bac0844baa518f5e")
                        .append("to", WALLET)
                        .append("contractAddress", "0x41ca7586cc1311807b4605fbb748a3b8862b42b5")
                        .append("tokenSymbol", "syrupUSDC")
                        .append("tokenName", "Syrup USDC")
                        .append("tokenDecimal", "6")
                        .append("value", "1887722544")
        )));
        rawTransaction.getRawData().put("input", "0x374f435d000000000000000000000000000000000000000000000000000000001af3bbc6");
        return rawTransaction;
    }

    private RawTransaction baseRaw(Document explorer) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("to", BUNDLER)
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("methodId", "0x374f435d")
                .append("functionName", "multicall(tuple[] bundle)")
                .append("explorer", explorer.append("internalTransfers", List.of())));
        return rawTransaction;
    }
}
