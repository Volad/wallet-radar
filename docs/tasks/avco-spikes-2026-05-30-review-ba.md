# BA Review — AVCO Spikes Implementation Plan
**Plan ref**: `docs/tasks/avco-spikes-2026-05-30-implementation-plan.md`  
**Audit ref**: `docs/tasks/avco-spikes-2026-05-30-audit.md`  
**Reviewer role**: Product / Business Correctness  
**Date**: 2026-05-30  
**Verdict**: **CONDITIONAL APPROVE** — implementation is sound but requires 4 AC gaps closed before sign-off (marked C-1 … C-4 below). 3 additional notes (N-1 … N-3) that do not block implementation.

---

## 1. Acceptance Criteria Completeness

### AC-1 (B-3) — PASS with gap
The single-event assertion is correct and sufficient for the happy path.  
**Gap (C-1)**: The plan only verifies one corridor occurrence (seq 5124). Audit states "B-3 is recurring — multiple similar spikes observed in Sep 2025 and Feb 2026." The AC does not verify that other corridor CARRY_IN events are no longer over-inflated. A regression scan AC is needed:

> **Required AC addendum**: After rebuild, no corridor `CARRY_IN` ledger point should have `costBasisDeltaUsd > pre-drain totalCostBasisUsd` (carry cannot exceed what the position held). Query below in §2.

### AC-2 (B-2) — PASS
Sequence ordering check is precise and sufficient.

### AC-3 (B-1) — INSUFFICIENT (C-2)
"equals the actual BASE carry-out basis (not spot-inflated $1,515)" is non-deterministic before the normalization fix runs. The expected value depends on what the LP_EXIT normalization produces for `0x0a757aee...`. This AC cannot be auto-verified by QA without knowing the target value upfront.

**Required AC rewrite**: 
1. Verify the two UNKNOWN tx IDs are re-normalized: `type = LP_EXIT`, flows contain ETH inflow ≥ 0.799 and ≥ 0.546 respectively.
2. Verify `quantityShortfallDelta = 0` on the BASE BRIDGE_OUT `0x4ca0b79e...` point after rebuild.
3. Verify the ARB BRIDGE_IN `0x38d445c4...` `costBasisDeltaUsd` ≠ $1,515.66 (spot-injected) and its basis matches the BASE CARRY_OUT basis from the same corridor.

### AC-4 (B-4) — PASS
Transient swing check is sufficient given that B-4 is same root as B-1.

### AC-5 (Global) — THRESHOLD MISMATCH (C-3)
The audit was run at a >10% spike threshold; the global AC uses 15% threshold. The relaxation is unexplained and would allow spikes between 10–15% to silently pass. Either:
- Lower the global AC threshold back to 10%, or  
- Document explicitly why 15% is the accepted tolerance and that B-5-style "correct but sharp" swings account for the remaining 3+ spikes above 10%.

**Required**: State which spike category accounts for the 10–15% band (B-5 / Bybit ping-pong is known-correct; how many such events are expected?).

---

## 2. Definition of Done — per Blocker

### B-3 DoD

#### Step 1 — Verify the concrete evidence point
```javascript
db.asset_ledger_points.findOne({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  normalizedTransactionId: /BYBIT-33625378:FUNDING_HISTORY:f9cfb4eb/,
  basisEffect: "CARRY_IN"
})
// PASS: costBasisDeltaUsd BETWEEN 1897.79 AND 1899.79
// FAIL: costBasisDeltaUsd == 2517.25 (old inflated value)
```

#### Step 2 — Corridor carry sanity (no carry > pre-drain basis) [new AC per C-1]
```javascript
// Find all CARRY_OUT points in corridor transactions
db.asset_ledger_points.aggregate([
  { $match: {
      accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
      basisEffect: "CARRY_OUT",
      correlationId: /^BYBIT-CORRIDOR:/
  }},
  { $lookup: {
      from: "asset_ledger_points",
      let: { cid: "$correlationId", asset: "$accountingAssetIdentity" },
      pipeline: [
        { $match: { $expr: { $and: [
          { $eq: ["$correlationId", "$$cid"] },
          { $eq: ["$accountingAssetIdentity", "$$asset"] },
          { $eq: ["$basisEffect", "CARRY_IN"] }
        ]}}}
      ],
      as: "carryIn"
  }},
  { $unwind: "$carryIn" },
  { $project: {
      corridor: "$correlationId",
      outBasis: { $abs: "$costBasisDeltaUsd" },
      inBasis: "$carryIn.costBasisDeltaUsd",
      inflation: { $subtract: ["$carryIn.costBasisDeltaUsd", { $abs: "$costBasisDeltaUsd" }] }
  }},
  { $match: { inflation: { $gt: 1 } } }  // tolerance $1
])
// PASS: 0 results (no corridor where inbound basis > outbound basis by more than $1)
```

#### Step 3 — Regression: fully-covered corridor positions unchanged
For any corridor CARRY_OUT where `uncoveredQuantityAfter == 0` at time of CARRY_OUT, the carry amount should be identical before and after the fix (since `proportionalBasis = totalBasis * 1 = perWalletAvco * movedQty` when uncoveredQty=0). No explicit query needed — covered by the spike scan in AC-5.

---

### B-2 DoD

#### Step 1 — Ordering fix confirmed for the evidence transaction
```javascript
db.asset_ledger_points.find({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  txHash: "0xd7c7736b8a4a536d72e705326256c92fc5af7031e9c3aca2907be7257ccf5de3",
  networkId: "MANTLE"
}).sort({ replaySequence: 1 })
// PASS: CARRY_OUT (walletAddress = 0xa0dd42...) has lower replaySequence than CARRY_IN (walletAddress = 0x1a87...)
// FAIL: CARRY_IN has lower replaySequence than CARRY_OUT
```

#### Step 2 — No transient double-count in the family timeline
```javascript
// Reconstruct running family AVCO at the B-2 cluster (seq range 1328–1335)
// Between the earliest and latest replaySequence for this tx, verify:
// max(avcoAfterUsd) - min(avcoAfterUsd) < 10% of the avg
// (manually verified by reading the 4 ledger points for this tx)
db.asset_ledger_points.find({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  txHash: "0xd7c7736b8a4a536d72e705326256c92fc5af7031e9c3aca2907be7257ccf5de3"
}).sort({ replaySequence: 1 })
// PASS: no ledger point shows avcoAfterUsd dropping >10% or rising >10% vs the point immediately before this tx
```

---

### B-1 DoD

#### Step 1 — UNKNOWN transactions are re-normalized
```javascript
// Feb 6 UNKNOWN tx
db.normalized_transactions.findOne({
  txHash: /0x0a757aee/,
  networkId: "BASE",
  walletAddress: /0x1a87/i
})
// PASS: type = "LP_EXIT", status = "CONFIRMED", flows contains a TRANSFER with quantityDelta >= 0.799 and assetSymbol = "ETH"
// FAIL: type = "UNKNOWN" or flows is empty
```

```javascript
// Nov 17 UNKNOWN tx
db.normalized_transactions.findOne({
  txHash: /0xbdf26819/, // or the second UNKNOWN hash
  networkId: "BASE",
  walletAddress: /0x1a87/i,
  type: "UNKNOWN"
})
// PASS: no result (type has changed)
```

#### Step 2 — BASE BRIDGE_OUT shortfall is eliminated
```javascript
db.asset_ledger_points.findOne({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  txHash: "0x4ca0b79ea7f374c8f90e4c13fc9da43a668f1d8352ae99b1d5a84ef4056ab4fb",
  networkId: "BASE",
  basisEffect: "CARRY_OUT"
})
// PASS: quantityShortfallDelta == 0, costBasisDeltaUsd != 0 (actual basis carried)
// FAIL: quantityShortfallDelta ~= 0.799 (shortfall still present)
```

#### Step 3 — ARB BRIDGE_IN no longer uses spot price
```javascript
db.asset_ledger_points.findOne({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  txHash: "0x38d445c4fc8f54149185a606240f0c7b212047a637dae42d7491a835b08d1cf2",
  networkId: "ARBITRUM",
  basisEffect: "CARRY_IN"
})
// PASS: abs(costBasisDeltaUsd - 1515.66) > 10 (the spot-injected value is gone)
//       AND costBasisDeltaUsd matches the BASE CARRY_OUT costBasisDeltaUsd within $1
// FAIL: costBasisDeltaUsd ~= 1515.66 (still using spot fallback)
```

---

### B-4 DoD

#### Step 1 — WRAP no longer creates zero-basis WETH
```javascript
db.asset_ledger_points.find({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  txHash: "0xbdf26819...",
  networkId: "BASE"
}).sort({ replaySequence: 1 })
// PASS: REALLOCATE_IN for WETH has costBasisDeltaUsd > 0
//       No family AVCO swing > 10% around these points
// FAIL: REALLOCATE_IN costBasisDeltaUsd = 0 (B-1 root not fixed)
```

*Note: B-4 DoD is entirely dependent on B-1 normalization fix. If B-1 passes all its queries above, B-4 is automatically resolved.*

---

## 3. B-3 Edge Cases

### 3a — `totalCostBasisUsd = 0` (zero-cost position, e.g., airdrop)

The fix's condition `preDrainTotalBasis.signum() > 0` is false → falls back to else branch → `carry = movedQty * corridorOutboundSliceAvco`. In this case `corridorOutboundSliceAvco = safeDivide(totalCostBasisUsd=0, coveredQty) = 0`, so `carry = 0`. **This is mathematically correct**: a zero-cost asset carries zero basis through a corridor.

**No code change needed**, but add a unit test:
```
Input: position with totalCostBasisUsd=0, totalQty=1.0, uncoveredQty=0.5 (airdrop)
corridor moves movedQty=0.5
Expected: carry.costBasis = 0 (not a negative or NaN value)
```

### 3b — `preDrainTotalQty = 0` (guard needed)

The guard `preDrainTotalQty.signum() > 0` prevents division by zero and is correct. If `preDrainTotalQty = 0` it falls to the else branch which uses `corridorOutboundSliceAvco`. Since the position is empty, `perWalletAvco` would also be zero (or undefined), so carry = 0. Acceptable defensive behavior.

However, a `preDrainTotalQty = 0` on an active corridor CARRY_OUT indicates a broader data integrity issue (draining an empty position). Consider adding a **warning log** rather than silently falling back:
```java
if (preDrainTotalQty == null || preDrainTotalQty.signum() <= 0) {
    log.warn("B-3 guard: preDrainTotalQty is zero or null on corridor CARRY_OUT for {}; falling back to perWalletAvco", carrySource.assetKey());
}
```

### 3c — Partial corridor move (50% of holdings)

With the fix: `carry = totalBasis * (movedQty / totalQty)`.  
- If position has `totalQty=1.0`, `totalBasis=$2000`, moves `movedQty=0.5`:  
  `carry = $2000 * 0.5 = $1000` ✓ Exactly half the basis leaves.  
- Residual position after drain: `qty=0.5, basis=$1000`.  
- This is arithmetically correct for any partial percentage.

**NEW EDGE CASE NOT IN PLAN (C-4)**: What if `movedQty > preDrainTotalQty`?

This can occur when a corridor CARRY_OUT is executed on a position that has a shortfall (i.e., the wallet is sending more ETH than it ever tracked receiving). In that case:
```
carry = totalBasis * (movedQty / preDrainTotalQty)
      = totalBasis * (1.2 / 1.0)  ← if movedQty > totalQty
      = 1.2 × totalBasis           ← carry EXCEEDS totalBasis!
```
This would be **worse than the current bug**. The existing condition `preDrainTotalQty.signum() > 0` does not guard against this.

**Required fix**: Add an upper-bound cap:
```java
corridorCarryBasis = preDrainTotalBasis
    .multiply(movedQty, MC)
    .divide(preDrainTotalQty, MC)
    .min(preDrainTotalBasis);   // carry cannot exceed what the position held
```
Or equivalently, add a pre-check:
```java
if (movedQty.compareTo(preDrainTotalQty) > 0) {
    // shortfall scenario: fall back to full-basis carry (best conservative option)
    corridorCarryBasis = preDrainTotalBasis;
} else {
    corridorCarryBasis = preDrainTotalBasis.multiply(movedQty, MC).divide(preDrainTotalQty, MC);
}
```

---

## 4. B-2 Scope: "Receive-then-forward" Pattern Safety

**Verdict: Safe.** The B-2 fix is scoped to single-transaction same-block corridors.

Rationale:
- "Receive from counterparty, then forward" involves **two separate transactions** with different `txHash` values and different `blockTimestamp` or `transactionIndex` positions. The replay ordering tiebreaker only activates when `blockTimestamp` AND `transactionIndex` are equal — which can only happen for two ledger points generated from the **same canonical transaction**.
- `INTERNAL_TRANSFER` specifically requires both source and destination wallet-local canonical rows to share the same `txHash + networkId`. A "receive then forward" flow would be classified as `EXTERNAL_TRANSFER_IN` followed by a separate `EXTERNAL_TRANSFER_OUT` — neither of which sets `continuityCandidate=true` as a single linked pair.
- The additional guard `correlationId != null && !correlationId.isBlank()` in the fix further restricts to already-correlated corridor pairs.

**One narrow risk (N-1)**: If two different `INTERNAL_TRANSFER` corridors happen to share the same block and transactionIndex on the same network (practically impossible on standard EVM chains, but theoretically possible in batch/bundled tx scenarios), the directional sort would still be correct because each corridor's source/destination wallets are independent. Different corridors have different `correlationId` values and would not interfere with each other's sort order.

No code change required. Note only.

---

## 5. B-1/B-4 Normalization Scope: Double-Counting Risk

**Verdict: Low risk for this specific fix, but boundary must be explicit.**

**Why double-counting is unlikely here**: The two UNKNOWN transactions currently have **empty flows** (stated in the plan, confirmed in audit: "BASE ETH history exists on-chain but was not backfilled"). An UNKNOWN transaction with empty flows contributes zero to AVCO. Fixing it to LP_EXIT adds ETH inflows that were entirely absent before — it does not create a second copy of already-tracked ETH.

**Residual risk (N-2)**: The normalization fix will target a function signature (PancakeSwap V3 `collect` or `decreaseLiquidity`). This fix applies to ALL matching UNKNOWN transactions on BASE, not just the two identified IDs. Any other BASE UNKNOWN transaction with the same function signature that was mistakenly left as UNKNOWN (but whose ETH output was tracked through a separate path — e.g., the ETH arrived as `EXTERNAL_TRANSFER_IN` from the pool) could receive a second LP_EXIT flow for the same ETH, creating a double-count.

**Required pre-fix check**: Before deploying the normalization fix, verify that no downstream canonical transactions for wallet `0x1a87...` on BASE already have ETH inflows that would duplicate what the LP_EXIT normalization will introduce. Specifically:
```javascript
// Check for any EXTERNAL_TRANSFER_IN or other inbound ETH on BASE
// in the date window of the two UNKNOWN txs (Feb 6 2026, Nov 17 2025)
db.normalized_transactions.find({
  walletAddress: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
  networkId: "BASE",
  type: { $in: ["EXTERNAL_TRANSFER_IN", "REWARD_CLAIM", "LP_EXIT"] },
  blockTimestamp: {
    $gte: ISODate("2025-11-15"),
    $lte: ISODate("2026-02-08")
  },
  "flows.assetSymbol": "ETH"
})
// If any results show ETH inflows of ~0.546 or ~0.799 from other transaction types,
// investigate before applying the normalization fix.
```

---

## 6. Out-of-Scope Boundaries (Explicit Statement)

The plan is silent on what is explicitly NOT changed. QA engineers need to know the blast radius.

### B-3 scope
- **In scope**: Corridor CARRY_OUT path in `TransferReplayHandler` where `corridorTransfer = true` (P0-C path, triggered by `BYBIT-CORRIDOR:*` correlationId).
- **Out of scope**: Non-corridor transfers (no P0-C override), Bybit internal earn transfers (`bybit-earn-principal-v1:*`), bridge corridors (`BRIDGE_OUT → BRIDGE_IN`), all non-BYBIT-CORRIDOR transfer types.
- **Unverified (N-3)**: The plan does not specify whether `corridorTransfer = true` is also set for on-chain-to-on-chain corridors (e.g., wallet A → wallet B on Mantle without Bybit involvement). If `corridorOutboundSliceAvco` is populated in that path, the fix would also apply there. This should be confirmed by the implementer.

### B-2 scope
- **In scope**: `ConfirmedReplayQueryService` tiebreaker for on-chain `INTERNAL_TRANSFER` rows that are `continuityCandidate=true` with non-null `correlationId`.
- **Out of scope**: Bybit source INTERNAL_TRANSFER ordering (unchanged), BRIDGE_OUT/BRIDGE_IN ordering (handled by separate corridor path), LENDING_DEPOSIT/LENDING_WITHDRAW ordering (different replay handler).

### B-1/B-4 scope
- **In scope**: Two specific UNKNOWN transaction IDs for wallet `0x1a87...` on BASE network, normalizer for PancakeSwap V3 LP functions (`collect`, `decreaseLiquidity`).
- **Out of scope**: Other wallets on BASE, other networks, other users, other LP protocols (Uniswap V3 on BASE would be affected by the same normalization fix if the same function signatures are used — this is a wider blast radius than the plan acknowledges).
- **Required clarification**: Does the normalization fix apply only to PancakeSwap V3 on BASE, or to all Uniswap V3-compatible LP managers? The plan says "add it to the appropriate classifier/parser so it generates LP_EXIT with correct flows" — this implies the fix could affect any Base wallet using the same protocol. This is desirable product behavior but needs to be stated as intentional scope.

---

## Summary of Required Actions

| ID | Type | Blocker? | Description |
|----|------|----------|-------------|
| C-1 | AC gap | Yes | Add corridor-scan AC: no CARRY_IN `costBasisDeltaUsd > pre-drain totalBasis` across all corridors |
| C-2 | AC rewrite | Yes | Rewrite B-1 AC with deterministic verifiable checks (normalization type, shortfall=0, basis match) |
| C-3 | AC gap | Yes | Reconcile 10% vs 15% threshold in global spike-scan AC with explicit B-5 exemption count |
| C-4 | Code gap | Yes | Add upper-bound cap in B-3 fix: `corridorCarryBasis = min(proportional, preDrainTotalBasis)` to guard `movedQty > preDrainTotalQty` shortfall cases |
| N-1 | Note | No | B-2 "receive-then-forward" risk analyzed — safe; no action |
| N-2 | Note | No | Run pre-fix duplicate-ETH check on BASE for `0x1a87...` before B-1/B-4 normalization goes live |
| N-3 | Note | No | Confirm whether `corridorTransfer=true` path is only `BYBIT-CORRIDOR:*` or also includes other corridor types; document in ADR-019 update |

---

## Final Verdict

**CONDITIONAL APPROVE**. The implementation approach for all four blockers is technically correct and the root causes are accurately diagnosed. The B-3 proportional-basis formula is sound. The B-2 ordering fix is safe and narrowly scoped. The B-1/B-4 normalization path is the right first-principles fix.

Four items must be resolved before sign-off:
- **C-1, C-2, C-3**: Close acceptance criteria gaps — current ACs are insufficient for QA to auto-verify correctness.  
- **C-4**: Add carry basis cap in B-3 fix — the current code has a potential over-inflation path in shortfall scenarios that would be worse than the existing bug.

Once C-1 through C-4 are addressed, the plan is ready for implementation handoff.
