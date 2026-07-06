# Lending Page Audit — Implementation Plan (rev 2)

**Slug:** `lending-audit-2026-06-07`  
**Audit source:** `results/lending-audit-2026-06-07.md`  
**Blocker IDs:** L-FLUID-PLASMA-DUPLICATE-01, L-CYCLE-PHANTOM-01/02, L-APR-DOLLAR-RETURN-01, L-BORROW-APR-ZERO-01, L-INTERNAL-RECEIPT-APR-01, L-EULER-CROSS-ASSET-01, L-HEALTH-ESTIMATE-01, L-AMBIGUOUS-17-B  
**Review status:** APPROVED_WITH_NOTES (Financial Auditor ✓, Architect ✓, BA ✓ rev-addressed)  
**Revised:** 2026-06-07

---

## Scope

- **Session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- **Networks:** Arbitrum, Base, Avalanche, Mantle, Linea, ZkSync, Plasma, Unichain
- **Protocols:** Aave V3, Compound, Euler V2, Fluid, Morpho, Silo, Curve
- **Component:** `SessionLendingQueryService`, `LendingAssetSymbolSupport`, `LendingFactualApyCalculator`, new `LendingAaveV3HealthCollector`
- **No changes** to AVCO replay, pricing, or normalization pipeline

---

## Root Cause Summary

All blockers trace to `SessionLendingQueryService` and related lending compute components:

| ID | Stage | Root Cause |
|----|-------|-----------|
| L-FLUID-PLASMA-DUPLICATE-01 | `lending_cycle_logic` | `LENDING_LOOP_DECREASE` tx creates two orphan cycles with same `startTxHash` — no dedup on `(marketKey, startTxHash)` |
| L-CYCLE-PHANTOM-01/02 | `lending_cycle_logic` | LENDING_WITHDRAW burns more aTokens than deposited in current cycle (pre-cycle balance carry-over); income = `principalOut − principalIn` over-counts |
| L-APR-DOLLAR-RETURN-01 | `lending_cycle_logic` | `factualSupplyAprByAsset` measures USD return (captures ETH price moves), not protocol yield |
| L-BORROW-APR-ZERO-01 | `lending_cycle_logic` | Open borrow APR uses cash-flow (repay − borrow = 0) instead of debtToken balance delta |
| L-INTERNAL-RECEIPT-APR-01 | `lending_cycle_logic` | APR uses `principalOut` not `principalOutCash` when `internalReceiptMovement > 0` |
| L-EULER-CROSS-ASSET-01 | `linking` | Euler EVK vaults share `evk-account` key → cross-vault cycle contamination |
| L-HEALTH-ESTIMATE-01 | `display` | Health factor uses accounting estimate; live Aave pool fetch not triggered for active borrow groups |
| L-AMBIGUOUS-17-B | `classification` | Fee-only tx (FEE flow only, no TRANSFER) classified as `LENDING_WITHDRAW` on Euler Unichain |

---

## Protocol Coverage: BUY-flow yield evidence

Not all protocols emit BUY-role flows for interest accrual. This determines which cycles can compute ESTIMATED PnL vs. remain UNAVAILABLE:

| Protocol / Token type | BUY flow emitted? | Income source |
|---|---|---|
| Aave V3 aTokens (rebasing) | **Yes** — at LENDING_WITHDRAW (underlying amount) | WITHDRAW-time BUY in underlying denomination |
| Compound V3 Comet (cTokens) | **No** — share-rate based | UNAVAILABLE — no yield-flow evidence |
| Morpho vault shares (WSTUSR, WSTETH) | **No** | UNAVAILABLE — share-rate history missing |
| Euler EVK vault shares | **No** | UNAVAILABLE — share-rate history missing |
| Fluid vaults | Partial | Case-by-case |
| Silo soUSDC | **Yes** (USDC BUY at withdraw) | ESTIMATED |

**Policy:** when no BUY flows are present, income = `null` / `precision = UNAVAILABLE` / `reason = NO_YIELD_FLOW_EVIDENCE`. Setting income = 0 is forbidden — it falsely implies "no yield was earned."

---

## Ordered Changes (upstream first)

### Task 1 — Fix fee-only tx classification (stage: classification)
**File:** relevant Euler classifier or `FunctionNameClassifier`  
**Blocker:** L-AMBIGUOUS-17-B  
**Change:** Guard: if a transaction has only FEE flows and zero TRANSFER/BUY/SELL flows, do not classify as any lending type. Apply specifically to the Euler Unichain tx `0xe3f3c0eaff...` (only `FEE ETH -2.34e-11`, no asset transfer).  
**Risk:** LOW — narrow guard.

---

### Task 2 — Fix Euler EVK market key per vault (stage: linking/grouping)
**File:** `SessionLendingQueryService.marketKey()` AND `SessionLendingQueryService.positionMarketAsset()`  
**Blocker:** L-EULER-CROSS-ASSET-01  
**Change:** For `EULER` protocol transactions, derive market key from the matched vault address: `evk-vault-{address[2..10]}` (analogous to Fluid's `vault-{shortAddr}`). The `positionMarketAsset()` method used to attach on-chain balances to cycles **must change in lockstep** — otherwise open Euler positions will detach from their history.  
**ADR required:** ADR-025 — Euler EVK per-vault market key format contract.  
**Risk:** MEDIUM — existing CLOSED Euler cycles on Linea, Base must be verified post-change (see AC).

---

### Task 3 — Deduplicate orphan cycles by (marketKey, startTxHash) (stage: cycle logic)
**File:** `SessionLendingQueryService` — FluidCycleGrouper or shared orphan-emit path  
**Blocker:** L-FLUID-PLASMA-DUPLICATE-01  
**Change:** Before emitting an orphan cycle, check if the result list already contains a cycle with the same `startTxHash` and `marketKey`. If so, **discard the second occurrence** (do not merge — they represent the same event counted twice, not two distinct events to be combined).  
**Risk:** LOW — emit-time dedup only.

---

### Task 4 — Cap supplyIncomeByAsset at WITHDRAW-time BUY flows (stage: cycle logic)
**File:** `SessionLendingQueryService.DeltaAccumulator`  
**Blocker:** L-CYCLE-PHANTOM-01/02  
**Change:** Replace `principalOut − principalIn` income calculation with the sum of BUY-role flows from **`LENDING_WITHDRAW` transactions only** (not LENDING_DEPOSIT), in the **underlying asset denomination** (USDC, ETH — not aToken units). Per `aave.md`: the WITHDRAW-time BUY represents the exchange-rate accretion on the burned aTokens and is always in underlying units.

DEPOSIT-time BUY flows (e.g. `0.041 aAvaUSDC` accrued from prior cycle) must be **excluded** — they represent exchange-rate differential from pre-cycle balances, not yield earned within this cycle, and are dimensionally different (receipt-token units, not underlying).

**Precision rules:**
- BUY flows present → `precision = ESTIMATED`
- BUY flows absent → `income = null`, `precision = UNAVAILABLE`, `reason = NO_YIELD_FLOW_EVIDENCE`
- Do NOT set `income = 0` when BUY flows are absent (falsely implies zero yield)

**Risk:** MEDIUM — changes income computation for all protocols. Validate Silo (has BUY, must stay ~$1.43) and Compound G9C0 (no BUY, must remain UNAVAILABLE not zero).

---

### Task 5 — Fix factualSupplyAprByAsset: WITHDRAW-time BUY accrual formula (stage: cycle logic)
**File:** `LendingFactualApyCalculator` (existing class — do not inline into service)  
**Blocker:** L-APR-DOLLAR-RETURN-01  
**Change:** Route through `LendingFactualApyCalculator`. Factual supply APR formula:

```
withdrawYieldQty  = sum of BUY-role flows (underlying denomination)
                    from LENDING_WITHDRAW txs within cycle window
openingSupplyQty  = quantity from first LENDING_DEPOSIT event (asset-denominated)
durationYears     = (closeTimestamp - startTimestamp) / 365.25d

factualSupplyAPR  = withdrawYieldQty / openingSupplyQty / durationYears
```

**Output rules:**
- When `withdrawYieldQty > 0` and `durationYears > 0` → emit computed APR, `precision = ESTIMATED`
- When `withdrawYieldQty = 0` or BUY flows absent → emit `null` with `apyUnavailableReason = NO_YIELD_FLOW_EVIDENCE`
- When `durationYears ≤ 0` → emit `null` with existing `apyUnavailableReason = NON_POSITIVE_EXPOSURE_DURATION` (also nullify the current finite APR emission)

**Risk:** MEDIUM — significant APY formula change. ETH cycles drop from 1019% to ~1–3%.

---

### Task 6 — Fix factualBorrowAprByAsset for OPEN and CLOSED cycles (stage: cycle logic)
**File:** `LendingFactualApyCalculator`  
**Blocker:** L-BORROW-APR-ZERO-01  
**Change:**
- **OPEN:** `borrowAPR = (currentDebtBalance − borrowedQty) / borrowedQty / durationYears`. Use `positions[BORROW].quantity − borrowedByAsset[asset]` as the observable accrual.
- **CLOSED:** `borrowAPR = (repaidByAsset − borrowedByAsset) / borrowedByAsset / durationYears`

**Risk:** LOW — additive using existing position data.

---

### Task 7 — Fix factualSupplyAprByAsset when internalReceiptMovement > 0 (stage: cycle logic)
**File:** `LendingFactualApyCalculator`  
**Blocker:** L-INTERNAL-RECEIPT-APR-01  
**Change:** When `internalReceiptMovementByAsset[asset] > 0`, use `principalOutCash[asset]` (not `principalOut[asset]`) as the exit value for APR numerator. Consistent with `netCashDeltaByAsset`.  
**Risk:** LOW — narrow condition.

---

### Task 8 — Live health factor fetch for active borrow groups (display)
**File:** New `LendingAaveV3HealthCollector` service; new `lending_health_factor_snapshots` collection  
**Blocker:** L-HEALTH-ESTIMATE-01  
**Design** (per Architect review):
- New collection `lending_health_factor_snapshots` with `(sessionId, protocolKey, walletAddress, capturedAt, healthFactor, source)`
- New `LendingAaveV3HealthCollector` performs `eth_call` to Aave V3 pool `getUserAccountData(walletAddress)` and stores result
- Refresh: **background-scheduled** (every 5–15 min), NOT triggered inline from the query path
- `SessionLendingQueryService` reads the latest snapshot for display; falls back to `ACCOUNTING_ESTIMATE` if snapshot is stale or unavailable

**Network scope and coverage:**

| Network | Live HF fetch | Status |
|---------|--------------|--------|
| Base | `eth_call` → Aave V3 Pool | ✅ In scope (active borrow $600 USDC) |
| Mantle | `eth_call` → Aave V3 Pool | ✅ In scope (active borrow $2503 USDe) |
| Arbitrum | No active borrows | Deferred (no urgency) |
| Avalanche | No active borrows | Deferred |
| Linea | No active borrows | Deferred |
| zkSync | RPC uncertainty | ACCOUNTING_ESTIMATE acceptable |

**ADR required:** ADR-026 — Live HF storage pattern and background-refresh schedule.  
**Fallback AC:** When RPC unavailable (network timeout), display `ACCOUNTING_ESTIMATE` with staleness indicator; do not fail query.  
**Risk:** MEDIUM — new RPC calls; timeout + cache TTL critical.

---

## Pre-backfill AMBIGUOUS_NEEDS_REVIEW UX (deferred)

16 orphan cycles show `AMBIGUOUS_NEEDS_REVIEW` because their matching deposit predates the 2-year backfill window. The current UI hides only exit-only orphans; supply-only orphans are shown with a label that implies user error.

**Deferred to follow-up task:** Add a "outside data window" label/status variant (`PRE_SCOPE_ORPHAN`) in the UI for these cycles. Not in scope for this implementation plan.

---

## L-PNL-UNAVAILABLE-01: Expected resolution after this plan

| Cycle | Expected outcome |
|-------|-----------------|
| G5C0 `aave:avalanche` USDC cycles | → ESTIMATED (have WITHDRAW-time BUY) |
| G0C2 `aave:arbitrum` AWETH | → ESTIMATED (if WITHDRAW BUY present) |
| G7C0 `aave:linea` AWETH | → ESTIMATED (if WITHDRAW BUY present) |
| G8C0 `aave:zksync` AWETH | → ESTIMATED (if WITHDRAW BUY present) |
| G9C0 `compound:base` | Remains UNAVAILABLE — no BUY flows (Compound V3 share-rate) |
| G13C1/C2 `euler:avalanche` | Remains UNAVAILABLE — share-rate history missing |
| G19C1 `morpho` WSTUSR | Remains UNAVAILABLE — share-rate + pricing gap |
| G19C4 `morpho` WSTETH | Remains UNAVAILABLE — share-rate + pricing gap |

---

## Documentation Required

- ADR-025: Euler EVK per-vault market key format
- ADR-026: Live Aave V3 health factor — storage and background-refresh pattern
- `docs/pipeline/normalization/rules/protocols/aave.md` — document WITHDRAW-time BUY as authoritative yield source; note DEPOSIT-time BUY exclusion from income
- `docs/pipeline/normalization/rules/protocols/euler.md` — per-vault EVK market key derivation
- `docs/frontend/lending-market.md` — document APR formula, HF source, UNAVAILABLE vs ESTIMATED semantics

---

## Acceptance Criteria

### Task 1
- Euler Unichain tx `0xe3f3c0eaff...` (FEE-only) no longer creates a `LENDING_WITHDRAW` cycle
- `euler:unichain:evk-account:orphan-2` no longer appears

### Task 2
- All CLOSED Euler cycles on Linea (`euler:linea:evk-account:cycle-1/2`) and Base (`euler:base:evk-account:cycle-1`) remain CLOSED with PnL within ±1% of pre-change values
- No CLOSED Euler cycle has `netCashDeltaByAsset` containing flows from multiple vault addresses
- Open Euler positions (if any) attach correctly to their history via the new per-vault key

### Task 3
- `fluid:plasma:vault-b4f3bf2d` has ≤1 orphan cycle per tx; no two cycles share `startTxHash` within the same market
- Same for `vault-f2c8f544`

### Task 4 — Phantom elimination
- `aave:avalanche:account-pool:cycle-5` `realizedPnl ∈ [−$2, $10]` (not $1809; not 0 forced)
- `aave:avalanche:account-pool:cycle-4` GHO `realizedPnl ∈ [−$1, $5]`; EURC APR still ~2.5%
- `silo:arbitrum:sousdc:cycle-1` `realizedPnl ≈ $1.43` (regression: ESTIMATED precision retained)
- **Compound regression:** `compound:base:cycle-1` `realizedPnl.precision = UNAVAILABLE` (not `$0 / ESTIMATED`)

### Task 5 — APR formula
- `aave:base:account-pool:cycle-3` ETH `factualSupplyAprByAsset ∈ [0.5%, 5%]` (not 1019%)
- Closed ETH cycle `aave:base:account-pool:cycle-2` ETH APR: `null` with `NO_YIELD_FLOW_EVIDENCE` or plausible ∈ [−5%, 5%] (not −7180%)
- `compound:base:cycle-1` ETH `factualSupplyAprByAsset = null` with `apyUnavailableReason = NO_YIELD_FLOW_EVIDENCE`
- Short-duration cycles (`NON_POSITIVE_EXPOSURE_DURATION`): `factualSupplyAprByAsset = null` (not finite extreme value)

### Task 6
- `aave:base:account-pool:cycle-3` USDC `factualBorrowAprByAsset ∈ [1%, 10%]` (not 0%)

### Task 7
- `euler:avalanche:evk-account:cycle-2` USDC `factualSupplyAprByAsset < 0` (consistent with `netStrategyAprPct = −298%`)

### Task 8
- `G1 Aave/BASE` and `G2 Aave/MANTLE` health factor `source = LIVE_PROTOCOL` (not `ACCOUNTING_ESTIMATE`)
- When RPC unavailable: query succeeds, `source = ACCOUNTING_ESTIMATE`, `stale = true` in response

---

## Risks

- **Task 4 + 5 coupling:** income and APR formulas must be consistent (both WITHDRAW-BUY based). Implement together, test together.
- **Task 2 Euler key split:** `positionMarketAsset()` must change in lockstep with `marketAsset()`. Missing one causes open positions to detach.
- **Compound G9C0 / non-BUY protocols:** explicitly remains UNAVAILABLE after this plan. No effort to compute yield for share-rate protocols in this cycle.
- **Task 8 RPC calls:** background-only, never inline. Timeout = 3s; cache TTL = 5 min. zkSync deferred.
- **No AVCO impact:** all changes confined to `SessionLendingQueryService` read path and new collector. Replay, AVCO, cost basis unaffected.
