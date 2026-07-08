package com.walletradar.costbasis.application.replay.state;

import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.CorrelationRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ReplayExecutionState {

    private final PositionStore positions = new PositionStore();
    private final ContinuityStore continuity = new ContinuityStore();
    private final PendingTransferStore pendingTransfers = new PendingTransferStore();
    private final PassThroughCorridorPlan passThroughCorridorPlan;
    private final Map<FlowRef, CarryTransfer> reservedPassThroughCarries = new LinkedHashMap<>();
    private final AsyncLifecycleBucketStore asyncLifecycleBuckets = new AsyncLifecycleBucketStore();
    private final AsyncSpotOrderBucketStore asyncSpotOrderBuckets = new AsyncSpotOrderBucketStore();
    private final LedgerPointCollector ledgerPointCollector;
    private final CounterpartyBasisPoolReplayContext counterpartyBasisPoolContext;
    private final BorrowLiabilityReplayContext borrowLiabilityContext;
    private final LpReceiptBasisPoolReplayContext lpReceiptBasisPoolContext;
    /**
     * Cycle/7 S5: continuity-path replay duplicate guard.
     * Holds {@code (correlationId|walletAddress|networkId|assetFamily|sign(qty))} fingerprints for flows
     * that have already passed through the continuity-transfer dispatch path during this replay
     * pass. If a duplicate appears (e.g., a stream mirror that survived the upstream collapser),
     * the dispatcher logs {@code REPLAY_DEDUP_MIRROR_SKIPPED} and skips the flow.
     */
    private final Set<String> seenContinuityFlows = new HashSet<>();
    /** Per {@code lp-position:} correlation — [entryEvents, principalExitEvents]. */
    private final Map<String, int[]> lpPositionReceiptLifecycle = new LinkedHashMap<>();

    public ReplayExecutionState(
            PassThroughCorridorPlan passThroughCorridorPlan,
            LedgerPointCollector ledgerPointCollector
    ) {
        this(passThroughCorridorPlan, ledgerPointCollector, null, null, null);
    }

    public ReplayExecutionState(
            PassThroughCorridorPlan passThroughCorridorPlan,
            LedgerPointCollector ledgerPointCollector,
            CounterpartyBasisPoolReplayContext counterpartyBasisPoolContext
    ) {
        this(passThroughCorridorPlan, ledgerPointCollector, counterpartyBasisPoolContext, null, null);
    }

    public ReplayExecutionState(
            PassThroughCorridorPlan passThroughCorridorPlan,
            LedgerPointCollector ledgerPointCollector,
            CounterpartyBasisPoolReplayContext counterpartyBasisPoolContext,
            BorrowLiabilityReplayContext borrowLiabilityContext
    ) {
        this(passThroughCorridorPlan, ledgerPointCollector, counterpartyBasisPoolContext, borrowLiabilityContext, null);
    }

    public ReplayExecutionState(
            PassThroughCorridorPlan passThroughCorridorPlan,
            LedgerPointCollector ledgerPointCollector,
            CounterpartyBasisPoolReplayContext counterpartyBasisPoolContext,
            BorrowLiabilityReplayContext borrowLiabilityContext,
            LpReceiptBasisPoolReplayContext lpReceiptBasisPoolContext
    ) {
        this.passThroughCorridorPlan = passThroughCorridorPlan;
        this.ledgerPointCollector = ledgerPointCollector;
        this.counterpartyBasisPoolContext = counterpartyBasisPoolContext;
        this.borrowLiabilityContext = borrowLiabilityContext;
        this.lpReceiptBasisPoolContext = lpReceiptBasisPoolContext;
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

    public CounterpartyBasisPoolReplayContext counterpartyBasisPoolContext() {
        return counterpartyBasisPoolContext;
    }

    public BorrowLiabilityReplayContext borrowLiabilityContext() {
        return borrowLiabilityContext;
    }

    public LpReceiptBasisPoolReplayContext lpReceiptBasisPoolContext() {
        return lpReceiptBasisPoolContext;
    }

    /** Returns true if the fingerprint was newly added; false if it was already seen. */
    public boolean markContinuityFlowSeen(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return true;
        }
        return seenContinuityFlows.add(fingerprint);
    }

    public void recordLpReceiptEntryEvent(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        int[] counts = lpPositionReceiptLifecycle.computeIfAbsent(correlationId, ignored -> new int[2]);
        counts[0]++;
    }

    public void recordLpReceiptPrincipalExitEvent(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        int[] counts = lpPositionReceiptLifecycle.computeIfAbsent(correlationId, ignored -> new int[2]);
        counts[1]++;
    }

    public boolean lpReceiptLifecycleClosed(String correlationId) {
        int[] counts = lpPositionReceiptLifecycle.get(correlationId);
        if (counts == null) {
            return false;
        }
        return counts[1] > 0 && counts[1] >= counts[0];
    }
}
