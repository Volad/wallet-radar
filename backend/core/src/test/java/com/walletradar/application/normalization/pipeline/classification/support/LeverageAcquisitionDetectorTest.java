package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.costbasis.support.leverage.AaveDebtLoanCorrelationSupport;
import com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector;
import com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector.EvidenceKind;
import com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector.LeverageAnnotation;
import com.walletradar.application.costbasis.support.leverage.LeverageAcquisitionDetector.LeverageDecision;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the ADR-028 leverage-acquisition detector: the value-divergence decision truth
 * table, borrow-evidence gating, the F-4 debt-mint guard, and the PENDING fail-safe.
 *
 * <p>Anchored to the cmETH leveraged-buy shape (tx {@code 0xbc69…}, seq 4778: cmETH ~$2,845 received
 * for USDC ~$1,005 paid → gap ~$1,840). Hashes are evidence anchors only.</p>
 */
class LeverageAcquisitionDetectorTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String CMETH = "0xe6829d9a7ee3040e1276fa75293bde931859e8fa";
    private static final String USDC = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
    private static final String MANTLE_AGGREGATOR_EXECUTE = "0x247d4981";
    private static final String AAVE_BORROW_TOPIC =
            "0xb3d084820fb1a9decffb176436bd02558d15fac9b0ddfed8c465bc7359d7dce0";

    private final LeverageAcquisitionDetector detector = new LeverageAcquisitionDetector();

    @Test
    void decideLeveragedWhenDivergentWithEvidence() {
        assertThat(detector.decide(new BigDecimal("2845"), new BigDecimal("1005"), true))
                .isEqualTo(LeverageDecision.LEVERAGED);
    }

    @Test
    void decidePendingWhenDivergentWithoutEvidence() {
        assertThat(detector.decide(new BigDecimal("2845"), new BigDecimal("1005"), false))
                .isEqualTo(LeverageDecision.PENDING_INFERENCE);
    }

    @Test
    void decideOrdinaryWhenGapBelowRelativeThreshold() {
        // gap 300 == 0.30 × 1000 threshold (not strictly greater) → ordinary swap slippage.
        assertThat(detector.decide(new BigDecimal("1300"), new BigDecimal("1000"), true))
                .isEqualTo(LeverageDecision.ORDINARY);
    }

    @Test
    void decideOrdinaryWhenGapBelowAbsoluteFloor() {
        // small swap: gap 70 < $100 abs floor even though relative threshold is tiny.
        assertThat(detector.decide(new BigDecimal("150"), new BigDecimal("80"), true))
                .isEqualTo(LeverageDecision.ORDINARY);
    }

    @Test
    void decideLeveragedWhenGapExceedsRelativeThreshold() {
        assertThat(detector.decide(new BigDecimal("1301"), new BigDecimal("1000"), true))
                .isEqualTo(LeverageDecision.LEVERAGED);
    }

    @Test
    void detectAnnotatesLeveragedBuyWithSyntheticGapCorrelationKey() {
        List<NormalizedTransaction.Flow> flows = List.of(
                buy(CMETH, "cmETH", "0.86155"),
                sell(USDC, "USDC", "1005.30")
        );
        OnChainRawTransactionView view = view(MANTLE_AGGREGATOR_EXECUTE, List.of());

        LeverageAnnotation annotation = detector.detect(view, NetworkId.MANTLE, WALLET, flows);

        assertThat(annotation).isNotNull();
        assertThat(annotation.borrowEvidence()).isTrue();
        assertThat(annotation.evidenceKind()).isEqualTo(EvidenceKind.LEVERAGE_ROUTER_SELECTOR);
        assertThat(annotation.collateralSymbol()).isEqualTo("cmETH");
        assertThat(annotation.loanCorrelationId())
                .startsWith(AaveDebtLoanCorrelationSupport.LEVERAGE_LOAN_PREFIX)
                .contains(CMETH)
                .contains(WALLET);
    }

    @Test
    void detectRecognisesAaveBorrowLogEvidence() {
        List<NormalizedTransaction.Flow> flows = List.of(
                buy(CMETH, "cmETH", "0.86155"),
                sell(USDC, "USDC", "1005.30")
        );
        Document borrowLog = new Document("topics", List.of(AAVE_BORROW_TOPIC));
        OnChainRawTransactionView view = view("0xabcd1234", List.of(borrowLog));

        LeverageAnnotation annotation = detector.detect(view, NetworkId.MANTLE, WALLET, flows);

        assertThat(annotation).isNotNull();
        assertThat(annotation.borrowEvidence()).isTrue();
        assertThat(annotation.evidenceKind()).isEqualTo(EvidenceKind.AAVE_BORROW_LOG);
    }

    @Test
    void detectReturnsNullForOrdinarySwapWithoutBorrowEvidence() {
        List<NormalizedTransaction.Flow> flows = List.of(
                buy(CMETH, "cmETH", "0.86155"),
                sell(USDC, "USDC", "1005.30")
        );
        OnChainRawTransactionView view = view("0xdeadbeef", List.of());

        assertThat(detector.detect(view, NetworkId.MANTLE, WALLET, flows)).isNull();
    }

    @Test
    void detectSkipsWhenDebtIdentityMintPresent() {
        // F-4 already owns the liability when a variableDebt* marker is minted.
        List<NormalizedTransaction.Flow> flows = List.of(
                buy(CMETH, "cmETH", "0.86155"),
                sell(USDC, "USDC", "1005.30"),
                buy(null, "variableDebtUSDC", "1840.0")
        );
        OnChainRawTransactionView view = view(MANTLE_AGGREGATOR_EXECUTE, List.of());

        assertThat(detector.detect(view, NetworkId.MANTLE, WALLET, flows)).isNull();
    }

    @Test
    void detectRoutesToPendingWhenCollateralHasNoContract() {
        // Native-token collateral with no contract → cannot key the synthetic liability → PENDING.
        List<NormalizedTransaction.Flow> flows = List.of(
                buy(null, "ETH", "0.86155"),
                sell(USDC, "USDC", "1005.30")
        );
        OnChainRawTransactionView view = view(MANTLE_AGGREGATOR_EXECUTE, List.of());

        LeverageAnnotation annotation = detector.detect(view, NetworkId.MANTLE, WALLET, flows);

        assertThat(annotation).isNotNull();
        assertThat(annotation.borrowEvidence()).isFalse();
        assertThat(annotation.loanCorrelationId()).isNull();
    }

    @Test
    void detectReturnsNullWhenReceivedSideIsStablecoin() {
        // A stablecoin-for-stablecoin swap is not a leveraged collateral buy.
        List<NormalizedTransaction.Flow> flows = List.of(
                buy(USDC, "USDC", "1005.0"),
                sell("0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7", "USDT", "1005.0")
        );
        OnChainRawTransactionView view = view(MANTLE_AGGREGATOR_EXECUTE, List.of());

        assertThat(detector.detect(view, NetworkId.MANTLE, WALLET, flows)).isNull();
    }

    private static NormalizedTransaction.Flow buy(String contract, String symbol, String qty) {
        return flow(NormalizedLegRole.BUY, contract, symbol, new BigDecimal(qty));
    }

    private static NormalizedTransaction.Flow sell(String contract, String symbol, String qty) {
        return flow(NormalizedLegRole.SELL, contract, symbol, new BigDecimal(qty).negate());
    }

    private static NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String contract,
            String symbol,
            BigDecimal qty
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetContract(contract);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(qty);
        return flow;
    }

    private static OnChainRawTransactionView view(String methodId, List<Document> logs) {
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xbc69");
        raw.setNetworkId(NetworkId.MANTLE.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("from", WALLET)
                .append("to", "0x2222222222222222222222222222222222222222")
                .append("methodId", methodId)
                .append("logs", new ArrayList<>(logs)));
        return OnChainRawTransactionView.wrap(raw);
    }
}
