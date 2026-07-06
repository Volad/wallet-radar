package com.walletradar.costbasis.application.replay.model;

import com.walletradar.domain.common.NetworkId;

import java.util.Objects;

public record AssetKey(
        String walletAddress,
        NetworkId networkId,
        String assetContract,
        String assetSymbol,
        String assetIdentity
) {
    public String id() {
        return walletAddress + ":" + Objects.toString(networkId, "BYBIT") + ":" + assetContract;
    }
}
