package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.application.costbasis.application.BorrowLiabilityTracker.RepayMatch;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

/**
 * REPAY disposal with zero realised PnL on liability-matched principal in BOTH the Market and Net
 * lanes (ADR-012 §D3 + ADR-040 §5, 2026-07-18 amendment). Over-repay beyond the tracked liability
 * (residual) is realised at position AVCO in both lanes.
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
        // F-4: the variableDebt*/stableDebt* burn leg is a liability marker, not a disposed asset.
        // The liability is closed against the repaid underlying (SELL) leg below; disposing the debt
        // token here would realise phantom PnL on a non-tradable receipt.
        if (AccountingAssetIdentitySupport.isDebtIdentity(flow.getAssetSymbol())) {
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
            String orderId = BorrowReplayHandler.resolveLoanOrderId(transaction, flow);
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
            // Full liability match: zero realised PnL in BOTH lanes (ADR-012 §D3 + ADR-040 §5).
            // applySell books realised at the position's blended Market AVCO and Net AVCO, either
            // of which may diverge from the individual liability AVCO used as the sale price. With
            // borrowed principal now entering both lanes at market-at-borrow basis (ADR-040 §5),
            // the two deltas are numerically equal for a pure borrowed pool, so zeroing each lane
            // is a no-op there. The explicit per-lane zeroing is defense-in-depth for blended pools
            // (netAvco != marketAvco): it removes any residual (marketAvco − netAvco) × matchedQty
            // Net-lane term that the prior priorRealised-only correction left behind — precisely the
            // phantom that the historical net-$0 borrow basis produced (net realised ≈ marketAvco ×
            // qty even when the Market lane already netted to $0). We subtract each lane's actual
            // applySell delta (measured against the pre-repay snapshot), so the correction is
            // coverage-aware and fires even when the Market delta is $0 but the Net delta is not.
            BigDecimal marketRealisedDelta =
                    position.totalRealisedPnlUsd().subtract(before.totalRealisedPnlUsd(), MC);
            BigDecimal netRealisedDelta =
                    position.totalNetRealisedPnlUsd().subtract(before.totalNetRealisedPnlUsd(), MC);
            if (marketRealisedDelta.signum() != 0) {
                position.setTotalRealisedPnlUsd(position.totalRealisedPnlUsd().subtract(marketRealisedDelta, MC));
            }
            if (netRealisedDelta.signum() != 0) {
                position.setTotalNetRealisedPnlUsd(position.totalNetRealisedPnlUsd().subtract(netRealisedDelta, MC));
            }
            flow.setRealisedPnlUsd(BigDecimal.ZERO);
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
                position.setTotalNetRealisedPnlUsd(position.totalNetRealisedPnlUsd().subtract(priorRealised, MC));
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
        position.setTotalNetRealisedPnlUsd(
                position.totalNetRealisedPnlUsd().subtract(priorRealised, MC).add(residualRealised, MC)
        );
        flow.setRealisedPnlUsd(residualRealised);
        flow.setAvcoAtTimeOfSale(avcoBefore);
    }

    private static Instant eventTime(NormalizedTransaction transaction) {
        return transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
    }
}
