# ETH AVCO Corridor Collision Fix — Implementation Plan v2

**Slug**: `avco-eth-corridor-collision-fix`  
**Date**: 2026-05-29  
**Status**: Awaiting Phase 3 final review approval  
**Audit source**: `results/avco-eth-v2/` (Phase 1, 2026-05-29)  
**Review round**: v2 — root cause corrected after financial auditor review

---

## 0. Executive Summary

ETH AVCO is stuck at **$1,953** (full-session) vs expected **$2,600–$2,800**. The root cause is a single gap in `TransferReplayHandler.attachLateBridgeCarryToPendingInbound`: when a BRIDGE_IN arrives before its paired BRIDGE_OUT in replay order, the method that later applies the authoritative carry to the ZKSync position **never calls `reservePassThroughCarryIfPlanned`**, so the pre-built pass-through corridor for the subsequent LENDING_DEPOSIT is never activated. The LENDING_DEPOSIT 35 seconds later drains from the already-depleted family pool and captures near-zero AVCO.

**Expected AVCO after fix: ~$2,692–$2,828.**

---

## 1. Scope

| Dimension | Value |
|-----------|-------|
| Session | `df5e69cc-a0c0-4910-8b7d-74488fa266e2` |
| Affected networks | ZKSync (origin of bug), ARBITRUM, MANTLE (downstream) |
| Affected assets | ETH, WETH, AZKSWETH, AARBWETH, AMANWETH (all FAMILY:ETH) |
| Primary backend path | `costbasis/application/replay/handler/TransferReplayHandler.java` |
| Secondary paths | `costbasis/application/replay/model/CarryTransfer.java`, `PassThroughCorridorPlanner.java` |
| No changes needed | frontend, pricing, classification, normalization |

---

## 2. Blocker Registry

| ID | Description | Stage | Severity | Missing Basis |
|----|-------------|-------|----------|---------------|
| **ETH-V2-C1** | `attachLateBridgeCarryToPendingInbound` does not call `reservePassThroughCarryIfPlanned` when BRIDGE_IN fires before BRIDGE_OUT — LENDING_DEPOSIT captures $3.49/$7.62 instead of $1,598/$1,343 | `replay` | **CRITICAL** | $2,930 |
| **ETH-V2-C2** | SWAP with 2 USDC input flows: only first USDC flow mapped to ETH ACQUIRE cost | `cost_basis` | HIGH | $87 |
| **ETH-V2-C3** | 14–18 LP_EXIT events use `basisEffect=UNKNOWN` instead of REALLOCATE_IN | `replay` | MEDIUM | ~$3,065 est. |
| **ETH-V2-C4** | INTERNAL_TRANSFER net drain −$1,547 (downstream of C1, resolves with C1) | `replay` | MEDIUM | — |

---

## 3. Root Cause — ETH-V2-C1 Precise Diagnosis

### Bridge pair identification

The two bridge events are a **legitimate LiFi bridge pair** — the ZKSync BRIDGE_IN and UNICHAIN BRIDGE_OUT cross-reference each other's txHashes as `matchedCounterparty` and share `correlationId = "bridge:lifi:0xd9d38471..."`. They are correctly paired via `BridgePendingKey(correlationId)`.

### The two replay ordering paths

**Happy path (BRIDGE_OUT fires first, BRIDGE_IN arrives second):**
```
applyLinkedBridgeTransfer (BRIDGE_IN, inbound) →
  findUniqueBridgeQueueIndex → finds BRIDGE_OUT carry in queue →
  bridgeInboundCarry → restoreToPosition →
  ✅ reservePassThroughCarryIfPlanned(BRIDGE_IN tx, BRIDGE_IN flowIdx, carry)
```
→ Pass-through corridor reserved. LENDING_DEPOSIT uses `takeReservedCarry` → correct AVCO captured.

**Broken path (BRIDGE_IN fires first, BRIDGE_OUT arrives second):**
```
applyLinkedBridgeTransfer (BRIDGE_IN, inbound) →
  findUniqueBridgeQueueIndex → queue empty →
  enqueuePendingInbound → adds pending carry to queue
  ❌ NO reservePassThroughCarryIfPlanned called

applyLinkedBridgeTransfer (BRIDGE_OUT, outbound) →
  removeTransferCarry → drains family pool by −$1,597.89 →
  findUniqueBridgeQueueIndex(isPendingInbound=true) → finds ZKSync pending →
  attachLateBridgeCarryToPendingInbound(carry=bridgeOutCarry, pendingInbound=zkSyncPending, ...) →
    bridgeInboundCarry → applyAuthoritativeLateInboundCarryBasis →
    ❌ NO reservePassThroughCarryIfPlanned called

[35 seconds later]
LENDING_DEPOSIT ZKSync →
  takeReservedCarry → returns null (nothing reserved!) →
  removeFromPosition → reads depleted family pool ($6.99 AVCO) →
  ❌ AZKSWETH captured at $3.49 basis instead of $1,598
```

### Sequence evidence (from `asset_ledger_points`)

| Seq | Timestamp | Event | costDelta | avcoAfter |
|-----|-----------|-------|-----------|-----------|
| 7265 | 08:27:11 | BRIDGE_IN CARRY_IN (ZKSync arrives) | +$1,597.90 | $3,198 ✓ |
| 7266 | 08:28:44 | BRIDGE_OUT CARRY_IN (UNICHAIN departs, drains pool) | −$1,597.89 | $6.99 ❌ |
| 7267 | 08:28:44 | BRIDGE_OUT CARRY_OUT (ETH qty leaves) | −$0.00 | NaN |
| 7269 | 08:29:19 | ZKSync LENDING_DEPOSIT REALLOCATE_OUT | −$3.49 | $6.99 ❌ |
| 7270 | 08:29:19 | AZKSWETH REALLOCATE_IN | +$3.49 | $1,755 |

The seq 7266 `CARRY_IN` with negative cost is the BRIDGE_OUT's `removeTransferCarry` draining the family pool to fund the corridor. The ZKSync position (updated by `attachLateBridgeCarryToPendingInbound`) has correct local AVCO, but the LENDING_DEPOSIT reads from the family pool, not from the reservation map, because the reservation was never placed.

---

## 4. Required Changes

### P0 — Fix `attachLateBridgeCarryToPendingInbound` to call pass-through reservation (CRITICAL)

**Files**:
1. `backend/src/main/java/com/walletradar/costbasis/application/replay/model/CarryTransfer.java`
2. `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/TransferReplayHandler.java`

**Step 1 — `CarryTransfer`: add `sourceFlowRef` field**

Add a nullable `FlowRef sourceFlowRef` to `CarryTransfer` to store the original inbound FlowRef when a pending inbound carry is enqueued. This lets `attachLateBridgeCarryToPendingInbound` call `reservePassThroughCarry` with the original BRIDGE_IN's FlowRef.

```java
// Add to record fields
@Nullable FlowRef sourceFlowRef  // set only for pendingInbound carries

// Update CarryTransfer.pendingInbound factory to accept and store FlowRef:
public static CarryTransfer pendingInbound(BigDecimal quantity, AssetKey assetKey, BigDecimal provisional, FlowRef sourceFlowRef) {
    return new CarryTransfer(quantity, ZERO, quantity, ZERO, null, true, assetKey, provisional, sourceFlowRef);
}
```

Existing `pendingInbound(quantity, assetKey)` and `pendingInbound(quantity, assetKey, provisional)` overloads delegate with `sourceFlowRef = null` for backward compatibility.

**Step 2 — `TransferReplayHandler.enqueuePendingInbound`: pass `flowIndex` and store FlowRef**

```java
// Change signature to accept flowIndex
private void enqueuePendingInbound(
    NormalizedTransaction transaction, NormalizedTransaction.Flow flow,
    int flowIndex,   // ← ADD
    PositionState position, ReplayExecutionState replayState,
    PendingTransferKey key
) {
    FlowRef sourceFlowRef = flowSupport.flowRef(transaction, flowIndex);  // ← ADD
    // ... existing provisionalBasis logic ...
    replayState.pendingTransfers().queue(key)
        .addLast(CarryTransfer.pendingInbound(
            flow.getQuantityDelta().abs(), position.assetKey(), provisionalBasis.orElse(ZERO),
            sourceFlowRef  // ← PASS through
        ));
}
```

Update all callers of `enqueuePendingInbound` to pass `flowIndex` (3–4 call sites in the same class).

**Step 3 — `attachLateBridgeCarryToPendingInbound`: call `reservePassThroughCarry`**

```java
private void attachLateBridgeCarryToPendingInbound(
    NormalizedTransaction transaction, NormalizedTransaction.Flow flow, int flowIndex,
    PositionStore positions, CarryTransfer pendingInbound, CarryTransfer carry,
    LedgerPointCollector ledgerPointCollector,
    PassThroughCorridorPlan passThroughCorridorPlan,       // ← ADD
    Map<FlowRef, CarryTransfer> reservedPassThroughCarries // ← ADD
) {
    // ... existing logic: bridgeInboundCarry, applyAuthoritativeLateInboundCarryBasis ...

    // After computing effectiveCarry and updating destination position:
    if (pendingInbound.sourceFlowRef() != null) {
        continuityCarryService.reservePassThroughCarry(
            passThroughCorridorPlan,
            pendingInbound.sourceFlowRef(),   // ← original BRIDGE_IN FlowRef
            effectiveCarry,
            reservedPassThroughCarries
        );
    }

    ledgerPointCollector.record(...);
}
```

Update the 2 call sites in `applyLinkedBridgeTransfer` to pass `replayState.passThroughCorridorPlan()` and `replayState.reservedPassThroughCarries()`.

### P0-b — Defensive: add `networkId` to `PassThroughScopeKey.scope` (SECONDARY)

As an architectural hardening measure (not the primary fix), add `networkId` to `PassThroughScopeKey.scope` to prevent any future case where wallet-scoped outbound matching could accidentally pair transactions across networks.

**Files**: `PassThroughCorridorPlanner.java`, `PassThroughScopeKey.java` (scope field only — record shape unchanged)

```java
// Inbound scope (currently line ~57):
String scopePrefix = networkId(transaction) + ":" + transaction.getWalletAddress() + "->" + transaction.getMatchedCounterparty();

// Wallet-scoped fallback startsWith check (line ~144):
!scopeKey.scope().startsWith(networkId(transaction) + ":" + walletAddress + "->")
```

where `networkId(transaction) = transaction.getNetworkId() != null ? transaction.getNetworkId().name() : "UNKNOWN"`.

**Note**: Pre-deploy check confirmed: 0 LENDING_DEPOSIT/STAKING_DEPOSIT/PROTOCOL_CUSTODY_DEPOSIT rows have `networkId=null` in this session. Null-safe fallback is still required for correctness.

### P1 — Fix SWAP multi-input cost attribution (ETH-V2-C2) — HIGH

3 SWAP events acquire ETH/WETH but only the first USDC DISPOSE flow is linked to cost. Fix: sum ALL DISPOSE flows of the same asset in the same tx for the ACQUIRE cost calculation.

**Expected location**: SWAP replay handler or `GenericFlowReplayEngine.computeAcquireCostBasis()`. Scope investigation before implement.

### P2 — Classify LP_EXIT UNKNOWN basisEffect (ETH-V2-C3) — MEDIUM

18 LP_EXIT events (reconcile with 14 in some audit tables — use 18 from `blockers.md` as authoritative) return ETH with `basisEffect=UNKNOWN`. Investigate and classify as `REALLOCATE_IN`.

**Note**: Undertake only after C1 verification; may require separate plan.

---

## 5. Documentation Updates

| Document | Change |
|----------|--------|
| `docs/03-accounting.md` | Add rule: "When a bridge CARRY_IN arrives before its paired CARRY_OUT (late-carry ordering), the authoritative carry applied by `attachLateBridgeCarryToPendingInbound` must also activate any pre-planned pass-through corridor reservation so that downstream LENDING_DEPOSIT/LP_ENTRY captures the correct AVCO." |
| `docs/adr/ADR-020-bridge-late-carry-passthrough-reservation.md` | **NEW ADR** — documents this invariant, the broken ordering scenario, and the fix. Do NOT amend ADR-019 (different concern). |
| `docs/adr/INDEX.md` | Add ADR-020 entry |

**User impact note**: After prod rebuild, session ETH AVCO will change from $1,953 to approximately $2,600–$2,800. All `asset_ledger_points` for FAMILY:ETH are recomputed. Historical P&L figures for ETH disposed after 2025-11-17 will change retroactively. No user action required — this restores the economically correct values.

---

## 6. Acceptance Criteria

| ID | Criterion | Current → Target |
|----|-----------|-----------------|
| A1 | `fullSessionCurrent.avcoUsd` ≥ $2,600 | $1,953 → ≥$2,600 |
| A2 | Latest `asset_ledger_points` for AMANWETH: `avcoAfterUsd` ≥ $2,800 | $1,714 → ≥$2,800 |
| A3 | AZKSWETH 2nd deposit (tx `0x4466d42d...`): `costBasisDeltaUsd` ≥ $1,500 | $3.49 → ≥$1,500 |
| A4 | AZKSWETH 3rd deposit (tx `0x1e8c5df4...`): `costBasisDeltaUsd` ≥ $1,200 | $7.62 → ≥$1,200 |
| A5 | Zero rows: `asset_ledger_points` where `normalizedType=BRIDGE_OUT` AND `basisEffect=CARRY_IN` AND `costBasisDeltaUsd < -100` | 2 rows → 0 |
| A6 | BRIDGE_OUT tx `0xd9d38471...UNICHAIN` (08:28) and `0x8f3dd850...UNICHAIN` (08:41): no `asset_ledger_point` with `|costBasisDeltaUsd| > $100` and `basisEffect=CARRY_IN` | present → absent |
| A7 | AVCO volatility: top-20 drop events no longer contain drops >$3,000 at events #4 and #5 (2025-11-17 08:28 and 08:41) | −$3,191, −$3,163 → ≤$500 |
| A8 | (P1) 3 SWAP ACQUIRE events: attributed cost ≥ 95% of sum of all DISPOSE flows in same tx | ~0.3% → ≥95% |
| A9 | `./gradlew :backend:test` BUILD SUCCESSFUL, test count unchanged or increased | pass |
| A10 | Regression: ZKSync BRIDGE_IN → ZKSync LENDING_DEPOSIT (same-network, first AZKSWETH deposit at 08:10): `costBasisDeltaUsd` ≥ $1,700 | $1,748 → still ≥$1,700 |
| A10b | Regression: same AZKSWETH deposit also tested with BRIDGE_IN-BEFORE-BRIDGE_OUT ordering in unit test | new test |
| A11 | Structural: after fix, 0 pass-through corridor pairs link transactions on different `networkId`s in any ledger point | not measurable in DB — verified via unit test |
| A12 | New unit test `PassThroughCorridorPlannerTest` (or `TransferReplayHandlerTest`) exercises "BRIDGE_IN before BRIDGE_OUT" ordering for LiFi bridge and asserts LENDING_DEPOSIT captures ≥$1,500 carry | new test passes |
| A13 | Bybit→MANTLE corridor (`0xa5e755...`): `avcoAfterUsd` on CARRY_IN ledger point ≥ $1,714 (ADR-019 not regressed) | not regressed |

---

## 7. Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Other `attachLateBridgeCarryToPendingInbound` call sites (non-linked-bridge path) also missing reservation | LOW | Only called from `applyLinkedBridgeTransfer` (bridge corridor path). The non-bridge `attachLateCarryToPendingInbound` is a different method and handles non-corridor transfers — less likely to need pass-through. |
| `attachLateBridgeSettlementCarryToPendingInbound` has same gap | MEDIUM | Inspect this method; apply same pattern if `PassThroughCorridorPlan` is relevant for settlement bridges. |
| `CarryTransfer.sourceFlowRef` not null-safe in existing code paths | LOW | Factory methods default to `null`; callers guarded by `if (pendingInbound.sourceFlowRef() != null)` check. |
| P0-b (networkId scope) breaks Bybit earn corridors | LOW | Bybit earn uses `ContinuityBucket` + earn prefix — completely independent of `PassThroughScopeKey`. |
| C3 LP_EXIT UNKNOWN — may use different replay path and require separate ADR | MEDIUM | Defer to Phase 2 after C1 fix verified. |

---

## 8. ADR-020 Outline

**Title**: Bridge Late-Carry Pass-Through Reservation Invariant  
**Context**: When BRIDGE_IN arrives before its paired BRIDGE_OUT, the authoritative carry is applied via `attachLateBridgeCarryToPendingInbound`. At this point, any pre-built pass-through corridor for the inbound flow must be activated.  
**Decision**: `attachLateBridgeCarryToPendingInbound` must call `reservePassThroughCarry` using the original BRIDGE_IN's `FlowRef` (stored in `CarryTransfer.sourceFlowRef`).  
**Invariant**: For all LiFi (and any future) bridge pairs where BRIDGE_IN fires before BRIDGE_OUT, a downstream LENDING_DEPOSIT/STAKING_DEPOSIT on the same network must capture AVCO from the corridor-reserved carry, not from the depleted family pool.

---

## 9. Review Log

- [x] `financial-logic-auditor`: REQUEST_CHANGES → root cause corrected, plan v2 addresses all 5 issues
- [x] `business-analyst`: REQUEST_CHANGES → A1 raised to $2,600; A6 made transaction-scoped; A11/A12/A13 added; user impact note added; C3 count reconciled in P2 note
- [x] `system-architect`: APPROVE (with ADR-020 requirement → addressed; null-safe guard → addressed)
- [ ] Final approval pending re-review of v2

---

## 10. Appendix: Verification Queries

```javascript
// A2 — AMANWETH corrected AVCO
db.asset_ledger_points.find(
  { walletAddress: /0x1a87f12/i, assetSymbol: "AMANWETH" },
  { avcoAfterUsd: 1, totalCostBasisAfterUsd: 1, quantityAfter: 1, _id: 0 }
).sort({ blockTimestamp: -1 }).limit(1)

// A3 — AZKSWETH 2nd deposit
db.asset_ledger_points.find(
  { txHash: /0x4466d42d/i, assetSymbol: "AZKSWETH", basisEffect: "REALLOCATE_IN" },
  { costBasisDeltaUsd: 1, avcoAfterUsd: 1, _id: 0 }
)

// A4 — AZKSWETH 3rd deposit
db.asset_ledger_points.find(
  { txHash: /0x1e8c5df4/i, assetSymbol: "AZKSWETH", basisEffect: "REALLOCATE_IN" },
  { costBasisDeltaUsd: 1, avcoAfterUsd: 1, _id: 0 }
)

// A5 — No BRIDGE_OUT CARRY_IN with large negative cost
db.asset_ledger_points.find(
  { normalizedType: "BRIDGE_OUT", basisEffect: "CARRY_IN",
    costBasisDeltaUsd: { $lt: Decimal128("-100") } },
  { txHash: 1, costBasisDeltaUsd: 1, _id: 0 }
)
```
