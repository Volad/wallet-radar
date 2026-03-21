package com.walletradar.ingestion.pipeline.classification.special;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistrySpecialHandlerType;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MorphoBundlerSpecialHandlerTest {

    private final MorphoBundlerSpecialHandler handler = new MorphoBundlerSpecialHandler();

    @Test
    @DisplayName("minted vault-share inbound becomes VAULT_DEPOSIT")
    void mintedVaultShareInboundBecomesVaultDeposit() {
        SpecialHandlerResult result = handler.classify(entry(), depositView(), depositLegs());
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT);
    }

    @Test
    @DisplayName("share-token outbound with principal inbound becomes VAULT_WITHDRAW")
    void shareTokenOutboundWithPrincipalInboundBecomesVaultWithdraw() {
        SpecialHandlerResult result = handler.classify(entry(), withdrawView(), withdrawLegs());
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.VAULT_WITHDRAW);
    }

    @Test
    @DisplayName("non-share one-in-one-out path falls back to SWAP")
    void nonShareOneInOneOutFallsBackToSwap() {
        SpecialHandlerResult result = handler.classify(entry(), swapView(), swapLegs());
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.SWAP);
    }

    private ProtocolRegistryEntry entry() {
        return new ProtocolRegistryEntry(
                "0x1fa4431bc113d308bee1d46b0e98cb805fb48c13",
                Set.of(NetworkId.ARBITRUM),
                ProtocolRegistryFamily.AGGREGATOR,
                ProtocolRegistryRole.ROUTER,
                null,
                ConfidenceLevel.HIGH,
                "Morpho",
                "Bundler3",
                true,
                ProtocolRegistrySpecialHandlerType.MORPHO_BUNDLER
        );
    }

    private OnChainRawTransactionView depositView() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("methodId", "0x374f435d")
                .append("functionName", "multicall(tuple[] bundle)")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x9954afb60bb5a222714c478ac86990f221788b88")
                                .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("value", "863000000"),
                        new Document("from", "0x0000000000000000000000000000000000000000")
                                .append("to", "0x1111111111111111111111111111111111111111")
                                .append("contractAddress", "0x7e97fa6893871a2751b5fe961978dccb2c201e65")
                                .append("tokenSymbol", "gtUSDCc")
                                .append("tokenName", "Gauntlet USDC CORE Vault")
                                .append("tokenDecimal", "18")
                                .append("value", "857839932590298984142")
                ))));
        return OnChainRawTransactionView.wrap(rawTransaction);
    }

    private OnChainRawTransactionView withdrawView() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("methodId", "0x374f435d")
                .append("functionName", "multicall(tuple[] bundle)")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x9954afb60bb5a222714c478ac86990f221788b88")
                                .append("contractAddress", "0x41ca7586cc1311807b4605fbb748a3b8862b42b5")
                                .append("tokenSymbol", "syrupUSDC")
                                .append("tokenName", "Syrup USDC")
                                .append("tokenDecimal", "6")
                                .append("value", "1887722544"),
                        new Document("from", "0x6c247b1f6182318877311737bac0844baa518f5e")
                                .append("to", "0x1111111111111111111111111111111111111111")
                                .append("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("value", "1710000000")
                ))));
        return OnChainRawTransactionView.wrap(rawTransaction);
    }

    private OnChainRawTransactionView swapView() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("methodId", "0x374f435d")
                .append("functionName", "multicall(tuple[] bundle)")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0x2222222222222222222222222222222222222222")
                                .append("contractAddress", "0xaaaa")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("value", "1000000"),
                        new Document("from", "0x3333333333333333333333333333333333333333")
                                .append("to", "0x1111111111111111111111111111111111111111")
                                .append("contractAddress", "0xbbbb")
                                .append("tokenSymbol", "DAI")
                                .append("tokenName", "Dai Stablecoin")
                                .append("tokenDecimal", "18")
                                .append("value", "1000000000000000000")
                ))));
        return OnChainRawTransactionView.wrap(rawTransaction);
    }

    private RawTransaction baseRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId("ARBITRUM");
        rawTransaction.setWalletAddress("0x1111111111111111111111111111111111111111");
        return rawTransaction;
    }

    private List<RawLeg> depositLegs() {
        return List.of(
                RawLeg.asset("0xaf88d065e77c8cc2239327c5edb3a432268e5831", "USDC", new BigDecimal("-863")),
                RawLeg.asset("0x7e97fa6893871a2751b5fe961978dccb2c201e65", "gtUSDCc", new BigDecimal("857.839932590298984142"))
        );
    }

    private List<RawLeg> withdrawLegs() {
        return List.of(
                RawLeg.asset("0x41ca7586cc1311807b4605fbb748a3b8862b42b5", "syrupUSDC", new BigDecimal("-1887.722544")),
                RawLeg.asset("0xaf88d065e77c8cc2239327c5edb3a432268e5831", "USDC", new BigDecimal("1710"))
        );
    }

    private List<RawLeg> swapLegs() {
        return List.of(
                RawLeg.asset("0xaaaa", "USDC", new BigDecimal("-1")),
                RawLeg.asset("0xbbbb", "DAI", new BigDecimal("1"))
        );
    }
}
