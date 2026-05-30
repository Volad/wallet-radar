# AVCO Fix Plan: LP_ENTRY Refund Shape + Equilibria cmETH Pool

**Slug**: `avco-lp-entry-refund-and-equilibria`  
**Date**: 2026-05-30  
**Audit sources**: `results/avco-remaining-audit/`  
**Status**: Draft

---

## Blocker Summary

| ID | Severity | Stage | Impact | Complexity |
|----|----------|-------|--------|------------|
| **P-C** | HIGH | `cost_basis` — LP receipt pool not created | $6,214.69 missed ETH basis across 14 LP_EXIT | Low — 1 method change |
| **S-4** | LOW | `cost_basis` — LP receipt pool not created for Equilibria cmETH | ~$617 momentary spike, seq 5129 only | Medium — need to investigate Equilibria LP token symbols |
| **P-B** | VERY LOW | `move_basis` — Bybit corridor zero-basis CARRY_IN | ~$10 direct; same class as ETH-C1 | Deferred — separate ETH-C1 fix track |
| **S-NEW-1** | DISPLAY | display layer — dust AVCO denominator | $0 accounting impact | Low — frontend threshold guard |

---

## Scope

- **Session**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- **Affected networks**: ETHEREUM, ARBITRUM, UNICHAIN (P-C); MANTLE (S-4)
- **Affected positions**: 14 Uniswap V3/V4 LP_EXIT UNKNOWN events; 1 Equilibria cmETH LP_EXIT

---

## P-C — `hasOnlyOutboundPrincipalFlows` Fails for LP_ENTRY with Router Refund

### Root Cause

**File**: `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/LpReceiptEntryReplayHandler.java`, method `hasOnlyOutboundPrincipalFlows()` (lines 68–91)

When adding liquidity to a Uniswap V3/V4 position, the caller sends slightly more tokens than needed. The router returns the excess as an inbound TRANSFER in the same transaction.

Example — `lp-position:unichain:uniswap:42775` LP_ENTRY:
```
TRANSFER: -801.45 USDT     (outbound principal)
TRANSFER: +0.015779 ETH    ← router refund  ← THE BUG TRIGGER
TRANSFER: -0.615779 ETH    (outbound principal, net -0.6 ETH)
FEE:      -5.97E-7 ETH     (gas)
TRANSFER: +1 LP-RECEIPT    (ignored by isLpReceiptMarker)
```

`hasOnlyOutboundPrincipalFlows()` sees `+0.015779 ETH` → sets `hasInboundPrincipal = true` → returns `false` → `isLpReceiptEntry()` returns `false` → LP receipt pool **never created**.

At LP_EXIT, `restoreInboundFromLpReceiptPool()` finds no pool → falls back to spot pricing → `basisEffect = UNKNOWN`.

Example — `lp-position:ethereum:uniswap:922846` LP_ENTRY:
```
TRANSFER: -400.09 USDC     (outbound principal)
TRANSFER: +6.85E-16 ETH    ← dust refund (rounding noise)
TRANSFER: -0.13061 ETH     (outbound principal)
```

**All 14 affected positions** have the same shape: at least one positive inbound TRANSFER of the same asset being deposited (a router refund), causing the check to fail.

**Net deposited amounts are still correct**: USDT net = -801.45, ETH net = +0.0158 − 0.6158 = −0.6. All assets are net outbound.

### Fix

Change `hasOnlyOutboundPrincipalFlows()` from per-flow check to **net-by-asset** logic: aggregate all principal TRANSFER flows per asset symbol, then check that every asset has a net negative (outbound) flow.

```java
static boolean hasOnlyOutboundPrincipalFlows(NormalizedTransaction transaction) {
    if (transaction == null || transaction.getFlows() == null) {
        return false;
    }
    // Aggregate net flow per exact asset symbol.
    // A Uniswap router refund produces a small positive inbound for a deposited asset,
    // but the net (outbound - refund) is still negative. Curve/Balancer shapes where
    // the pool returns a DIFFERENT asset (or a large amount of the same asset) produce
    // net positive flows and are correctly rejected by this check.
    java.util.Map<String, java.math.BigDecimal> netByAsset = new java.util.LinkedHashMap<>();
    for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            continue;
        }
        if (flow.getRole() == NormalizedLegRole.FEE || isLpReceiptMarker(flow)) {
            continue;
        }
        if (flow.getRole() != NormalizedLegRole.TRANSFER) {
            continue;
        }
        String key = flow.getAssetSymbol() == null
                ? ""
                : flow.getAssetSymbol().toLowerCase(java.util.Locale.ROOT);
        netByAsset.merge(key, flow.getQuantityDelta(), java.math.BigDecimal::add);
    }
    if (netByAsset.isEmpty()) {
        return false;
    }
    boolean hasNetOutbound = false;
    for (java.math.BigDecimal net : netByAsset.values()) {
        if (net.signum() > 0) {
            return false;  // net inbound for some asset → Curve/Balancer or unexpected shape
        }
        if (net.signum() < 0) {
            hasNetOutbound = true;
        }
    }
    return hasNetOutbound;
}
```

**Behavior unchanged for existing cases:**
- Standard no-refund LP_ENTRY: all flows negative → net negative per asset → `true` ✓
- Curve/Balancer LP_ENTRY (returns different asset): some asset has net positive → `false` ✓
- LP_ENTRY with router refund: outbound > refund → net negative per asset → `true` ✓

**`apply()` is already correct**: it deposits outbound flows to pool, then withdraws the refund from pool via `applyInboundReceipt()`. Net pool basis = deposited - refund.

### Test Cases Required

| # | Scenario | Expected |
|---|----------|----------|
| T1 | LP_ENTRY with ETH refund (net ETH outbound): +0.015 ETH refund, -0.615 ETH out, -800 USDT out | `true` |
| T2 | LP_ENTRY with dust refund (net ETH outbound): +6.85e-16 ETH, -0.13 ETH, -400 USDC | `true` |
| T3 | LP_ENTRY no refund (standard): -0.5 ETH, -400 USDC | `true` (unchanged) |
| T4 | Curve/Balancer: +0.05 ETH received, -100 USDC deposited | `false` (unchanged) |
| T5 | All flows zero net: +0.5 ETH, -0.5 ETH | `false` |

### Acceptance Criteria

| Check | Expected |
|-------|---------|
| A1 | `lp_receipt_basis_pools` has ETH-family pool for `lp-position:unichain:uniswap:42775` with `basisHeldUsd` reduced to ≈ deposited - refund cost after replay |
| A2 | `asset_ledger_points` for LP_EXIT txs (`0x3321a28e`, `0xbfd6d849`, `0x89ce8e60`) show `basisEffect=REALLOCATE_IN` (not UNKNOWN) |
| A3 | Total missed basis recovered: ≈ $6,214.69 redistributed to REALLOCATE_IN ledger points |
| A4 | All existing `LpReceiptEntryReplayHandlerTest` tests pass |
| A5 | Single-asset LP_ENTRY tests unchanged |

---

## S-4 — Equilibria/Pendle cmETH LP on Mantle: Missing LP_ENTRY + Absent Pool

### Root Cause (Updated After Investigation)

Three Mantle LP_EXIT transactions all have `correlationId: undefined`. Investigation confirmed:

1. **All 3 LP_EXIT txs have `correlationId: undefined`** — the Pendle LP correlationId is not assigned. The token symbols present in flows:
   - tx `0x2cdd7d52`: `PENDLE-LPT` (outbound), `cmETH` (inbound), `PENDLE` (reward)
   - tx `0x89b5f24e`: `PENDLE-LPT` (outbound), `cmETH` (inbound), `PENDLE` (reward)
   - tx `0xf7f8908b`: `eqbPENDLE-LPT` (round-trip), `cmETH` (inbound), `PENDLE` (reward)

2. **`PENDLE-LPT` and `eqbPENDLE-LPT` contain `-LPT`** — `PendleLpCorrelationSupport.marketIdFromSymbol()` SHOULD match. BUT `correlationIdFromMovementLegs()` requires `leg.assetContract() != null`. Mantle raw transactions have **zero persistedLogs** (Etherscan sync without log data) — the raw legs for LP token transfers likely lack contract addresses → correlationId skipped.

3. **Mantle LP_ENTRY count: 0** — The corresponding LP_ENTRY transactions for these Pendle positions are also absent from `normalized_transactions`. Either:
   - LP_ENTRY raw txs were also synced without enough detail to classify
   - LP_ENTRY classifier for Pendle on Mantle doesn't fire for Etherscan-only data
   
   Without an LP_ENTRY, no LP receipt pool is created → even if LP_EXIT gets a correlationId, `lp_receipt_basis_pools` has no matching pool.

4. **Spike is momentary**: cmETH CARRY_OUT'd at seq 5130 immediately after LP_EXIT at seq 5129.

### Decision: DEFERRED

**Rationale**: 
- The fix requires both LP_ENTRY reclassification AND LP_EXIT correlationId assignment for Pendle/Equilibria on Mantle using Etherscan-synced (no-log) data
- Dollar impact: **$617 momentary spike** at a single ledger point — corrected 1 ledger point later
- Fix complexity: HIGH — involves both classifier changes and raw data enrichment for Mantle
- Relative priority: LOW vs. P-C ($6,214 systemic issue)

**Recommended next step**: Add full RPC log backfill for Mantle LP transactions, then rerun normalization. Once logs are available, Pendle classification and LP pool creation will work via the existing `PendleLpCorrelationSupport` path.

---

## S-NEW-1 — Dust AVCO Display Artifact

### Root Cause

231 WRAP/UNWRAP events on BASE (May 2025) show AVCO of $4,000–$5,300 because `basisBackedQuantityAfter < 0.00005 ETH` (all ETH in LP pools). No accounting error.

### Fix

Frontend display guard: when `basisBackedQuantityAfter < DUST_THRESHOLD` (e.g., 0.001 ETH), suppress AVCO display (show `null` or `"—"`). This is a **frontend-only change**.

No backend changes required for S-NEW-1.

---

## Out-of-Scope: P-B (Bybit Corridor Zero-Basis CARRY_IN)

Direct impact: ~$10 ETH. Deferred to the ETH-C1 class fix track. Same code path as previously planned `move_basis` corridor carry fix.

---

## Documentation Updates

1. **`docs/03-accounting.md`** — add to LP_ENTRY accounting section:
   > LP_ENTRY shape detection for receipt-pool routing uses net-by-asset flow aggregation. A Uniswap router may return excess deposited tokens (refund) in the same transaction; this is correctly recognized as a net-outbound position and routed to the LP receipt pool path.

2. **`docs/adr/ADR-022-…`** — amend with P-C net-flow fix.

3. **`docs/adr/INDEX.md`** — update ADR-022 entry.

---

## Implementation Order

1. **P-C code change** — `LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows()`
2. **P-C tests** — T1–T5 in `LpReceiptEntryReplayHandlerTest`
3. **S-4 investigation** — run MongoDB query to find exact Equilibria LP token symbol
4. **S-4 code change** (if symbol identified) — extend `PendleLpCorrelationSupport` or add `EquilibriaLpCorrelationSupport`
5. **Run full test suite** — `./gradlew :backend:test`
6. **Documentation** — `docs/03-accounting.md` + ADR-022 amendment
7. **Rebuild** — `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
8. **Verify** — acceptance criteria A1–A5 (P-C), A1–A4 (S-4)
9. **S-NEW-1** — separate frontend PR (display guard, no backend rebuild needed)

---

## Rebuild Command

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

No pricing cache clear needed — P-C and S-4 are replay/classification defects.
