package com.walletradar.costbasis.application.replay.state;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionState;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PositionStore {

    private final Map<AssetKey, PositionState> positions = new LinkedHashMap<>();

    public PositionState position(AssetKey assetKey) {
        return positions.computeIfAbsent(assetKey, ignored -> new PositionState(assetKey));
    }

    public Map<AssetKey, PositionState> asMap() {
        return positions;
    }
}
