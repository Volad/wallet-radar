# Design — GMX GM / Pendle PT held-exposure fold (F2) + dashboard rate-adjusted average cost (F3)

- **Status:** Proposed (architecture only — no implementation)
- **Date:** 2026-07-23
- **Theme:** Read-model / break-even (effective cost) / dashboard + move-basis parity
- **Relates to:** ADR-062 (break-even effective-cost metric; Wave 3 AC-7/AC-9/§5), ADR-054 (per-asset AVCO for staked derivatives), ADR-059 (config-plane loader→service pattern), ADR-080/081 (identity-driven LP-receipt recognition), ADR-061 (blended-exposure series), GMX GM/GLV snapshot valuation
- **Scope items:** **F2** — fold GMX GM ETH/USD + Pendle PT-ETH ETH-share into `FAMILY:ETH` break-even. **F3** — surface a rate-adjusted `averageCostUsd` on the dashboard token DTO.

---

## Summary / recommendation up front

- **F3 is P1 (ship first, ~½ day).** The value is *already computed* by `BreakEvenCalculator.averageCostUsd()` and silently discarded in the dashboard mapper. Surfacing it centralizes the metric on the one compute authority (ADR-062 §5) and makes the dashboard "avg cost" column track the same semantics as the move-basis "Average cost" headline. It is a pure additive read-model/DTO change with zero replay, RPC, or schema impact.
- **F2 is P2 (design now, defer implementation).** The mechanism (`foldHeldExposure`) already exists; what is missing is (a) a stable accounting identity for LP-receipt/PT instruments and (b) a **snapshot-persisted, zero-RPC-at-read** live ETH-share composition source. The correct design spans classification + a registry plane + the background LP-refresh job + the read-model fold. Against a **~$411 / +0.11 ETH-equivalent** live materiality, the ROI is currently low. Ship the minimal robust design when GM/PT ETH exposure becomes material or as part of a general "LP volatile-leg exposure fold" effort.

The two items are **decoupled**: F3 does not depend on F2 and should not wait for it.

---

## A. Decisions & assumptions (rationale)

### A1. F3 — the dashboard already runs `BreakEvenCalculator`; only the output is dropped

`SessionDashboardQueryService.computeBreakEvenByFamily(...)` already builds `FamilyBreakEvenInput`s (market basis = `provableBasisUsd`, denominator = family covered quantity) and calls `breakEvenCalculator.compute(...)`. The resulting `BreakEvenResult` carries `averageCostUsd` (= `heldBasis_market ÷ covered-qty`), but `TokenPositionView.withBreakEven(...)` only threads `breakEvenUsd`, `lockedSurplusUsd`, `incomeReceivedUsd`, `attributionTargetFamily`. **Decision:** thread `result.averageCostUsd()` through the same seam and add it to the DTO. No new compute, query, or endpoint.

### A2. F3 — is `averageCostUsd` "meaningfully different" from `avcoUsd`?

- **Single-wallet, single-family, no fold:** identical by construction. Dashboard `avcoUsd = totalCostBasisUsd ÷ coveredQty`; the calculator numerator `provableBasisUsd = avcoUsd × coveredQty`, denominator `coveredQty` → `averageCostUsd == avcoUsd`. This is expected and fine.
- **Multi-wallet / multi-network family (already true today):** the dashboard renders one row **per (wallet, network, family)** with a *row-scoped* `avcoUsd`, but break-even is computed at the **family level** (aggregated across all wallets/networks). So `averageCostUsd` is the *family-weighted* average cost and legitimately differs from a single row's `avcoUsd` when a family (e.g. ETH held on Arbitrum + Ethereum at different costs) spans rows. This matches the move-basis header, which is family-scoped. **This is the honest "meaningfully different" case that exists even before F2.**
- **Once F2 folds land:** `FAMILY:ETH` `averageCostUsd` additionally absorbs the folded GM/PT ETH-share basis + ETH-equivalent quantity, so it diverges from any per-token `avcoUsd`. Surfacing the calculator value now means the column tracks that automatically later.

**Decision:** surface `averageCostUsd`; keep `avcoUsd`/`netAvcoUsd` in the DTO as demoted diagnostic lanes (mirrors ADR-062 §5 for the move-basis header). The frontend "avg cost" column reads `averageCostUsd` with `avcoUsd` behind a diagnostic/detail affordance.

### A3. F2 — identity must come from a config/classification plane, never a raw contract in break-even config

GM tokens (`GM:WETH-USD`) do not match any LP-receipt symbol convention (`-LP`, `-LPT`, `LP-RECEIPT:`) and are not in the C1/C2 registry, so `AccountingAssetFamilySupport.continuityIdentity(...)` falls through to the **raw contract** (`0x70d9…`). That raw value is not a legal break-even source key (the loader requires `CLUSTER:*` / `FAMILY:*`). **Decision:** bind the raw contract to a **stable synthetic accounting family** in a config plane (the protocol registry is the correct home for contract→classification bindings), stamp that stable identity at classification/replay, and reference the stable key in `break-even-attribution.json`. No raw magic value ever appears in the break-even plane.

### A4. F2 — the live ETH-share must be snapshot-persisted (GET stays zero-RPC)

The GMX ETH-share (`walletGmQty ÷ totalSupply × poolAmountLong(WETH)`) and Pendle PT underlying rate require RPC/HTTP. GET dashboard/move-basis endpoints must do **zero RPC** (snapshot-first). **Decision:** compute the composition in the **background LP-refresh job** (which already calls `GmxProtocolSnapshotValuationService`) and persist it on `lp_position_snapshots`; the read model consumes the snapshot. This reuses the existing GMX snapshot/DefiLlama-by-contract path — no new pricing source.

### A5. F2 — fold only the ETH *slice*, not the whole LP basis; the calculator API is unchanged

A staked derivative is ~100% ETH exposure, so folding its *whole* basis + rate-adjusted qty is exact. A GM **ETH/USD** LP is only ~50% ETH — folding the whole basis would pollute `FAMILY:ETH` with the USDC leg. **Decision:** the **caller** supplies a *composition-scaled* fold child input — `marketBasisUsd = ethShareFraction × GM covered basis`, `coveredQuantity = ETH-equivalent qty` — using the persisted composition. `BreakEvenCalculator` already accepts arbitrary per-input basis/quantity, so **no calculator change is required**; the slicing is a read-model responsibility.

### A6. F2 — fail policy: never silent; materiality-aware

Strict AC-7/AC-9 semantics null the *entire* target family metric when a fold child's ETH-equivalent quantity is missing. Nulling a large, correct `FAMILY:ETH` break-even because a ~$411 GM sleeve's composition snapshot is stale is a poor trade. **Decision:** two-tier, both explicit:
1. **Pure single-leg fold children (staked derivatives, PT with a known SY/underlying rate):** keep strict fail-closed (`coveredQuantity == null` → family metric null). A 100%-ETH child whose rate is unknown genuinely invalidates the family denominator.
2. **Multi-leg LP fold children (GMX GM, Pendle LP):** if the composition snapshot is unavailable/stale, **do not fold**; leave the sleeve **disclosed-excluded** and raise a `breakEvenFoldIncomplete` disclosure flag on the target family so the UI can annotate "ETH exposure from GMX GM excluded (composition unavailable)". This is fail-*visible*, never silent, and avoids destroying a correct headline over an immaterial, optional sleeve.

Assumptions: single GMX GM ETH/USD position and no Pendle PT-ETH held today; ETH exposure is the material volatile leg; USDC/stable legs are not folded (they belong to their own stable families and are immaterial to an ETH break-even).

---

## B. ASCII data-flow

```
                          ┌──────────────────────── BACKGROUND (writes; RPC/HTTP allowed) ─────────────────────────┐
                          │                                                                                        │
   GMX /markets/info      │   LpPositionRefreshService ──▶ GmxPositionReader / PendleLpReader                      │
   /tokens /tickers ──────┼──▶ GmxProtocolSnapshotValuationService (poolAmountLong, longToken, prices, totalSupply)│
   DefiLlama by-contract   │              │                                                                         │
   RPC totalSupply/balance │              ▼  compute ETH-share slice                                               │
                          │   LpPositionSnapshot { token0/token1, + exposureLegFamily=FAMILY:ETH,                  │
                          │                        exposureEthEquivQty=+0.11, exposureBasisUsd=$411,               │
                          │                        exposureCompositionAt, exposureStale }                          │
                          │              │ persist                                                                 │
                          └──────────────┼─────────────────────────────────────────────────────────────────────┘
                                         ▼
                             lp_position_snapshots (Mongo)          break-even-attribution.json (classpath)
                                         │                                     │  { source: FAMILY:GMLP:<net>:<mkt>,
                                         │                                     │    target: FAMILY:ETH,
        protocol-registry.json          │                                     │    foldHeldExposure: true }
        (contract → stable family +      │                                     │
         exposureFamily=FAMILY:ETH)      │                                     ▼
                 │  classification stamp  │                        BreakEvenAttributionService (loader→service)
                 ▼                        │                                     │
        AssetLedgerPoint.accountingFamilyIdentity = FAMILY:GMLP:<net>:<mkt>     │
                                         │                                     │
   ┌──────────────────────── READ (GET; ZERO RPC, snapshot-first) ────────────┼─────────────────────────────────┐
   │                                     ▼                                     ▼                                 │
   │  SessionDashboardQueryService / AssetLedgerQueryService                                                     │
   │     buildFamilyBreakEvenInputs()                                                                            │
   │        • self family (FAMILY:ETH): marketBasis, covered qty (C1 = ETH-eq 1:1)                               │
   │        • fold child (GM/PT): read lp_position_snapshot exposure* ──▶ sliced { basis, ethEquivQty }          │
   │              └─ if snapshot stale/absent: strict(single-leg)=null | disclosed-exclude(multi-leg)+flag       │
   │                                     │                                                                        │
   │                                     ▼                                                                        │
   │              BreakEvenCalculator.compute(...)  →  { breakEvenUsd, averageCostUsd, ... }   [SOLE AUTHORITY]   │
   │                                     │                                                                        │
   │            F3 ┌────────────────────┴───────────────────┐                                                    │
   │               ▼                                         ▼                                                    │
   │   TokenPositionEntry.averageCostUsd (NEW)      CurrentState.averageCostUsd (already surfaced on move-basis)  │
   └────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## C. Module breakdown & package boundaries

| Concern | Package / file | Change | Item |
|---|---|---|---|
| Break-even compute | `application.costbasis.breakeven.BreakEvenCalculator` | **None** (already returns `averageCostUsd`; accepts arbitrary fold inputs) | F2, F3 |
| Attribution config | `resources/break-even-attribution.json` + `BreakEvenAttributionLoader`/`Service` | Add fold entries keyed on the **stable** LP family key; loader already parses `foldHeldExposure` | F2 |
| Stable LP identity | `resources/protocol-registry.json` (contract→family + `exposureFamily`) + normalization/classification stamp | Bind GM/PT market-token contracts to a deterministic `FAMILY:GMLP:<net>:<mkt>` / `FAMILY:PENDLE-PT:<net>:<mkt>`; stamp `accountingFamilyIdentity` | F2 |
| Live composition (write) | `liquiditypools.application.LpPositionRefreshService`, `enrichment.GmxPositionReader`, `enrichment.PendleLpReader`, `pricing.application.GmxProtocolSnapshotValuationService` | Decompose GM into long/short legs; persist ETH-share slice fields | F2 |
| Composition persistence | `liquiditypools.persistence.LpPositionSnapshot` | Add `exposureLegFamily`, `exposureEthEquivQty`, `exposureBasisUsd`, `exposureCompositionAt`, `exposureStale` | F2 |
| Dashboard read wiring | `portfolio.application.SessionDashboardQueryService` (`TokenPositionView.withBreakEven`, `toView`) | Thread `averageCostUsd`; (F2) join `lp_position_snapshots` exposure slice into the fold-child input | F2, F3 |
| Move-basis read wiring | `costbasis.application.AssetLedgerQueryService` (`enrichWithBreakEven`) | (F2) add GM/PT fold-child inputs from snapshot; `averageCostUsd` already surfaced | F2 |
| Dashboard DTO | `api.dto.SessionDashboardResponse.TokenPositionEntry` + `api.portfolio.SessionPortfolioBffMapper` | Add `averageCostUsd` field + map it; (F2) optional `breakEvenFoldIncomplete` | F2, F3 |
| Frontend model | `frontend/.../wallet-api.models.ts` `TokenPosition` + dashboard column | Read `averageCostUsd`; demote `avcoUsd` to diagnostic | F3 |

**Boundary invariants preserved:** GET read paths import no `platform.networks` RPC adapters (RPC stays in the background refresh job); `BreakEvenCalculator` remains the sole metric authority; ADR-054 AVCO/replay untouched; no dataset-specific magic values (all mappings are registry/config entries keyed by contract, not tx hashes or wallets).

---

## D. Mongo collections & indexes

- **No new collection.** F3 touches no storage.
- **`lp_position_snapshots` (F2):** additive fields only (`exposureLegFamily`, `exposureEthEquivQty`, `exposureBasisUsd`, `exposureCompositionAt`, `exposureStale`). Existing indexes (`lp_snap_universe_status_idx {universeId,status}`, `lp_snap_network_status_idx {networkId,status}`) already serve the read-time lookup (scope by universe + open status, then match by wallet/contract in memory). No new index required.
- **`asset_ledger_points` (F2):** no schema change; the stable `accountingFamilyIdentity` is written through the existing field and rides the existing `asset_ledger_universe_family_order_idx`. Reclassification/replay rerun (not a migration) repopulates it.

---

## E. Data flows

### E1. F3 read flow (dashboard avg cost) — implement first
1. GET dashboard → `SessionDashboardQueryService.computeBreakEvenByFamily(...)` builds inputs and calls `BreakEvenCalculator.compute(...)` (already happening).
2. `TokenPositionView.withBreakEven(...)` additionally captures `result.averageCostUsd()`.
3. `SessionPortfolioBffMapper` maps `averageCostUsd` onto `TokenPositionEntry`.
4. Frontend column renders `averageCostUsd`; `avcoUsd` demoted to a diagnostic tooltip. Zero RPC, zero new query.

### E2. F2 background composition refresh (writes)
1. `LpPositionRefreshService` refreshes open GM/PT positions (existing cadence).
2. `GmxPositionReader` obtains market composition from `GmxProtocolSnapshotValuationService` (`longToken`, `poolAmountLong`, token prices, `totalSupply`) and wallet GM balance → `ethEquivQty = gmQty/totalSupply × poolAmountLong` (when `longToken` ∈ ETH family), `ethShareUsd`, `ethShareFraction`. `PendleLpReader` derives PT underlying via SY/underlying rate (DefiLlama-by-contract PT price ÷ ETH price as fallback).
3. Persist `exposure*` fields with `exposureCompositionAt`; mark `exposureStale` when the upstream source failed (never write a guessed value).

### E3. F2 read flow (fold into FAMILY:ETH)
1. Classification/replay has stamped GM/PT holdings with `accountingFamilyIdentity = FAMILY:GMLP:<net>:<mkt>` (from the registry plane).
2. `break-even-attribution.json` maps that key → `FAMILY:ETH` with `foldHeldExposure:true`.
3. At GET, the read model resolves the attribution; for the fold child it reads the persisted `lp_position_snapshots` exposure slice and supplies `{ marketBasisUsd = ethShareFraction × GM covered basis, coveredQuantity = exposureEthEquivQty }` as the fold child input.
4. `BreakEvenCalculator` folds the slice into `FAMILY:ETH` basis + ETH-equivalent denominator (mechanism unchanged).
5. **Fail policy (A6):** snapshot absent/`exposureStale` → single-leg child = strict null; multi-leg LP child = disclosed-exclude + `breakEvenFoldIncomplete=true`.

### E4. Reconciliation / rerun
- F3: none.
- F2: activated by a **reclassification + replay rerun** (stamps the stable family) — not a post-hoc sweep over historical rows. This satisfies the "fix the earliest wrong stage and rebuild downstream" principle: the identity gap is a classification-stage defect, corrected by registry config + rerun, not by a repair job over normalized rows.

---

## F. Scaling path

- **Now:** F3 shipped; F2 designed, deferred. One GMX GM position, zero Pendle PT.
- **Phase 2 (F2 minimal):** GMX-only fold via the composition slice; Pendle PT left as a supported-but-inactive branch (identity + attribution wired, reader stub fail-closed until a PT position exists).
- **Phase 3 (generalize):** promote the ad-hoc GM/PT identity into a first-class **"LP volatile-leg exposure" descriptor** (`exposureFamily` per volatile leg) so any multi-asset LP (BTC leg → `FAMILY:BTC`, etc.) folds via the same plane without per-instrument code. No microservice or new datastore is warranted at any phase.

---

## G. Cost analysis

| Item | Build cost | Runtime cost | Materiality / benefit |
|---|---|---|---|
| **F3** | ~½ day (thread one field + DTO + FE column) | Zero (no new compute/RPC/query) | Metric-authority consolidation; correct family-level avg cost across multi-wallet ETH today; auto-tracks F2 later |
| **F2** | ~3–5 days (registry plane + classification stamp + reclassify/replay rerun + background composition persistence + read-model fold + fail-policy + tests) | Background: a few extra derived fields per GM/PT refresh (composition already fetched for pricing — near-zero marginal). Read: one extra snapshot field read (already loaded). | **~$411 / +0.11 ETH-equivalent** today — low ROI now |

Cost-first verdict: **F3 yes now; F2 deferred** until GM/PT ETH exposure is material or a general LP-leg fold is prioritized. All sources reused (GMX snapshot, DefiLlama-by-contract); no paid indexer/SaaS.

---

## H. Risks & mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | F3 confuses users when `averageCostUsd == avcoUsd` for most rows | Expected; label as the headline "avg cost", demote `avcoUsd` to diagnostic (ADR-062 §5 parity). Divergence is correct family-level behavior. |
| R2 | F2 nulls the whole `FAMILY:ETH` metric over a stale $411 sleeve | Two-tier fail policy (A6): multi-leg LP → disclosed-exclude + `breakEvenFoldIncomplete`, never silent, never destroys a correct headline. |
| R3 | Folding the whole GM basis pollutes ETH with the USDC leg | Fold only the composition-scaled ETH slice (A5); caller supplies sliced basis + ETH-equiv qty; calculator API unchanged. |
| R4 | GET path pulls RPC for composition | Snapshot-first (A4): composition computed in background refresh, persisted, read-only at GET. |
| R5 | Raw-contract magic value leaks into break-even config | Stable synthetic family from the registry plane (A3); break-even config references only `FAMILY:*`/`CLUSTER:*`. |
| R6 | Stable-family stamping is a normalization/classification change needing rerun | Activated via reclassification + replay rerun (E4), aligned with the rebuild workflow; no post-hoc row patching. |
| R7 | Double-count between the fold slice and the demoted diagnostic AVCO / conservation gate | Fold affects only the break-even/`averageCostUsd` read-model lane; `avcoUsd`, `marketValueUsd`, and the conservation gate are untouched. Partition invariant (one target per family) preserved. |
| R8 | Pendle PT rate source unreliable | PT branch fail-closed (strict, single-leg) until a real SY/underlying or DefiLlama PT price is available; no 1:1 assumption. |

---

## Ordered, minimal changes

**F3 (P1):**
1. `BreakEvenResult.averageCostUsd()` → thread through `TokenPositionView.withBreakEven(...)` and `toView`.
2. Add `averageCostUsd` to `SessionDashboardResponse.TokenPositionEntry`; map in `SessionPortfolioBffMapper`.
3. Frontend `TokenPosition` + dashboard column reads `averageCostUsd`; demote `avcoUsd`.

**F2 (P2, when material):**
1. Config/identity: register GM/PT market-token contracts in `protocol-registry.json` with a deterministic stable family + `exposureFamily`; stamp `accountingFamilyIdentity` at classification.
2. `break-even-attribution.json`: add `foldHeldExposure:true` entries for the stable keys → `FAMILY:ETH`.
3. Background: persist ETH-share slice on `lp_position_snapshots` (GMX via existing snapshot service; Pendle via SY/underlying, fail-closed).
4. Read model: supply the composition-scaled fold-child input; implement the two-tier fail policy + `breakEvenFoldIncomplete`.
5. Reclassify + replay rerun to activate.
