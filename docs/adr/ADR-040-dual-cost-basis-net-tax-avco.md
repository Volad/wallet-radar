# ADR-040: Dual cost basis — Net AVCO and Tax AVCO

| Field | Value |
|---|---|
| **Status** | Accepted (amended 2026-07-02 — net-carry conservation invariant; UI label "Market AVCO") |
| **Date** | 2026-06-30 |
| **Theme** | Cost basis / replay / UI |

## Context

WalletRadar tracks average cost (AVCO) for portfolio analytics. Rewards, airdrops, and LP fee claims increase quantity at fair-market value (FMV), which raises the displayed average cost. Users also want a **Net AVCO** view where zero-cost acquisitions (rewards at $0 basis) lower the average — closer to "what did I actually pay per coin still held?"

Both views must share the **same quantity pool** (covered/uncovered); only cost numerators differ.

## Decision

1. **Tax AVCO** (existing semantics, field `avcoUsd`): FMV at receipt for rewards/airdrops/LP fee claims; used for realised PnL (tax-style) and secondary UI.
2. **Net AVCO** (new, field `netAvcoUsd`): $0 acquisition cost for `REWARD_CLAIM`, LP fee-claim sideflows, and other types flagged by `ZeroCostAcquisitionSupport`; primary dashboard and move-basis display.
3. **Shared quantity**: `applyBuy` / `applySell` / carry paths update quantity once; net and tax cost basis updated in parallel in `GenericFlowReplayEngine`.
4. **Persistence**: `AssetLedgerPoint` stores both lanes (`netTotalCostBasis*`, `netAvco*`, `netRealisedPnlDeltaUsd`).
5. **BORROW / REPAY**: treated identically in both lanes (not zero-cost in Net).
6. **API contract**: `avcoUsd` remains Tax AVCO for backward-compatible API consumers; `netAvcoUsd` is explicit Net AVCO.

## Net-carry conservation invariant (amendment 2026-07-02)

A Phase-1 financial audit (`results/blockers.md`, `results/required-changes.md`) proved that, without
explicit enforcement, every `REALLOCATE_IN`/`CARRY_IN` IN-leg silently re-seeds net basis from tax —
cancelling the reward discount entirely (ETH terminal Net $2,676.99 ≈ Tax $2,677.30 despite ~$1,701 of
$0-net income). This amendment adds the net-carry conservation rule as a **first-class invariant**:

### Net-carry conservation rule

> **Net basis travels with quantity on every IN leg, transported 1:1 from the source carry. Net basis must
> never be re-derived from (nor silently defaulted to) the tax basis, except for source-less orphan
> acquisitions where no reward-discount evidence exists.**

Concretely:

1. **Apply site is the canonical seam.** `GenericFlowReplayEngine.restoreToPosition(CarryTransfer, PositionState)`
   must credit both lanes independently: tax from `carry.costBasisUsd()`/`carry.avco()`, **net from
   `carry.netCostBasisUsd()`/`carry.netAvco()`** (peg-floor applied per-lane). All IN-leg handlers
   (WRAP/UNWRAP/REALLOCATE/CARRY_IN, LP receipt entry/exit, lending, bridge late-carry, earn REALLOCATE,
   custody) route through this seam. The 5-arg tax-only overload `restoreToPosition(qty,pos,cost,uncov,avco)`
   is retired from IN-leg use. `applyAuthoritativeLateInboundCarryBasis` carries an explicit net param.
2. **Construction safety.** `CarryTransfer` net-less general constructors are deleted; any carry produced
   from a known `PositionState`/`ContinuityBucket` must pass explicit net args. `pendingInbound*` factories
   retain `net=null→tax` (unknown until refine, correct for provisional materialization).
3. **Orphan fallback (preserve).** A source-less `CARRY_IN` or fresh priced acquire with no
   reward-discount evidence → `net = tax`. This is correct and unchanged.
4. **Runtime invariant.** A finalize-time guard asserts `|Σ netCostBasisDelta| ≤ dust` on every closed
   round-trip (WRAP↔UNWRAP, spot↔receipt, REALLOCATE_IN↔OUT) on a single position key, for **all**
   families — exactly as `Σ taxCostBasisDelta = 0` is enforced for tax.
5. **Global gate.** `Net AVCO ≤ Tax AVCO` for every asset/family in every view; `Net AVCO ≥ 0`.

### UI terminology (amendment 2026-07-02)

The Tax AVCO lane is displayed in the UI as **"Market AVCO"** (rewards/fees booked at fair-market value).
Internal field names (`avcoUsd`, `avcoAfterUsd`, etc.) are unchanged for backward compatibility.

## Consequences

- Replay handlers and carry restore must propagate `CarryTransfer.netCostBasisUsd()` / `netAvco` where
  basis moves between sub-ledgers — now a compile-enforced contract (net-less constructors deleted).
- After a net-conserving re-replay: ETH Net AVCO will be materially below Market/Tax (~$2,240–$2,600 vs
  $2,677 Tax), reflecting the ~$1,701 of $0-net LP-fee/reward income. No-reward assets (LINK/LTC/DOGE)
  remain Net≈Tax.
- Dashboard "Avg Cost" and move-basis primary chart use Net AVCO; Market/Tax AVCO shown as secondary.
- Ledger points roughly double stored cost fields; query aggregation uses covered-quantity weighting for
  both lanes.
- Phase 0 Bybit Earn corridor pairing (ADR-029 family) remains a prerequisite for trustworthy MNT
  quantities before dual-basis numbers are interpreted.

## References

- `GenericFlowReplayEngine`, `ZeroCostAcquisitionSupport`, `AssetLedgerPoint`, `CarryTransfer`
- `AssetLedgerQueryService`, `SessionDashboardQueryService`
- `docs/pipeline/cost-basis/02-avco-rules.md`, `docs/pipeline/cost-basis/03-basis-pools-and-carry.md`
- `docs/tasks/net-avco-carry-conservation-and-umbrella-double-add-implementation-plan.md`
- `docs/tasks/bybit-earn-corridor-pairing-implementation-plan.md`
