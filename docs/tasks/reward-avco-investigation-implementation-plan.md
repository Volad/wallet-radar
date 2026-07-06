# Implementation Plan — Reward AVCO Investigation & Display Fix

**Slug:** `reward-avco-investigation`  
**Date:** 2026-06-29  
**Audit reference:** `results/blockers.md`, `results/accounting-failure-analysis.md`  
**Phase 3 review:** Financial Auditor, Business Analyst, System Architect (2026-06-29)

---

## Symptom

User reports many positions with AVCO that seems too high for assets received primarily as rewards (AVAX, MNT, SUSHI). Expected AVCO to be much lower; actual figures look inflated.

---

## Scope

- Universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- Assets investigated: AVAX, MNT, SUSHI (all wallets, all networks)
- Pipeline stages: `cost_basis`, `avco`, `verification` (display/reporting)

---

## Root Cause per Blocker

### B-1 — MNT AVCO (NOT a pipeline bug)

**Finding:** AVCO is mathematically correct.  
- Jan 2025: Genuine Bybit spot buys at $1.27–1.37 (priceSource=EXECUTION)
- Aug–Sep 2025 bull market: Genuine Bybit spot buys at $1.15–1.29
- Oct–Nov 2025: Near-empty pool when $1.63 REWARD_CLAIM arrived → AVCO reset to $1.63
- Since then: Slow AVCO decay via daily rewards at $0.44–0.65

**Combined MNT AVCO (Phase 3 correction):** $0.990 across 6 wallet pools ($6,140 / 6,204 MNT).  
The per-wallet figure of $1.267 used in Phase 1 was single-wallet (BYBIT:33625378) only.

**User perception:** MNT now trades at $0.44–0.65 — this is an **unrealized loss**, not a pipeline error.  
**Action required:** None for MNT AVCO itself. Explanation only. UX improvement recommended separately.

### B-2 — AVAX displayed AVCO $6.19 vs correct combined $14.23 (**DISPLAY BUG**)

**Finding:** Per-wallet AVCO is correct:
- wallet1: $14.43 AVCO, 0.259 AVAX, $3.74 cost basis
- wallet2: $6.196 AVCO, 0.006 AVAX, $0.036 cost basis
- Combined correct AVCO: $3.776 / 0.265 = **$14.23**

**Root cause (Phase 3 architectural finding):** `fullSessionCurrentView()` in `AssetLedgerQueryService` already computes the correct weighted AVCO from `asset_ledger_points` — the problem is that the dashboard display uses `currentStateView.avcoUsd` (which depends on `on_chain_balances` snapshots and can miss wallets) rather than `fullSessionCurrentView.avcoUsd`. Additionally, the frontend aggregates positions using `quantity` as the weight instead of `coveredQuantity`.

**Stage:** `verification` (reporting layer)  
**Severity:** HIGH — misleads user about their actual average cost

### B-3 — SUSHI ($0.55) — CLEAN

No action required. All 4 REWARD_CLAIM events at correct market prices.

### B-4 — BORROW-as-ACQUIRE (architectural policy issue)

**Finding:** Bybit BORROW events are classified as `ACQUIRE` (adding cost basis at market price); REPAY removes basis at current pool AVCO. A BORROW→REPAY round-trip changes AVCO because removal price ≠ injection price.

For MNT:
- 9 BORROW events: +$2,541.85 cost basis injected at $0.72 avg
- 7 REPAY events: −$873.56 removed at blended AVCO
- Net: $1,668.29 excess cost basis remains for 1,246 MNT still in pool

**Phase 3 corrections:**
- **Option A direction is reversed:** REALLOCATE_IN(0) for BORROW would *raise* combined MNT AVCO (~$0.98–1.05), not lower it. Current REPAY|DISPOSE at pool AVCO slightly erodes genuine basis; Option A preserves it. A completed BORROW→REPAY cycle under Option A is exactly cost-neutral.
- **Architectural issue is narrower:** The actual bug is in `RepayReplayHandler.applySell()` — it removes `qty × poolAvco` from basis when it should remove `qty × liabilityAvcoUsd`. The borrow-liability tracking machinery is already in place but the removal price is wrong.

**Impact on MNT AVCO:** Not the primary cause of high MNT AVCO (high AVCO is driven by genuine purchases at $1.27+). Borrow cycles cause minor AVCO drift.

**Long-term risk:** Future high-price borrow cycles could inflate AVCO artificially.

---

## Changes

### Change 1 — Fix combined AVCO display (Priority: HIGH)

**What:** Surface `fullSessionCurrentView.avcoUsd` (which already computes weighted AVCO from ledger points) instead of `currentStateView.avcoUsd` (which depends on on-chain balance snapshots and can miss wallets). Fix frontend weight to use `coveredQuantity` instead of `quantity`.

**Where to fix:**
- **Backend:** `AssetLedgerQueryService` — use `fullSessionCurrentView` as the primary AVCO source in the asset position summary response; no new aggregation needed
- **Frontend:** `dashboard.component.ts` lines 643 and 694 — replace `quantity` with `coveredQuantity` as the accumulation and division weight

**Required guards (from Phase 3 review):**
- Zero-qty wallet pools must be excluded from the denominator (avoid division by zero; two Bybit sub-accounts have zero MNT)
- Negative-qty wallets (leveraged borrow outstanding): treat as zero contribution to combined AVCO (document as unsupported scope)
- Explicitly verify cross-wallet bridge transfers are net-zero in aggregation input (bridge double-count risk)

**Scope:** Backend `AssetLedgerQueryService` (minimal) + frontend `dashboard.component.ts`. No replay needed.

### Change 2 — Fix BORROW/REPAY cost basis accuracy (Priority: MEDIUM, requires policy decision)

**Three options on the table after Phase 3 review:**

| Option | Description | Verdict |
|--------|-------------|---------|
| A | BORROW → REALLOCATE_IN(0); REPAY → REALLOCATE_OUT(0) | CONDITIONAL_APPROVE (auditor); raises AVCO slightly, makes round-trips cost-neutral |
| B | Exclude BORROW/REPAY from AVCO pool entirely | Deferred; higher implementation complexity |
| C *(new, recommended by architect)* | Keep BORROW as ACQUIRE; fix `RepayReplayHandler.applySell()` to use `liabilityAvcoUsd` instead of `poolAvco` | Narrowest fix; removes the $1,668.29 stranded basis; requires per-wallet replay |

**Option C detail:**
- Bug: `RepayReplayHandler.applySell()` removes `qty × poolAvco` from basis pool; should remove `qty × liabilityAvcoUsd`
- `BorrowLiabilityTracker` and PnL zeroing are already correct in design; only the removal price is wrong
- **Usage pattern dependency:** if wallet uses hold-and-repay (buy→borrow→repay, no sell), Option C alone is sufficient. If wallet uses sell-and-repay (borrow→sell→repay), the conservation gate also needs adjustment — verify first.
- Requires full cost-basis replay scoped to wallets with BORROW events

**Required design artifact before any implementation (BA requirement):** LIFO borrow-stack spec — define the data structure and drain order for partial REPAYs (REPAY must consume borrowed units before purchased units).

**Requires:** User approval of policy choice (Option A, B, or C)

### Change 3 — Historical prices backfill for Sep–Oct 2025 (Priority: LOW)

AVAX/WAVAX pricing from Bybit for Sep–Oct 2025 is absent from `historical_prices`. The Sep 20, 2025 WAVAX reward at $33.62 cannot be independently verified. Low risk — price is within market range.

**Action:** Optionally backfill Bybit historical klines for AVAX and WAVAX for Sep 1 – Nov 1, 2025.

---

## Acceptance Criteria

### For Change 1 (Display fix)

- [ ] AVAX displayed AVCO = `$14.23` ± $0.50 (weighted across all wallet pools)
- [ ] MNT displayed AVCO ≈ `$0.990` (combined across 6 wallet pools), not $1.267
- [ ] Zero-qty wallet pools excluded from denominator (no division-by-zero error)
- [ ] Two wallets with same asset, different AVCO history → combined value verified manually
- [ ] Single-wallet assets: displayed AVCO unchanged from pre-fix
- [ ] Cross-wallet bridge assets: verify no double-count in combined qty/basis
- [ ] `financial-logic-auditor` sign-off: combined AVCO matches `sum(basis)/sum(qty)` reconstruction

### For Change 2 (BORROW fix, after policy approval)

- [ ] AC-2.1: A completed BORROW→REPAY round-trip (no sell in between) leaves AVCO unchanged from pre-borrow state
- [ ] AC-2.2: After re-replay — MNT AVCO reflects only genuine purchases and rewards
- [ ] AC-2.3: Borrowed MNT balance tracked in `borrow_liabilities` with correct market value
- [ ] AC-2.4: Partial REPAY draws from borrowed units before purchased units (LIFO borrow stack)
- [ ] AC-2.5: `conservationBreached` warnings absent after replay
- [ ] AC-2.6: `financial-logic-auditor` sign-off on MNT ledger
- [ ] AC-2.7: (If option C) `$1,668.29` stranded basis resolved; MNT combined AVCO converges to expected value

---

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Display change picks wrong AVCO source for assets with no `fullSessionCurrentView` data | Medium | Fallback to `currentStateView` if `fullSessionCurrentView` absent |
| Bridge double-count in combined AVCO (BRIDGE_OUT wallet1 + BRIDGE_IN wallet2 counted twice) | Medium | Verify net-zero for known internal bridge pairs before releasing |
| BORROW reclassification requires full replay | Medium | Scope to affected wallets; test on dev data first |
| Option C changes realized PnL for REPAY events | Medium | Need to verify PnL impact is acceptable; verify wallet usage pattern (hold-and-repay vs sell-and-repay) first |
| Negative-qty wallets (leveraged borrow outstanding) distort combined AVCO | Low | Exclude from denominator; document as unsupported scope |

---

## Phase Gate

**Change 1 (display fix):** CONDITIONAL_APPROVE from all three reviewers. Can proceed after addressing zero-qty guard and bridge double-count verification.

**Change 2 (BORROW fix):** Blocked on user decision:
1. Which option? (A — REALLOCATE_IN(0), B — exclude entirely, C — fix RepayReplayHandler)
2. Wallet usage pattern: does the Bybit wallet use hold-and-repay or sell-and-repay? (determines whether conservation gate also needs fixing)
3. LIFO borrow-stack design artifact required before implementation

**Change 3:** Optional, low priority — proceed at discretion.
