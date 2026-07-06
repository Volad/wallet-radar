package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.domain.BorrowLiability;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.LeverageBorrowAnnotation;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.ingestion.pipeline.classification.support.AaveDebtLoanCorrelationSupport;
import com.walletradar.ingestion.pipeline.classification.support.LeverageAcquisitionDetector;
import com.walletradar.ingestion.pipeline.classification.support.LeverageAcquisitionDetector.LeverageDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Records the synthetic borrow of an inferred leveraged buy (ADR-028) during AVCO replay.
 *
 * <p>The collateral BUY leg is already applied at market spot by the generic flow path, so this hook
 * adds <strong>no asset lot</strong> — it only registers the value gap as a liability via
 * {@link BorrowLiabilityTracker#recordBorrow}. This keeps {@code ΔadjustedMtm = ΔMTM − ΔopenLiab = 0}
 * at the leveraged buy, so the collateral no longer fabricates a gain when it is later disposed.</p>
 *
 * <p>The borrow is denominated in synthetic USD principal ({@code asset="USD", qty=gap, avco=$1}); a
 * later disposal of the collateral realises true PnL on the market basis, and the liability stays
 * open (the embedded borrow has no separate on-chain REPAY row of its own type).</p>
 */
@Component
@Slf4j
public class LeverageBorrowReplayHook {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String SYNTHETIC_USD_ASSET = "USD";
    private static final BigDecimal SYNTHETIC_USD_AVCO = BigDecimal.ONE;
    /** Below this absolute end-of-ledger quantity the collateral is treated as drained, not held. */
    private static final BigDecimal COLLATERAL_HELD_EPSILON = new BigDecimal("0.000001");

    private final LeverageAcquisitionDetector detector;
    private final BorrowLiabilityTracker borrowLiabilityTracker;

    public LeverageBorrowReplayHook(
            LeverageAcquisitionDetector detector,
            BorrowLiabilityTracker borrowLiabilityTracker
    ) {
        this.detector = detector;
        this.borrowLiabilityTracker = borrowLiabilityTracker;
    }

    /**
     * Registers the synthetic borrow exactly once for a leveraged-buy transaction. No-op for ordinary
     * swaps, non-divergent acquisitions, or when no liability context is present.
     */
    public void applyIfLeverage(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || replayState == null) {
            return;
        }
        if (!LeverageBorrowAnnotation.isLeveragedBuy(transaction)) {
            return;
        }
        BorrowLiabilityReplayContext liabilityContext = replayState.borrowLiabilityContext();
        if (liabilityContext == null) {
            return;
        }
        String collateralSymbol = LeverageBorrowAnnotation.collateralSymbol(transaction);
        BigDecimal collateralMarketUsd = receivedValueUsd(transaction, collateralSymbol);
        BigDecimal considerationUsd = paidValueUsd(transaction);
        LeverageDecision decision = detector.decide(collateralMarketUsd, considerationUsd, true);
        if (decision != LeverageDecision.LEVERAGED) {
            // Value gap collapsed once both legs were market-priced — not actually leveraged.
            return;
        }
        BigDecimal principalUsd = collateralMarketUsd.subtract(considerationUsd, MC);
        if (principalUsd.signum() <= 0) {
            return;
        }
        String correlationId = LeverageBorrowAnnotation.loanCorrelationId(transaction);
        borrowLiabilityTracker.recordBorrow(
                liabilityContext.universeId(),
                correlationId,
                transaction.getWalletAddress(),
                SYNTHETIC_USD_ASSET,
                principalUsd,
                SYNTHETIC_USD_AVCO,
                PriceSource.STABLECOIN,
                eventTime(transaction),
                liabilityContext.liabilitiesByCompositeId(),
                liabilityContext.dirtyCompositeIds()
        );
        log.debug("LEVERAGE_BORROW_RECORDED txId={} corrId={} collateral={} principalUsd={}",
                transaction.getId(), correlationId, collateralSymbol, principalUsd);
    }

    /**
     * Closes synthetic leverage liabilities (ADR-028) whose funded collateral has fully drained from
     * the leverage wallet by end-of-ledger.
     *
     * <p>An inferred leverage borrow is keyed {@code evm-lev:&lt;network&gt;:&lt;collateralContract&gt;:&lt;wallet&gt;}
     * and has no on-chain USD {@code REPAY} of its own type, so {@code RepayReplayHandler} can never
     * match it. For a fully round-tripped leveraged trade (collateral sold, zapped, or corridored out)
     * the proceeds already realised the true PnL on the market basis; the embedded borrow must then be
     * settled, otherwise the open liability permanently depresses {@code expectedPnl} with no offsetting
     * entry in {@code reportedPnl} (architect risk H).</p>
     *
     * <p>This runs once after the replay loop, when every position is final. It is conservation-safe and
     * fails safe to OPEN: if the collateral contract is still held at the leverage wallet (genuinely
     * outstanding leverage), or the correlation id cannot be parsed, the liability is left untouched.
     * The trigger is the collateral end-state, never a transaction hash.</p>
     */
    public void closeDrainedLeverageLiabilities(ReplayExecutionState replayState) {
        if (replayState == null) {
            return;
        }
        BorrowLiabilityReplayContext liabilityContext = replayState.borrowLiabilityContext();
        if (liabilityContext == null || liabilityContext.liabilitiesByCompositeId() == null) {
            return;
        }
        Map<String, BorrowLiability> book = liabilityContext.liabilitiesByCompositeId();
        for (BorrowLiability liability : List.copyOf(book.values())) {
            if (!isOpenSyntheticLeverage(liability)) {
                continue;
            }
            LeverageCollateralRef ref = LeverageCollateralRef.parse(liability.getOrderId());
            if (ref == null) {
                continue;
            }
            if (collateralStillHeld(replayState, ref)) {
                continue;
            }
            BigDecimal principalToClose = liability.getQtyOpen();
            borrowLiabilityTracker.recordRepay(
                    liabilityContext.universeId(),
                    liability.getOrderId(),
                    liability.getAccountRef(),
                    liability.getAsset(),
                    principalToClose,
                    closeTime(liability),
                    book,
                    liabilityContext.dirtyCompositeIds()
            );
            log.debug("LEVERAGE_LIABILITY_CLOSED_ON_DRAIN orderId={} collateralContract={} principalClosed={}",
                    liability.getOrderId(), ref.collateralContract(), principalToClose);
        }
    }

    private static boolean isOpenSyntheticLeverage(BorrowLiability liability) {
        if (liability == null || liability.getOrderId() == null) {
            return false;
        }
        if (!liability.getOrderId().startsWith(AaveDebtLoanCorrelationSupport.LEVERAGE_LOAN_PREFIX)) {
            return false;
        }
        BigDecimal open = liability.getQtyOpen();
        return open != null && open.signum() > 0;
    }

    private static boolean collateralStillHeld(ReplayExecutionState replayState, LeverageCollateralRef ref) {
        for (Map.Entry<AssetKey, PositionState> entry : replayState.positions().asMap().entrySet()) {
            if (!ref.matches(entry.getKey())) {
                continue;
            }
            BigDecimal qty = entry.getValue().quantity();
            if (qty != null && qty.abs().compareTo(COLLATERAL_HELD_EPSILON) > 0) {
                return true;
            }
        }
        return false;
    }

    private static Instant closeTime(BorrowLiability liability) {
        if (liability.getLastTouchedAt() != null) {
            return liability.getLastTouchedAt();
        }
        if (liability.getOpenedAt() != null) {
            return liability.getOpenedAt();
        }
        return Instant.now();
    }

    /**
     * Parsed {@code evm-lev:&lt;network&gt;:&lt;collateralContract&gt;:&lt;wallet&gt;} correlation key. Network is
     * compared best-effort: the collateral contract plus wallet already identify the funded position
     * uniquely, and {@code AssetKey.networkId()} renders as the {@code NetworkId} enum name.
     */
    private record LeverageCollateralRef(String network, String collateralContract, String wallet) {

        private static LeverageCollateralRef parse(String orderId) {
            if (orderId == null || !orderId.startsWith(AaveDebtLoanCorrelationSupport.LEVERAGE_LOAN_PREFIX)) {
                return null;
            }
            String[] parts = orderId.split(":");
            if (parts.length < 4) {
                return null;
            }
            String network = parts[1];
            String collateralContract = parts[2];
            String wallet = parts[3];
            if (collateralContract == null || collateralContract.isBlank()
                    || wallet == null || wallet.isBlank()) {
                return null;
            }
            return new LeverageCollateralRef(network, collateralContract, wallet);
        }

        private boolean matches(AssetKey key) {
            if (key == null) {
                return false;
            }
            if (key.walletAddress() == null || !key.walletAddress().equalsIgnoreCase(wallet)) {
                return false;
            }
            if (key.assetContract() == null || !key.assetContract().equalsIgnoreCase(collateralContract)) {
                return false;
            }
            String keyNetwork = Objects.toString(key.networkId(), null);
            return keyNetwork == null || network == null || keyNetwork.equalsIgnoreCase(network);
        }
    }

    private BigDecimal receivedValueUsd(NormalizedTransaction transaction, String collateralSymbol) {
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!isReceivedNonFee(flow)) {
                continue;
            }
            if (collateralSymbol != null
                    && (flow.getAssetSymbol() == null || !collateralSymbol.equalsIgnoreCase(flow.getAssetSymbol()))) {
                continue;
            }
            BigDecimal value = flowValueUsd(flow);
            if (value != null) {
                total = total.add(value, MC);
            }
        }
        return total;
    }

    private BigDecimal paidValueUsd(NormalizedTransaction transaction) {
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!isPaidNonFee(flow)) {
                continue;
            }
            BigDecimal value = flowValueUsd(flow);
            if (value != null) {
                total = total.add(value, MC);
            }
        }
        return total;
    }

    private BigDecimal flowValueUsd(NormalizedTransaction.Flow flow) {
        if (flow.getValueUsd() != null) {
            return flow.getValueUsd().abs();
        }
        if (flow.getQuantityDelta() != null && flow.getUnitPriceUsd() != null) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
        }
        return null;
    }

    private boolean isReceivedNonFee(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0;
    }

    private boolean isPaidNonFee(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() < 0;
    }

    private static Instant eventTime(NormalizedTransaction transaction) {
        return transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
    }
}
