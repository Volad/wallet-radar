package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;

public record PassThroughCandidate(
        FlowRef flowRef,
        PassThroughScopeKey scopeKey,
        BigDecimal quantity
) {
}
