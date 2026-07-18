package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.PendingTransferStore;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.support.BridgeSettlementMetadataSupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * B-ETH-01 guard-orphan safety: the asset-converting (non-peg cross-family) settlement realizes P&L
 * on the source and acquires the destination at the carried destination fair value, and — critically
 * — leaves NO residual carry on the asset-suffix-less {@code bridge-settlement:} queue, so the real
 * {@link CorridorBasisConservationGuard} does not HARD_FAIL.
 */
class LinkedBridgeTransferReplaySupportTest {

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;
    private CorridorBasisConservationGuard guard;

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var cache = org.mockito.Mockito.mock(com.walletradar.application.pricing.persistence.HistoricalPriceCacheService.class);
        var orchestrator = org.mockito.Mockito.mock(com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator.class);
        org.mockito.Mockito.lenient().when(orchestrator.prioritizedSources(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(com.walletradar.domain.common.PriceSource.BINANCE));
        var marketAuthority = new ReplayMarketAuthority(cache, orchestrator);
        var engine = new GenericFlowReplayEngine(marketAuthority);
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        var keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        handler = TransferReplayHandlerFixtures.handler(
                flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now())
        );
        guard = new CorridorBasisConservationGuard();
    }

    @Test
    void assetConvertingSettlementDrainsQueueAndLeavesNoOrphanOutboundFirst() {
        PositionState wbtc = seedWbtcSource();
        PositionState eth = replayState.position(ethKey());

        NormalizedTransaction bridgeOut = assetConvertingOut();
        handler.applyTransfer(bridgeOut, bridgeOut.getFlows().getFirst(), 0, wbtc, replayState);
        NormalizedTransaction bridgeIn = assetConvertingIn();
        handler.applyTransfer(bridgeIn, bridgeIn.getFlows().getFirst(), 0, eth, replayState);

        assertRealizedAndAcquiredThenGuardGreen(wbtc, eth);
    }

    @Test
    void assetConvertingSettlementDrainsQueueAndLeavesNoOrphanInboundFirst() {
        PositionState wbtc = seedWbtcSource();
        PositionState eth = replayState.position(ethKey());

        NormalizedTransaction bridgeIn = assetConvertingIn();
        handler.applyTransfer(bridgeIn, bridgeIn.getFlows().getFirst(), 0, eth, replayState);
        NormalizedTransaction bridgeOut = assetConvertingOut();
        handler.applyTransfer(bridgeOut, bridgeOut.getFlows().getFirst(), 0, wbtc, replayState);

        assertRealizedAndAcquiredThenGuardGreen(wbtc, eth);
    }

    private void assertRealizedAndAcquiredThenGuardGreen(PositionState wbtc, PositionState eth) {
        // Source realized P&L = destination FMV ($42,000) − source AVCO ($40,000).
        assertThat(wbtc.quantity()).isEqualByComparingTo("0");
        assertThat(wbtc.totalRealisedPnlUsd()).isEqualByComparingTo("2000");
        // Destination acquired at carried destination FMV (tax lane); net inherits source net.
        assertThat(eth.quantity()).isEqualByComparingTo("14");
        assertThat(eth.totalCostBasisUsd()).isEqualByComparingTo("42000");
        assertThat(eth.netTotalCostBasisUsd()).isEqualByComparingTo("40000");
        assertThat(eth.uncoveredQuantity()).isZero();

        // Guard-orphan safety: no residual carry parked on any bridge-settlement: queue.
        List<PendingTransferStore.ResidualCoveredCarry> residuals =
                replayState.pendingTransfers().residualCoveredCarries(new BigDecimal("1.00"));
        assertThat(residuals)
                .as("no bridge-settlement orphan may remain after the asset-converting settlement")
                .noneMatch(residual -> residual.queueKey().startsWith("bridge-settlement:"));
        assertThat(replayState.pendingTransfers().nonEmptyQueueKeys())
                .noneMatch(key -> key.startsWith("bridge-settlement:"));

        // The real HARD_FAIL guard must not fire on the drained queue.
        assertThatCode(() -> guard.evaluate(replayState)).doesNotThrowAnyException();
    }

    private PositionState seedWbtcSource() {
        PositionState wbtc = replayState.position(wbtcKey());
        wbtc.setQuantity(new BigDecimal("1"));
        wbtc.setTotalCostBasisUsd(new BigDecimal("40000"));
        wbtc.setNetTotalCostBasisUsd(new BigDecimal("40000"));
        wbtc.setUncoveredQuantity(BigDecimal.ZERO);
        wbtc.setPerWalletAvco(new BigDecimal("40000"));
        wbtc.setPerWalletNetAvco(new BigDecimal("40000"));
        return wbtc;
    }

    private static AssetKey wbtcKey() {
        return new AssetKey("wallet-a", NetworkId.ARBITRUM, "0xwbtc", "WBTC", "SYMBOL:WBTC");
    }

    private static AssetKey ethKey() {
        return new AssetKey("wallet-a", NetworkId.KATANA, null, "ETH", "SYMBOL:ETH");
    }

    private static NormalizedTransaction assetConvertingOut() {
        NormalizedTransaction tx = bridgeTx("out", "0xbridge-out", NormalizedTransactionType.BRIDGE_OUT,
                NetworkId.ARBITRUM, "WBTC", "0xwbtc", new BigDecimal("-1"), "0xbridge-in");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(tx, true, new BigDecimal("42000"));
        return tx;
    }

    private static NormalizedTransaction assetConvertingIn() {
        NormalizedTransaction tx = bridgeTx("in", "0xbridge-in", NormalizedTransactionType.BRIDGE_IN,
                NetworkId.KATANA, "ETH", null, new BigDecimal("14"), "0xbridge-out");
        BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(tx, true, new BigDecimal("42000"));
        return tx;
    }

    private static NormalizedTransaction bridgeTx(
            String id,
            String txHash,
            NormalizedTransactionType type,
            NetworkId networkId,
            String symbol,
            String contract,
            BigDecimal quantityDelta,
            String matchedCounterparty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(txHash);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setWalletAddress("wallet-a");
        tx.setNetworkId(networkId);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        tx.setCorrelationId("bridge:lifi:wbtc-to-eth");
        tx.setContinuityCandidate(false);
        tx.setMatchedCounterparty(matchedCounterparty);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(quantityDelta);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
