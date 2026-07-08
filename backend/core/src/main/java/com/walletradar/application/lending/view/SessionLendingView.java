package com.walletradar.application.lending.view;

import java.util.List;

public record SessionLendingView(
        String sessionId,
        LendingSummaryView summary,
        List<LendingGroupView> groups
) {
}
