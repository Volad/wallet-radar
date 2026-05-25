package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.costbasis.application.BorrowLiabilityTracker.RepayMatch;
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
 * REPAY disposal with zero realised PnL on liability-matched principal (ADR-012 §D3).
 */
@Component
public class RepayReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final BorrowLiabilityTracker borrowLiabilityTracker;
    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;

    public RepayReplayHandler(
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
        RepayMatch match = RepayMatch.zero();
        BorrowLiabilityReplayContext liabilityContext = replayState.borrowLiabilityContext();
        if (liabilityContext != null) {
            String orderId = BorrowReplayHandler.resolveOrderId(transaction);
            if (orderId != null) {
                match = borrowLiabilityTracker.recordRepay(
                        liabilityContext.universeId(),
                        orderId,
                        flow.getAccountRef() != null ? flow.getAccountRef() : transaction.getWalletAddress(),
                        flow.getAssetSymbol(),
                        qty,
                        eventTime(transaction),
                        liabilityContext.liabilitiesByCompositeId(),
                        liabilityContext.dirtyCompositeIds()
                );
            }
        }

        if (match.matchedQty().signum() > 0 && match.residualQty().signum() == 0) {
            flow.setUnitPriceUsd(match.liabilityAvcoUsd());
            flow.setPriceSource(PriceSource.LIABILITY_MATCH);
            flowSupport.applySell(flow, position);
        } else {
            flowSupport.applySell(flow, position);
            if (match.matchedQty().signum() > 0 && match.liabilityFound()) {
                adjustMixedRepayRealised(flow, position, before, match);
            }
        }

        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowIndex,
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.DISPOSE
        );
    }

    private void adjustMixedRepayRealised(
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot before,
            RepayMatch match
    ) {
        BigDecimal marketPrice = flow.getUnitPriceUsd();
        BigDecimal avcoBefore = before.perWalletAvco();
        if (marketPrice == null || avcoBefore == null || match.residualQty().signum() <= 0) {
            BigDecimal priorRealised = flow.getRealisedPnlUsd() == null ? BigDecimal.ZERO : flow.getRealisedPnlUsd();
            if (match.matchedQty().signum() > 0) {
                position.setTotalRealisedPnlUsd(position.totalRealisedPnlUsd().subtract(priorRealised, MC));
                flow.setRealisedPnlUsd(BigDecimal.ZERO);
                flow.setAvcoAtTimeOfSale(match.liabilityAvcoUsd());
                flow.setPriceSource(PriceSource.LIABILITY_MATCH);
            }
            return;
        }
        BigDecimal residualRealised = marketPrice.subtract(avcoBefore, MC).multiply(match.residualQty(), MC);
        BigDecimal priorRealised = flow.getRealisedPnlUsd() == null ? BigDecimal.ZERO : flow.getRealisedPnlUsd();
        position.setTotalRealisedPnlUsd(
                position.totalRealisedPnlUsd().subtract(priorRealised, MC).add(residualRealised, MC)
        );
        flow.setRealisedPnlUsd(residualRealised);
        flow.setAvcoAtTimeOfSale(avcoBefore);
    }

    private static Instant eventTime(NormalizedTransaction transaction) {
        return transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
    }
}
