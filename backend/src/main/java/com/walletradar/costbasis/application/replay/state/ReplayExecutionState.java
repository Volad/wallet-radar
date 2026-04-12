package com.walletradar.costbasis.application.replay.state;

import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.CorrelationRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ReplayExecutionState {

    private final PositionStore positions = new PositionStore();
    private final ContinuityStore continuity = new ContinuityStore();
    private final PendingTransferStore pendingTransfers = new PendingTransferStore();
    private final PassThroughCorridorPlan passThroughCorridorPlan;
    private final Map<FlowRef, CarryTransfer> reservedPassThroughCarries = new LinkedHashMap<>();
    private final AsyncLifecycleBucketStore asyncLifecycleBuckets = new AsyncLifecycleBucketStore();
    private final AsyncSpotOrderBucketStore asyncSpotOrderBuckets = new AsyncSpotOrderBucketStore();
    private final LedgerPointCollector ledgerPointCollector;

    public ReplayExecutionState(
            PassThroughCorridorPlan passThroughCorridorPlan,
            LedgerPointCollector ledgerPointCollector
    ) {
        this.passThroughCorridorPlan = passThroughCorridorPlan;
        this.ledgerPointCollector = ledgerPointCollector;
    }

    public PositionStore positions() {
        return positions;
    }

    public PositionState position(AssetKey assetKey) {
        return positions.position(assetKey);
    }

    public ContinuityStore continuity() {
        return continuity;
    }

    public PendingTransferStore pendingTransfers() {
        return pendingTransfers;
    }

    public PassThroughCorridorPlan passThroughCorridorPlan() {
        return passThroughCorridorPlan;
    }

    public Map<FlowRef, CarryTransfer> reservedPassThroughCarries() {
        return reservedPassThroughCarries;
    }

    public AsyncLifecycleBucketStore asyncLifecycleBuckets() {
        return asyncLifecycleBuckets;
    }

    public com.walletradar.costbasis.application.replay.model.AsyncLifecycleBucket asyncLifecycleBucket(String correlationId) {
        return asyncLifecycleBuckets.bucket(CorrelationRef.from(correlationId));
    }

    public void removeAsyncLifecycleBucket(String correlationId) {
        CorrelationRef correlationRef = CorrelationRef.from(correlationId);
        if (correlationRef != null) {
            asyncLifecycleBuckets.remove(correlationRef);
        }
    }

    public AsyncSpotOrderBucketStore asyncSpotOrderBuckets() {
        return asyncSpotOrderBuckets;
    }

    public com.walletradar.costbasis.application.replay.model.AsyncSpotOrderBucket asyncSpotOrderBucket(String correlationId) {
        return asyncSpotOrderBuckets.bucket(CorrelationRef.from(correlationId));
    }

    public com.walletradar.costbasis.application.replay.model.AsyncSpotOrderBucket findAsyncSpotOrderBucket(String correlationId) {
        CorrelationRef correlationRef = CorrelationRef.from(correlationId);
        return correlationRef == null ? null : asyncSpotOrderBuckets.find(correlationRef);
    }

    public void removeAsyncSpotOrderBucket(String correlationId) {
        CorrelationRef correlationRef = CorrelationRef.from(correlationId);
        if (correlationRef != null) {
            asyncSpotOrderBuckets.remove(correlationRef);
        }
    }

    public LedgerPointCollector ledgerPointCollector() {
        return ledgerPointCollector;
    }
}
