package com.walletradar.costbasis.application.replay.model;

import java.util.Map;

public record PassThroughCorridorPlan(
        Map<FlowRef, PassThroughCorridor> byInboundFlowRef,
        Map<FlowRef, PassThroughCorridor> byOutboundFlowRef
) {
}
