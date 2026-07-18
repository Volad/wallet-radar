package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.ExternalCapitalBoundary;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NEW-02 (label-only): external-capital {@code EXTERNAL_TRANSFER_IN} / {@code INFLOW} deposits that
 * the inbound spot fallback fully prices at market value must be labelled {@code ACQUIRE}, not
 * {@code UNKNOWN}. This must not change any quantity, cost basis, net lane, or AVCO.
 *
 * <p>Fixtures mirror the Dzengi fiat deposit shape: a single inbound {@code TRANSFER} leg with no
 * txHash / correlationId (so {@link ReplayPendingTransferKeyFactory#transferKey} is null and
 * {@code TransferReplayHandler.applyTransfer} falls to {@code applyUnknownTransfer} → UNKNOWN),
 * priced either via the flow's own USD peg or via the replay market authority.</p>
 */
class ReplayDispatcherExternalCapitalInflowLabelTest {

    private static final String WALLET = "DZENGI:1023141508";

    @Test
    void usdInflowPricedViaFlowPeg_isLabelledAcquire_withUnchangedNumbers() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        NormalizedTransaction deposit = externalTransferIn(
                "USD", "100", ExternalCapitalBoundary.INFLOW, PriceSource.STABLECOIN, BigDecimal.ONE);

        dispatcher.dispatch(deposit, replayState);

        AssetLedgerPoint point = singlePoint(ledgerPoints);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
        // Numbers identical to the pre-fix UNKNOWN behaviour (label-only change).
        assertThat(point.getQuantityAfter()).isEqualByComparingTo("100");
        assertThat(point.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");
        assertThat(point.getAvcoAfterUsd()).isEqualByComparingTo("1");
        assertThat(point.getUncoveredQuantityAfter()).isEqualByComparingTo("0");

        AssetKey key = assetSupport.assetKey(deposit, deposit.getFlows().getFirst());
        assertThat(replayState.position(key).uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(replayState.position(key).totalCostBasisUsd()).isEqualByComparingTo("100");
        assertThat(replayState.position(key).perWalletAvco()).isEqualByComparingTo("1");
    }

    @Test
    void bynInflowPricedViaMarketAuthority_isLabelledAcquire_withUnchangedNumbers() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.of(
                new ReplayMarketAuthority.ResolvedMarketPrice(
                        new BigDecimal("0.31"),
                        PriceSource.DZENGI,
                        ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE
                )
        ));
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        // Unpriced flow (priceSource UNKNOWN) — the market authority supplies the BYN→USD spot.
        NormalizedTransaction deposit = externalTransferIn(
                "BYN", "1000", ExternalCapitalBoundary.INFLOW, null, null);

        dispatcher.dispatch(deposit, replayState);

        AssetLedgerPoint point = singlePoint(ledgerPoints);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(point.getQuantityAfter()).isEqualByComparingTo("1000");
        assertThat(point.getTotalCostBasisAfterUsd()).isEqualByComparingTo("310");
        assertThat(point.getAvcoAfterUsd()).isEqualByComparingTo("0.31");
        assertThat(point.getUncoveredQuantityAfter()).isEqualByComparingTo("0");
    }

    @Test
    void inflowUnpriced_leavesUncovered_staysUnknown() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        // Unpriced flow AND no market authority quote → spot fallback leaves uncovered > 0.
        NormalizedTransaction deposit = externalTransferIn(
                "BYN", "1000", ExternalCapitalBoundary.INFLOW, null, null);

        dispatcher.dispatch(deposit, replayState);

        AssetLedgerPoint point = singlePoint(ledgerPoints);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.UNKNOWN);
        assertThat(point.getUncoveredQuantityAfter()).isEqualByComparingTo("1000");

        AssetKey key = assetSupport.assetKey(deposit, deposit.getFlows().getFirst());
        assertThat(replayState.position(key).uncoveredQuantity()).isEqualByComparingTo("1000");
    }

    @Test
    void nonExternalTransferIn_pricedInboundTransfer_staysUnknown() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        // INTERNAL_TRANSFER inbound, fully priced (uncovered == 0) but NOT external-capital inflow.
        NormalizedTransaction transfer = inboundTransfer(
                NormalizedTransactionType.INTERNAL_TRANSFER,
                "USD", "100", null, PriceSource.STABLECOIN, BigDecimal.ONE);

        dispatcher.dispatch(transfer, replayState);

        AssetLedgerPoint point = singlePoint(ledgerPoints);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.UNKNOWN);
        assertThat(point.getUncoveredQuantityAfter()).isEqualByComparingTo("0");
    }

    @Test
    void externalTransferInWithOutflowBoundary_pricedInbound_staysUnknown() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        // Wrong boundary (OUTFLOW) → helper must not relabel even though it is priced/covered.
        NormalizedTransaction deposit = externalTransferIn(
                "USD", "100", ExternalCapitalBoundary.OUTFLOW, PriceSource.STABLECOIN, BigDecimal.ONE);

        dispatcher.dispatch(deposit, replayState);

        AssetLedgerPoint point = singlePoint(ledgerPoints);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.UNKNOWN);
    }

    @Test
    void externalTransferInWithOutboundFlow_staysUnknown() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = newReplayState(ledgerPoints);

        // Outbound flow (negative qty) → helper must not relabel (not an inflow acquisition).
        NormalizedTransaction deposit = inboundTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                "USD", "-100", ExternalCapitalBoundary.INFLOW, PriceSource.STABLECOIN, BigDecimal.ONE);

        dispatcher.dispatch(deposit, replayState);

        AssetLedgerPoint point = singlePoint(ledgerPoints);
        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.UNKNOWN);
    }

    private static AssetLedgerPoint singlePoint(List<AssetLedgerPoint> ledgerPoints) {
        assertThat(ledgerPoints).hasSize(1);
        return ledgerPoints.getFirst();
    }

    private static ReplayExecutionState newReplayState(List<AssetLedgerPoint> ledgerPoints) {
        return new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("dzengi-universe", ledgerPoints, Instant.now()),
                null,
                null,
                null
        );
    }

    private static NormalizedTransaction externalTransferIn(
            String symbol,
            String qty,
            ExternalCapitalBoundary boundary,
            PriceSource priceSource,
            BigDecimal unitPriceUsd
    ) {
        return inboundTransfer(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN, symbol, qty, boundary, priceSource, unitPriceUsd);
    }

    private static NormalizedTransaction inboundTransfer(
            NormalizedTransactionType type,
            String symbol,
            String qty,
            ExternalCapitalBoundary boundary,
            PriceSource priceSource,
            BigDecimal unitPriceUsd
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("dzengi:" + type.name() + ":" + symbol);
        tx.setSource(NormalizedTransactionSource.DZENGI);
        tx.setType(type);
        tx.setWalletAddress(WALLET);
        tx.setBlockTimestamp(Instant.parse("2025-01-15T10:00:00Z"));
        tx.setExternalCapitalBoundary(boundary);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAccountRef(WALLET);
        flow.setQuantityDelta(new BigDecimal(qty));
        if (unitPriceUsd != null) {
            flow.setUnitPriceUsd(unitPriceUsd);
        }
        if (priceSource != null) {
            flow.setPriceSource(priceSource);
        }

        tx.setFlows(List.of(flow));
        return tx;
    }

    private ReplayDispatcher buildDispatcher(ReplayAssetSupport assetSupport, ReplayMarketAuthority marketAuthority) {
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(marketAuthority);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        ContinuityCarryService carryService = new ContinuityCarryService(engine, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        ReplayTransferClassifier transferClassifier = new ReplayTransferClassifier(keyFactory);
        TransferReplayHandler transferReplayHandler = TransferReplayHandlerFixtures.handler(
                flowSupport, carryService, keyFactory, transferClassifier,
                new ReplayPendingTransferMatcher(), marketAuthority
        );
        BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler =
                new BybitVenueInternalReplayHandler(transferClassifier, transferReplayHandler);
        ReplayTransactionRouter replayTransactionRouter = mock(ReplayTransactionRouter.class);
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());
        ReplayRouteHandlerRegistry replayRouteHandlerRegistry = ReplayRouteHandlerRegistryFactory.create(
                mock(EulerLoopReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class),
                mock(GenericAsyncLifecycleReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class),
                mock(LiquidStakingReplayHandler.class),
                mock(FamilyEquivalentCustodyReplayHandler.class)
        );
        return new ReplayDispatcher(
                replayTransactionRouter, assetSupport, flowSupport, transferClassifier, keyFactory,
                replayRouteHandlerRegistry, mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler, bybitVenueInternalReplayHandler,
                mock(LiquidStakingReplayHandler.class), mock(FamilyEquivalentCustodyReplayHandler.class),
                mock(GenericAsyncLifecycleReplayHandler.class), mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class), mock(PositionScopedLpExitReplayHandler.class),
                mock(AsyncSpotOrderReplayHandler.class), mock(CounterpartyBasisPoolReplayHook.class),
                mock(LeverageBorrowReplayHook.class), mock(BorrowReplayHandler.class),
                mock(RepayReplayHandler.class), marketAuthority
        );
    }
}
