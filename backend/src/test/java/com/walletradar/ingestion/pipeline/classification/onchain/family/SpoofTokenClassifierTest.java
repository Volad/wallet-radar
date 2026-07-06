package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.classification.support.SpoofTokenQuarantineSupport;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpoofTokenClassifierTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String FAKE_CONTRACT = "0x000000000000000000000000000000000000dead";
    private static final String REAL_USDC_CONTRACT = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";
    private static final String CYRILLIC_USDC = "U\u0405D\u0421";       // UЅDС
    private static final String REAL_USDT0 = "USD\u20AE0";               // USD₮0 (allow-listed ₮)

    private final SpoofTokenClassifier classifier = new SpoofTokenClassifier();

    @Test
    @DisplayName("confusable-symbol OUT leg is quarantined (excludedFromAccounting + reason)")
    void quarantinesConfusableOutbound() {
        OnChainClassificationContext context = context(List.of(
                RawLeg.fee("ETH", new BigDecimal("-0.00001")),
                RawLeg.asset(FAKE_CONTRACT, CYRILLIC_USDC, new BigDecimal("-107.315094"))
        ));

        Optional<ClassificationDecision> decision = classifier.classify(context);

        assertThat(decision).isPresent();
        ClassificationDecision result = decision.orElseThrow();
        assertThat(result.type()).isEqualTo(NormalizedTransactionType.UNKNOWN);
        assertThat(result.excludedFromAccounting()).isTrue();
        assertThat(result.accountingExclusionReason()).isEqualTo(SpoofTokenQuarantineSupport.REASON);
        assertThat(result.missingDataReasons()).contains(SpoofTokenQuarantineSupport.REASON);
    }

    @Test
    @DisplayName("a swap whose one leg is a spoof token is quarantined")
    void quarantinesSwapWithSpoofLeg() {
        OnChainClassificationContext context = context(List.of(
                RawLeg.asset(FAKE_CONTRACT, CYRILLIC_USDC, new BigDecimal("-50")),
                RawLeg.asset(REAL_USDC_CONTRACT, "USDC", new BigDecimal("49.9"))
        ));

        Optional<ClassificationDecision> decision = classifier.classify(context);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().excludedFromAccounting()).isTrue();
    }

    @Test
    @DisplayName("real ASCII USDC transfer is NOT quarantined")
    void realUsdcNotQuarantined() {
        OnChainClassificationContext context = context(List.of(
                RawLeg.asset(REAL_USDC_CONTRACT, "USDC", new BigDecimal("-107.315094"))
        ));

        assertThat(classifier.classify(context)).isEmpty();
    }

    @Test
    @DisplayName("real USD₮0 (whitelisted ₮) transfer is NOT quarantined")
    void realUsdt0NotQuarantined() {
        OnChainClassificationContext context = context(List.of(
                RawLeg.asset(FAKE_CONTRACT, REAL_USDT0, new BigDecimal("-25"))
        ));

        assertThat(classifier.classify(context)).isEmpty();
    }

    @Test
    @DisplayName("a confusable symbol that only appears on a FEE leg is NOT quarantined")
    void confusableFeeOnlyNotQuarantined() {
        OnChainClassificationContext context = context(List.of(
                RawLeg.fee(CYRILLIC_USDC, new BigDecimal("-0.001")),
                RawLeg.asset(REAL_USDC_CONTRACT, "USDC", new BigDecimal("-10"))
        ));

        assertThat(classifier.classify(context)).isEmpty();
    }

    private static OnChainClassificationContext context(List<RawLeg> legs) {
        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0xspoof")
                .setNetworkId(NetworkId.BASE.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", WALLET)
                        .append("from", WALLET)
                        .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(rawTransaction),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                legs
        );
    }
}
