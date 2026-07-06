# Lending per-asset USD P&L — Implementation Plan

> Slug: `lending-per-asset-usd-pnl`
> Track A of the lending page redesign. Backend-only read-model correctness fix that unlocks USD-per-asset P&L in the UI.
> Status: **revised after 3-role review (auditor / BA / architect) — all REQUEST_CHANGES, specification-level. Awaiting approval (stop-gate) before backend code.**

## Scope

- **Surface:** lending cycle per-asset P&L (read model `GET /api/v1/sessions/{id}/lending`).
- **Wallets/networks/assets:** all; anchor on Aave V3 ZKSYNC WETH/USDC, wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` (deposit `0xcfe0fd4d…`, borrow `0x69aa8504…`, repay `0xf54b10cc…`, withdraw `0xb2060084…`, reward `0x7e5aac2a…`); cross-check Aave V3 ARBITRUM ETH/USDC.
- **Out of scope:** classification, clarification, linking, pricing, move_basis, cost_basis, avco, replay. Additive read-model only. **No re-pricing of in-kind yield** (see C-1 method decision).

## Root cause (corrected per architect/auditor)

Earliest failed stage: `verification` / read-model. Evidence state `EVIDENCE_PRESENT_UNUSABLE`.

In `backend/src/main/java/com/walletradar/lending/application/SessionLendingQueryService.java`:

- The frontend renders the per-asset map **correctly as token quantity** with an asset suffix (`assetPnlLines()` reads top-level `Cycle.assetDenominatedPnlByAsset`; `formatAssetPnl` → `"+0.13 ETH"`). It does **not** mislabel quantity as USD.
- The real gap: **there is no USD-per-asset map at all.** `DeltaAccumulator` keeps USD only as **scalars** (`borrowedUsd`, `repaidUsd`, `rewardUsd`, `principalInUsd`, `principalOutUsd`, `principalOutCashUsd`, `feesUsd`); per-event `event.valueUsd()` / `event.feeUsd()` are available at accumulation time but never aggregated per asset. Track B's USD chips have nothing to bind to.
- C-2 (secondary): `precisionByAsset()` labels CLOSED supply yield `EXACT` even when derived from an unreconciled `withdrawYield` flow.

## C-1 method decision (resolves auditor vs architect divergence)

Two candidate definitions were raised. We adopt the **per-asset cashflow decomposition** (auditor), not a yield-only USD formula (original draft) and not implied-unit-price yield isolation (architect alternative), because:

- The cycle header "Net P&L" the chips must reconcile to is `totalValuation.totalUsdPnl` (CLOSED) / `unrealizedTotalUsdPnl` (OPEN) — a **full-cashflow** number, not the yield-only `pnlBreakdown` number.
- Isolating non-stable supply income in USD (e.g. ETH yield) would require pricing an in-kind yield *quantity*, which violates "read-model only" scope.

**Per-asset USD (CLOSED), summing exactly to `totalValuation.totalUsdPnl`:**

```
netUsdByAsset[a] = (principalOutUsd[a] − principalInUsd[a])   // supply leg P&L
                 + (borrowedUsd[a]   − repaidUsd[a])          // borrow leg: negative = net interest cost
                 + rewardUsd[a]                               // rewards
                 − gasUsd[a]                                  // per-asset gas
```

- **OPEN cycles:** decompose `unrealizedTotalUsdPnl` per asset = (current position USD value of asset − net invested USD in asset) + realized borrow/reward/gas legs; precision `ESTIMATED`. Mirror whatever components `totalValuation` uses for the open path so the cross-foot holds.
- Each term reuses values already priced upstream (`event.valueUsd()` in the matching `switch` arm). No new pricing, RPC, or replay coupling.

## Changes (ordered)

### C-1 (P0) — genuine per-asset USD via cashflow decomposition

1. **`DeltaAccumulator`** (≈line 3123): add per-asset USD maps populated **inline next to the existing scalar lines, in the same `switch` arms, same `LinkedHashMap` + `merge(.., MC)` pattern** (deterministic by construction):
   - `principalInUsdByAsset` (DEPOSIT/VAULT_DEPOSIT/LOOP_OPEN), `principalOutUsdByAsset` (WITHDRAW/CASH_EXIT/LOOP_CLOSE)
   - `borrowedUsdByAsset` (BORROW), `repaidUsdByAsset` (REPAY)
   - `rewardUsdByAsset` (REWARD_CLAIM)
   - `gasUsdByAsset`: attribute `event.feeUsd()` to the **native fee-asset key from `feeQuantityByAsset`**, not the `"USD"` sentinel used by the quantity `feesByAsset` map.
   - Per-asset USD priceability flag: if any contributing valuable event for an asset has null/zero `valueUsd`/`feeUsd`, mark that asset's USD precision degraded (mirror the existing `hasMissingGasUsdValuation` pattern).
   - Add accessors mirroring the quantity ones.

2. **`SessionLendingQueryService`**: compute `netIncomeUsdByAsset` (CLOSED) / open-path equivalent from the new accumulators using the decomposition above; map into the DTO in `toCycle()`. Keep the existing quantity computation untouched.

3. **DTO `SessionLendingResponse.PnlAssetBreakdown`** (`backend/.../api/dto/SessionLendingResponse.java`): **additive** new field `netIncomeUsdByAsset` (and optionally the four components `supplyPnlUsdByAsset`/`borrowPnlUsdByAsset`/`rewardsUsdByAsset`/`gasUsdByAsset` if Track B needs them). **Keep `netIncomeByAsset` (quantity) and `Cycle.assetDenominatedPnlByAsset` unchanged. No rename. No ADR** (additive optional field; both are already paired with the self-documenting `assetDenominatedPnlByAsset`).

4. **USD precision** inherits `totalValuation` gating: when the cycle total is `UNAVAILABLE` / unreconciled, per-asset USD is `UNAVAILABLE`; any leg derived via cross-time subtraction or with missing `valueUsd` is at best `ESTIMATED`, **never `EXACT`, never emitted as `$0`**.

### C-2 (P2) — precision honesty

5. **`precisionByAsset()`** (≈line 2317): CLOSED supply yield derived from unreconciled `withdrawYield` → `ESTIMATED`, not `EXACT`.

### Frontend mapping (enables Track B USD chips)

6. `frontend/src/app/core/models/wallet-api.models.ts`: extend `SessionLendingPnlAssetBreakdownResponse` with the new USD maps (nullable).
7. `frontend/src/app/core/models/lending.models.ts`: extend `LendingPnlAssetBreakdown` with `netIncomeUsdByAsset` (+ components).
8. `frontend/src/app/core/services/lending-data.service.ts`: map the new USD maps with `?? {}` fallback.
9. **UI contract:** an asset whose USD precision is `UNAVAILABLE` shows no USD chip (consistent with existing `UNAVAILABLE → hidden`). Track B per-asset USD chips wire only after this lands.

## User-facing semantics (BA — highest-priority gap)

- Per-asset USD is **net P&L attributable to that asset**, not principal value.
- **Borrow leg** USD = `borrowed − repaid` → normally **negative = net interest cost paid in that asset**, NOT lost principal. Document this in the lending family doc and `docs/reference/api.md`, and reflect in any UI tooltip.
- **Supply leg** USD = `principalOut − principalIn` = realized P&L on the supplied asset.
- Reported in two denominations: asset **quantity** (existing) + **USD** (new); both shown for two-leg cycles.

## Edge cases (BA)

- **Supply-only / no-debt:** borrow/repay legs empty → only supply + reward − gas; cross-foot still holds.
- **AMBIGUOUS_NEEDS_REVIEW** / `hasUnresolvedPrincipalExitByAsset` / missing-receipt: per-asset USD `UNAVAILABLE` (inherit existing `assetPnlUnavailable` gate); no `$0`.
- **OPEN vs CLOSED:** OPEN reconciles to `unrealizedTotalUsdPnl` (ESTIMATED); CLOSED to `totalUsdPnl` (EXACT only where every leg is directly priced).
- **$1-pegged legs (USDC):** USD ≈ quantity; non-$1 legs (ETH/ZK) now correct (no 30–325× error).

## Acceptance (testable)

- **Cross-foot (authoritative):** `Σ_assets netIncomeUsdByAsset == cycle published total` — `totalValuation.totalUsdPnl` (CLOSED) / `unrealizedTotalUsdPnl` (OPEN) — within tolerance **|Δ| ≤ $0.01 or 0.1%**, whichever larger. This is the primary correctness gate.
- **Anchor reconstruction:** for the ZKSYNC WETH/USDC cycle, per-asset USD matches the `financial-logic-auditor` authoritative reconstruction; exact per-asset figures and the cycle total are re-derived from raw evidence during implementation (the earlier draft numbers −$0.41 etc. are NOT assumed correct — auditor flagged they matched no published total).
- **Quantity map unchanged** (`assetDenominatedPnlByAsset` byte-for-byte identical).
- **Precision honest:** missing `valueUsd`/`feeUsd` → `ESTIMATED`/`UNAVAILABLE`, never `$0`; C-2 unreconciled CLOSED yield → `ESTIMATED`.
- **Determinism:** repeated runs produce identical ordered maps.
- Re-run `financial-logic-auditor`; sign-off recorded in `results/`.

## Docs

- Lending family doc + `docs/reference/api.md`: document the dual-denomination contract, the cashflow-decomposition derivation, the negative-borrow-leg semantic, and precision rules.
- `docs/frontend/lending-market.md`: USD-per-asset availability + precision + UNAVAILABLE→hidden.

## Verify

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```
Wait for normalization + cost-basis replay, then re-delegate `financial-logic-auditor` against the acceptance checks (cross-foot first).

## Risks

- **Cross-foot mismatch** if a `totalValuation` component is not represented per asset → reconcile arm-by-arm against the open/closed total composition before asserting.
- **Gas key mismatch** (native asset vs `"USD"` sentinel) → use `feeQuantityByAsset` keys.
- **Priceability gaps** → degrade to `ESTIMATED`/`UNAVAILABLE`, never `$0`.
- **Determinism** → mirror existing `LinkedHashMap`/`MC` pattern in the same switch arms.
