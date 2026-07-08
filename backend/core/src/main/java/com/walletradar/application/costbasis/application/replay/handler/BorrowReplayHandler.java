package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport;
import com.walletradar.application.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.BybitPledgeLoanCorrelationSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Optional;

/**
 * Basis-neutral BORROW inflow with parallel liability tracking (ADR-012 §D2).
 */
@Component
public class BorrowReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final BorrowLiabilityTracker borrowLiabilityTracker;
    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplayMarketAuthority replayMarketAuthority;

    public BorrowReplayHandler(
            BorrowLiabilityTracker borrowLiabilityTracker,
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            @Autowired(required = false) ReplayMarketAuthority replayMarketAuthority
    ) {
        this.borrowLiabilityTracker = borrowLiabilityTracker;
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.replayMarketAuthority = replayMarketAuthority;
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
        // F-4: the variableDebt*/stableDebt* mint leg is a liability marker, not an acquired asset.
        // The liability is recorded against the borrowed underlying (BUY) leg below; acquiring the
        // debt token here would re-introduce the phantom debt-as-asset position and fabricated uPnL.
        if (AccountingAssetIdentitySupport.isDebtIdentity(flow.getAssetSymbol())) {
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
        BigDecimal portfolioAvco;
        PriceSource avcoSource;
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0) {
            portfolioAvco = flow.getUnitPriceUsd();
            avcoSource = flow.getPriceSource() == null ? PriceSource.UNKNOWN : flow.getPriceSource();
        } else {
            // F-5(b): a borrowed asset entering the spot pool must carry market-at-borrow basis so
            // borrow→sell→rebuy→repay nets only the price change and the borrowed units never
            // depress the AVCO of pre-owned units (e.g. a 3,532 MNT borrow blending the pool to a
            // sub-market ~$0.72). Resolve the block-time market price instead of defaulting to $0.
            Optional<ReplayMarketAuthority.ResolvedMarketPrice> marketAtBorrow = resolveMarketAtBorrow(transaction, flow);
            if (marketAtBorrow.isPresent()) {
                portfolioAvco = marketAtBorrow.get().unitPriceUsd();
                avcoSource = marketAtBorrow.get().priceSource();
            } else {
                portfolioAvco = BigDecimal.ZERO;
                avcoSource = flow.getPriceSource() == null ? PriceSource.UNKNOWN : flow.getPriceSource();
            }
        }

        BorrowLiabilityReplayContext liabilityContext = replayState.borrowLiabilityContext();
        if (liabilityContext != null) {
            String orderId = resolveLoanOrderId(transaction, flow);
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

        // ADR-012 §D2 / Bug D: borrowed assets are liabilities, not economic acquisitions.
        // Tax (market) AVCO reflects the market price at borrow time so sell→repay cycles
        // price correctly for tax purposes. Net AVCO is $0: the borrowed unit has no net
        // cost basis since the corresponding liability exactly offsets the asset.
        BigDecimal acquisitionCostUsd = qty.multiply(portfolioAvco, MC);
        flowSupport.applyBuyWithExplicitNetCost(flow, position, acquisitionCostUsd, BigDecimal.ZERO);

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

    private Optional<ReplayMarketAuthority.ResolvedMarketPrice> resolveMarketAtBorrow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (replayMarketAuthority == null || transaction == null) {
            return Optional.empty();
        }
        return replayMarketAuthority.resolve(transaction, flow)
                .filter(price -> price.unitPriceUsd() != null && price.unitPriceUsd().signum() > 0);
    }

    static String resolveOrderId(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getCorrelationId() == null || transaction.getCorrelationId().isBlank()) {
            return null;
        }
        return transaction.getCorrelationId().trim();
    }

    /**
     * R-4: resolves the liability key for a loan flow. Bybit pledge BORROW/REPAY legs share a
     * deterministic per-(uid, asset) revolving key so the repay nets against its opening borrow and
     * books ~$0. All other loans (on-chain Aave {@code evm:*} and exchange-issued orderIds) keep
     * the transaction correlation id unchanged.
     */
    static String resolveLoanOrderId(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        String bybitPledgeLoanId = BybitPledgeLoanCorrelationSupport.syntheticLoanCorrelationId(transaction, flow);
        if (bybitPledgeLoanId != null) {
            return bybitPledgeLoanId;
        }
        return resolveOrderId(transaction);
    }

    private static Instant eventTime(NormalizedTransaction transaction) {
        return transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
    }
}
