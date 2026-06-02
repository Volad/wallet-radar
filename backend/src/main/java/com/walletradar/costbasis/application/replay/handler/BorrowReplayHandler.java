package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

/**
 * Basis-neutral BORROW inflow with parallel liability tracking (ADR-012 §D2).
 */
@Component
public class BorrowReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final BorrowLiabilityTracker borrowLiabilityTracker;
    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;

    public BorrowReplayHandler(
            BorrowLiabilityTracker borrowLiabilityTracker,
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport
    ) {
        this.borrowLiabilityTracker = borrowLiabilityTracker;
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
    }

    public void apply(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            ReplayExecutionState replayState
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);

        BigDecimal qty = flow.getQuantityDelta().abs();
        // Borrowed assets are fresh acquisitions: always price at market, not at position AVCO.
        // Using position AVCO inflates basis when the existing AVCO diverges from the borrow
        // asset's market price (e.g., a USDC position with $1,532 AVCO from LP rebalancing
        // would price a $800 USDC borrow at $1,225,570).
        BigDecimal portfolioAvco = flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0
                ? flow.getUnitPriceUsd()
                : BigDecimal.ZERO;
        PriceSource avcoSource = flow.getPriceSource() == null ? PriceSource.UNKNOWN : flow.getPriceSource();

        BorrowLiabilityReplayContext liabilityContext = replayState.borrowLiabilityContext();
        if (liabilityContext != null) {
            String orderId = resolveOrderId(transaction);
            if (orderId != null) {
                borrowLiabilityTracker.recordBorrow(
                        liabilityContext.universeId(),
                        orderId,
                        flow.getAccountRef() != null ? flow.getAccountRef() : transaction.getWalletAddress(),
                        flow.getAssetSymbol(),
                        qty,
                        portfolioAvco,
                        avcoSource,
                        eventTime(transaction),
                        liabilityContext.liabilitiesByCompositeId(),
                        liabilityContext.dirtyCompositeIds()
                );
            }
        }

        BigDecimal acquisitionCostUsd = qty.multiply(portfolioAvco, MC);
        flowSupport.applyBuyWithAcquisitionCost(flow, position, acquisitionCostUsd);

        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );
    }

    static String resolveOrderId(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getCorrelationId() == null || transaction.getCorrelationId().isBlank()) {
            return null;
        }
        return transaction.getCorrelationId().trim();
    }

    private static Instant eventTime(NormalizedTransaction transaction) {
        return transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
    }
}
