package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
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

    @BeforeEach
    void setUp() {
        var assetSupport = new com.walletradar.costbasis.application.replay.support.ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine();
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
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
        handler = new TransferReplayHandler(
                flowSupport,
                carryService,
                keyFactory,
                classifier,
                matcher,
                marketAuthority
        );
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now()),
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
        earnOut.setType(NormalizedTransactionType.LENDING_WITHDRAW);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:ae372912");
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
        earnOut.setType(NormalizedTransactionType.LENDING_WITHDRAW);
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
        earnOut.setType(NormalizedTransactionType.LENDING_WITHDRAW);
        earnOut.setWalletAddress("BYBIT:33625378:EARN");
        earnOut.setContinuityCandidate(true);
        earnOut.setCorrelationId("bybit-earn-principal-v1:88b50f43");
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
        fundIn.setType(NormalizedTransactionType.LENDING_WITHDRAW);
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
        earnOut.setType(NormalizedTransactionType.LENDING_WITHDRAW);
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
        umbrellaIn.setType(NormalizedTransactionType.LENDING_WITHDRAW);
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
}
