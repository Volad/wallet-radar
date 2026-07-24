package com.walletradar.application.costbasis.application.replay.planning;

import com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Predicate;

@Component
public class ReplayTransactionRouter {

    public ReplayRoutingDecision route(
            NormalizedTransaction transaction,
            Predicate<NormalizedTransaction> gmxLpEntryRequestMatcher,
            Predicate<NormalizedTransaction> gmxLpEntrySettlementMatcher,
            Predicate<NormalizedTransaction> lpReceiptEntryMatcher,
            Predicate<NormalizedTransaction> positionScopedLpExitMatcher,
            Function<NormalizedTransaction, LiquidStakingFlowSelection> liquidStakingSelector,
            Function<NormalizedTransaction, SimpleFamilyCustodySelection> familyCustodySelector
    ) {
        if (transaction == null) {
            return ReplayRoutingDecision.generic();
        }
        if (transaction.getType() == NormalizedTransactionType.LENDING_LOOP_REBALANCE) {
            return ReplayRoutingDecision.of(ReplayRoute.EULER_LOOP);
        }
        if (gmxLpEntryRequestMatcher.test(transaction)) {
            return ReplayRoutingDecision.of(ReplayRoute.GMX_LP_ENTRY_REQUEST);
        }
        if (gmxLpEntrySettlementMatcher.test(transaction)) {
            return ReplayRoutingDecision.of(ReplayRoute.GMX_LP_ENTRY_SETTLEMENT);
        }
        if (lpReceiptEntryMatcher.test(transaction)) {
            return ReplayRoutingDecision.of(ReplayRoute.LP_RECEIPT_ENTRY);
        }
        if (transaction.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT) {
            return ReplayRoutingDecision.of(ReplayRoute.ASYNC_LP_EXIT_SETTLEMENT);
        }
        if (positionScopedLpExitMatcher.test(transaction)) {
            return ReplayRoutingDecision.of(ReplayRoute.POSITION_SCOPED_LP_EXIT);
        }

        LiquidStakingFlowSelection liquidStakingSelection = liquidStakingSelector.apply(transaction);
        if (!liquidStakingSelection.outbound().isEmpty() && !liquidStakingSelection.inbound().isEmpty()) {
            return ReplayRoutingDecision.clusterCarry(liquidStakingSelection);
        }

        SimpleFamilyCustodySelection familyCustodySelection = familyCustodySelector.apply(transaction);
        if (!familyCustodySelection.pairs().isEmpty()) {
            return ReplayRoutingDecision.familyEquivalentCustody(familyCustodySelection);
        }
        return ReplayRoutingDecision.generic();
    }
}
