# ADR-041 — Bybit Earn Corridor Pairing and `:FUND` Carry Symmetry

> **Extended-by:** [ADR-043](ADR-043-corridor-carry-basis-conservation.md) (corridor carry basis conservation — the basis half of this invariant).

**Date**: 2026-06-30
**Status**: Accepted
**Related**: ADR-016 (Bybit internal-transfer bundling and round-trip pairing), ADR-019 (corridor carry policy), ADR-023 (Bybit corridor spot basis), ADR-029 (deterministic CEX corridor basis continuity), ADR-040 (dual Net/Tax cost basis — Phase 0 prerequisite)
**Implements**: `docs/tasks/bybit-earn-corridor-pairing-implementation-plan.md` (RC-1, RC-2, running-balance pairing, conservation guard)

---

## Context

Multiple Bybit cost-basis pools were inflated versus live balances because the two halves of an
intra-account custody round-trip (Flexible-Savings / Launchpool / staking subscribe ↔ redeem, and
plain `FUND ↔ UTA` internal transfers) were not netting to zero. Two distinct defects were proven
against raw evidence (`results/blockers.md`, `results/reconciliation.md`):

- **RC-1 — Earn-corridor classification asymmetry.** `BybitCanonicalTransactionBuilder` classified
  Flexible-Savings *redemption* but not *subscription*, so the subscribe leg fell through to
  `INTERNAL_TRANSFER`. The stream collapser one-sidedly demoted it (`bybit-collapsed-v1`,
  `excludedFromAccounting=true`) while the redeem leg stayed booked, so every completed cycle re-added
  its principal to `:FUND`. Decisive penny-check: EARN redeem `−3,238` + open subscribe `+108.776` +
  re-included excluded subscribe `+3,233` = **+103.776 = live EARN balance**.
- **RC-2 — `:FUND` position-key carry asymmetry in replay.** Confirmed to the penny via XRP (zero Earn
  activity): the six `FUND ↔ UTA` round-trips net to ≈0 in normalization, but replay credited inbound
  legs onto the `:FUND` position key while the matching outbound legs were dropped or stripped to the
  umbrella, leaving `:FUND` inbound-only (`12.37` phantom vs live `0`). LDO is pure RC-2 (`906.93`
  phantom); USDT is RC-2 + RC-1 across four sub-accounts.

A subsequent regression pass (the ETH `$2.2k → $2.6k` dashboard jump and the LTC `$18,731` move-basis
chart line) confirmed the same two defects, surfaced by an in-flight fix attempt that had relocated the
RC-2 asymmetry onto the umbrella ETH key (phantom `1.149` ETH + `0.693` EARN ETH + METH/CMETH against
raw net ≈ 0) and created a LTC `:EARN` basis ghost (`+$41.54` basis booked with `quantityDelta=0`).

---

## Invariant

> A Bybit intra-account custody round-trip is **basis- and quantity-conserving on a single position
> key**: the inbound and outbound legs of a `FUND ↔ UTA` / `FUND ↔ EARN` move resolve to the **same**
> replay position key and net to zero, so a closed subscribe→redeem cycle leaves no phantom inventory
> and produces no `quantityShortfall`. **Basis follows quantity** — a principal subscribe never injects
> basis onto a key that did not also receive the quantity. An Earn principal leg may be excluded from
> accounting **only if its paired leg is also excluded** (no one-sided exclusion). The corridor
> correlation key is a **pure, order-stable function of `{uid, family, |qty|, blockTimestamp.epochSecond}`**
> — never of Mongo `_id` or import order.

---

## Decisions

### D1 — RC-1: Flexible-Savings subscription is a custody type symmetric with redemption (CLASSIFICATION)

- `BybitCanonicalTransactionBuilder.resolveEarnLifecycleCanonicalType` classifies a principal
  *Flexible-Savings subscription* as `EARN_FLEXIBLE_SAVING` (the same custody type as redemption,
  direction by flow sign). Only **principal** rows are matched — auto-compounded interest/reward rows
  stay `REWARD_CLAIM`.
- This removes the subscribe leg from the collapser's `INTERNAL_TRANSFER`-only demotion (the one-sided
  exclusion disappears at source), admits it to `BybitEarnPrincipalTransferPairer`, and keeps the
  existing pipeline order (collapse → earn-pair → exclusivity). No new correlation pass is added.

### D2 — Running-balance (FIFO) principal pairing (LINKING)

- `BybitEarnPrincipalTransferPairer` models a **running principal balance** per corridor key
  `{universe, uid, asset, earnProduct}`: subscribe increments, redeem decrements, FIFO/time-ordered
  consumption. Bybit allows `1 subscribe : N partial redeems` and open positions (subscribed, not yet
  redeemed), so an equal-`principalQty` key is insufficient; equality is kept only as a closed-cycle
  assertion. The correlation key is derived solely from `{uid, family, |qty|, blockTimestamp.epochSecond}`
  for determinism across rebuild and incremental refresh.

### D3 — RC-2: `:FUND` carry symmetry in replay (REPLAY)

- `TransferReplayHandler.resolveCarrySourcePosition` scopes the `:FUND` venue-internal drain redirect to
  contexts where principal **legitimately sits on `:FUND` and is leaving into a product/receipt**:
  earn-principal / on-chain-fund corridor correlations, or a principal-deposit transaction type
  (`STAKING_DEPOSIT`, `LENDING_DEPOSIT`, `EARN_FLEXIBLE_SAVING`, `VAULT_DEPOSIT`).
- Plain `bybit-collapsed-v1:` `FUND ↔ UTA` `INTERNAL_TRANSFER` round-trips are **excluded** from the
  redirect: their inbound and outbound legs both resolve to the umbrella key and net to zero, so they no
  longer accumulate inbound-only `:FUND` phantoms. `AccountingAssetIdentitySupport.isBybitCollapsedFundSide`
  no longer preserves `:FUND` on inbound collapsed legs (both directions strip to the umbrella).
- `ReplayFlowSupport.continuityBasisEffect` is **unchanged** (verified symmetric). The legitimate
  liquid-staking carry (`ETH → METH`, FAMILY:ETH conserved, realised = 0) is preserved by the
  type-aware drain context — staked principal AVCO carries into the receipt instead of minting at $0.

### D4 — Sub-pool conservation guard (REPLAY, WARN-first)

- `BybitEarnSubPoolConservationGuard` evaluates, after replay and before publication, per asset and
  umbrella: (1) round-trip net per corridor ≈ open principal; (2) `:FUND` and `:EARN` each reconcile to
  `bybit_live_balances.{fundQty,earnQty}` (not just the umbrella sum); (3) exclusion symmetry; (4)
  idempotency (recycling the same principal cannot grow quantity). **Rollout: `WARN` first** (structured
  breach log, no replay failure); promotion to hard-fail is a single-line severity change once a clean
  rebuild confirms zero breaches — consistent with the ADR-029 `CorridorBasisConservationGuard` rollout.

---

## Consequences

- Bybit sub-pools reconcile to live only when **both** contracts hold: deterministic upstream
  correlation in linking **and** replay materialization of the paired inbound restore.
- Phase-2 runtime verification (2026-07-03) confirmed the first half for all 22 targeted bundles:
  each `FUND out + UTA out -> EARN in` bundle now carries one shared `bybit-earn-principal-v1:*`
  correlation id.
- The second half is still mixed at replay time: some bundles now emit the expected inbound
  `REALLOCATE_IN` / `CARRY_IN`, but others still persist only outbound rows. The LTC anchor bundle is
  still failing this contract after rerun.
- Therefore this ADR's invariant is accepted as the target behavior, but the current implementation is
  only **partially realized** in runtime evidence and cannot yet be treated as fully closed.
- The read-model quantity clamp is nevertheless correct and independent: when Bybit live quantity is
  above ledger quantity, the excess stays uncovered and must not inflate covered quantity, total/net
  basis, or displayed net AVCO inputs.

---

## Open questions

- **Q1 (guard severity):** `WARN → HARD_FAIL` staged rollout via `BybitEarnSubPoolConservationGuard`
  severity, flipped only after a clean rebuild shows zero breaches.
- **Q2 (Dual-Asset / asset-switch Earn):** products that break the equal-asset assumption are out of
  scope; fold in later if observed.
- **Q3 (BORROW-as-ACQUIRE):** the Bybit Crypto-Loans liability-vs-acquire policy (B-4) remains a separate
  decision and is not changed here.
- **Q4 (paired inbound persistence):** when a linked multi-source bundle replays with the inbound EARN
  credit observed before its paired outbound carry, the system must still persist a materialized inbound
  ledger row for that EARN-side event. Current runtime evidence shows this is not guaranteed yet.
- **Q5 (`principalFlow()` assumption):** current Bybit evidence supports the first non-fee non-zero flow
  assumption for these rows, but this remains a residual risk if Bybit later emits multi-principal,
  multi-non-fee rows for the same product family.
