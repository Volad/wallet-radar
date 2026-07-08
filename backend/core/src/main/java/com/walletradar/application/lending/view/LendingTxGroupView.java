package com.walletradar.application.lending.view;

import java.time.Instant;
import java.util.List;

public record LendingTxGroupView(
        String id,
        String type,
        Instant timestamp,
        String dateLabel,
        Integer loopSteps,
        String loopAssetIn,
        String loopAssetOut,
        List<LendingTxItemView> items
) {
}
