package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue 2 (ADR-043) coverage for {@link TransferReplayHandler}: the FIX-B late-attach path must
 * distinguish a MATERIALIZED unresolved pending inbound (linked Earn-principal inbound with no
 * market quote yet) from a MATERIALIZED priced one. The unresolved case must still conserve the
 * inbound quantity immediately on the destination, then let later multi-source outbound carries
 * refine the basis slice-by-slice without dropping or double-crediting quantity.
 */
class TransferReplayHandlerUnmaterializedDeferTest {

    private List<AssetLedgerPoint> ledgerPoints;

    @Test
    void ltcShapeUnpricedInboundStillMaterializesQuantityAtEnqueue() {
        // LTC shape: the paired :EARN inbound is UNPRICED and replays BEFORE its outbound bundle.
        // Once linking is proven, replay must still conserve the inbound quantity immediately on
        // :EARN so later carry slices can refine basis instead of dropping quantity.
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        TransferReplayHandler handler = buildHandler(marketAuthority);
        ReplayExecutionState replayState = newState();

        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:LTC", "LTC", "SYMBOL:LTC");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.76"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("42.0939"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("55.3867"));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:LTC", "LTC", "SYMBOL:LTC");
        PositionState earn = replayState.position(earnKey);

        NormalizedTransaction earnIn = earnPrincipalTx("ltc-earn-in", "BYBIT:33625378:EARN", "LTC",
                "bybit-earn-principal-v1:ltc-sub", new BigDecimal("0.75"));
        handler.applyTransfer(earnIn, earnIn.getFlows().getFirst(), 0, earn, replayState);

        // Unpriced but linked: quantity is conserved immediately as unresolved inventory.
        assertThat(earn.quantity()).isEqualByComparingTo("0.75");
        assertThat(earn.uncoveredQuantity()).isEqualByComparingTo("0.75");

        NormalizedTransaction fundOut = earnPrincipalTx("ltc-fund-out", "BYBIT:33625378:FUND", "LTC",
                "bybit-earn-principal-v1:ltc-sub", new BigDecimal("-0.75"));
        handler.applyTransfer(fundOut, fundOut.getFlows().getFirst(), 0, umbrella, replayState);

        // The authoritative paired carry refines basis onto the conserved quantity.
        assertThat(earn.quantity()).isEqualByComparingTo("0.75");
        assertThat(earn.totalCostBasisUsd()).isBetween(new BigDecimal("41"), new BigDecimal("42"));
        assertThat(earn.uncoveredQuantity()).isZero();
    }

    @Test
    void multiSourceInboundFirstConsumesBothOutboundCarries() {
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        TransferReplayHandler handler = buildHandler(marketAuthority);
        ReplayExecutionState replayState = newState();

        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:LTC", "LTC", "SYMBOL:LTC");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.76"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("42.0939"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("55.3867"));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:LTC", "LTC", "SYMBOL:LTC");
        PositionState earn = replayState.position(earnKey);

        NormalizedTransaction earnIn = earnPrincipalTx("ltc-earn-in", "BYBIT:33625378:EARN", "LTC",
                "bybit-earn-principal-v1:ltc-bundle", new BigDecimal("0.51096338"));
        handler.applyTransfer(earnIn, earnIn.getFlows().getFirst(), 0, earn, replayState);

        NormalizedTransaction fundOut = earnPrincipalTx("ltc-fund-out", "BYBIT:33625378:FUND", "LTC",
                "bybit-earn-principal-v1:ltc-bundle", new BigDecimal("-0.01146338"));
        handler.applyTransfer(fundOut, fundOut.getFlows().getFirst(), 0, umbrella, replayState);

        NormalizedTransaction utaOut = earnInternalTransferTx("ltc-uta-out", "BYBIT:33625378:UTA", "LTC",
                "bybit-earn-principal-v1:ltc-bundle", new BigDecimal("-0.4995"));
        handler.applyTransfer(utaOut, utaOut.getFlows().getFirst(), 0, umbrella, replayState);

        assertThat(earn.quantity()).isEqualByComparingTo("0.51096338");
        assertThat(earn.uncoveredQuantity()).isZero();
        assertThat(earn.totalCostBasisUsd()).isBetween(new BigDecimal("28"), new BigDecimal("29"));
        assertThat(replayState.pendingTransfers().find(
                new ReplayPendingTransferKeyFactory(new ReplayAssetSupport()).transferKey(earnIn, earnIn.getFlows().getFirst())
        )).isNull();
    }

    @Test
    void linkShapeMaterializedDeferStaysRefineOnlyNoDoubleCredit() {
        // LINK shape: the paired :EARN inbound IS priceable, so it MATERIALIZES its 0.75 quantity at
        // enqueue. The later FUND-out carry must only REFINE the basis — the materialized branch is
        // untouched, so quantity stays 0.75 (never double-credited to 1.5).
        HistoricalPriceCacheService cache = mock(HistoricalPriceCacheService.class);
        PriceExternalSourceOrchestrator orchestrator = mock(PriceExternalSourceOrchestrator.class);
        when(orchestrator.prioritizedSources(any())).thenReturn(List.of(PriceSource.BINANCE));
        when(cache.findQuote(any(), eq(PriceSource.BINANCE))).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("55.3867"), PriceSource.BINANCE,
                Instant.parse("2026-03-25T10:00:00Z"), "LTC", "test")));
        ReplayMarketAuthority marketAuthority = new ReplayMarketAuthority(cache, orchestrator);
        TransferReplayHandler handler = buildHandler(marketAuthority);
        ReplayExecutionState replayState = newState();

        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:LTC", "LTC", "SYMBOL:LTC");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.76"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("42.0939"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("55.3867"));

        AssetKey earnKey = new AssetKey("BYBIT:33625378:EARN", null, "SYMBOL:LTC", "LTC", "SYMBOL:LTC");
        PositionState earn = replayState.position(earnKey);

        NormalizedTransaction earnIn = earnPrincipalTx("link-earn-in", "BYBIT:33625378:EARN", "LTC",
                "bybit-earn-principal-v1:link-sub", new BigDecimal("0.75"));
        handler.applyTransfer(earnIn, earnIn.getFlows().getFirst(), 0, earn, replayState);

        // Materialized at enqueue: quantity is already conserved on the position.
        assertThat(earn.quantity()).isEqualByComparingTo("0.75");

        NormalizedTransaction fundOut = earnPrincipalTx("link-fund-out", "BYBIT:33625378:FUND", "LTC",
                "bybit-earn-principal-v1:link-sub", new BigDecimal("-0.75"));
        handler.applyTransfer(fundOut, fundOut.getFlows().getFirst(), 0, umbrella, replayState);

        // Refine-only: quantity is NOT re-added (no double credit), basis refined by the paired carry.
        assertThat(earn.quantity()).isEqualByComparingTo("0.75");
        assertThat(earn.uncoveredQuantity()).isZero();
    }

    private TransferReplayHandler buildHandler(ReplayMarketAuthority marketAuthority) {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(marketAuthority);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        ContinuityCarryService carryService = new ContinuityCarryService(engine, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        ReplayTransferClassifier classifier = new ReplayTransferClassifier(keyFactory);
        ReplayPendingTransferMatcher matcher = new ReplayPendingTransferMatcher();
        return TransferReplayHandlerFixtures.handler(flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
    }

    private ReplayExecutionState newState() {
        ledgerPoints = new ArrayList<>();
        return new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null,
                null,
                null
        );
    }

    private static NormalizedTransaction earnPrincipalTx(
            String id, String wallet, String asset, String correlationId, BigDecimal quantityDelta
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
        flow.setAssetSymbol(asset);
        flow.setAccountRef(wallet);
        flow.setQuantityDelta(quantityDelta);
        tx.setFlows(List.of(flow));
        return tx;
    }

    private static NormalizedTransaction earnInternalTransferTx(
            String id, String wallet, String asset, String correlationId, BigDecimal quantityDelta
    ) {
        NormalizedTransaction tx = earnPrincipalTx(id, wallet, asset, correlationId, quantityDelta);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        return tx;
    }
}
