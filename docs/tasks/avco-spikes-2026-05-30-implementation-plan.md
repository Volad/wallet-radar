# AVCO Spikes — Implementation Plan (Revised v2)
**Audit ref**: `docs/tasks/avco-spikes-2026-05-30-audit.md`  
**Date**: 2026-05-30  
**Blockers**: B-1, B-2, B-3, B-4  
**Review incorporated**: auditor (R-1, R-2, R-3), BA (C-1, C-2, C-3, C-4), architect (F1, F3, F4, F5)

---

## Summary

| Blocker | Severity | Root Cause | Fix Type |
|---------|----------|------------|----------|
| B-3 | High — recurring +26% spike | P0-C corridor carry override inflates basis when source position has uncovered qty | Code fix in `TransferReplayHandler` |
| B-2 | High — ±25-35% transient spikes | Replay ordering: on-chain INTERNAL_TRANSFER destination CARRY_IN before source CARRY_OUT | Code fix in `ConfirmedReplayQueryService` |
| B-1 | High — permanent +30% basis injection | BASE ETH shortfall from `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` multicall tx that should be LP_EXIT | Normalization investigation + fix |
| B-4 | Medium — ±19-23% transient spikes | BASE ETH shortfall, same root cause as B-1 — **Nov 17 UNKNOWN is PROMO_SPAM_PHISHING, not an LP_EXIT** (R-2); requires separate targeted investigation | Separate normalization investigation |

---

## B-3 — P0-C Corridor Carry Over-Inflation

### Root Cause

`TransferReplayHandler.applyTransfer()`, P0-C ADR-019 override block (~lines 140–195):

```java
BigDecimal corridorOutboundSliceAvco = corridorTransfer ? position.perWalletAvco() : null;
// ... line 145: carrySource assigned ...
// ... line 147: removeTransferCarry drains position ...
// P0-C override:
if (corridorOutboundSliceAvco != null && corridorOutboundSliceAvco.signum() > 0) {
    BigDecimal movedQty = flow.getQuantityDelta().abs();
    carry = continuityCarryService.buildExplicitCarryTransfer(
            movedQty, movedQty.multiply(corridorOutboundSliceAvco, MC), carrySource.assetKey()
    );
}
```

`position.perWalletAvco()` = `totalCostBasis / coveredQty` — excludes uncovered qty. When uncovered qty > 0, this is **inflated**. Multiplying by `movedQty` (which spans both covered and uncovered) over-states the carry.

**Verified in production** (Sep 10 2025, Mantle→Bybit cmETH, seq 5120–5124):
- Position: totalQty=0.862092, uncovQty=0.211806, totalBasis=$1,898.79
- perWalletAvco = $1,898.79 / 0.650286 = $2,919.93 (inflated)
- P0-C override: 0.862092 × $2,919.93 = **$2,517.25** (wrong)
- Correct: totalBasis × (0.862092 / 0.862092) = **$1,898.79**

Additional occurrence confirmed: Across bridge corridor seq 617→619 (ETH), same bug, +$3.38 inflation (R-3).

### Fix

**File**: `TransferReplayHandler.java`, corridor CARRY_OUT block (after `carrySource` assignment on ~line 145, before `removeTransferCarry()` call on ~line 147).

**Step 1 — Add before `removeTransferCarry()` call** (must be AFTER line 145 where `carrySource` is assigned — architect F1):

```java
// Capture pre-drain totals for P0-C proportional basis (B-3 fix)
BigDecimal preDrainTotalBasis = carrySource.totalCostBasisUsd();
BigDecimal preDrainTotalQty   = carrySource.quantity();
```

**Step 2 — Replace the P0-C block**:

```java
// P0-C: For corridor outbound, force carry basis = proportional cost (ADR-019 amended).
// Use totalCostBasis × (movedQty / totalQty) instead of movedQty × perWalletAvco.
// perWalletAvco divides by covered qty only and inflates basis when uncoveredQty > 0.
if (corridorOutboundSliceAvco != null && corridorOutboundSliceAvco.signum() > 0) {
    BigDecimal movedQty = flow.getQuantityDelta().abs();
    BigDecimal corridorCarryBasis;
    if (preDrainTotalQty != null && preDrainTotalQty.signum() > 0
            && preDrainTotalBasis != null && preDrainTotalBasis.signum() > 0) {
        corridorCarryBasis = preDrainTotalBasis
                .multiply(movedQty, MC)
                .divide(preDrainTotalQty, MC);
    } else {
        corridorCarryBasis = movedQty.multiply(corridorOutboundSliceAvco, MC);
    }
    // Cap: movedQty can never exceed totalQty due to shortfall, but guard against
    // any rounding/edge case where formula exceeds the position's total basis (R-1 / C-4)
    if (preDrainTotalBasis != null) {
        corridorCarryBasis = corridorCarryBasis.min(preDrainTotalBasis);
    }
    carry = continuityCarryService.buildExplicitCarryTransfer(
            movedQty, corridorCarryBasis, carrySource.assetKey()
    );
}
```

### Edge cases (confirmed safe)

| Scenario | Old result | New result |
|----------|-----------|------------|
| uncovQty = 0 (clean position) | `movedQty × (totalBasis / totalQty)` | same — no change |
| uncovQty > 0, full move | inflated carry | `totalBasis` exactly |
| uncovQty > 0, partial move (50%) | inflated carry | 50% of totalBasis |
| preDrainTotalBasis = 0 (zero-cost, e.g., airdrop) | carry = 0 | carry = 0 (fallback to corridorOutboundSliceAvco = 0 or null → no override) |
| movedQty > preDrainTotalQty (shortfall) | uncapped inflation | capped at `preDrainTotalBasis` |

---

## B-2 — INTERNAL_TRANSFER Corridor Replay Ordering

### Root Cause

`ConfirmedReplayQueryService.bybitContinuityFlowSign()` only applies to `source=BYBIT`. On-chain INTERNAL_TRANSFER corridors (source=ON_CHAIN) get sign=0, fall through to lexicographic ID sort. `0x1a87...` < `0xa0dd42...` → CARRY_IN (destination) precedes CARRY_OUT (source) → transient double-count.

**Verified**: tx `0xd7c7736b...MANTLE`, seq 1330 (CARRY_IN $0 basis) → seq 1331 (+$1254 basis) → seq 1332 (CARRY_OUT) produces -25%/+35% spikes.

### Fix

**File**: `ConfirmedReplayQueryService.java`

Replace `bybitContinuityFlowSign()`:

```java
// Renamed (F4): now covers on-chain BYBIT-CORRIDOR transfers, not only Bybit-source rows
private static int corridorContinuityFlowSign(NormalizedTransaction tx) {
    if (tx == null || tx.getFlows() == null) {
        return 0;
    }
    boolean isBybit = tx.getSource() == NormalizedTransactionSource.BYBIT;
    // F3: use BYBIT-CORRIDOR: prefix only — not a generic !blank check
    boolean isOnChainCorridor = !isBybit
            && tx.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
            && tx.getCorrelationId() != null
            && tx.getCorrelationId().startsWith("BYBIT-CORRIDOR:");
    if (!isBybit && !isOnChainCorridor) {
        return 0;
    }
    return switch (tx.getType()) {
        case INTERNAL_TRANSFER, LENDING_DEPOSIT, LENDING_WITHDRAW -> bybitPrincipalFlowSign(tx);
        default -> 0;
    };
}
```

Update the `REPLAY_ORDER` comparator to reference the renamed method:
```java
.thenComparingInt(ConfirmedReplayQueryService::corridorContinuityFlowSign)
```

### Confirmed safe

- Non-corridor on-chain INTERNAL_TRANSFER (no `BYBIT-CORRIDOR:` prefix) → sign=0, unaffected (F3 guard)
- "Receive then forward" patterns on-chain → these are two separate transactions with different tx hashes; the same-tx ordering only applies when sharing the same blockTimestamp+transactionIndex+corrId
- Sort is deterministic: ID tiebreaker (level 5) guarantees total order (architect F4 ✓)

---

## B-1 — BASE ETH Shortfall (Feb 6 2026 BRIDGE_OUT)

### Root Cause

Wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` on BASE has a normalized transaction classified as:
```
type:   UNKNOWN
reason: ROUTER_METHOD_OVERLOAD_UNSUPPORTED
id:     0x0a757aeeb58667c545017cd8e5cd... (Feb 6, 07:15:37)
```

This is a multicall/PancakeSwap V3 LP close that the current normalizer cannot parse (overloaded router method). It should produce an `LP_EXIT` with TRANSFER ETH +0.799 flow. Without it:
- BASE ETH position has `quantityShortfallDelta = 0.799`
- BRIDGE_OUT carries $0 basis
- BRIDGE_IN at ARB applies spot fallback ($1896.96) → **$1,515 phantom basis** injected

### Fix

Investigate and extend the BASE on-chain normalizer to handle the `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` method signature. Specifically:

1. Pull the tx trace for `0x0a757aeeb58667c545017cd8e5cd...` on BASE via RPC
2. Parse token transfer events to extract the ETH (or WETH) inflow
3. Classify the multicall shape as `LP_EXIT` with correct flows
4. Re-normalize and rebuild

This is a targeted normalization fix; detailed implementation plan to follow after on-chain trace analysis.

---

## B-4 — BASE ETH Shortfall (Nov 17 2025 WRAP)

### Root Cause (corrected — R-2)

The Nov 17 UNKNOWN transaction (`0x47cf19b7...`) is **NOT** a PancakeSwap LP exit. MongoDB confirms:
- `missingDataReasons: ['PROMO_SPAM_PHISHING']`
- `methodId: 0xa06c1a33` — batch distribution contract, 200 wallet addresses
- Not the source of 0.546 ETH

The actual source of the 0.546 ETH for the Nov 17 WRAP is **unknown and requires separate investigation**. The LP_EXIT at 07:58:53 confirmed returned USDC only. There may be prior BASE ETH history not backfilled, or another untracked transaction.

### Fix

**Separate investigation required** — do NOT attempt to fix the `0x47cf19b7...` UNKNOWN (it is correctly classified as PROMO_SPAM_PHISHING). Instead:

1. Reconstruct BASE ETH balance history for `0x1a87...` from genesis to Nov 17 2025
2. Identify the transaction(s) that should have created 0.546 ETH in the position
3. Write a targeted normalization fix or data backfill

This is deferred to a separate plan after investigation.

---

## Rebuild Order

| Step | Blocker(s) | Action |
|------|-----------|--------|
| 1 | B-3 | Implement `TransferReplayHandler` proportional carry fix with cap |
| 2 | B-2 | Implement `ConfirmedReplayQueryService` ordering fix + rename |
| 3 | B-3, B-2 | `./gradlew :backend:test` — verify no regressions |
| 4 | B-3, B-2 | `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` |
| 5 | B-1 | Investigate UNKNOWN tx on-chain trace → normalization fix → rebuild |
| 6 | B-4 | Separate investigation (deferred) |
| 7 | All | Financial auditor sign-off |

---

## Acceptance Criteria

### B-3

1. Ledger seq 5124 (`BYBIT-33625378:FUNDING_HISTORY:f9cfb4eb...`) `costBasisDeltaUsd` = $1,898.79 (±$1)
2. Ledger seq 619 (Across bridge ETH CARRY_IN) `costBasisDeltaUsd` = $7.68 (±$0.10), down from $11.06 (R-3)
3. **Corpus AC** (C-1): No `CARRY_IN` ledger point for any `BYBIT-CORRIDOR:*` has `costBasisDeltaUsd` materially exceeding the paired CARRY_OUT `|costBasisDeltaUsd|`. Query:
   ```js
   // Run after rebuild: find CARRY_IN cbDelta > 110% of paired CARRY_OUT cbDelta
   db.asset_ledger_points.aggregate([
     { $match: { basisEffect: "CARRY_IN", correlationId: /^BYBIT-CORRIDOR:/ } },
     // join with CARRY_OUT on same correlationId, compare cbDelta ratio
   ]);
   ```

### B-2

4. For tx `0xd7c7736b...MANTLE`: source CARRY_OUT has lower `replaySequence` than destination CARRY_IN
5. No intermediate ledger point between source and destination has `uncoveredQuantityDelta > 0` for the moved asset

### B-1

6. After normalization fix: the UNKNOWN tx `0x0a757aee...` (Feb 6, BASE) is reclassified as `LP_EXIT` with ETH TRANSFER flow
7. `quantityShortfallDelta = 0` on the BRIDGE_OUT `0x4ca0b79e...BASE` (was 0.799)
8. BRIDGE_IN `0x38d445c4...ARB` `costBasisDeltaUsd` equals the BRIDGE_OUT `|costBasisDeltaUsd|` (±$1) — carry preserved through bridge

### Global

9. AVCO spike scan at >10% threshold: ≤ 5 spikes above 10% family AVCO change (B-5 Bybit FUND ping-pong accounts for remaining legitimate swings); run the same query as the audit (C-3)

---

## Documentation Updates Required

- **ADR-019** (`docs/adr/ADR-019-corridor-carry-policy.md`) (F5):
  - Amend Rule 1: `carryBasis = totalCostBasisUsd × (movedQty / totalQty)` with cap at `totalCostBasisUsd`; note that `perWalletAvco × movedQty` is incorrect when position has `uncoveredQuantity > 0`
  - Add Rule: On-chain `BYBIT-CORRIDOR:` INTERNAL_TRANSFER corridors are subject to CARRY_OUT-before-CARRY_IN replay sequencing (same as Bybit-source corridors)

---

## Risk Notes

- **B-3 fix**: When `uncoveredQty = 0`, result is algebraically identical to before. No regression risk for clean positions. Cap guard prevents any edge case from being worse than today.
- **B-2 fix**: Guard uses `startsWith("BYBIT-CORRIDOR:")` — future on-chain INTERNAL_TRANSFERs with a different correlationId prefix are not affected.
- **B-1 normalization**: Fixing `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` multicalls may reclassify other similar BASE transactions. Run normalization tests and spot-check before full prod rebuild.
- **B-4**: Deferred; do not modify the PROMO_SPAM_PHISHING UNKNOWN transaction.
