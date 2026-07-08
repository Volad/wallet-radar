package com.walletradar.application.costbasis.application.replay.state;

import com.walletradar.application.costbasis.application.replay.model.AsyncSpotOrderBucket;
import com.walletradar.application.costbasis.application.replay.model.CorrelationRef;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AsyncSpotOrderBucketStore {

    private final Map<CorrelationRef, AsyncSpotOrderBucket> buckets = new LinkedHashMap<>();

    public AsyncSpotOrderBucket bucket(CorrelationRef correlationRef) {
        return buckets.computeIfAbsent(correlationRef, ignored -> new AsyncSpotOrderBucket());
    }

    public AsyncSpotOrderBucket find(CorrelationRef correlationRef) {
        return buckets.get(correlationRef);
    }

    public void remove(CorrelationRef correlationRef) {
        buckets.remove(correlationRef);
    }
}
