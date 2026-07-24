package com.walletradar.application.costbasis.application.replay.planning;

import com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodySelection;

public record ReplayRoutingDecision(
        ReplayRoute route,
        LiquidStakingFlowSelection liquidStakingSelection,
        SimpleFamilyCustodySelection familyCustodySelection
) {
    public static ReplayRoutingDecision of(ReplayRoute route) {
        return new ReplayRoutingDecision(route, LiquidStakingFlowSelection.empty(), SimpleFamilyCustodySelection.empty());
    }

    public static ReplayRoutingDecision clusterCarry(LiquidStakingFlowSelection selection) {
        return new ReplayRoutingDecision(ReplayRoute.CLUSTER_CARRY, selection, SimpleFamilyCustodySelection.empty());
    }

    public static ReplayRoutingDecision familyEquivalentCustody(SimpleFamilyCustodySelection selection) {
        return new ReplayRoutingDecision(ReplayRoute.FAMILY_EQUIVALENT_CUSTODY, LiquidStakingFlowSelection.empty(), selection);
    }

    public static ReplayRoutingDecision generic() {
        return of(ReplayRoute.GENERIC);
    }
}
