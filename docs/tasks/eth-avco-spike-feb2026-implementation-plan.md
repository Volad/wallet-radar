# ETH AVCO Spike (Feb 6 window) — Implementation Plan

**Slug:** `eth-avco-spike-feb2026`  
**Audit source:** `results/eth-avco-spike-feb2026-blockers.md`  
**Blocker ID:** E-AVCO-SPIKE-01  
**Review status:** PENDING  
**Created:** 2026-06-08

---

## Scope

- **Session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- **Wallet:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
- **Regression window:** `0x0a757a…` (Pancake LP exit) → `0x9e6825…` (Aave borrow 1000 USDT)
- **Components:** `TimelineAvcoAuthority`, `AccountingAssetFamilySupport` (rollup policy), `AssetLedgerQueryService`, `asset-ledger-page.component.ts`
- **Out of scope:** Lending page APR/HF; LiFi supplemental pairing (separate plan, done)

---

## Root Cause

**Stage:** `avco` read model + chart `verification`

Custody moves (Aave `LENDING_DEPOSIT`, GMX dust) and admin borrows produce timeline rows where `TimelineAvcoAuthority` either:

1. selects a receipt/accrual ledger point with distorted `avcoAfterUsd`, or  
2. divides aggregated family cost basis by near-zero **spot** covered quantity.

Frontend Y-scale uses those outliers → normal ~$580 AVCO renders at the bottom; panning exposes genesis AVCO (“скачет к начальной транзакции”).

---

## Ordered Changes

### Task 1 — Custody REALLOCATE_OUT carry-forward (backend)

**File:** `TimelineAvcoAuthority.java`

Extend LP-lock pattern to **protocol custody deposit**:

- When grouped event contains spot `ETH/WETH` `REALLOCATE_OUT` with `LENDING_DEPOSIT` / `STAKING_DEPOSIT` / `VAULT_DEPOSIT` and family covered qty drops materially, return `KIND_CARRIED_FORWARD` from last spot-native series AVCO.
- Do **not** use `aggregatedTotalCostBasisUsdAfter / aggregatedCoveredQuantityAfter` when covered qty < 1% of pre-event family qty (reuse `LOW_COVERAGE_RATIO`).

**Test:** fixture mirroring `0x3099ace…` (0.798 ETH → aArbWETH) — timeline AVCO stays ~prior spot, not 100k+.

### Task 2 — Exclude receipt tokens from spot PRIMARY selection (backend)

**Files:** `TimelineAvcoAuthority.java`, optionally `AccountingAssetFamilySupport.java`

- For `FAMILY:ETH`, `selectAuthoritativePoint()` must not choose **aToken / receipt** symbols (`aArbWETH`, `aBasWETH`, …) as PRIMARY when a spot-native `REALLOCATE_OUT` exists in the same event.
- Optional stricter policy: add aTokens to spot timeline **exclusion** list for quantity rollup (display custody on separate sub-line later) — document in `docs/pipeline/cost-basis/`.

### Task 3 — Suppress AVCO on fee-only admin events (backend)

**File:** `AssetLedgerQueryService.java` or `TimelineAvcoAuthority.java`

- For `BORROW` / `REPAY` / `APPROVE` grouped events where FAMILY:ETH `|quantityDelta| < dust threshold` and no `REALLOCATE_*` / `CARRY_*`, force `KIND_UNAVAILABLE` (do not emit PRIMARY from FEE leg).

**Test:** `0x9e6825…` row — AVCO carried or unavailable, not spike.

### Task 4 — Cap carried/computed AVCO (backend safety net)

**File:** `TimelineAvcoAuthority.java`

- Any computed AVCO > `medianSpotAvco × 10` → treat as outlier; fall back to carry-forward or `UNAVAILABLE`.
- Apply in `resolveCarriedForwardAvco()` before return.

### Task 5 — Chart Y-scale fix (frontend)

**File:** `asset-ledger-page.component.ts`

- `buildYProjection()` for AVCO line: use **only** `avcoAfterUsd` (after `reconcileMarkerAvcoSeries`), not swap `unitPriceUsd`.
- Clamp display max to e.g. 95th percentile of window AVCO × 1.2 so single outlier cannot flatten the series.
- Optional: break line on `avcoKind === UNAVAILABLE` instead of drawing to chart bottom.

---

## Acceptance Criteria

1. API timeline FAMILY:ETH between `0x0a757a…` and `0x9e6825…`: no `avcoAfterUsd` > $10k unless replay ledger point proves real ACQUIRE at that price.
2. Aave deposit rows (`0x3099ace…`, `0xa6a38d…`): `avcoKind` = `CARRIED_FORWARD` or PRIMARY ≈ prior spot AVCO (~$2–3k band), not 100k+.
3. Borrow rows (`0xe392b7…`, `0x9e6825…`): no new AVCO spike; stablecoin legs invisible on ETH AVCO series.
4. Chart: current header AVCO (~$580) visible in-window (not pinned to bottom); no vertical cliffs from outlier → null.
5. Panning within Feb 6 window does not connect to genesis AVCO unless genesis is in selected range.
6. Unit tests: `TimelineAvcoAuthorityTest`, `AssetLedgerQueryServiceTest` + one frontend projection test if present.

---

## Risks

| Risk | Mitigation |
|------|------------|
| Over-aggressive carry-forward hides real ACQUIRE | Only trigger on REALLOCATE_OUT + custody types; PRIMARY still wins for genuine spot BUY |
| Excluding aTokens from rollup breaks quantity line | Keep quantity aggregation; only fix AVCO authority selection first |
| Frontend clamp hides legitimate high AVCO | Clamp display only; table keeps raw API values |

---

## Verification

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
# wait COST_BASIS replay
curl "http://127.0.0.1:18086/api/v1/sessions/df5e69cc-a0c0-4910-8b7d-74488fa266e2/asset-ledger?familyIdentity=FAMILY%3AETH"
```

Manual: select chart range LP exit → borrow; confirm AVCO band ~$500–3000 without needles.
