package com.walletradar.application.costbasis.application;

import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodyPair;
import com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoute;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayTransactionRouterTest {

    private final ReplayTransactionRouter router = new ReplayTransactionRouter();

    @Test
    void routesLiquidStakingBeforeFamilyEquivalentCustodyFallback() {
        ReplayRoutingDecision decision = router.route(
                transaction(NormalizedTransactionType.STAKING_DEPOSIT),
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> new LiquidStakingFlowSelection(List.of(indexedFlow(0)), List.of(indexedFlow(1))),
                ignored -> new SimpleFamilyCustodySelection(List.of(new SimpleFamilyCustodyPair(indexedFlow(2), indexedFlow(3))), java.util.Map.of())
        );

        assertThat(decision.route()).isEqualTo(ReplayRoute.LIQUID_STAKING);
        assertThat(decision.liquidStakingSelection().outbound()).hasSize(1);
        assertThat(decision.familyCustodySelection().pairs()).isEmpty();
    }

    @Test
    void routesFamilyEquivalentCustodyWhenNoHigherPriorityHandlerMatches() {
        ReplayRoutingDecision decision = router.route(
                transaction(NormalizedTransactionType.LENDING_DEPOSIT),
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> LiquidStakingFlowSelection.empty(),
                ignored -> new SimpleFamilyCustodySelection(List.of(new SimpleFamilyCustodyPair(indexedFlow(0), indexedFlow(1))), java.util.Map.of())
        );

        assertThat(decision.route()).isEqualTo(ReplayRoute.FAMILY_EQUIVALENT_CUSTODY);
        assertThat(decision.familyCustodySelection().pairs()).hasSize(1);
    }

    @Test
    void routesGmxRequestBeforeSelectionDrivenHandlers() {
        ReplayRoutingDecision decision = router.route(
                transaction(NormalizedTransactionType.LP_ENTRY_REQUEST),
                ignored -> true,
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> new LiquidStakingFlowSelection(List.of(indexedFlow(0)), List.of(indexedFlow(1))),
                ignored -> new SimpleFamilyCustodySelection(List.of(new SimpleFamilyCustodyPair(indexedFlow(2), indexedFlow(3))), java.util.Map.of())
        );

        assertThat(decision.route()).isEqualTo(ReplayRoute.GMX_LP_ENTRY_REQUEST);
        assertThat(decision.liquidStakingSelection().outbound()).isEmpty();
        assertThat(decision.familyCustodySelection().pairs()).isEmpty();
    }

    @Test
    void fallsBackToGenericWhenNoSpecializedRouteMatches() {
        ReplayRoutingDecision decision = router.route(
                transaction(NormalizedTransactionType.SWAP),
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> LiquidStakingFlowSelection.empty(),
                ignored -> SimpleFamilyCustodySelection.empty()
        );

        assertThat(decision.route()).isEqualTo(ReplayRoute.GENERIC);
    }

    private NormalizedTransaction transaction(NormalizedTransactionType type) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(type);
        return transaction;
    }

    private IndexedFlow indexedFlow(int index) {
        return new IndexedFlow(index, new NormalizedTransaction.Flow());
    }
}
