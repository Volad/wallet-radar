package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.CounterpartyBasisPoolService;
import com.walletradar.costbasis.application.CounterpartyBasisPoolService.PopResult;
import com.walletradar.costbasis.domain.CounterpartyBasisPool;
import com.walletradar.costbasis.domain.CounterpartyBasisPoolKey;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.CounterpartyBasisPoolReplayContext;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.clarification.CounterpartyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

/**
 * Applies per-counterparty basis pool adjustments during AVCO replay (ADR-015 §D2).
 */
@Component
public class CounterpartyBasisPoolReplayHook {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final CounterpartyBasisPoolService poolService;
    private final ReplayTransferClassifier transferClassifier;

    public CounterpartyBasisPoolReplayHook(
            CounterpartyBasisPoolService poolService,
            ReplayTransferClassifier transferClassifier
    ) {
        this.poolService = poolService;
        this.transferClassifier = transferClassifier;
    }

    public boolean shouldApplyPool(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            boolean usesContinuityTransferPath
    ) {
        if (!poolService.shouldTrackFlow(flow)) {
            return false;
        }
        // ADR-015 §D3: pool keys include network; flows without a resolved network (e.g. Bybit FH
        // rows whose chain sibling was not hydrated) cannot deterministically pick a per-network
        // bucket and must therefore bypass the counterparty pool path.
        if (transaction == null || transaction.getNetworkId() == null) {
            return false;
        }
        if (!tracksCounterpartyPool(transaction.getType())) {
            return false;
        }
        if (usesContinuityTransferPath && defersToContinuityCarry(transaction, flow)) {
            return false;
        }
        return flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL;
    }

    private static boolean tracksCounterpartyPool(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || type == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || type == NormalizedTransactionType.INTERNAL_TRANSFER;
    }

    public BigDecimal acquisitionCostUsdForBuy(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            CounterpartyBasisPoolReplayContext poolContext,
            boolean usesContinuityTransferPath
    ) {
        if (poolContext == null || !shouldApplyPool(transaction, flow, usesContinuityTransferPath)) {
            return null;
        }
        CounterpartyBasisPool pool = poolService.lookupOrCreate(
                poolContext.universeId(),
                transaction.getNetworkId(),
                flow,
                poolContext.pools(),
                poolContext.dirtyKeys(),
                eventTime(transaction)
        );
        PopResult pop = poolService.popIn(pool, flow.getQuantityDelta().abs(), flow.getUnitPriceUsd());
        flow.setRealisedPnlUsd(BigDecimal.ZERO);
        return pop.popBasisUsd().add(pop.residualBasisUsd(), MC);
    }

    public void afterSell(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionSnapshot positionBeforeSell,
            CounterpartyBasisPoolReplayContext poolContext,
            boolean usesContinuityTransferPath
    ) {
        if (poolContext == null || !shouldApplyPool(transaction, flow, usesContinuityTransferPath)) {
            return;
        }
        BigDecimal qty = flow.getQuantityDelta().abs();
        BigDecimal avcoBefore = positionBeforeSell.perWalletAvco();
        BigDecimal outBasisUsd = avcoBefore == null
                ? BigDecimal.ZERO
                : qty.multiply(avcoBefore, MC);
        CounterpartyBasisPool pool = poolService.lookupOrCreate(
                poolContext.universeId(),
                transaction.getNetworkId(),
                flow,
                poolContext.pools(),
                poolContext.dirtyKeys(),
                eventTime(transaction)
        );
        poolService.pushOut(pool, qty, outBasisUsd, flow);
    }

    public void undoSellRealisedPnl(
            NormalizedTransaction.Flow flow,
            PositionState position,
            PositionSnapshot beforeSell
    ) {
        BigDecimal realised = flow.getRealisedPnlUsd();
        if (realised != null && realised.signum() != 0) {
            position.setTotalRealisedPnlUsd(
                    position.totalRealisedPnlUsd().subtract(realised, MC)
            );
            position.setTotalNetRealisedPnlUsd(
                    position.totalNetRealisedPnlUsd().subtract(realised, MC)
            );
        }
        flow.setRealisedPnlUsd(BigDecimal.ZERO);
        flow.setAvcoAtTimeOfSale(beforeSell.perWalletAvco());
    }

    private boolean defersToContinuityCarry(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (!transferClassifier.shouldTreatAsContinuityTransfer(transaction, flow)) {
            return false;
        }
        if (transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return true;
        }
        return CounterpartyType.PERSONAL_WALLET.equals(flow.getCounterpartyType())
                || CounterpartyType.CEX.equals(flow.getCounterpartyType());
    }

    private static Instant eventTime(NormalizedTransaction transaction) {
        return transaction.getBlockTimestamp() == null ? Instant.now() : transaction.getBlockTimestamp();
    }
}
