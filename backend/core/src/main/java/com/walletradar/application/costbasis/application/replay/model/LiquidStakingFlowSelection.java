package com.walletradar.application.costbasis.application.replay.model;

import java.util.List;

public record LiquidStakingFlowSelection(
        List<IndexedFlow> outbound,
        List<IndexedFlow> inbound
) {
    public static LiquidStakingFlowSelection empty() {
        return new LiquidStakingFlowSelection(List.of(), List.of());
    }
}
