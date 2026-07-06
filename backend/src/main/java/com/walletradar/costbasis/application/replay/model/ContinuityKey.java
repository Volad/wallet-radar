package com.walletradar.costbasis.application.replay.model;

import com.walletradar.domain.common.NetworkId;

public record ContinuityKey(
        String walletAddress,
        NetworkId networkId,
        String continuityIdentity
) {
}
