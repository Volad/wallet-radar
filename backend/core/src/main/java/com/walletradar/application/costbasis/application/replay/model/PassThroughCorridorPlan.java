package com.walletradar.application.costbasis.application.replay.model;

import java.util.Collections;
import java.util.Map;

public record PassThroughCorridorPlan(
        Map<FlowRef, PassThroughCorridor> byInboundFlowRef,
        Map<FlowRef, PassThroughCorridor> byOutboundFlowRef
) {

    public static PassThroughCorridorPlan empty() {
        return new PassThroughCorridorPlan(Collections.emptyMap(), Collections.emptyMap());
    }
}
