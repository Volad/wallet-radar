package com.walletradar.application.costbasis.application.replay.state;

import com.walletradar.application.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.application.costbasis.application.replay.model.ContinuityKey;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ContinuityStore {

    private final Map<ContinuityKey, ContinuityBucket> buckets = new LinkedHashMap<>();

    public ContinuityBucket bucket(ContinuityKey key) {
        return buckets.computeIfAbsent(key, ignored -> new ContinuityBucket());
    }

    public Map<ContinuityKey, ContinuityBucket> asMap() {
        return buckets;
    }
}
