package com.walletradar.application.normalization.pipeline.classification.onchain.family;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.ClassificationDecision;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationContext;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.application.normalization.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.application.normalization.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FunctionNameClassifierTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Mock
    private ProtocolRegistryService protocolRegistryService;
    @Mock
    private NativeAssetSymbolResolver nativeAssetSymbolResolver;

    @Test
    void feeOnlyWithdrawDoesNotClassifyAsLendingWithdraw() {
        FunctionNameClassifier classifier = new FunctionNameClassifier(protocolRegistryService, nativeAssetSymbolResolver);
        OnChainClassificationContext context = context(
                "withdraw(uint256)",
                List.of(RawLeg.fee("ETH", new BigDecimal("-0.0000000000234")))
        );

        Optional<ClassificationDecision> decision = classifier.classify(context);

        assertThat(decision).isEmpty();
    }

    @Test
    void withdrawWithUnderlyingTransferStillClassifiesAsLendingWithdraw() {
        FunctionNameClassifier classifier = new FunctionNameClassifier(protocolRegistryService, nativeAssetSymbolResolver);
        OnChainClassificationContext context = context(
                "withdraw(uint256)",
                List.of(
                        RawLeg.fee("ETH", new BigDecimal("-0.0000000000234")),
                        RawLeg.asset("0xusdc", "sUSDC", new BigDecimal("-10"))
                )
        );

        Optional<ClassificationDecision> decision = classifier.classify(context);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LENDING_WITHDRAW);
    }

    private static OnChainClassificationContext context(String functionName, List<RawLeg> legs) {
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xfeeonly")
                .setNetworkId(NetworkId.UNICHAIN.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", WALLET)
                        .append("from", WALLET)
                        .append("functionName", functionName)
                        .append("methodId", "0x69328dec")
                        .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                legs
        );
    }
}
