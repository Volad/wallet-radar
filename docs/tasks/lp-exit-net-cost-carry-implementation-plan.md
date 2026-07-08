# LP_EXIT Net Cost Carry — Implementation Plan (rev 2)

**Blocker ID:** BB-LP-CMETH-1  
**Severity:** MEDIUM  
**Filed:** 2026-07-07  
**Revised after:** Phase 3 review (financial-auditor + system-architect + business-analyst, all NEEDS_REVISION → incorporated)

---

## 1. Scope

- **Wallets / networks:** all wallets, all networks with correlated LP_ENTRY→LP_EXIT pairs (Pendle, Balancer, Velodrome, Aerodrome, any protocol using `lp_receipt_basis_pools`)
- **Assets affected:** any asset carried into an LP at a non-market net cost (Bug B propagated)
- **Pipeline stage:** `cost_basis` / `replay` → `PositionScopedLpExitReplayHandler`
- **Blocker ID:** BB-LP-CMETH-1

---

## 2. Root Cause

### Pre-Bug-B world (correct)

Net cost = market cost for all acquisitions. `lp_receipt_basis_pools` stored `totalBasisHeldUsd`. LP_EXIT restored that value — identical result for both lanes.

### Post-Bug-B world (broken)

SWAP propagates the seller's `netCostBasis` into the acquired token (intentionally). Example:
- USDC (net cost ~$1) → cmETH: net cost = **$1,005**, market cost = $3,328
- LP_ENTRY carries `netBasisHeldUsd = $1,005` **and** `totalBasisHeldUsd = $3,328` into `lp_receipt_basis_pools` (✅ this already works — ADR-040 Change 2)
- LP_EXIT `restoreInboundFromLpReceiptPool()` accumulates `totalBasis` from both same-asset and cross-asset pool drains — but **never accumulates `totalNetBasis`**, then calls the **5-arg `restoreToPosition`** which assigns `netCostBasis = totalBasis` (i.e. market value) instead of the stored `netBasisHeldUsd`
- Result: cmETH exits LP with net cost = $3,398 (market), vs correct $1,005 → **$2,393 phantom inflation**

### Already done (ADR-040 Change 2, do NOT re-implement)

| Item | Status |
|---|---|
| `netBasisHeldUsd` field on `LpReceiptBasisPool` | ✅ Already in model |
| LP_ENTRY deposits net cost into `netBasisHeldUsd` | ✅ `LpReceiptEntryReplayHandler` already does this |
| Backward compat null→market in `withdraw()` | ✅ Already handled |
| `LpReceiptExitReplayHandler` | 🚫 Dead code — never wired into `ReplayRouteHandlerRegistryFactory`, never called in production — leave as-is |

---

## 3. Fix — Ordered Tasks

| # | Task | File | Change |
|---|---|---|---|
| **T4** | Add `totalNetBasis` tracking in `restoreInboundFromLpReceiptPool()` | `PositionScopedLpExitReplayHandler.java` | Introduce `totalNetBasis` accumulator alongside `totalBasis`; drain from `pool.netBasisHeldUsd()` in both same-asset and cross-asset loops; switch to 6-arg `restoreToPosition` |
| **T4b** | Fix `applySettlement()` bucket carry path | `PositionScopedLpExitReplayHandler.java` | Same method: bucket-carry path uses 5-arg `restoreToPosition` → switch to 6-arg with `carry.netCostBasisUsd()` |
| **T4c** | Fix `synthesizeReceiptFromOutbound()` net basis | `PositionScopedLpExitReplayHandler.java` | Secondary gap: receipt synthesis during hold period should propagate net basis for display |
| **T7** | Tests — 6 scenarios | `PositionScopedLpExitReplayHandlerTest.java` | See Section 5 |
| **T8** | Deploy | — | `--skip-frontend` (full pipeline reset, cost basis replay) |

---

## 4. Implementation Detail — T4 (`restoreInboundFromLpReceiptPool`)

```java
// BEFORE (broken):
BigDecimal totalBasis = BigDecimal.ZERO;
for (pool : pools) {
    totalBasis = totalBasis.add(service.withdraw(pool, qty, ...));
}
support.restoreToPosition(flow, position, totalBasis);  // 5-arg — sets net = market

// AFTER (fix):
BigDecimal totalBasis = BigDecimal.ZERO;
BigDecimal totalNetBasis = BigDecimal.ZERO;
for (pool : pools) {
    LpReceiptBasisPool.WithdrawResult result = service.withdrawWithNet(pool, qty, ...);
    totalBasis = totalBasis.add(result.marketBasis());
    totalNetBasis = totalNetBasis.add(result.netBasis());  // uses netBasisHeldUsd, falls back to marketBasis if null
}
support.restoreToPosition(flow, position, totalBasis, totalNetBasis);  // 6-arg
```

The `service.withdraw()` already returns proportional `netBasisHeldUsd` (or falls back to `totalBasisHeldUsd` when null — backward compat already guaranteed). T4 just needs to **use** that value.

---

## 5. Acceptance Criteria (relationship-based, not dollar amounts)

**AC-1 (Regression — Pendle cmETH):**
After fix + full replay: ETH `netAvco` at LP_EXIT ≤ ETH `netAvco` at LP_ENTRY (net cost not inflated by LP round-trip).

**AC-2 (Net cost preserved through LP):**
For any LP_ENTRY followed by LP_EXIT on the same asset: `netCostBasis_at_exit ≈ netCostBasis_at_entry × (qty_exit / qty_entry)` (proportional).

**AC-3 (Dual-lane conservation):**
Σ `totalCostBasisDelta` across LP_ENTRY + LP_EXIT = 0 (market lane conserved).  
Σ `netCostBasisDelta` across LP_ENTRY + LP_EXIT = 0 (net lane conserved independently).

**AC-4 (Partial exit):**
Partial LP_EXIT returns proportional net cost. Remaining pool record retains the un-withdrawn net cost.

**AC-5 (Zero-cost entry — reward→LP):**
If asset entered LP at `netCostBasis = 0` (e.g. via REWARD_CLAIM → LP_ENTRY), LP_EXIT restores `netCostBasis = 0`, NOT market value. (`netBasisHeldUsd = 0` must NOT fall back to market — only `null` falls back.)

**AC-6 (Backward compat — old pool records):**
Existing `lp_receipt_basis_pools` records with `netBasisHeldUsd = null` produce identical results to pre-fix (net lane = market lane).

**AC-7 (No regression on other protocols):**
Balancer, Velodrome, Aerodrome LP positions: net AVCO unchanged (these all route through `PositionScopedLpExitReplayHandler` — verify in code).

---

## 6. Test Scenarios (T7)

| ID | Scenario | Expected |
|---|---|---|
| S1 | Regression: Pendle cmETH (Bug B entry, full exit) | `netCostBasis_exit ≈ $1,005` |
| S2 | Backward compat: pool with `netBasisHeldUsd=null` | `netCostBasis_exit = totalCostBasis_exit` |
| S3 | Zero-cost entry (REWARD_CLAIM → LP_ENTRY → LP_EXIT) | `netCostBasis_exit = 0` |
| S4 | Partial exit (50%) | Proportional net cost; remaining pool retains other 50% |
| S5 | Cross-asset drain (multi-pool) | Both pools contribute net cost proportionally |
| S6 | Dual-lane conservation check | Σ marketDelta = 0, Σ netDelta = 0 across ENTRY+EXIT |

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Zero (`netBasisHeldUsd=0`) must NOT fall back to market | Distinguish null vs 0 in `withdraw()` fallback logic; confirm existing code handles this |
| LP token transferred between wallets before exit | Out of scope for this fix; document as known limitation |
| `LpReceiptExitReplayHandler` dead code | Leave as-is; add TODO comment for future cleanup |

---

## 8. Status

- [x] Phase 1 — Audit complete (blocker identified)
- [x] Phase 2 — Plan drafted
- [x] Phase 3 — Review complete (all 3 reviewers: NEEDS_REVISION → incorporated in rev 2)
- [ ] **Phase 3 re-approval** — user sign-off required
- [ ] Phase 4 — Docs update
- [ ] Phase 5 — Implementation (`backend-dev`)
- [ ] Phase 6 — Verify
