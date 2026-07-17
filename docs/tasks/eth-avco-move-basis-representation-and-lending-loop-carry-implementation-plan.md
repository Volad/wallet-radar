# ETH AVCO — move-basis representation + lending-loop carry + asset-converting bridge basis

> **Status:** REVISED after Phase 3 review (all three reviewers APPROVE-WITH-CHANGES). B-ETH-02 / B-ETH-01 / B-ETH-03 / B-ETH-04 consolidated-approved for implementation; **B-ETH-00 blocked on a scope decision** (see §8).

## 8. Phase 3 review outcome (2026-07-17) — consolidated required changes

Reviewers: [financial-logic-auditor](836d5065-7285-4ee5-99a9-a07567b6e933), [business-analyst](40d7e3da-5cd3-4268-adbc-489a28bd492b), [system-architect](f61d0e14-c390-44a9-beec-7e6b5a5580fb). All **APPROVE-WITH-CHANGES**. The economic models for B-ETH-02 and B-ETH-01 are sound; the required changes are mechanism/scope corrections. Revisions below **supersede** the §3 designs where they conflict.

### 8.0 B-ETH-00 — definitive finding (blocks implementation of Change C)
ADR-045 already establishes that the **existing** spot-family move-basis line's **terminal reconciles to the Net AVCO card** ($2,677.30 Tax / $2,676.99 Net), and that the large intermediate swings are **economically-real** covered-weighted re-weighting caused by LP-RECEIPT/locked ETH being excluded from the spot-family view. Therefore:
- The auditor's "blended = `fullSessionCurrentView` predicate (no reattribution)" would merely **reproduce the existing line** (same family membership) → redundant, does **not** smooth the spikes. Rejected.
- A line that actually **does not spike** requires **including/reattributing ETH-origin basis parked in LP-RECEIPT / lending / GLV / staking receipts** back into the `FAMILY:ETH` series. This is exactly the **ADR-045-deferred RC-E3** ("LP-exit/native basis reattribution") — a real cost-basis read-model change, not a cheap overlay, requiring an ADR-045 amendment/supersession and a precise attribution rule.

**Consequence:** "overlay" has two very different costs. The cheap version adds nothing; the useful (smooth) version = RC-E3. This is a genuine scope decision for the user (see §9). Frontend-only explanation work (AVCO-series legend + pool≈0 marker + tooltip clarifying "liquid ETH ≈ 0, basis parked in LP/lending; a fresh buy snaps the liquid line to spot — expected") can ship independently and immediately, since the numbers are already correct.

### 8.1 Change A (B-ETH-02) — revised
- **No new replay handler; no `ReplayTransactionRouter` edit; drop the §3 A3 `PriceableFlowPolicy` edit.** Route OPEN/DECREASE/CLOSE through the existing generic `applyFlow` + `ReplayTransferClassifier.isBucketOutbound/isBucketInbound` + `ContinuityBucket` + `ReplayPendingTransferKeyFactory.continuityKey` path (the LP-exit/wrapper carry pattern). `EulerLoopReplayHandler` stays REBALANCE-only (it is same-tx, not cross-tx).
- **Correlation granularity: `lending-loop:{openTxHash}` (per OPEN instance), NOT per position key** — else conservation breaks when a position is opened→closed→re-opened. Match decreases/closes to the most-recent still-open OPEN with residual bucket; `pair()` yields a single deterministic match (crossnet pattern).
- **Bucket key network-agnostic**: anchor on `correlationId` (+ collateral asset identity), not `(wallet, network)` — loops can close on a different network than they opened.
- **Principal-leg selection rule**: only the collateral principal outbound is bucketed; specify selection (mirror `lpCompositeBucketIdentity` dominant/non-`FAMILY:` logic) and verify interaction with `LeverageBorrowReplayHook` / `EulerEvkDebtTokenTagger` / `AaveVariableDebtTokenTagger` so the borrow/debt leg is not double-counted.
- **Pricing**: rely on replay bucket-inbound precedence over market; **retain** the existing inbound-shortfall spot fallback so a *linked* close whose bucket is empty/uncovered books spot for the uncovered remainder, **never $0**; a genuinely **unpaired** loop keeps today's market pricing.
- **Own conservation assertion (A4 revised)**: `CorridorBasisConservationGuard` does NOT watch `ContinuityBucket`s, so "no HARD_FAIL" proves nothing here. Add a dedicated end-of-replay assertion/telemetry over `lending-loop:` buckets: Σ open carry-out == Σ restored carry-in + residual (still-open parked), to the cent.

### 8.2 Change B (B-ETH-01 / B-ETH-03) — revised
- **Drop the correlationId net accumulator.** Reuse the existing **bridge-settlement queue** (`bridgeSettlementKey` → carry). The only missing piece is source realization.
- **Decide family + peg at the OUTBOUND (`BRIDGE_OUT`) leg** (not fragile inbound re-derivation). When `sourceFamily ≠ destFamily && !pegNeutral`: **DISPOSE** the source (book realized P&L vs source AVCO on the source family), enqueue a carry whose basis = **realized proceeds = destination fair market value at settlement timestamp**; the **INBOUND** leg **ACQUIREs at that carried USD**. Net lane inherits source net (capped at market), tax lane = market — mirroring same-tx SWAP semantics.
- **Guard-orphan safety**: the source carry on the `bridge-settlement:{corr}` queue **must be drained/discarded** after realization (or add an explicit guard carve-out for asset-converting settlement legs), else `CorridorBasisConservationGuard` HARD_FAILs. Explicit test required.
- **B-ETH-03 folded in**: stamp the settlement sub-mode flag (`BRIDGE_SETTLEMENT_ASSET_CONVERTING` vs `BRIDGE_CONTINUITY_SAME_ASSET`) at the outbound decision — this both distinguishes the modes and lets the inbound skip re-derivation.
- **Peg fast path untouched** (USDC/USDT/… via `PegNeutralBridgeAssumptionSupport`): existing USDC→ETH corridors stay **byte-identical**, regression-guarded by existing `AvcoReplayServiceTest` cases. Enumerate the peg set + its source of truth in the ADR.
- **Conservation semantics differ from B-ETH-02**: cross-family convert **realizes P&L** (basis NOT conserved across families) — acceptance asserts non-zero P&L booked, not Σcarry conservation.

### 8.3 Change C (B-ETH-00) — revised (pending §9 decision)
- If RC-E3 chosen: extract a dedicated `BlendedExposureAvcoSeriesBuilder` (do not bloat `AssetLedgerChartService`); define ETH-origin attribution tracked through `REALLOCATE_OUT` into receipt buckets; feed the **unfiltered** family superset (today `AssetLedgerQueryService` pre-filters with `includeInSpotFamilyTimelineAggregation`, which drops receipt buckets). Additive nullable DTO fields. **Amend/supersede ADR-045.**
- Hard invariant to pin in the ADR: define exactly what the blended terminal must equal (and reconcile the three surfaces: card = `currentStateView` live-balance; existing line terminal = spot-family Method B; blended = ETH-origin total-exposure). Soft cross-check to the on-chain card within a stated tolerance (the W-06 `asset_positions` reconciliation gap means the card is not a hard on-chain-tied target).
- **C2 exclusion regression guard**: wstETH/cmETH/weETH (own families, ADR-054) must never enter `FAMILY:ETH` blended.
- Frontend: single AVCO-series render helper (not a third copy of `buildAvcoLineSegments`); disambiguated legend copy to avoid the **Net-name collision** ("Liquid-pool AVCO" for the existing solid line vs the card's "Net AVCO"); define ε for pool≈0 and line-break/UNAVAILABLE behavior per line; tooltip shows all three AVCOs + covered qty + liquid qty.

### 8.4 B-ETH-04 — acceptance added
`LP_EXIT_FINAL` with `costBasisDelta == 0` emits no spurious AVCO (seq 4875 no longer shows $249); LP replay baseline otherwise byte-identical; keep last/optional; verify no perturbation of the LP composite bucket restore.

### 8.5 ADRs required
- **New ADR** — Lending-loop open↔close carry pool (pairing contract + park/restore + conservation invariant); sibling of ADR-043.
- **New ADR** — Asset-converting bridge realize-on-convert (dispose source at dest market value, acquire dest; peg fast path retained; settlement sub-mode reason code); references ADR-040/043.
- **Amend/supersede ADR-045** — only if RC-E3 blended series is approved (§9).
- B-ETH-03 / B-ETH-04: doc-only.

### 8.6 Expanded test scenarios (from BA)
Partial decreases 1→N; open-in/close-out **and** open-out/close-in; loop still open at session end (residual conserved); two overlapping loops same market (no mis-pair); WBTC→ETH and ETH→USDC and WBTC→ETH (non-peg→non-peg); USDC→USDT stable→stable fast path; USDC→ETH byte-identical regression; uncovered/partially-covered source; multi-wallet ETH family aggregation; C2-exclusion guard; blended==defined while liquid breaks at pool≈0; zero-LP session ⇒ blended == liquid; B-ETH-04 dust.

## 9. Decision required before Change C (B-ETH-00)

The correctness fixes (B-ETH-02, B-ETH-01, B-ETH-03, B-ETH-04) proceed under standing authorization. Change C's scope is a genuine product/effort choice (RC-E3 vs frontend-only explanation) — captured for the user in chat.

---

## Original Phase 2 draft (superseded where §8 conflicts)

> **Status:** DRAFT (Phase 2 plan, awaiting Phase 3 review + user approval)
> **Audit session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
> **Wallets:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` (+ `0x68bc…`)
> **Symptom source:** ETH "Move basis" chart shows repeated AVCO spikes to ~$5k while the Net AVCO card reads ~$2.68k.

## 0. Executive summary

A deep `/financial-audit` of the flagged ETH interval (seq 4408→6767, 435 ETH ledger
points) established the **primary symptom is not a pipeline defect**: the ~$5k spikes are
**economically correct** liquid-pool AVCO. The liquid ETH pool is repeatedly drained to ≈0
by `REALLOCATE_OUT` into LP / lending / GLV / staking receipts (basis correctly parked
in-family), then refilled by **real spot purchases** at genuine Aug–Sep-2025 prices
($4,000–$4,526). When a fresh buy lands on a near-empty pool, running AVCO correctly snaps to
spot. The chart plots the **liquid-pool** AVCO; the card shows the **blended total-exposure**
AVCO. Same conserved basis, two aggregations.

The audit also surfaced one **confirmed correctness defect** and two latent items:

| ID | Sev | Stage | One-line | Impact |
|----|-----|-------|----------|--------|
| **B-ETH-02** | High | linking → cost_basis | `LENDING_LOOP_OPEN`↔`CLOSE` never paired; close re-prices returned collateral at market instead of restoring carried basis | **$549.28 realized** + $2,169.15 latent |
| **B-ETH-01** | Medium | replay carry | asset-converting bridge (USDC→ETH) blindly copies source USD basis onto dest asset; correct only because source is $1-pegged | ~$0 now, latent on any non-stable convert |
| **B-ETH-03** | Low | semantics | `BRIDGE_IN` label conflates same-asset carry vs asset-converting settlement | $0 (observability) |
| **B-ETH-00** | High (symptom) | presentation | chart shows liquid-pool AVCO only; no blended total-exposure line, no pool≈0 marker | $0 (misleading only) |
| B-ETH-04 | Low | cost_basis | `LP_EXIT_FINAL` cbd=0 → avco $249 (seq 4875) dust | <$1 |

**User decisions (2026-07-17):** B-ETH-00 → *overlay* (keep liquid-pool line, add blended
total-exposure line + AVCO legend + pool≈0 marker). Scope → **all Medium+** (B-ETH-02,
B-ETH-01, B-ETH-03, B-ETH-00; B-ETH-04 folded in as cheap cleanup).

## 1. Scope

**In scope**
- B-ETH-02: pair `LENDING_LOOP_OPEN`↔`CLOSE`/`DECREASE`; park basis on open, restore pro-rata on close.
- B-ETH-01: make asset-converting bridge basis correct for non-peg sources (realize source / acquire dest), keep the peg-neutral fast path.
- B-ETH-03: distinguish same-asset carry vs asset-converting settlement (observability reason code / metadata; no behavior change).
- B-ETH-00: backend blended total-exposure AVCO timeline series + frontend overlay line, AVCO legend, pool≈0 markers.
- B-ETH-04: LP_EXIT_FINAL zero-cbd dust guard.

**Out of scope**
- Any change to the *correct* liquid-pool AVCO computation (Method B) — the spikes stay as-is; we only *add* a second line.
- CEX (Bybit) replay paths (the flagged spot execution `2280000001325333946` is CLEAN).
- Pricing-cache changes (no pricing policy change).

## 2. Root causes (from audit + code exploration)

### B-ETH-02 — lending-loop open/close continuity gap
- `LENDING_LOOP_*` classified per-protocol (`LendingClassifier` + Euler/Morpho/Compound/Fluid semantic classifiers) but **no linking service pairs OPEN↔CLOSE**; `correlationId` is never shared, so `lifecycleChainId` is each leg's own tx hash and `lifecycleStage` is always `SINGLE`.
- Replay routes only `LENDING_LOOP_REBALANCE` to `EulerLoopReplayHandler`; OPEN/CLOSE/DECREASE fall through to generic `applyFlow`.
- `PriceableFlowPolicy.requiresMarketPrice()` forces **market price** on lending-loop collateral `TRANSFER` inflows (to avoid $0 basis), so the close re-acquires at spot.
- No carry pool parks basis between open and close (`ContinuityBucket`/`PendingTransferStore` lists exclude `LENDING_LOOP_*`).
- **Evidence:** open `0xcb8483…` (seq 5927) removes 0.919170 ETH @ pool avco $4,338.30 (`CARRY_OUT`); close `0x38d742…` (seq 8164, out of interval) re-injects 0.419170 ETH @ market $3,027.90 (`priceSource=DZENGI`). Correct carried restore = $1,818.49; actual $1,269.21 → **−$549.28**; 0.5 ETH ($2,169.15) remains parked off-ledger and will mis-restore identically on the next close.

### B-ETH-01 / B-ETH-03 — asset-converting bridge basis carry
- Linking (`LiFiBridgePairLinkService`, `CrossNetworkBridgePairFallbackService`) detects family mismatch via `BridgePairLinkSupport.supportsBridgeContinuity()` → sets `continuityCandidate=false` → `ReplayPendingTransferKeyFactory.bridgeSettlementKey()` → REALLOCATE corridor.
- `ContinuityCarryService.bridgeSettlementInboundCarry()` copies the **entire source `costBasisUsd`** onto the destination `AssetKey` with **no source-vs-dest family check and no peg check**. AVCO(dest) = sourceUsd / destQty.
- Peg-neutral by luck: for USDC/USDT the $1 basis transfers cleanly; for a non-stable source (e.g. WBTC→ETH, or ETH→USDC) the copy corrupts both sides' basis and books no realized P&L on the disposal.
- B-ETH-03: both same-asset and asset-converting corridors emit type `BRIDGE_IN`; only `continuityCandidate` + basis effect differ — hard to audit.

### B-ETH-00 — chart shows only liquid-pool AVCO
- `AssetLedgerChartService.buildTimelineProjection` computes Method B covered-weighted AVCO over **spot-family-filtered** buckets only (`AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation` excludes LP receipts + C2 derivatives). No blended (all-in-family) series exists on the timeline; `fullSessionCurrentView` computes an all-family rollup only at the **terminal** snapshot (and the UI never reads it).
- The frontend Canvas chart draws two spot-family lines (Net cyan, Market/Tax dashed) with no AVCO-series legend and no pool≈0 marker.

## 3. Design & ordered changes (upstream-first)

### Change A — B-ETH-02: lending-loop open↔close pairing + carry restore (backend)

**A1. Linking — new `LendingLoopOpenClosePairLinkService`**
(`application/linking/pipeline/clarification/`), modeled on `CrossNetworkBridgePairFallbackService` / `GmxEntryRequestLinkService`.
- Match candidates by: `walletAddress` + `networkId` + `protocolName` + position identity
  (`metadata.positionKey` when present, else `LendingMarketKeyResolver` market asset + collateral asset symbol/contract) + monotonic **open-before-close** + bounded time window (loops can be open for months — window sized generously, gated by position identity + protocol, not just time).
- Write shared `correlationId = lending-loop:{positionKey}` on both legs; support **1→N** (one open, multiple partial decreases + final close).
- Register in `LinkingBatchProcessor` convergent passes.

**A2. Replay — park on open, restore pro-rata on close**
- Route `LENDING_LOOP_OPEN`/`DECREASE`/`CLOSE` to a dedicated handler (extend `EulerLoopReplayHandler` or add `LendingLoopReplayHandler`); fix stale `docs/pipeline/replay/02-handlers.md`.
- Park: extend `ReplayTransferClassifier.isBucketOutbound()` for `LENDING_LOOP_OPEN` collateral outbound; add composite continuity key `lending-loop:{correlationId}:{collateralAssetIdentity}` in `ReplayPendingTransferKeyFactory`.
- Restore: extend `isBucketInbound()` for `LENDING_LOOP_CLOSE`/`DECREASE`; use `ContinuityBucket.take(qty, assetKey)` for **pro-rata partial** restore (same pattern as LP-exit) → emit `CARRY_IN`/`REALLOCATE_IN` instead of market `ACQUIRE`. Remaining parked basis stays in the bucket for the next decrease/close.

**A3. Pricing gate**
- `PriceableFlowPolicy.requiresMarketPrice()`: skip market pricing for lending-loop collateral inflows when `correlationId` starts with `lending-loop:` **and** a bucket carry exists (mirror `isContinuityPrincipal()` for bridges). Preserve the market-price fallback when a loop is genuinely unpaired (no correlation), so an unlinked close never lands at $0.

**A4. Conservation invariant**
- Σ open `CARRY_OUT` basis == Σ (partial + final) `CARRY_IN` basis per loop correlation, to the cent; any residual is the still-open parked basis (must equal open − restored). Add a guard/telemetry counter analogous to the corridor conservation guard.

### Change B — B-ETH-01 / B-ETH-03: asset-converting bridge basis (backend)

**B1. Peg-neutral fast path (unchanged, documented).** When source family == dest family (same-asset) → existing `CARRY_IN`/`CARRY_OUT`. When source is a **$1 peg-neutral stablecoin** (USDC/USDT/…) and dest differs → keep the current REALLOCATE settlement carry (it equals the correct realize-at-$1 + acquire-at-$1).

**B2. General cross-family correct path.** When source family ≠ dest family **and** source is **not** peg-neutral → treat the corridor as a **cross-family swap**: realize the source on `BRIDGE_OUT` (`DISPOSE` at its realized USD, booking P&L against its own AVCO) and acquire the dest on `BRIDGE_IN` (`ACQUIRE` at that same USD), propagating net basis across the two transactions via a `correlationId`-keyed accumulator (mirror the same-tx `swapNetRef` pattern in `ReplayDispatcher`).
- Insertion point: `LinkedBridgeTransferReplaySupport.applyLinkedBridgeSettlementTransfer()` inbound branch — before `bridgeSettlementInboundCarry`, compare `BridgeAssetFamilySupport.continuityIdentity()` of the queued carry source vs the current dest flow; if mismatch **and** not peg-neutral → route through realize/acquire instead of blind restore. Reuse `PegNeutralBridgeAssumptionSupport` for the peg test.

**B3. Observability (B-ETH-03).** Emit a distinguishing reason code / ledger metadata on the settlement legs (e.g. `BRIDGE_SETTLEMENT_ASSET_CONVERTING` vs `BRIDGE_CONTINUITY_SAME_ASSET`) so the two `BRIDGE_IN` sub-modes are auditable. No behavior change.

### Change C — B-ETH-00: blended total-exposure AVCO overlay (backend + frontend)

**C1. Backend series.** In `AssetLedgerChartService.buildTimelineProjection`, in addition to the spot-family Method B buckets, maintain a **second bucket set over all buckets whose `resolvedFamilyIdentity == familyIdentity`** (the per-event analog of `fullSessionCurrentView`) and compute `blendedAvcoAfterUsd` / `blendedNetAvcoAfterUsd` via the existing `coveredWeightedFamilyAvco`. Add fields to `TimelineEntryView` → `SessionAssetLedgerResponse.TimelineEntry` → `AssetLedgerBffMapper`. Also expose `liquidQuantityAfter` (alias of current `quantityAfter`) explicitly for the pool≈0 marker.
- **Open design question for reviewers:** whether basis parked in `FAMILY:LP_RECEIPT` / lending / GLV receipts should be attributed back to `FAMILY:ETH` for the blended line, or whether "blended" = only buckets that already resolve to the requested family (excludes LP_RECEIPT). Must reconcile so the terminal blended point equals the Net AVCO card (~$2.68k) for ETH.

**C2. Frontend overlay.** In `asset-ledger-page.component.ts` `renderChart()`: add a third `buildAvcoLineSegments` series (blended, distinct color/style) alongside Net + Market; add an **AVCO-series legend** (Net / Market / Blended, distinct from the tx-type sidebar legend); draw **pool≈0 markers** when `marker.quantityAfter < ε` (or `coveredQuantityAfter < ε`). Add TS model fields + `MarkerView` mapping + y-range + tooltip line.

### Change D — B-ETH-04: LP_EXIT_FINAL zero-cbd dust guard (backend)
- Guard `LP_EXIT_FINAL` with `costBasisDelta == 0` producing a spurious avco (seq 4875, $249, <$1) — clamp/skip per existing dust posture. Low priority; include only if it does not perturb the LP replay baseline.

## 4. Docs to update
- `docs/pipeline/replay/02-handlers.md` — correct the lending-loop routing (OPEN/CLOSE now paired + carried).
- `docs/pipeline/cost-basis/03-basis-pools-and-carry.md` — lending-loop carry pool + asset-converting bridge realize/acquire rule.
- `docs/pipeline/linking/02-rules-and-repairs.md` — new lending-loop open↔close pairer.
- `docs/frontend/move-basis.md` + `docs/adr/ADR-045-*` — blended total-exposure overlay decision (revisits ADR-045's deferral); likely a **new ADR** for the blended series semantics + the asset-converting bridge realize/acquire rule.
- `docs/reference/ledger-points-and-basis-effects.md` — bridge settlement asset-converting reason code.

## 5. Acceptance criteria
- **B-ETH-02:** after pairing, the close `0x38d742…` restores 0.419170 ETH at carried $4,338.30 (basis $1,818.49, not $1,269.21); Σ open carry-out == Σ restored carry-in + still-parked; ETH-family terminal AVCO reflects the corrected $549.28; no CorridorBasisConservation HARD_FAIL; `asset_ledger_points` count stable modulo intended new carry points.
- **B-ETH-01:** a synthetic non-peg asset-converting bridge (e.g. WBTC→ETH) books realized P&L on the source and market-consistent basis on the dest (not a blind USD copy); USDC→ETH remains byte-identical to today (peg fast path). Golden replay test.
- **B-ETH-03:** settlement vs continuity sub-mode distinguishable via reason code; no value change.
- **B-ETH-00:** chart renders 3 AVCO lines (Net / Market / Blended) + legend + pool≈0 markers; the terminal blended point reconciles with the Net AVCO card (~$2.68k) within tolerance; existing Net/Market lines unchanged.
- Full `:backend:core:test` green; `financial-logic-auditor` re-audit of the interval: spikes explained by the blended overlay, B-ETH-02 conserved, no new Medium+.

## 6. Risks
- **Loop pairing false positives:** long-open loops + multiple partial decreases; mitigate by keying on position identity + protocol (not time alone) and requiring open-before-close monotonicity. Risk of mis-pairing two distinct loops on the same market — gate by collateral asset + wallet + protocol.
- **Replay carry ordering:** parked basis must survive out-of-interval closes (close is months later); the bucket must persist across the whole session replay (it already does for LP/bridge).
- **B-ETH-01 regression surface:** changing the cross-family branch must not perturb the many existing USDC→ETH corridors — the peg fast path must be exercised by the existing `AvcoReplayServiceTest` cases unchanged.
- **Blended series definition:** getting the family attribution wrong makes the overlay disagree with the card; must reconcile terminal point == card before shipping (reviewer gate).
- **Frontend-only vs pipeline rebuild:** Change C backend needs `--skip-frontend` renorm to populate the new DTO fields; Change A/B need full renorm + auditor.

## 7. Sequencing
1. Change A (B-ETH-02) — highest correctness value; land + audit.
2. Change B (B-ETH-01/03) — latent hardening; land + audit (verify USDC→ETH unchanged).
3. Change C (B-ETH-00) — presentation overlay; backend series first, then frontend.
4. Change D (B-ETH-04) — cheap cleanup, optional.
Each change: implement → `:backend:core:test` → `--skip-frontend` rebuild + renorm → `financial-logic-auditor` against the acceptance above.
