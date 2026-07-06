package com.walletradar.costbasis.application.replay.state;

import com.walletradar.costbasis.application.replay.model.AsyncLifecycleBucket;
import com.walletradar.costbasis.application.replay.model.CorrelationRef;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AsyncLifecycleBucketStore {

    private final Map<CorrelationRef, AsyncLifecycleBucket> buckets = new LinkedHashMap<>();

    public AsyncLifecycleBucket bucket(CorrelationRef correlationRef) {
        return buckets.computeIfAbsent(correlationRef, ignored -> new AsyncLifecycleBucket());
    }

    public void remove(CorrelationRef correlationRef) {
        buckets.remove(correlationRef);
    }
}
