package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionScopedLpExitReplayHandlerTest {

    @Mock
    private ReplayAssetSupport assetSupport;
    @Mock
    private ReplayFlowSupport flowSupport;
    @Mock
    private ReplaySettlementAllocator settlementAllocator;
    @Mock
    private LedgerPointCollector ledgerPointCollector;

    @Test
    void allocatesCrossAssetExitInflowsByReplayKnownValueWithoutSameIdentityTouch() {
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport,
                flowSupport,
                settlementAllocator
        );

        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(NormalizedTransactionType.LP_EXIT);
        transaction.setCorrelationId("lp-position:bsc:test:1");
        transaction.setBlockTimestamp(Instant.parse("2026-04-21T10:00:00Z"));
        transaction.setFlows(List.of(
                flow("USDT", "0xusdt", "1.234868226418219121"),
                flow("XYZ", "0xxyz", "4156.773205715470746588")
        ));

        IndexedFlow usdt = new IndexedFlow(0, transaction.getFlows().get(0));
        IndexedFlow xyz = new IndexedFlow(1, transaction.getFlows().get(1));

        when(flowSupport.indexedFlows(transaction)).thenReturn(List.of(usdt, xyz));
        when(assetSupport.continuityIdentity(transaction, transaction.getFlows().get(0))).thenReturn("FAMILY:USDT");
        when(assetSupport.continuityIdentity(transaction, transaction.getFlows().get(1))).thenReturn("SYMBOL:XYZ");
        when(assetSupport.allSameAsset(List.of(transaction.getFlows().get(0), transaction.getFlows().get(1)), transaction)).thenReturn(false);
        when(assetSupport.allHaveKnownReplayPrices(transaction, List.of(usdt, xyz))).thenReturn(true);

        ReplayExecutionState replayState = new ReplayExecutionState(new PassThroughCorridorPlan(Map.of(), Map.of()), ledgerPointCollector);
        replayState.asyncLifecycleBucket(transaction.getCorrelationId()).add(
                "LP:POSITION",
                new CarryTransfer(
                        new BigDecimal("1"),
                        new BigDecimal("1"),
                        BigDecimal.ZERO,
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        false,
                        new AssetKey("wallet", NetworkId.BSC, "lp", "LP", "LP:POSITION")
                )
        );

        handler.apply(transaction, replayState);

        verify(settlementAllocator).allocateIndexedSettlementByReplayKnownValue(
                eq(transaction),
                eq(List.of(usdt, xyz)),
                eq(replayState.positions()),
                eq(new BigDecimal("100")),
                eq(ledgerPointCollector)
        );
        verify(settlementAllocator, never()).allocateIndexedSettlementByQuantity(any(), any(), any(), any(), any());
    }

    private NormalizedTransaction.Flow flow(String symbol, String contract, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }
}
