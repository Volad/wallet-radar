# Architecture Review — AVCO Spikes Implementation Plan
**Plan ref**: `docs/tasks/avco-spikes-2026-05-30-implementation-plan.md`  
**Audit ref**: `docs/tasks/avco-spikes-2026-05-30-audit.md`  
**Date**: 2026-05-30  
**Reviewer**: System Architect

---

## Verdict: CONDITIONAL APPROVE

The plan is structurally sound and the fixes are correctly targeted at the right pipeline stages. However, three implementation-level concerns must be addressed before merge, and one design concern warrants strengthening. B-1/B-4 cannot be merged as a plan step — it is an investigation placeholder, not an implementable specification.

---

## Finding 1 — B-3: Capture insertion point is WRONG (BLOCKER)

**Severity**: Blocker — will cause a `NullPointerException` if implemented as written.

The plan states:
> Add before the drain, just after `boolean venueInternal = classifier.usesBybitVenueInternalCarryQueue(transaction);`

In the actual code (`TransferReplayHandler.java`):
- Line 141: `boolean venueInternal = ...`  
- **Line 145**: `PositionState carrySource = earnPrincipalCarrySourcePosition(transaction, position, replayState);`  
- Line 147: `CarryTransfer carry = continuityCarryService.removeTransferCarry(...)` ← **the drain**

`carrySource` is not assigned until line 145. The two capture lines:
```java
BigDecimal preDrainTotalBasis = carrySource.totalCostBasisUsd();
BigDecimal preDrainTotalQty   = carrySource.quantity();
```
must be inserted **between lines 145 and 147** (after `carrySource` assignment, before `removeTransferCarry`). Inserting them before line 145 as the plan implies will not compile.

**Fix required in plan**: Change insertion point to "after `carrySource` assignment (line 145), before `removeTransferCarry` call."

---

## Finding 2 — B-3: `corridorOutboundSliceAvco` source is inconsistent with capture source (CONCERN)

**Severity**: Concern — not a bug in the common case, but an architectural inconsistency.

`corridorOutboundSliceAvco` is captured from `position.perWalletAvco()` at line 144 (before `carrySource` is assigned). The new `preDrainTotalBasis` / `preDrainTotalQty` are captured from `carrySource` (after line 145). These refer to different `PositionState` instances whenever `earnPrincipalCarrySourcePosition()` diverges from `position` — i.e., for Bybit earn-principal corridor outbounds where the `:EARN` sub-account is empty.

In that scenario:
- `corridorOutboundSliceAvco` = `position.perWalletAvco()` on the empty `:EARN` slice → null or 0 → the entire P0-C block is **skipped** (the gate `corridorOutboundSliceAvco != null && signum > 0` fails).
- `preDrainTotalBasis` / `preDrainTotalQty` would be read from the umbrella position.

This means the fix does not activate for earn-principal corridor outbounds where `:EARN` is empty — the existing `normalizeBybitEarnProductCarry` / `applyEarnPrincipalLotCarryOverride` path handles those instead. This is probably acceptable for the current production case (B-3 is a direct on-chain Mantle CMETH corridor, not earn-principal), but the inconsistency should be documented in a code comment so a future author does not inadvertently move the `corridorOutboundSliceAvco` capture to `carrySource` without also reviewing the earn-principal path.

**Required action**: Add a code comment at line 144 explaining that `position` is intentionally used (not `carrySource`) so earn-principal outbounds with empty `:EARN` skip the P0-C override and fall through to the earn-principal handlers.

---

## Finding 3 — B-2: `isOnChainCorridor` predicate is too broad (CONCERN)

**Severity**: Concern — potential blast radius on future normalization changes.

The proposed fix:
```java
boolean isOnChainCorridor = !isBybit
        && Boolean.TRUE.equals(tx.getContinuityCandidate())
        && tx.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
        && tx.getCorrelationId() != null
        && !tx.getCorrelationId().isBlank();
```

This matches **any** on-chain `INTERNAL_TRANSFER` with `continuityCandidate=true` and a non-blank `correlationId`, regardless of prefix. If a future normalizer assigns a non-blank correlationId to an on-chain `INTERNAL_TRANSFER` for reasons other than a corridor pair (e.g., a DEX internal leg), it would be sorted with outbound-before-inbound.

Current production evidence: only `BYBIT-CORRIDOR:*`-correlated on-chain INTERNALs exist. But as the normalization pipeline grows (Solana, future CEX integrations), this predicate silently broadens.

**Stronger alternative** — scope to the known corridor correlationId namespace or add a check against a more specific marker. If all on-chain corridor INTERNAL_TRANSFERs carry `BYBIT-CORRIDOR:` prefix (confirmed: `isCorridorTransfer()` checks exactly this prefix), the safest version is:

```java
boolean isOnChainCorridor = !isBybit
        && tx.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
        && tx.getCorrelationId() != null
        && tx.getCorrelationId().startsWith("BYBIT-CORRIDOR:");
```

Note: `Boolean.TRUE.equals(tx.getContinuityCandidate())` is redundant here — `BYBIT-CORRIDOR:` prefix already implies it's a continuity pair. Dropping the flag check is cleaner.

**However**: using `BYBIT-CORRIDOR:` prefix ties the comparator to a naming convention. If a non-Bybit on-chain corridor later uses a different prefix, it will miss this fix. A medium-term solution is to add a `corridorRole: SOURCE|DESTINATION` field to normalized transactions set at pairing time — but that requires a normalization model change outside this plan's scope.

**Required action**: Replace the broad `correlationId != null && !blank` condition with `correlationId.startsWith("BYBIT-CORRIDOR:")` to match the exact same scope as `isCorridorTransfer()`. Update the method comment to reflect that it now handles on-chain BYBIT-CORRIDOR INTERNAL_TRANSFERs in addition to Bybit-source transactions.

**Also required**: Rename `bybitContinuityFlowSign` → `corridorContinuityFlowSign` (or similar) to accurately reflect its scope post-fix. The existing Javadoc comment "Scoped to Bybit continuity rows" becomes incorrect.

---

## Finding 4 — B-2: Sort is deterministic (CONFIRMED OK)

The `REPLAY_ORDER` comparator has 5 levels:
1. `blockTimestamp` (null-last natural)
2. `transactionIndex` (null-last natural)
3. `bybitSameDayTransactionClassOrder` (returns `-2`, `-1`, or `0`, Bybit-only)
4. `bybitContinuityFlowSign` (returns `-1`, `0`, or `+1`)
5. `getId()` lexicographic (null-last)

After the B-2 fix, if two on-chain corridor rows share the same `blockTimestamp`, `transactionIndex`, AND the same sign result (impossible for a paired corridor — one is outbound `-1` and one is inbound `+1` by definition), the ID tiebreaker at level 5 guarantees a total order. IDs are stored strings, unique per `normalized_transaction` document. **No non-determinism risk.**

`bybitPrincipalFlowSign()` iterates flows of the stored transaction object; flow order is stable across replay runs (document not mutated). **Determinism confirmed.**

The `bybitSameDayTransactionClassOrder` step 3 only activates for `source=BYBIT`, so it does not interfere with the new on-chain branch. ✓

---

## Finding 5 — B-3: ADR-019 alignment requires update (REQUIRED)

ADR-019 Rule 1 states: `carryBasis = movedQty × outboundSliceAvco`.

The B-3 fix replaces this with: `carryBasis = preDrainTotalBasis × (movedQty / preDrainTotalQty)` when `uncoveredQty > 0`.

**Mathematical equivalence** when `uncoveredQty = 0`:  
`outboundSliceAvco = totalBasis / totalQty` (since `coveredQty = totalQty`)  
`→ movedQty × outboundSliceAvco = totalBasis × (movedQty / totalQty)` ✓ identical.

**When `uncoveredQty > 0`**:  
`outboundSliceAvco = totalBasis / coveredQty` (inflated because denominator < totalQty)  
`→ movedQty × outboundSliceAvco > totalBasis × (movedQty / totalQty)` ← bug the fix corrects.

The ADR-019 "outbound-AVCO preservation" rule is **not violated** by the fix — rather, the fix corrects an erroneous implementation of that rule for partially-uncovered positions. ADR-019 must be updated to:
1. Clarify that `outboundSliceAvco` must be `totalBasis / totalQty` (not `totalBasis / coveredQty`), or equivalently, that the proportional-basis formula is the canonical form.
2. Note the `uncoveredQty > 0` scenario and the pre-drain capture requirement.
3. Add a note about the on-chain INTERNAL_TRANSFER corridor ordering extension (B-2 fix).

The B-2 ordering fix does not require a new ADR — it is an implementation detail of the corridor carry pipeline. Adding it as a note in the ADR-019 update is sufficient.

---

## Finding 6 — B-3: Non-corridor outbound paths are NOT affected (CONFIRMED OK)

`isCorridorTransfer()` (`ReplayTransferClassifier.java`, lines 183–189) returns `true` only when `correlationId.startsWith("BYBIT-CORRIDOR:")`. The P0-C block entry condition is:

```java
BigDecimal corridorOutboundSliceAvco = corridorTransfer ? position.perWalletAvco() : null;
// ...
if (corridorOutboundSliceAvco != null && corridorOutboundSliceAvco.signum() > 0) { ... }
```

For `corridorTransfer = false` (all non-BYBIT-CORRIDOR outbound transfers), `corridorOutboundSliceAvco = null` and the block is unreachable. The fix is fully scoped to corridor outbound transfers. **No regression risk for non-corridor transfers.**

---

## Finding 7 — B-1/B-4: Migration strategy is correct but plan is INCOMPLETE (CONCERN)

**Normalization change + rebuild** is the correct migration strategy. The `prod-reset-rebuild-backend.sh --skip-frontend` wipes and rebuilds from scratch — there is no incremental row-patch required. This is consistent with the pipeline architecture (normalize → classify → replay is deterministic and idempotent given the same normalized data).

**Risk**: re-normalization runs for all transactions, not only the two UNKNOWN ones. If the added PancakeSwap V3 `collect()`/`decreaseLiquidity()` function-signature recognizer is accidentally broad (e.g., matches the function selector on other networks or protocols), it could misclassify currently-correct transactions. The plan must:
1. Pin the fix to a specific function selector (e.g., `0x4f1eb3d8` for `collect()` on PancakeSwap V3 pools on BASE specifically, or scoped to the BASE network + counterparty address of the known PancakeSwap V3 NonfungiblePositionManager).
2. Verify the fix against the full normalization test suite before rebuild.

**The plan is an investigation placeholder, not an implementable spec.** B-1/B-4 cannot be handed to a developer as-is. A separate implementation plan is required after on-chain trace analysis.

**The ADR question**: B-1/B-4 normalization fix is a classifier extension. It may need a new ADR or an update to ADR-001 (on-chain classification) when implemented. This is deferred until the on-chain investigation is complete.

---

## Summary of Required Changes to Plan

| # | Blocker | Change Required | Severity |
|---|---------|----------------|----------|
| F1 | B-3 | Fix insertion point: capture `preDrainTotalBasis`/`preDrainTotalQty` AFTER `carrySource` assignment (line 145), not before it | **BLOCKER** |
| F2 | B-3 | Add code comment at line 144 documenting why `position` (not `carrySource`) is used for `corridorOutboundSliceAvco` gate | Required |
| F3 | B-2 | Replace `isOnChainCorridor` predicate: use `correlationId.startsWith("BYBIT-CORRIDOR:")` instead of `!= null && !blank` | Required |
| F4 | B-2 | Rename `bybitContinuityFlowSign` → `corridorContinuityFlowSign`; update Javadoc | Required |
| F5 | ADR-019 | Update with: (a) proportional-basis clarification for uncovered positions, (b) on-chain corridor ordering extension | Required |
| F6 | B-1/B-4 | Separate implementation plan required after on-chain trace investigation | Required before B-1/B-4 impl |

---

## ADR Update Draft — ADR-019

Add the following sections to `docs/adr/ADR-019-corridor-carry-policy.md`:

### Addendum 1 — P0-C Proportional Basis Correction (B-3 fix)

**Problem**: When the source position has `uncoveredQuantity > 0`, `perWalletAvco` is computed as `totalBasis / coveredQty` (covered qty only). Using `movedQty × perWalletAvco` produces a carry basis larger than `totalBasis`, inflating the inbound side.

**Corrected rule**: The carry basis for corridor `CARRY_OUT` must be:
```
carryBasis = preDrainTotalBasis × (movedQty / preDrainTotalQty)
```
where `preDrainTotalBasis` and `preDrainTotalQty` are captured **before** `removeTransferCarry` drains the position. This is mathematically equivalent to `movedQty × outboundSliceAvco` when `uncoveredQty = 0` and correct when `uncoveredQty > 0`.

Fallback: if `preDrainTotalQty` or `preDrainTotalBasis` is zero or null, fall back to `movedQty × perWalletAvco` (original rule).

**Applies to**: `corridorTransfer = true` (BYBIT-CORRIDOR prefix) outbound flows only.

---

### Addendum 2 — On-chain INTERNAL_TRANSFER Corridor Ordering (B-2 fix)

**Problem**: On-chain `INTERNAL_TRANSFER` corridor pairs (two wallet rows for the same txHash, `source=ON_CHAIN`, `correlationId.startsWith("BYBIT-CORRIDOR:")`) are not covered by the existing Bybit-source-only `bybitContinuityFlowSign` tiebreaker. When both rows have identical `blockTimestamp` and `transactionIndex`, lexicographic ID sort can place the destination `CARRY_IN` before the source `CARRY_OUT`, creating a transient double-count.

**Rule**: The `REPLAY_ORDER` comparator tiebreaker for flow direction must also apply to on-chain `INTERNAL_TRANSFER` transactions with `correlationId.startsWith("BYBIT-CORRIDOR:")`. Outbound (qty < 0) must sort before inbound (qty > 0) within the same logical corridor transaction.

**Implementation**: Extend `bybitContinuityFlowSign()` (renamed `corridorContinuityFlowSign()`) to return the flow sign for on-chain `INTERNAL_TRANSFER` rows when `correlationId.startsWith("BYBIT-CORRIDOR:")`.

---

## ADRs NOT requiring updates

- **ADR-023** (Pendle LPT / BYBIT-CORRIDOR inbound): Describes the inbound spot-price acquisition path (D5). The B-3 fix modifies the outbound path only. No conflict or overlap. No update required.
- **ADR-020** (bridge late-carry pass-through): Not touched by any of B-1..B-4. No update required.
- **ADR-015** (per-counterparty basis pool): Not touched. No update required.
