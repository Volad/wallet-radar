package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferReplayHandlerEarnPrincipalTest {

    private TransferReplayHandler handler;
    private ReplayPendingTransferKeyFactory keyFactory;
    private ReplayExecutionState replayState;
    private java.util.List<com.walletradar.costbasis.domain.AssetLedgerPoint> ledgerPoints;

    @BeforeEach
    void setUp() {
        var assetSupport = new com.walletradar.costbasis.application.replay.support.ReplayAssetSupport();
        var cache = mock(com.walletradar.pricing.persistence.HistoricalPriceCacheService.class);
        var orchestrator = mock(com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator.class);
        when(orchestrator.prioritizedSources(any())).thenReturn(java.util.List.of(PriceSource.BINANCE));
        when(cache.findQuote(any(), eq(PriceSource.BINANCE))).thenReturn(java.util.Optional.of(
                new com.walletradar.pricing.domain.PriceQuote(
                        new BigDecimal("3000"),
                        PriceSource.BINANCE,
                        Instant.parse("2026-03-25T10:00:00Z"),
                        "ETH",
                        "test"
                )
        ));
        var marketAuthority = new ReplayMarketAuthority(cache, orchestrator);
        // FIX B (ADR-043): the engine MUST carry the same market authority so materialize-then-refine
        // can price the paired EARN inbound at market (permitUncovered=false). Without it the engine
        // cannot price and the inbound falls back to the deferred-zero boundary path — which is the
        // production behaviour only when the leg is genuinely unpriceable, not the common case.
        var engine = new GenericFlowReplayEngine(marketAuthority);
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        handler = TransferReplayHandlerFixtures.handler(flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        ledgerPoints = new ArrayList<>();
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null,
                null,
                null
        );
    }

    @Test
    void earnOutboundQueuesCoveredCarryFromUmbrellaBasis() {
        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("1.0"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("3000"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("3000"));

        NormalizedTransaction earnOut = new NormalizedTransaction();
        earnOut.setId("earn-out");
        earnOut.setSource(NormalizedTransactionSource.BYBIT);
        earnOut.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:ae372912");
        earnOut.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("-0.151"));
        earnOut.setFlows(java.util.List.of(flow));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState earnPosition = replayState.position(earnKey);

        handler.applyTransfer(earnOut, flow, 0, earnPosition, replayState);

        assertThat(umbrella.quantity()).isEqualByComparingTo("0.849");
        assertThat(earnPosition.quantityShortfall()).isZero();
        var queue = replayState.pendingTransfers().find(
                keyFactory.transferKey(earnOut, flow)
        );
        assertThat(queue).isNotNull();
        assertThat(queue.peekFirst().costBasisUsd()).isGreaterThan(new BigDecimal("400"));
    }

    @Test
    void earnOutboundPrefersEarnSliceWhenDepositLandedBasisThere() {
        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState earn = replayState.position(earnKey);
        earn.setQuantity(new BigDecimal("0.151"));
        earn.setTotalCostBasisUsd(new BigDecimal("453"));
        earn.setUncoveredQuantity(BigDecimal.ZERO);
        earn.setPerWalletAvco(new BigDecimal("3000"));

        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("1.0"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("3000"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);

        NormalizedTransaction earnOut = new NormalizedTransaction();
        earnOut.setId("earn-out");
        earnOut.setSource(com.walletradar.domain.transaction.normalized.NormalizedTransactionSource.BYBIT);
        earnOut.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        NormalizedTransaction.Flow outFlow = new NormalizedTransaction.Flow();
        outFlow.setRole(NormalizedLegRole.TRANSFER);
        outFlow.setAssetSymbol("ETH");
        outFlow.setQuantityDelta(new BigDecimal("-0.151"));
        earnOut.setFlows(java.util.List.of(outFlow));

        handler.applyTransfer(earnOut, outFlow, 0, earn, replayState);

        assertThat(earn.quantity()).isEqualByComparingTo("0");
        assertThat(umbrella.quantity()).isEqualByComparingTo("1.0");
        var queue = replayState.pendingTransfers().find(keyFactory.transferKey(earnOut, outFlow));
        assertThat(queue).isNotNull();
        assertThat(queue.peekFirst().costBasisUsd()).isGreaterThan(new BigDecimal("400"));
    }

    @Test
    void fundInboundRestoresFromQueuedCarryOnFundWallet() {
        AssetKey fundKey = new AssetKey("BYBIT:33625378:FUND", null, "SYMBOL:AGLD", "AGLD", "SYMBOL:AGLD");
        PositionState fund = replayState.position(fundKey);

        NormalizedTransaction earnOut = new NormalizedTransaction();
        earnOut.setId("earn-out");
        earnOut.setSource(NormalizedTransactionSource.BYBIT);
        earnOut.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        earnOut.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        NormalizedTransaction.Flow outFlow = new NormalizedTransaction.Flow();
        outFlow.setRole(NormalizedLegRole.TRANSFER);
        outFlow.setAssetSymbol("AGLD");
        outFlow.setAccountRef("BYBIT:33625378:EARN");
        outFlow.setQuantityDelta(new BigDecimal("-50.22"));
        earnOut.setFlows(java.util.List.of(outFlow));

        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:AGLD", "AGLD", "SYMBOL:AGLD");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("100"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("500"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("5"));

        handler.applyTransfer(earnOut, outFlow, 0, replayState.position(
                new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:AGLD", "AGLD", "SYMBOL:AGLD")
        ), replayState);

        NormalizedTransaction fundIn = new NormalizedTransaction();
        fundIn.setId("fund-in");
        fundIn.setSource(NormalizedTransactionSource.BYBIT);
        fundIn.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        fundIn.setWalletAddress("BYBIT:33625378:FUND");
        fundIn.setContinuityCandidate(true);
        fundIn.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        NormalizedTransaction.Flow inFlow = new NormalizedTransaction.Flow();
        inFlow.setRole(NormalizedLegRole.TRANSFER);
        inFlow.setAssetSymbol("AGLD");
        inFlow.setAccountRef("BYBIT:33625378:FUND");
        inFlow.setQuantityDelta(new BigDecimal("50.22"));
        fundIn.setFlows(java.util.List.of(inFlow));

        handler.applyTransfer(fundIn, inFlow, 0, fund, replayState);

        assertThat(fund.quantity()).isEqualByComparingTo("50.22");
        assertThat(fund.totalCostBasisUsd()).isGreaterThan(new BigDecimal("200"));
        assertThat(fund.uncoveredQuantity()).isZero();
        assertThat(fund.assetKey().walletAddress()).isEqualTo("BYBIT:33625378:FUND");
    }

    @Test
    void earnUmbrellaInboundRestoresFromQueuedCarry() {
        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState umbrella = replayState.position(umbrellaKey);

        NormalizedTransaction earnOut = new NormalizedTransaction();
        earnOut.setId("earn-out");
        earnOut.setSource(com.walletradar.domain.transaction.normalized.NormalizedTransactionSource.BYBIT);
        earnOut.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        NormalizedTransaction.Flow outFlow = new NormalizedTransaction.Flow();
        outFlow.setRole(NormalizedLegRole.TRANSFER);
        outFlow.setAssetSymbol("ETH");
        outFlow.setQuantityDelta(new BigDecimal("-0.151"));
        earnOut.setFlows(java.util.List.of(outFlow));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState earn = replayState.position(earnKey);
        earn.setQuantity(new BigDecimal("0.151"));
        earn.setTotalCostBasisUsd(new BigDecimal("453"));
        earn.setUncoveredQuantity(BigDecimal.ZERO);
        earn.setPerWalletAvco(new BigDecimal("3000"));

        handler.applyTransfer(earnOut, outFlow, 0, earn, replayState);

        NormalizedTransaction umbrellaIn = new NormalizedTransaction();
        umbrellaIn.setId("earn-in");
        umbrellaIn.setSource(com.walletradar.domain.transaction.normalized.NormalizedTransactionSource.BYBIT);
        umbrellaIn.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        umbrellaIn.setWalletAddress("BYBIT:33625378");
        umbrellaIn.setContinuityCandidate(true);
        umbrellaIn.setCorrelationId("bybit-earn-principal-v1:88b50f43");
        NormalizedTransaction.Flow inFlow = new NormalizedTransaction.Flow();
        inFlow.setRole(NormalizedLegRole.TRANSFER);
        inFlow.setAssetSymbol("ETH");
        inFlow.setQuantityDelta(new BigDecimal("0.151"));
        umbrellaIn.setFlows(java.util.List.of(inFlow));

        handler.applyTransfer(umbrellaIn, inFlow, 0, umbrella, replayState);

        assertThat(umbrella.quantity()).isEqualByComparingTo("0.151");
        assertThat(umbrella.totalCostBasisUsd()).isGreaterThan(new BigDecimal("400"));
        assertThat(umbrella.uncoveredQuantity()).isLessThan(new BigDecimal("0.02"));
    }

    /**
     * FIX B (ADR-043): subscribe with the EARN-side inbound leg replayed BEFORE the paired FUND-side
     * outbound leg (the production ordering for the LTC defect). Under "materialize-then-refine" the
     * paired EARN inbound materialises its covered quantity immediately at market (no unconditional
     * zero-defer), so :EARN inventory is conserved even before the FUND-out carry arrives; the later
     * authoritative paired carry then REFINES the basis without double-crediting quantity. No
     * quantityDelta=0 / costBasisDelta>0 ghost is ever produced on :EARN.
     *
     * <p>Note: this test drives {@code handler.applyTransfer} directly, so the {@code +0.75}
     * acquisition ledger point (emitted by the dispatcher at materialize time from its own
     * before/after snapshot) is not observable here — the invariants are asserted on the resulting
     * {@link PositionState}. The refine path emits a basis-only CARRY_IN (quantityDelta≈0).</p>
     */
    @Test
    void subscribeInboundFirstMaterialisesQuantityAndBasisWithoutGhost() {
        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.76"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("2280"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("3000"));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState earn = replayState.position(earnKey);

        NormalizedTransaction earnIn = earnPrincipalTx("earn-in", "BYBIT:33625378:EARN",
                "bybit-earn-principal-v1:sub", new BigDecimal("0.75"));
        handler.applyTransfer(earnIn, earnIn.getFlows().get(0), 0, earn, replayState);

        // Materialize-then-refine: the paired EARN inbound conserves quantity immediately at market,
        // instead of the #11 unconditional zero-defer that dropped the OPEN-subscribe :EARN leg.
        assertThat(earn.quantity()).isEqualByComparingTo("0.75");
        assertThat(earn.uncoveredQuantity()).isZero();

        NormalizedTransaction fundOut = earnPrincipalTx("fund-out", "BYBIT:33625378:FUND",
                "bybit-earn-principal-v1:sub", new BigDecimal("-0.75"));
        handler.applyTransfer(fundOut, fundOut.getFlows().get(0), 0, umbrella, replayState);

        // The authoritative paired carry REFINES basis WITHOUT double-crediting quantity (still 0.75).
        assertThat(earn.quantity()).isEqualByComparingTo("0.75");
        assertThat(earn.totalCostBasisUsd()).isBetween(new BigDecimal("2200"), new BigDecimal("2300"));
        assertThat(earn.uncoveredQuantity()).isZero();
        assertThat(umbrella.quantity()).isEqualByComparingTo("0.01");

        // RC-A demotion preserved: basis is authoritative (no $0-cost dilution), and no qty=0 ghost.
        assertThat(earnSubPoolGhostPoints()).isEmpty();
    }

    /**
     * RC-1 / ADR-041 invariant: a closed subscribe→redeem cycle nets to ZERO on :EARN and restores
     * the principal (quantity + basis) onto the umbrella, with no qty=0 basis ghost on :EARN.
     */
    @Test
    void closedSubscribeRedeemCycleNetsToZeroOnEarn() {
        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.76"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("2280"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("3000"));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:ETH", "ETH", "SYMBOL:ETH");
        PositionState earn = replayState.position(earnKey);

        // Subscribe (corr S): EARN inbound first, then FUND outbound.
        NormalizedTransaction subEarnIn = earnPrincipalTx("sub-earn-in", "BYBIT:33625378:EARN",
                "bybit-earn-principal-v1:sub", new BigDecimal("0.75"));
        handler.applyTransfer(subEarnIn, subEarnIn.getFlows().get(0), 0, earn, replayState);
        NormalizedTransaction subFundOut = earnPrincipalTx("sub-fund-out", "BYBIT:33625378:FUND",
                "bybit-earn-principal-v1:sub", new BigDecimal("-0.75"));
        handler.applyTransfer(subFundOut, subFundOut.getFlows().get(0), 0, umbrella, replayState);

        assertThat(earn.quantity()).isEqualByComparingTo("0.75");

        // Redeem (corr R): EARN outbound first, then FUND inbound.
        NormalizedTransaction redEarnOut = earnPrincipalTx("red-earn-out", "BYBIT:33625378:EARN",
                "bybit-earn-principal-v1:red", new BigDecimal("-0.75"));
        handler.applyTransfer(redEarnOut, redEarnOut.getFlows().get(0), 0, earn, replayState);
        NormalizedTransaction redFundIn = earnPrincipalTx("red-fund-in", "BYBIT:33625378:FUND",
                "bybit-earn-principal-v1:red", new BigDecimal("0.75"));
        handler.applyTransfer(redFundIn, redFundIn.getFlows().get(0), 0, umbrella, replayState);

        assertThat(earn.quantity()).isEqualByComparingTo("0");
        assertThat(umbrella.quantity()).isEqualByComparingTo("0.76");
        assertThat(umbrella.totalCostBasisUsd()).isBetween(new BigDecimal("2270"), new BigDecimal("2290"));
        assertThat(earnSubPoolGhostPoints()).isEmpty();
    }

    private java.util.List<com.walletradar.costbasis.domain.AssetLedgerPoint> earnSubPoolGhostPoints() {
        return ledgerPoints.stream()
                .filter(point -> point.getWalletAddress() != null
                        && point.getWalletAddress().toUpperCase(java.util.Locale.ROOT).endsWith(":EARN"))
                .filter(point -> point.getQuantityDelta() != null
                        && point.getQuantityDelta().signum() == 0)
                .filter(point -> point.getCostBasisDeltaUsd() != null
                        && point.getCostBasisDeltaUsd().abs().compareTo(new BigDecimal("0.01")) > 0)
                .toList();
    }

    private static NormalizedTransaction earnPrincipalTx(
            String id,
            String wallet,
            String correlationId,
            BigDecimal quantityDelta
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.EARN_FLEXIBLE_SAVING);
        tx.setWalletAddress(wallet);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId(correlationId);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setAccountRef(wallet);
        flow.setQuantityDelta(quantityDelta);
        tx.setFlows(java.util.List.of(flow));
        return tx;
    }
}
