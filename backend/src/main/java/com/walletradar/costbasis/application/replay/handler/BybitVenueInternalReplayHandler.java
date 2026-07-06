package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.ingestion.pipeline.bybit.BybitEarnPrincipalTransferPairer;
import com.walletradar.ingestion.pipeline.clarification.BybitOnChainEarnOrphanRepairService;
import org.springframework.stereotype.Component;

/**
 * Routes Bybit venue-internal principal moves that were paired at ingestion with a shared
 * {@code bybit-earn-principal-v1:*}, {@code bybit-earn-onchain-v1:*}, or
 * {@code bybit-earn-onchain-fund-v1:*} correlation through the continuity transfer path.
 *
 * <p>{@code bybit-earn-onchain-v1:} covers spot-funded FUND→EARN repairs (METH, USDT, etc.).
 * {@code bybit-earn-onchain-fund-v1:} covers corridor-funded repairs where the asset arrived
 * via a BYBIT-CORRIDOR deposit into the {@code :FUND} sub-account (e.g. ARB).
 */
@Component
public class BybitVenueInternalReplayHandler {

    private final ReplayTransferClassifier classifier;
    private final TransferReplayHandler transferReplayHandler;

    public BybitVenueInternalReplayHandler(
            ReplayTransferClassifier classifier,
            TransferReplayHandler transferReplayHandler
    ) {
        this.classifier = classifier;
        this.transferReplayHandler = transferReplayHandler;
    }

    public boolean applies(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (transaction == null
                || flow == null
                || flow.getRole() == NormalizedLegRole.FEE
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0
                || Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null
                || (!correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)
                        && !correlationId.startsWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_CORR_PREFIX)
                        && !correlationId.startsWith(BybitOnChainEarnOrphanRepairService.EARN_ONCHAIN_FUND_CORR_PREFIX))) {
            return false;
        }
        return Boolean.TRUE.equals(transaction.getContinuityCandidate())
                && classifier.shouldTreatAsContinuityTransfer(transaction, flow);
    }

    public AssetLedgerPoint.BasisEffect apply(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        return transferReplayHandler.applyTransfer(transaction, flow, flowIndex, position, replayState);
    }

}
