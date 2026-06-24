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
 * T-01 — {@code isShareLikeSymbol} contract-aware fix in {@link LendingClassifier}.
 *
 * <p>An 'e'-prefixed symbol must only be treated as a share-like receipt when the associated
 * contract is a known Euler vault address. Without the contract guard, tokens that merely start
 * with 'e' (including Euler debt receipts and unrelated tokens) would be incorrectly classified
 * as vault shares, causing incorrect cost-basis carry in the replay engine.
 *
 * <p>Tests exercise the full-receipt-clarification path of {@link LendingClassifier#classify}
 * (skipping the protocol-semantic shortcut) so that {@code hasShareLikeMovement} and
 * {@code hasNonShareMovement} are actually evaluated.
 */
class LendingClassifierShareLikeSymbolContractTest {

    /**
     * Known Euler vault on Avalanche (eUSDC-2).
     * Must match an entry in {@code EULER_KNOWN_VAULT_CONTRACTS} in LendingClassifier.
     */
    private static final String EUSDC2_CONTRACT = "0x39de0f00189306062d79edec6dca5bb6bfd108f9";

    /** An arbitrary non-Euler contract that should NOT be treated as a vault share. */
    private static final String UNKNOWN_CONTRACT = "0x0000000000000000000000000000000000099999";

    private static final String USDC_CONTRACT = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    private final LendingClassifier classifier = new LendingClassifier();

    @Test
    @DisplayName("eUSDC-2 inbound with known Euler vault contract → treated as share-like → LENDING_DEPOSIT")
    void eusdc2WithKnownVaultContractIsShareLike() {
        // eUSDC-2 inbound (share) + USDC outbound (principal) = LENDING_DEPOSIT shape.
        OnChainClassificationContext ctx = fullyResolvedContext(List.of(
                RawLeg.asset(USDC_CONTRACT, "USDC", new BigDecimal("-1000")),       // principal out
                RawLeg.asset(EUSDC2_CONTRACT, "eUSDC-2", new BigDecimal("1000"))    // share in (known vault)
        ));

        Optional<ClassificationDecision> decision = classifier.classify(ctx);

        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().type()).isEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
    }

    @Test
    @DisplayName("eUSDC-2 inbound with unknown contract → NOT treated as share-like → NOT LENDING_DEPOSIT")
    void eusdc2WithUnknownContractIsNotShareLike() {
        // eUSDC-2 from an unknown contract + USDC outbound.
        // Without the contract guard this would have been misclassified as LENDING_DEPOSIT.
        OnChainClassificationContext ctx = fullyResolvedContext(List.of(
                RawLeg.asset(USDC_CONTRACT, "USDC", new BigDecimal("-1000")),
                RawLeg.asset(UNKNOWN_CONTRACT, "eUSDC-2", new BigDecimal("1000"))  // 'e' prefix, non-vault contract
        ));

        Optional<ClassificationDecision> decision = classifier.classify(ctx);

        // The 'e'-prefixed token from an unknown contract is NOT treated as a vault share.
        // The classifier therefore does NOT see shareInbound=true + principalOutbound=true.
        if (decision.isPresent()) {
            assertThat(decision.orElseThrow().type())
                    .as("eUSDC-2 with unknown contract must not be classified as a lending deposit")
                    .isNotEqualTo(NormalizedTransactionType.LENDING_DEPOSIT);
        }
        // If empty — that is also acceptable; the point is no false LENDING_DEPOSIT.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a context that:
     * <ul>
     *   <li>passes {@code isClarifiedBatchCandidate} (methodId = 0xc16ae7a4)</li>
     *   <li>passes {@code hasFullReceiptClarificationEvidence} via
     *       {@code rawData.clarificationEvidence.transfers.tokenTransfers} (the field path
     *       that {@code shouldExposeReceiptClarificationEvidence()} reads)</li>
     *   <li>has NO protocol-semantic hint so the heuristic path (step 6) is exercised</li>
     * </ul>
     */
    private OnChainClassificationContext fullyResolvedContext(List<RawLeg> legs) {
        // Token-transfer evidence must live under clarificationEvidence.transfers so that
        // hasFullReceiptClarificationEvidence() returns true (it reads that path, not explorer).
        List<Document> transfers = List.of(
                new Document("contractAddress", EUSDC2_CONTRACT)
                        .append("tokenSymbol", "eUSDC-2")
                        .append("tokenDecimal", "6")
                        .append("from", "0x0000000000000000000000000000000000000000")
                        .append("to", WALLET)
                        .append("value", "1000000000")
        );
        Document clarificationEvidence = new Document("transfers",
                new Document("tokenTransfers", transfers)
                        .append("internalTransfers", List.of()));
        RawTransaction raw = new RawTransaction()
                .setTxHash("0xtest_share_like")
                .setNetworkId(NetworkId.AVALANCHE.name())
                .setWalletAddress(WALLET)
                .setRawData(new Document()
                        .append("timeStamp", "1700000000")
                        .append("transactionIndex", "1")
                        .append("blockNumber", "67399112")
                        .append("from", WALLET)
                        .append("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                        .append("methodId", "0xc16ae7a4")
                        .append("functionName", "batch(...)")
                        .append("value", "0")
                        .append("txreceipt_status", "1")
                        .append("clarificationEvidence", clarificationEvidence)
                        .append("explorer",
                                new Document("tokenTransfers", List.of())
                                        .append("internalTransfers", List.of())));
        return new OnChainClassificationContext(
                OnChainRawTransactionView.wrap(raw),
                ProtocolDiscoveryResult.empty(),
                ProtocolSemanticResult.empty(),
                legs
        );
    }
}
