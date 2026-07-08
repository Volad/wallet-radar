package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;

public record PassThroughCorridor(
        FlowRef inboundFlowRef,
        FlowRef outboundFlowRef,
        BigDecimal reservedQuantity
) {
}
