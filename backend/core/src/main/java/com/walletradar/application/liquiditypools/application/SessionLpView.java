package com.walletradar.application.liquiditypools.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SessionLpView(
        String sessionId,
        LpSummaryView summary,
        List<LpPositionView> positions
) {
}
