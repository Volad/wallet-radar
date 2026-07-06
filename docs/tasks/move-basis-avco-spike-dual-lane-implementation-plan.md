# Move Basis AVCO Spike (Dual-Lane Regression) — Implementation Plan

**Slug:** `move-basis-avco-spike-dual-lane`  
**Audit source:** `results/blockers.md` (B-SPIKE-01…05), Phase 1 financial-logic-auditor  
**Created:** 2026-06-30  
**Review status:** REVISED — pending user approval

---

## Scope

| Field | Value |
|---|---|
| **Symptom** | AVCO periodically jumps very high on move-basis timeline chart |
| **Session** | `df5e69cc-a0c0-4910-8b7d-74488fa266e2` |
| **Families (anchors)** | `FAMILY:ETH` (CMETH Feb 6–7, 2026), Bybit ETH/MNT (Mar 12, 2026 collapse window), Jan 10 dust earn |
| **Blocker IDs** | B-SPIKE-01 (primary), B-SPIKE-02, B-SPIKE-03, B-SPIKE-04, B-SPIKE-05 |
| **Components** | `TimelineAvcoAuthority`, `AssetLedgerQueryService`, carry/collapse replay paths, `asset-ledger-page.component.ts` |

### Supported

- Session `df5e69cc-a0c0-4910-8b7d-74488fa266e2`; all families using timeline authority
- Event types: ACQUIRE, DISPOSE, CARRY_*, REALLOCATE_*, REWARD_CLAIM, LP_FEE_CLAIM, BORROW, REPAY, GAS_ONLY (carry)
- Move-basis chart (Net primary, Tax dashed), economics/all presets, 2-year backfill

### Unsupported (this plan)

- MNT phantom `:EARN` quantity (~2963 vs live ~104) — `bybit-earn-corridor-pairing-implementation-plan.md`
- Dashboard header AVCO aggregation changes
- LP_RECEIPT dedicated timeline page; NFT/CEX/rebase/tax exports

---

## Root Cause

**Earliest user-visible failure:** `verification` (read-model) — **B-SPIKE-01**

ADR-040 made **Net AVCO the primary chart series** while `TimelineAvcoAuthority` (ADR-017) still applies **only to Tax**:

| Lane | Timeline source today | Authority |
|---|---|---|
| Tax (`avcoAfterUsd`) | `TimelineAvcoAuthority.resolve()` | Outlier rejection, GAS_ONLY/custody carry-forward |
| Net (`netAvcoAfterUsd`) | `AggregatedState.netAvco()` raw family rollup | **None** |

The cyan line plots unfiltered `netTotalCostBasisUsd / coveredQuantity`. Upstream replay defects inflate rollup numerators; the read-model gap amplifies them into spikes.

**Upstream amplifiers (ledger truth, separate closure tier):**

- **B-SPIKE-02** — CMETH Bybit Earn `CARRY_IN` ~$959k/pt (0.0005 qty) → family rollup +88% Feb 6–7, 2026
- **B-SPIKE-03** — `bybit-collapsed-v1` same-second FUND vs umbrella ordering (Mar 12, 2026)
- **B-SPIKE-04** — Frontend Y-scale includes `unitPriceUsd` without clamp
- **B-SPIKE-05** — Dust earn window net-only micro-spike (Jan 10, 2026)

**Non-goal clarification:** Session spot ETH headline economics remain sound. Spikes are timeline/chart artifacts **and** per-point ledger defects on CMETH/Bybit sub-ledgers until Tier B ships.

---

## Dual closure model

| Tier | Tasks | Closes | User-visible |
|---|---|---|---|
| **A — Chart authority** | 1, 2, 5 + Task 6 checklist | B-SPIKE-01, 04, 05; Tax regression | Move-basis chart stable |
| **B — Ledger truth** | 3, 4 | B-SPIKE-02, 03 per-point + aggregate numerators | Raw ledger rows, delta columns, header rollup |

Tier A does **not** repair `asset_ledger_points` CMETH ~$959k/pt or Mar 12 per-wallet replay state.

---

## Ordered Changes

### Task 1 — Extend `TimelineAvcoAuthority` for dual lane (backend, **Tier A**)

**Files:** `TimelineAvcoAuthority.java`, `AssetLedgerQueryService.java`

**Contract (single selection pass, dual emit):**

```java
record Resolution(
    BigDecimal avcoAfterUsd,
    BigDecimal netAvcoAfterUsd,
    String avcoKind,              // shared — event-level, not lane-level
    String accountingAssetIdentity
)
```

1. **One lane-agnostic selection** per event (`selectAuthoritativePoint`, scoring, GAS_ONLY, custody carry). Refactor `scorePoint()` so Net-valid points (e.g. SUSHI net ≈ $0) are not demoted because tax AVCO is null/zero.
2. **Per-lane outlier check** on the selected point: `>10× medianSpotAvco` (tax) and `>10× medianSpotNetAvco` (net). If a lane fails, carry-forward **that lane only** from `lastAvcoByAssetIdentity` / `lastNetAvcoByAssetIdentity`.
3. **No cross-lane bleed:** carry-forward reads lane-native series maps only. Net fallback uses `aggregatedNetTotalCostBasisUsdAfter / coveredQty` when applicable — **never** tax aggregate for net carry.
4. **`avcoKind` shared:** if tax carries and net is outlier, both carry (or both UNAVAILABLE) — not mixed kinds.
5. Timeline row: `netAvcoAfterUsd` from `Resolution.netAvcoAfterUsd()`, **not** `state.netAvco()`. Keep `state.netTotalCostBasisUsd` for delta columns only.
6. Extend `updateSeries()` / `avcoBeforeForSeries()` with parallel net series map.

**Task 6 checklist (both lanes, merge from `eth-avco-spike-feb2026`):**

- [ ] Custody `REALLOCATE_OUT` carry-forward (Aave/vault deposit)
- [ ] Exclude receipt tokens from PRIMARY when spot-native flow exists in same event
- [ ] Fee-only admin (`BORROW`/`REPAY`/`APPROVE` dust, no REALLOCATE/CARRY) → `KIND_UNAVAILABLE` both lanes
- [ ] Median cap in `resolveCarriedForwardAvco()` before aggregate division — **both lanes**

**Tests:** `TimelineAvcoAuthorityTest` — CMETH outlier, SUSHI net $0 eligible, GAS_ONLY dual carry, Jan 10 dust. `AssetLedgerQueryServiceTest` — Feb 6–7, 2026 ETH window.

### Task 2 — Merged into Task 1

Dual median helpers: `medianSpotAvco(family, points, Lane.TAX|NET)`. Do not ship as separate deliverable.

### Task 3 — CMETH Earn carry rate (backend, **Tier B**)

**Files:** `TransferReplayHandler` corridor carry restore; reference ADR-029

- Fix CMETH `CARRY_IN` basis: inherit outbound ETH-family AVCO on corridor receipt, not FMV-per-receipt-token (~$478 on 0.0005 → $959k/pt).
- Distinct from MNT corridor **pairing** plan — this is **carry normalization** on existing paired corridor.
- Acceptance: CMETH ledger `avcoAfterUsd` and `netAvcoAfterUsd` in ~$3k–4k band.

### Task 4 — Bybit collapse outbound-first ordering (backend, **Tier B**)

**Files:** `TransferReplayHandler`, collapse replay ordering for `bybit-collapsed-v1`

- Process FUND `:CARRY_OUT` before umbrella `:CARRY_IN` on same-second groups.
- Acceptance measured on **replay aggregate simulation** and per-wallet ledger points (not authority output alone).

### Task 5 — Chart Y-scale clamp (frontend, **Tier A**)

**File:** `asset-ledger-page.component.ts`

- AVCO Y-scale: **only** authority-resolved `avcoAfterUsd` + `netAvcoAfterUsd` (exclude `unitPriceUsd`).
- Clamp display max to 95th percentile × 1.2 (display-only; tooltips/table keep API values).
- Optional: break line on `avcoKind === UNAVAILABLE`.

---

## Documentation

| Doc | Change |
|---|---|
| `docs/adr/ADR-040-dual-cost-basis-net-tax-avco.md` | Timeline Net uses same ADR-017 authority as Tax; family rollup not chart source for either lane |
| `docs/adr/ADR-017-timeline-avco-authority.md` | Authority emits both chart AVCO fields |
| `docs/pipeline/cost-basis/02-avco-rules.md` | Dual-lane timeline read-model |
| `docs/frontend/move-basis.md` | Y-scale clamp; intentional Tax/Net divergence on rewards |

---

## Acceptance Criteria

### Tier A — Chart authority (Tasks 1 + 5; approve to code)

- [ ] **A1.** `FAMILY:ETH` chart Feb 1–15, **2026**: primary Net line in ~$2.5k–$4.5k band; no needles >$10k except genuine large purchase (not outlier-flagged).
- [ ] **A2.** Net timeline field never sourced from `state.netAvco()` rollup.
- [ ] **A3.** Tax dashed line unchanged vs pre-ADR-040 (regression).
- [ ] **A4.** SUSHI: Net AVCO ≈ $0; Tax AVCO > $0 when FMV reward history exists (lanes did not collapse).
- [ ] **A5.** BORROW/REPAY: Tax and Net AVCO aligned on same row (within tolerance).
- [ ] **A6.** REWARD_CLAIM / LP_FEE_CLAIM: intentional Tax > Net gap on chart — not a defect.
- [ ] **A7.** GAS_ONLY: both lanes CARRIED_FORWARD with lane-native values.
- [ ] **A8.** B-SPIKE-05 Jan 10 dust: no net-only micro-spike; CARRIED_FORWARD or UNAVAILABLE.
- [ ] **A9.** Y-scale excludes `unitPriceUsd`; clamp display-only.
- [ ] **A10.** `./gradlew :backend:test` (TimelineAvcoAuthority, AssetLedgerQueryService) + `npm run build` green.
- [ ] **A11.** Auditor sign-off: B-SPIKE-01, 04, 05 closed.

**Verify Tier A:** `./scripts/prod-reset-rebuild-backend.sh` (full stack if Task 5 included) or `--skip-frontend` for backend-only chart-field check + manual UI on move-basis.

### Tier B — Ledger truth (Tasks 3 + 4)

- [ ] **B1.** CMETH per-point `avcoAfterUsd` and `netAvcoAfterUsd` < $15k (10× ETH median guard).
- [ ] **B2.** Mar 12, 2026 Bybit window: no >50% single-event **replay aggregate** AVCO jump without matching trade notional (both lanes).
- [ ] **B3.** Auditor sign-off: B-SPIKE-02, 03 closed.

**Verify Tier B:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` → financial-logic-auditor re-run.

---

## Risks

| Risk | Mitigation |
|---|---|
| Over-smoothing genuine high-cost buys | PRIMARY selection on basis-moving flows; 10× guard targets artifacts |
| Dual independent resolve passes | **Forbidden** — single selection pass (architect review) |
| Frontend clamp hides real spikes | Display-only; tooltips retain authority values |
| Tier A without Tier B | Document polluted numerators in delta columns until B ships |

---

## Implementation order

1. **User approval** (this plan) — stop gate
2. **Tier A:** Task 1 (+ merged Task 2 / Task 6 checklist) + Task 5
3. **Tier B:** Tasks 3 + 4 (may follow in same PR if small)
4. **Verify** per tier acceptance above

**Phase 3 review:** financial-logic-auditor, business-analyst, system-architect — all **REVISE → incorporated** in this revision.
