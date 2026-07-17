# ADR-061 — Blended total-exposure move-basis AVCO series (RC-E3)

**Status:** Accepted (amended 2026-07-17 — B-ETH-05 cross-asset LP-exit closure; B-ETH-06 generalized cross-family closure)
**Date:** 2026-07-17
**Amends:** ADR-045 (adds a second, opt-in AVCO series; the ADR-045 Method-B spot-family
line is retained byte-identical as the "Liquid-pool AVCO" line).
**Related:** ADR-017 (timeline AVCO authority — retired from chart path), ADR-031 (AVCO
undefined representation), ADR-040 (dual Net/Market AVCO), ADR-054 (C1/C2 per-asset AVCO),
ADR-018 (LP protocol-family materialization), ADR-043 (corridor carry conservation).
**Implements:** RC-E3 ("LP-exit/native basis reattribution"), deferred in ADR-045 Consequences.
**Plan:** `docs/tasks/eth-avco-move-basis-representation-and-lending-loop-carry-implementation-plan.md` §8.0, §8.3.

## Context

ADR-045 established the primary move-basis line as the Method-B **spot-family**
covered-weighted per-bucket AVCO. Its terminal reconciles to the Net AVCO card
($2,677.30 Tax / $2,676.99 Net), but it exhibits large, economically-real intermediate
swings (peak ~+101 %, up to ~$5k) because ETH-origin basis parked in
`FAMILY:LP_RECEIPT` / lending / GLV / Euler eWETH receipts is excluded from the
`FAMILY:ETH` view (inherited ADR-017 exclusion). When the liquid ETH pool drains to ≈0 and a
fresh spot buy lands, the covered-weighted average correctly snaps to spot. ADR-045
Consequences explicitly deferred a "genuinely smooth total ETH cost-basis line" to RC-E3.

This ADR adds that line **without** changing the spot-family line, the replay engine, cost
basis, or pricing. It is a read-model-only addition.

## Decision

Introduce a **second, additive move-basis AVCO series** — the **blended total-exposure AVCO**
— computed at read time in a dedicated `BlendedExposureAvcoSeriesBuilder`, alongside the
retained ADR-045 spot-family series.

### Attribution rule (pinned)

The blended series re-includes ETH-origin basis that has been **reallocated** out of the
liquid `FAMILY:ETH` pool into a basis-conserving receipt corridor:

1. The parking signal is `basisEffect == REALLOCATE_OUT` on a `FAMILY:ETH` ledger point
   carrying a non-blank `correlationId`. It parks `(|quantityΔ| − |uncoveredQuantityΔ|,
   |costBasisΔ|, |netCostBasisΔ|)` into a per-`correlationId` pool.
2. The un-parking signal is `basisEffect == REALLOCATE_IN` on a `FAMILY:ETH` ledger point
   whose `correlationId` matches a still-open parked slice; it withdraws the restored amount
   (clamped ≥ 0). A `REALLOCATE_IN` with no matching parked slice (e.g. pure yield accrual)
   does not touch the pool — its quantity is already in the liquid pool.
3. **Only `REALLOCATE` participates.** `DISPOSE`/`ACQUIRE` (realized identity change, e.g.
   ETH→wstETH/weETH C2 derivatives per ADR-054) and `CARRY_OUT`/`CARRY_IN` (same-asset
   bridge corridors, already `FAMILY:ETH` on both legs) never park. This structurally
   excludes C2 staked/value-accruing derivatives (own families per ADR-054) and confusable
   /scam tokens from the blended `FAMILY:ETH` pool.

### Series definition

At each grouped timeline event `t`, over the spot-family live buckets `S` (the ADR-045
Method-B `liveAvcoBuckets`) and the reconstructed parked pool `P`:

> `blendedMarketAvcoAfterUsd(t) = (Σ_S coveredᵢ·avcoᵢ + Σ_P parkedMarketBasis) /
>                                 (Σ_S coveredᵢ  + Σ_P parkedCoveredQty)`
>
> `blendedNetAvcoAfterUsd(t)`   = same with `netAvcoᵢ` and `parkedNetBasis`.

Rules:

1. **Basis conservation ⇒ smoothness.** Because `REALLOCATE_OUT`/`_IN` conserve basis,
   `spotBasis + parkedBasis` is invariant to reallocation; the blended line moves only on
   genuine `ACQUIRE`/`DISPOSE`/`CARRY`/gas events. This is why it does not spike.
2. **Undefined (ADR-031).** When `Σ_S coveredᵢ + Σ_P parkedCoveredQty ≤ 0`, emit `null`;
   the blended line breaks and is never plotted at $0. The blended line therefore stays
   defined while the liquid line breaks at pool≈0, and breaks only when total ETH-origin
   basis-backed quantity is zero.
3. **`blendedAvcoKind ∈ {PRIMARY_FLOW, UNAVAILABLE}`** only; `FAMILY_ROLLUP` is never emitted.
4. **Backend owns before/after** chaining (mirrors ADR-045 rule 5): additive nullable fields
   `blendedAvcoBeforeUsd/AfterUsd`, `blendedNetAvcoBeforeUsd/AfterUsd`,
   `blendedCoveredQuantityAfter`, `liquidQuantityAfter`, `blendedAvcoKind` on
   `TimelineEntryView` → `SessionAssetLedgerResponse.TimelineEntry` → BFF mapper.
5. **Family superset input.** The builder consumes the repo family-scoped points
   (`accountingFamilyIdentity == FAMILY:ETH`, pre-`includeInSpotFamilyTimelineAggregation`),
   re-applying only the C2 guard (`!isC2DistinctAsset`) — never the LP-receipt-symbol drop.
6. **Read-model only.** No replay change, no persisted ETH-origin tag, no new Mongo
   collection/index, zero RPC. The `REALLOCATE_OUT`/`_IN` points are already loaded.

### Reconciliation invariant

- **Definitional (to the cent, unit-testable):** `blendedCoveredQty(t) = spotCoveredQty(t)
  + parkedCoveredQty(t)` and `blendedBasis(t) = spotBasis(t) + parkedBasis(t)`; blended
  covers ≥ spot quantity at every event.
- **Terminal (hard):** `blendedBasis(T) = fullSessionCurrentView.totalCostBasisUsd
  + parkedBasis(T)` and `blendedCoveredQty(T) = fullSessionCurrentView.coveredQuantity
  + parkedCoveredQty(T)`. When all corridors are closed (`parkedCoveredQty(T) ≤ ε`),
  `blendedNetAvco(T) == fullSessionCurrentView.netAvcoUsd` (≈ Method-B terminal, ~$2,677).
- **Parked-pool cross-check (hard, integration):** `parkedBasis(T)`/`parkedCoveredQty(T)`
  equals the sum of still-open `lp_receipt_basis_pools` ETH-origin rows (and, once B-ETH-02
  lands, still-open `lending-loop:` `ContinuityBucket` residuals).
- **Card (soft):** `|blendedNetAvco(T) − currentStateView.netAvcoUsd| ≤ 2 %` — logged, not
  gated, because the W-06 `asset_positions` gap means the on-chain card is not a hard target.

## Consequences

- The chart gains a smooth "Blended (total-exposure) AVCO — matches Net AVCO card" line while
  the ADR-045 spot-family line (renamed on the legend to "Liquid-pool AVCO" to resolve the
  Net-name collision with the card) is retained byte-identical.
- pool≈0 markers (`ε = 1e-6` ETH) and a per-line legend explain the liquid-line spikes as
  expected re-weighting, not defects.
- Blended automatically covers future basis-conserving corridors (lending-loop carry from
  B-ETH-02, GLV, Euler eWETH) with no further change, because it keys on the generic
  `REALLOCATE_OUT`/`_IN` + `correlationId` contract.
- Rebuild: read-model only — `--backend-only` + `--frontend-only`; no replay reset.
- Dashboard header AVCO, per-event raw `LedgerPointView` AVCO, and the ADR-045 primary line
  are unchanged.

## Related

- Code: `AssetLedgerQueryService` (family superset plumbing), `AssetLedgerChartService`
  (Method-B spot terms), `BlendedExposureAvcoSeriesBuilder` (new),
  `LpReceiptEntryReplayHandler` (REALLOCATE_OUT attribution source), `AssetLedgerBffMapper`,
  `asset-ledger-page.component.ts` (`renderChart`, shared `renderAvcoSeries` helper).
- Cross-check surfaces: `AssetLedgerReconciliationService.fullSessionCurrentView`,
  `lp_receipt_basis_pools`.

## Amendment — 2026-07-17 — B-ETH-05: cross-asset LP-exit slice closure

### Defect

The original `BlendedExposureAvcoSeriesBuilder` reconstructs the parked ETH-origin pool
**only** from `FAMILY:ETH` `REALLOCATE_OUT`/`REALLOCATE_IN` keyed by `correlationId`. That is
sufficient for a **same-asset** LP exit (ETH→WETH→ETH), where the returned principal lands back
on `FAMILY:ETH` as a matching `REALLOCATE_IN` and the parked slice closes. It is **wrong** for a
**cross-asset** LP exit (e.g. ETH→USDC): the return lands on `FAMILY:USDC` and the receipt burn
is a `REALLOCATE_OUT` on the `FAMILY:LP_RECEIPT` position, so `FAMILY:ETH` never receives a
matching `REALLOCATE_IN`. The parked slice never closes → **over-park** (measured +$2,092.82
terminal on the audit universe: blended $2,359.19 / 0.9159 ETH vs authoritative open pools
$266.37 / 0.157 ETH).

### Decision (read-model only; no replay/schema change; zero RPC)

The blended series keeps all existing `FAMILY:ETH` park/un-park accounting intact and adds two
zero-RPC snapshot inputs (both reuse existing indexes; supplied by
`AssetLedgerQueryService.toView`):

1. **`FAMILY:LP_RECEIPT` superset points** for the universe, via the family-ordered
   `AssetLedgerPointRepository` query (`accountingFamilyIdentity == FAMILY:LP_RECEIPT`, existing
   `asset_ledger_universe_family_order_idx`), filtered in-memory to the parked `correlationId`s.
   The stored family constant is `AccountingAssetFamilySupport.FAMILY_LP_RECEIPT`; an
   `isLpReceiptSymbol` fallback tolerates divergent stamping. `correlationId` is stamped on the
   receipt-burn `REALLOCATE_OUT` (via `LedgerPointCollector`, from `transaction.correlationId`).
2. **`lp_receipt_basis_pools` snapshot** for the universe (via `LpReceiptBasisPoolService`),
   filtered to family-origin rows (`continuityIdentity(assetSymbol, assetContract) ==
   familyIdentity`, C2 excluded), grouped by LP `correlationId`, holding =
   `(qtyHeld − uncoveredQtyHeld, basisHeldUsd, netBasisHeldUsd)`.

The builder tracks a per-correlation `parkedGrossCovered` (cumulative covered quantity ever
parked) and merges the parked-correlation `FAMILY:LP_RECEIPT` burn events into the ordered event
stream by `(blockTimestamp, transactionIndex, replaySequence)`:

- **Close-boundary clamp (never up):** at a receipt-burn with receipt `qtyBefore=Rb`,
  `qtyAfter=Ra`, `remainingFraction = Rb>0 ? Ra/Rb : 0`; set
  `slice(c).qty := min(slice(c).qty, parkedGrossCovered(c) × remainingFraction)` and scale
  `basis`/`netBasis` by the resulting ratio (AVCO preserved). Full burn (`Ra==0`) closes the
  slice. Because same-asset exits already reduced the slice via `FAMILY:ETH REALLOCATE_IN`, the
  `min` is a **no-op** there and never double-reduces.
- **Terminal exactness clamp:** at the terminal event each LP-receipt-managed parked slice is
  set to `ethOriginHolding(lp_receipt_basis_pools[c])` (0 when the corridor has no open
  family-origin pool), guaranteeing the cent-exact terminal cross-check.
- **Lending-loop (B-ETH-02) correlations** have neither a `FAMILY:LP_RECEIPT` burn nor an
  ETH-origin pool row, so they are never LP-receipt-managed and are left untouched (they keep
  their `REALLOCATE` residual). C2 exclusion and "only `REALLOCATE` participates" are unchanged.

The blended definition is unchanged: `blended = (spotBasis + parkedBasis) / (spotQty + parkedQty)`.
The clamp is monotonic-down, so the blended line converges toward the liquid line without a
spike, `$0`, or lane-hop.

### Corrected invariant

At every event, `parkedCoveredQty`/`parkedBasis` equals the Σ family-origin holdings still OPEN
in `lp_receipt_basis_pools` for parked correlations (deposits − same-asset restores −
cross-asset burn-fraction closures) **plus** the open lending-loop residual. Terminal (to the
cent) `== Σ` open `lp_receipt_basis_pools` ETH-origin `(qtyHeld − uncoveredQtyHeld) / basisHeldUsd`
(plus open lending-loop residual). The ADR-045 spot (liquid-pool) line and the B-ETH-02
lending-loop path remain byte-identical.

## Amendment — 2026-07-17 — B-ETH-06: generalized cross-family closure

### Defect

B-ETH-05 closed the LP cross-asset over-park to the cent, but a residual over-park remained in
**non-LP `REALLOCATE` corridors** that have neither a `FAMILY:LP_RECEIPT` burn nor an open
`lp_receipt_basis_pools` row, so neither B-ETH-05 clamp touched them (measured +$103.54 mkt /
+$102.08 net / 0.029 ETH on the audit universe). It was dominated by a DEX limit-order ETH→wstETH
conversion (ETH `REALLOCATE_OUT` → `FAMILY:WSTETH ACQUIRE`, same `correlationId`), plus `bridge:lifi:*`
OUT-only dust and `evm:*` deposits. Root class is identical to B-ETH-05 (ETH-origin basis that has
LEFT `FAMILY:ETH` into another/C2 family must be `$0` in the blended `FAMILY:ETH` pool), but the
mechanism is a **cross-family settlement**, not an LP-receipt burn.

### Decision (read-model only; no replay/schema change; zero RPC)

The economic rule is generalized: a parked ETH-origin slice for correlation `c` closes when the
ETH-origin exposure leaves `FAMILY:ETH` — whether via (i) an LP-receipt burn (B-ETH-05), (ii) a
cross-family settlement (DEX order / bridge) sharing `c`, or (iii) simply having no open ETH-origin
`lp_receipt_basis_pools` row at terminal.

1. **Generalized terminal exactness clamp (all correlations).** The terminal clamp is applied to
   **every** parked `REALLOCATE` correlation, not only LP-receipt-managed ones:
   `slice(c) := ethOriginHolding(lp_receipt_basis_pools[c])`, or `0` when `c` has no open ETH-origin
   pool row. This is correct because the only legitimately-still-parked ETH-origin `REALLOCATE`
   exposure is an open LP pool. This alone drives the terminal over-park (LP + non-LP) to `$0`.
2. **Per-event cross-family settlement close.** For a parked correlation with no same-family
   `REALLOCATE_IN` return and no `FAMILY:LP_RECEIPT` burn, the **earliest** ledger point sharing `c`
   on a DIFFERENT accounting family (`accountingFamilyIdentity != FAMILY:ETH`) with basis effect
   `ACQUIRE`/`REALLOCATE_IN` closes the slice at that event's `(blockTimestamp, transactionIndex,
   replaySequence)` (encoded as a `remainingFraction == 0` clamp in the merged close stream). These
   settlement points are resolved zero-RPC by scoping the query to the parked slices'
   **`correlationId`s** (`findAllByAccountingUniverseIdAndCorrelationIdIn`), NOT by the parked-out
   `normalizedTransactionId` — this captures DEX/escrow settlements that land in a **separate
   transaction** from the park leg (e.g. an ETH→wstETH order whose escrow park and wstETH settlement
   are different txs sharing the correlation). No dedicated `correlationId` index is added: the
   single-universe query rides the `accountingUniverseId` leading prefix of the existing compound
   indexes and filters `correlationId` during the indexed universe scan; results are filtered in-memory
   to a different family + settlement basis effect. This keeps the line smooth (closes at the
   conversion, not only at terminal). Bridge/EVM OUT-only corridors have no settlement anywhere and are
   closed by the generalized terminal clamp.
3. **Mutual exclusion / no double-close.** A correlation owned by the LP-receipt burn path or by a
   same-family `REALLOCATE_IN` return is excluded from the cross-family close path. **Lending-loop
   (`lending-loop:`) correlations** use `CARRY` (never enter the `REALLOCATE` pool) and are
   additionally protected by an explicit prefix guard in both the cross-family close builder and the
   terminal clamp, so their basis-conserving residual is left untouched.

The blended definition, monotonic-down clamp property, byte-identical spot Method-B line, and `$0`
C2 contribution are all unchanged.

### Corrected invariant (generalized)

At every event, blended `FAMILY:ETH` `parkedCoveredQty`/`parkedBasis` `== Σ` ETH-origin holdings still
OPEN in `lp_receipt_basis_pools` for parked correlations (LP) **plus** the open lending-loop residual,
and `0` for any correlation whose ETH-origin has settled to another family (DEX / bridge / EVM) or has
no open pool row. Terminal (to the cent) `== Σ` open `lp_receipt_basis_pools` ETH-origin (plus open
lending-loop residual) — total terminal over-park (LP + non-LP) `== $0`. The ADR-045 spot line, the
B-ETH-02 lending-loop path, C2 exclusion, and the B-ETH-05 LP-receipt behavior remain unchanged.
