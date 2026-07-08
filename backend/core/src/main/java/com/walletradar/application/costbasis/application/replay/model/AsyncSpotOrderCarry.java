package com.walletradar.application.costbasis.application.replay.model;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

public record AsyncSpotOrderCarry(
        CarryTransfer carry,
        NormalizedTransaction.Flow requestFlow
) {
}
