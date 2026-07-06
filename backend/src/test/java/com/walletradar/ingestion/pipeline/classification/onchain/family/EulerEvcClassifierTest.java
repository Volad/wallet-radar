package com.walletradar.ingestion.pipeline.classification.onchain.family;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.ClassificationDecision;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationContext;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolDiscoveryResult;
import com.walletradar.ingestion.pipeline.classification.onchain.protocol.ProtocolSemanticResult;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-01 — {@link EulerEvcClassifier}: Euler EVC batch router on Avalanche.
 *
 * <p>Covers the three Avalanche transactions (0x305f37, 0x1e0c42, 0x233c2b) that call the
 * Euler EVC at {@code 0xddcbe30a761edd2e19bba930a977475265f36fa1} with method {@code 0xc16ae7a4}
 * and should be classified as LENDING_LOOP_REBALANCE.
 */
class EulerEvcClassifierTest {

    private static final String EULER_EVC_ADDRESS = "0xddcbe30a761edd2e19bba930a977475265f36fa1";
    private static final String EULER_BATCH_METHOD = "0xc16ae7a4";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    private final EulerEvcClassifier classifier = new EulerEvcClassifier();

    @Test
    @DisplayName("AVALANCHE + EVC address + batch method → LENDING_LOOP_REBALANCE")
    void avalancheEvcBatchMatchesLendingLoopRebalance() {
        OnChainClassificationContext ctx = context(
                NetworkId.AVALANCHE,
                EULER_EVC_ADDRESS,
                EULER_BATCH_METHOD
        );

        Optional<ClassificationDecision> decision = classifier.classify(ctx);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
    }

    @Test
    @DisplayName("address match is case-insensitive (mixed-case EVC address)")
    void addressMatchIsCaseInsensitive() {
        OnChainClassificationContext ctx = context(
                NetworkId.AVALANCHE,
                EULER_EVC_ADDRESS.toUpperCase(),
                EULER_BATCH_METHOD
        );

        Optional<ClassificationDecision> decision = classifier.classify(ctx);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
    }

    @Test
    @DisplayName("non-AVALANCHE network with same address and method → no match")
    void nonAvalancheNetworkDoesNotMatch() {
        for (NetworkId network : List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM, NetworkId.BASE)) {
            Optional<ClassificationDecision> decision = classifier.classify(context(
                    network, EULER_EVC_ADDRESS, EULER_BATCH_METHOD));
            assertThat(decision)
                    .as("should be empty for network %s", network)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("AVALANCHE + wrong address → no match")
    void wrongAddressDoesNotMatch() {
        Optional<ClassificationDecision> decision = classifier.classify(context(
                NetworkId.AVALANCHE,
                "0x0000000000000000000000000000000000000001",
                EULER_BATCH_METHOD
        ));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("AVALANCHE + EVC address + wrong method → no match")
    void wrongMethodDoesNotMatch() {
        Optional<ClassificationDecision> decision = classifier.classify(context(
                NetworkId.AVALANCHE,
                EULER_EVC_ADDRESS,
                "0xdeadbeef"
        ));

        assertThat(decision).isEmpty();
    }

    @Test
    @DisplayName("classifier runs at PROTOCOL_LIFECYCLE insertion point")
    void runsAtProtocolLifecycleInsertionPoint() {
        assertThat(classifier.insertionPoint())
                .isEqualTo(OnChainClassificationInsertionPoint.PROTOCOL_LIFECYCLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private OnChainClassificationContext context(NetworkId network, String toAddress, String methodId) {
        RawTransaction raw = new RawTransaction()
                .setTxHash("0xtest")
                .setNetworkId(network.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document("to", toAddress)
                        .append("from", WALLET)
                        .append("input", methodId)
                        .append("explorer", new Document("tokenTransfers", List.of())
                                .append("internalTransfers", List.of())));
        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(raw),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                List.of(
                        RawLeg.asset("0xeusdc2contract", "eUSDC-2", new BigDecimal("1000"))
                )
        );
    }
}
