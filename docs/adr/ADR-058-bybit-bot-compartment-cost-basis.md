# ADR-058: Bybit Trading-Bot Compartment Cost Basis

**Status:** Accepted — **revised 2026-07-16 after Phase 2 verification (NEW-12-R); implemented and verified in prod**
**Date:** 2026-07-16
**Scope:** Bybit normalization / cost basis — Trading-Bot cross-asset conversions (defect NEW-12)

> **Revision (NEW-12-R):** the original D3/D4 redistributed an unpriced asset's share of
> `netConsumed` onto the priced sibling(s), which for member `516601508`'s Oct-2025 session
> (no execution fills in window) collapsed the whole session's `$119.23` onto `0.0137 ETH`
> → `$8,673/ETH`, then carried out into member `33625378`'s ETH pool. D3/D4 are revised below to
> **cap each asset at `returnedQty × avgExecPrice`** and **forbid cross-asset cost dumping**;
> the §Consequences claim that the `EXECUTION_SPOT` re-backfill is "optional" is corrected —
> it is **required** whenever a return falls outside the ingested execution window.

> **Numbering note:** the task brief referenced `ADR-055`, but `ADR-055`
> (`ADR-055-fh-crypto-loans-source-authority.md`), `ADR-056`, and `ADR-057` are already taken.
> This decision ships as **ADR-058**. Rename + fix cross-references if a different number is
> preferred.

## Context

Bybit Trading Bots move stablecoins (typically USDT) from the user's Funding/UTA wallet into a
managed bot compartment, trade them into one or more crypto assets, and later return the crypto
(plus stablecoin dust) to the user. The only stream that records the FUND-side of these moves is
`FUNDING_HISTORY` (`bybitType="Bot"`, description `"Transfer to/from Trading Bot"`), which is
**single-legged**: it shows the user's side of the transfer but not the bot's internal fills.

The bot's internal fills *are* available via `/v5/execution/list?category=spot`
(`EXECUTION_SPOT`), which is already ingested and extracted as `SWAP` rows. However, for real
bot sessions the execution history is **incomplete** (2-year retention + internal rebalancing):
for member `516601508` the ingested fills bought `0.01811 ETH` + `0.000362 BTC` for `91.79 USDT`,
while the bot actually returned `0.01374624 ETH` + `0.001286712 BTC` against `−185 USDT`.

**Prior behaviour (NEW-12 defect):** `BybitCanonicalFlowCounterpartySupport.reclassifyBotTransfer`
re-types bot legs to `EXTERNAL_TRANSFER_IN/OUT` and leaves non-stablecoin returns at
`PENDING_PRICE`. `BybitBotTransferCostBasisService` resolves **single-asset** sessions from the
net stablecoin consumed (`BOT_LEDGER`), but **multi-asset** sessions fall through to the generic
pricing pipeline, which stamps **FMV** — fabricating ~$53 ETH + ~$127 BTC ≈ the $185
consideration (inventory leakage, phantom basis).

**Prior fix attempt (Phase 1) failed:** re-typing the to-bot USDT as a *continuity*
`INTERNAL_TRANSFER` created a guarded `CARRY_OUT` while the crypto return stayed
`EVIDENCE_MISSING`, leaving the corridor unbalanced — 28 orphaned carries ≈ $280 tripped
`CorridorBasisConservationGuard` (HARD_FAIL) and aborted the entire replay.

## Decision

### D1. The bot is a per-session `:BOT` compartment resolved at normalization time

Group `BOT_TRANSFER` rows per `(uid)` into sessions (14-day gap, existing rule). Resolve each
session's basis **before replay**, in a generalised `BybitBotCompartmentCostBasisService`
(evolution of `BybitBotTransferCostBasisService`). Deciding basis authority pre-replay prevents
double-counting between the execution `SWAP` legs and the transfer legs.

### D2. Total session basis = net stablecoin actually consumed

`netConsumed = Σ(to-bot stable) − Σ(from-bot stable dust)`. This is the exact USD value that
left the user's spendable stable inventory and is the authoritative **total** cost of the crypto
returned. No fair-market value is ever introduced.

### D3. Per-asset basis = execution unit price × returned qty, capped by net consumed (no cross-asset dumping)

For each returned crypto asset, `avgExecPrice = Σ execValue / Σ execQty` over that asset's BUY
fills **inside the session window**. Each asset's basis is
`assetBasis = returnedQty · avgExecPrice` — the asset is valued at *its own* execution cost,
**never** at a sibling's cost.

- **Conservation cap:** `Σ assetBasis` must not exceed `netConsumed`. If the priced assets'
  execution-derived basis sums **above** `netConsumed` (fills bought more than was net-consumed,
  e.g. dust/rebalancing), scale all priced assets down by `netConsumed / Σ assetBasis`.
- **No upward redistribution (NEW-12-R):** if `Σ assetBasis < netConsumed`, the shortfall is
  **retained as unallocated compartment residual** and is **never** pushed onto a priced asset.
  A priced asset's per-unit basis is therefore bounded by its own `avgExecPrice`; it can only be
  scaled **down**, never up, by conservation.
- **Single-asset degenerate case:** exactly one returned crypto asset **and** that asset has
  execution coverage → `netConsumed / returnedQty` (bit-identical to legacy `BOT_LEDGER`; no
  regression). Single asset **without** execution coverage → D4.

Resolved returns are `CONFIRMED` with `PriceSource = BOT_LEDGER`; stablecoin dust returns keep
the `STABLECOIN` $1 peg.

### D4. Execution data unavailable → bounded EVIDENCE_MISSING, never FMV, never sibling-funded

If a returned crypto asset has **no** usable execution unit price **inside its session window**,
that asset is flagged bounded `EVIDENCE_MISSING` (undefined AVCO per [ADR-031](ADR-031-avco-undefined-representation.md)),
not priced at FMV **and not funded from a sibling asset's `netConsumed` share**. Its portion of
`netConsumed` stays unallocated (D3 residual). This holds in **both** multi-asset and single-asset
sessions: a session with **zero** execution coverage produces **no** fabricated per-unit basis —
every returned asset is `EVIDENCE_MISSING`, surfacing the genuine data gap rather than collapsing
the deposit onto whichever asset happened to be priced.

> **Why:** the pre-revision rule made the *last priced asset standing* absorb every unpriced
> sibling's cost. Removing an asset to `EVIDENCE_MISSING` must **reduce** the allocatable base,
> not concentrate it. This is the NEW-12-R invariant: **no returned asset's AVCO may exceed its
> own execution unit price.**

### D4a. Execution coverage is mandatory, not optional

The per-asset split is only sound when `EXECUTION_SPOT` covers the session window. Ingestion MUST
backfill `/v5/execution/list?category=spot` across the **full** bot-active span (first to last
`FUNDING_HISTORY bybitType="Bot"` row per uid), not merely a recent window. When a session window
has no fills, D4 applies (bounded `EVIDENCE_MISSING`) — the system must **surface** the gap, never
paper over it with net-consumed concentration.

### D5. Bot legs stay off guarded continuity queues (conservation by construction)

`BOT_TRANSFER` rows keep `correlationId = null` and `continuityCandidate = false` and remain
`EXTERNAL_TRANSFER_IN/OUT` on the standalone path. They therefore never enqueue a `CARRY_OUT`
on any guarded queue (`corr-family:`, `bridge:`, `bridge-settlement:`, `bybit-earn-carry:`), so
`CorridorBasisConservationGuard` **cannot** fire on them. This is the explicit anti-Phase-1
invariant: **no bot leg may ever be made a continuity candidate or given a correlation id.**

The compartment is materialised only as an `accountRef`/`walletRef` suffix `BYBIT:<uid>:BOT`,
which collapses to the `BYBIT:<uid>` umbrella position (same as `:FUND`/`:UTA`/`:EARN`). It adds
audit visibility, not a new replay position or conservation surface.

## Consequences

- Multi-asset bot sessions get deterministic, execId-anchored basis; the ~$180 of fabricated
  ETH/BTC FMV for member `516601508` is eliminated and ETH+BTC together consume the real
  consideration.
- The single-asset `BOT_LEDGER` path (member `421325298`, DOGE) is preserved exactly.
- Replay conservation stays green with no use of `isOutOfScopeCarry` /
  `isCrossAssetCorridorSwap`; those sanctioned carve-outs remain available only if a future
  variant deliberately routes bot legs through a guarded queue.
- A full-window `EXECUTION_SPOT` API re-backfill (D4a) is **required** before renormalization:
  the *total* comes from FUNDING_HISTORY, but the *per-asset split* needs execution coverage of
  every return window. Returns outside the ingested execution window (e.g. member `516601508`'s
  Oct-2025 / Dec-2025 returns vs the Nov-only ingest) otherwise fall to D4 `EVIDENCE_MISSING` at
  best, or (pre-NEW-12-R) inflated single-asset concentration at worst.

## Scope limitations & open questions

- **Total basis (net vs gross):** default uses `netConsumed`; owner may elect `stableOut` gross.
- **Unpriced asset in a multi-crypto session:** default flags it `EVIDENCE_MISSING` and splits
  `netConsumed` across the priced assets (D4). Any alternative split is still non-FMV but a
  chosen policy.
- **PriceSource:** reuses `BOT_LEDGER`; a distinct `BOT_EXECUTION` value is optional reporting
  polish only.

## Related

- [ADR-031 AVCO undefined representation](ADR-031-avco-undefined-representation.md)
- [ADR-013 CEX cross-system linking](ADR-013-cex-cross-system-linking.md) /
  [ADR-049 venue-agnostic CEX transfer linking](ADR-049-venue-agnostic-cex-transfer-linking.md)
- `CorridorBasisConservationGuard` (RC-9 D3, HARD_FAIL invariant)
- Implementation plan: [`docs/tasks/bybit-bot-execution-basis-phase2-implementation-plan.md`](../tasks/bybit-bot-execution-basis-phase2-implementation-plan.md)
