package com.walletradar.application.costbasis.application.replay.model;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

public record IndexedFlow(
        int index,
        NormalizedTransaction.Flow flow
) {
}
