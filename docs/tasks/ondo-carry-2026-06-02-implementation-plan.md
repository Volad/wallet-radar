# B-ONDO-CARRY-1: Bybit ONDO EARN Carry Key Routing Fix

## Scope

- Wallet: `BYBIT:33625378` (UTA/FUND sub-accounts)
- Asset: ONDO
- Affected: 4 orphaned `bybit-collapsed-v1:` CARRY_OUTs with no matching CARRY_IN
- Missing basis: ~$81 total ($37.75 + $1.34 + $27.68 + $14.22)

---

## Root cause

**Stage:** replay — pending transfer key generation  
**Evidence state:** `EVIDENCE_PRESENT_UNLINKED`

### What happens

When a Bybit ONDO EARN subscription occurs, two distinct events are recorded from different Bybit API streams:

1. **FUND/UTA → EARN internal transfer** (`bybit-collapsed-v1:X` corrId, `continuityCandidate=true`) — the CARRY_OUT that drains the principal
2. **EARN subscription bundle** (`bybit-it-bundle-v1:Y` corrId) — the CARRY_IN via `applyBybitMultiLegBundleTransfer()`

In `ReplayPendingTransferKeyFactory.transferKey()`, the `corr-family:` check runs **before** `usesBybitVenueInternalCarryQueue()`:

```java
// Current order (broken):
if (corrId != null && continuityCandidate == true) {
    return new TransferPendingKey("corr-family:" + corrId + ":" + assetKey);  // ← intercepts FUND→EARN
}
if (isBybitEarnInternalTransfer(tx) || isBybitEarnProductTransfer(tx)) {
    return new TransferPendingKey("bybit-earn-carry:" + uid + ":" + earnAssetKey);  // ← never reached
}
```

The FUND/UTA side has `bybit-collapsed-v1:X` corrId + `continuityCandidate=true`, so it enters the `corr-family:` branch and enqueues the CARRY_OUT under `corr-family:bybit-collapsed-v1:X:FAMILY:ONDO`.

The EARN bundle CARRY_IN arrives via `applyBybitMultiLegBundleTransfer()` which uses `corr-family:bybit-it-bundle-v1:Y:FAMILY:ONDO`.

Different keys → permanent orphan carry.

### Why 3 of 7 transfers work

For those 3, the EARN-side event was also tagged with a `bybit-collapsed-v1:` corrId (not a bundle event). Both sides generate the same `corr-family:bybit-collapsed-v1:X:` key → match.

---

## Changes required

### 1. `ReplayPendingTransferKeyFactory.transferKey()` — reorder EARN check before corr-family

**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayPendingTransferKeyFactory.java`

Move **only** the `isBybitEarnInternalTransfer || isBybitEarnProductTransfer` check **before** the `corr-family:` block. `isBybitSameUidInternalTransfer` is intentionally **not** moved — it stays in its current position to minimize blast radius.

```java
// NEW order:
if (isBybitEarnInternalTransfer(transaction) || isBybitEarnProductTransfer(transaction)) {
    String uid = extractBybitUid(transaction.getWalletAddress());
    String earnAssetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
    return new TransferPendingKey("bybit-earn-carry:" + uid + ":" + earnAssetKey);
}
// corr-family: block follows — isBybitSameUidInternalTransfer stays after this block (unchanged)
if (transaction.getCorrelationId() != null && !transaction.getCorrelationId().isBlank()
        && Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
    String assetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
    return new TransferPendingKey("corr-family:" + transaction.getCorrelationId() + ":" + assetKey);
}
if (isBybitSameUidInternalTransfer(transaction)) {
    String uid = extractBybitUid(transaction.getWalletAddress());
    String earnAssetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
    return new TransferPendingKey("bybit-earn-carry:" + uid + ":" + earnAssetKey);
}
// ... rest unchanged
```

**Safety:** `isBybitEarnInternalTransfer()` already returns `false` for `bybit-it-bundle-v1:` corrIds, so EARN bundle transactions are not affected. Bundle transactions are dispatched via `isBybitMultiLegBundleTransfer()` check in `TransferReplayHandler.applyTransfer()` **before** `transferKey()` is ever called — bundles never reach `transferKey()` at all.

### 2. `TransferReplayHandler.applyBybitMultiLegBundleTransfer()` — add FIFO fallback

**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/TransferReplayHandler.java`

The inbound path in `applyBybitMultiLegBundleTransfer()` uses a `remaining`-tracking `while` loop that drains carries from the primary `corr-family:bybit-it-bundle-v1:Y:` queue using `matcher.findUniqueBridgeQueueIndex` + `sliceCarryTransfer`. After this loop exhausts the primary queue with `remaining > 0`, inject a secondary FIFO fallback before the existing pending-inbound enqueue:

```java
// After primary while-loop: if remaining > 0, try FIFO earn queue as fallback
if (remaining.signum() > 0) {
    TransferPendingKey fifoKey = keyFactory.earnCarryFifoKey(transaction, flow);
    Deque<CarryTransfer> fifoQueue = fifoKey != null
            ? replayState.pendingTransfers().find(fifoKey) : null;
    while (fifoQueue != null && remaining.signum() > 0) {
        int carryIndex = matcher.findUniqueBridgeQueueIndex(fifoQueue, false);
        if (carryIndex < 0) break;
        CarryTransfer carry = matcher.removeQueueElement(fifoQueue, carryIndex);
        BigDecimal takeQty = remaining.min(carry.quantity());
        CarryTransfer effectiveCarry = continuityCarryService.sliceCarryTransfer(
                carry, takeQty, position.assetKey());
        flowSupport.restoreToPosition(takeQty, position,
                effectiveCarry.costBasisUsd(),
                effectiveCarry.uncoveredQuantity(),
                effectiveCarry.avco());
        // reservePassThroughCarryIfPlanned if applicable (mirror primary loop)
        remaining = remaining.subtract(takeQty, MC);
        restoredAny = true;
    }
    if (fifoKey != null && fifoQueue != null && fifoQueue.isEmpty()) {
        replayState.pendingTransfers().remove(fifoKey);
    }
}
// Existing pending-inbound enqueue for whatever remains follows unchanged
```

Key properties:
- **Quantity-bounded**: uses `remaining.min(carry.quantity())` and `sliceCarryTransfer` — identical to the primary loop pattern. R3 (over-drain) is prevented.
- **Drains from queue, not position**: uses `matcher.removeQueueElement` + `sliceCarryTransfer`, not `removeTransferCarry` (which is the outbound drain).
- **No interference with other assets**: FIFO key is UID+asset-scoped so no cross-asset leakage.

**New method on `ReplayPendingTransferKeyFactory`:**

```java
/** Returns the bybit-earn-carry FIFO key for the given transaction and flow,
 *  without the isBybitEarnInternalTransfer guard. Used as a secondary fallback
 *  in applyBybitMultiLegBundleTransfer when the primary corr-family queue is empty. */
public TransferPendingKey earnCarryFifoKey(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
    String uid = extractBybitUid(transaction.getWalletAddress());
    if (uid == null) return null;
    String earnAssetKey = assetSupport.correlatedTransferIdentity(transaction, flow);
    return new TransferPendingKey("bybit-earn-carry:" + uid + ":" + earnAssetKey);
}
```

### 3. Unit tests

Add to `AvcoReplayServiceTest`:

1. `ondoEarnSubscriptionFundSideUsesEarnFifoQueue` — FUND outbound with `bybit-collapsed-v1:` corrId + `continuityCandidate=true` receives `bybit-earn-carry:` key (not `corr-family:` key) after reorder
2. `ondoEarnBundleInboundFallsBackToEarnFifoQueueWhenPrimaryQueueEmpty` — EARN bundle inbound that finds empty `corr-family:` queue correctly drains from `bybit-earn-carry:` and receives correct cbD
3. `crossUidCorr-familyKeyUnaffectedByReorder` — cross-UID `bybit-cross-uid-v1:` corrId still uses `corr-family:` key (not earn FIFO), ensuring B-CROSS-UID fix is not regressed

### 4. Rebuild and verify

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

---

## Docs

No new ADR required — this is a bug fix in the replay key routing priority.

---

## Acceptance criteria

After rebuild:

1. **AC-1**: All 4 previously orphaned ONDO CARRY_OUTs have a matching CARRY_IN in `asset_ledger_points`.
   ```js
   db.asset_ledger_points.find({
     basisEffect: "CARRY_OUT", assetSymbol: "ONDO",
     correlationId: { $regex: "bybit-collapsed-v1:" }
   }).toArray().forEach(out => {
     var matchIn = db.asset_ledger_points.findOne({ correlationId: out.correlationId, basisEffect: "CARRY_IN" });
     print(out.correlationId.substring(0,50), "CARRY_IN:", matchIn ? "✅ cbD=" + (matchIn.costBasisDeltaUsd ? parseFloat(matchIn.costBasisDeltaUsd.toString()).toFixed(2) : "0") : "❌ MISSING");
   });
   ```
   Expected: all lines show ✅ with cbD > 0.

2. **AC-2**: Total ONDO CARRY_IN cbD ≥ $80 (sum of previously orphaned).

3. **AC-3**: No regression in B-CROSS-UID — ETH/BTC cross-UID CARRY_INs still have cbD > 0.
   ```js
   db.asset_ledger_points.find({
     basisEffect: "CARRY_IN",
     assetSymbol: { $in: ["ETH", "BTC"] },
     correlationId: { $regex: "bybit-cross-uid-v1:" }
   }, { assetSymbol:1, costBasisDeltaUsd:1, _id:0 }).toArray()
   ```
   Expected: all have costBasisDeltaUsd > 0.

4. **AC-4**: No regression in existing EARN carries — other assets (e.g., USDe, USDT) that use `bybit-earn-carry:` FIFO are unaffected.

5. **AC-5**: Pipeline clean — CONFIRMED=N, PENDING=0, NEEDS_REVIEW=0.

6. **AC-6**: The 3 previously-working ONDO carries (both sides had `bybit-collapsed-v1:` corrId) are not regressed — verify they still have matched CARRY_INs with cbD > 0 after routing changes.
   ```js
   db.asset_ledger_points.find({
     basisEffect: "CARRY_IN", assetSymbol: "ONDO",
     costBasisDeltaUsd: { $gt: 0 }
   }, { correlationId:1, costBasisDeltaUsd:1, _id:0 }).toArray()
   ```
   Expected: ≥ 3 records with cbD > 0 (previously working carries intact).

---

## Risks

### R1 — EARN transfers that legitimately need `corr-family:` key

The `corr-family:` block previously captured Bybit EARN internal transfers with `continuityCandidate=true`. After the reorder, these are intercepted by the EARN FIFO check first. Any EARN transfer pair that was matching via `corr-family:` because both sides had the same `bybit-collapsed-v1:` corrId will now use the FIFO queue instead.

**Impact:** If both sides had matching corrIds, they would have matched regardless of queue type — the FIFO queue is per-UID and per-asset, so same-UID same-asset pairs still match. No basis loss expected.

**Mitigation:** AC-4 verifies no regression in existing EARN carries.

### R2 — isBybitSameUidInternalTransfer not reordered (decision documented)

`isBybitSameUidInternalTransfer` is intentionally left in its current position (after `corr-family:`). Only `isBybitEarnInternalTransfer || isBybitEarnProductTransfer` is moved before `corr-family:`. This is the minimal-blast-radius approach. The inconsistency with `usesBybitVenueInternalCarryQueue()` (which returns true for `isBybitSameUidInternalTransfer`) is a known gap but does not affect correctness for the current dataset.

### R3 — Bundle fallback over-drains FIFO queue

The EARN FIFO queue is a shared queue per UID+asset. If the fallback drain in `applyBybitMultiLegBundleTransfer()` is too aggressive, it could consume carries meant for other bundles.

**Mitigation:** Use quantity-bounded drain (not unconditional drain). Only consume up to the inbound flow's quantity.
