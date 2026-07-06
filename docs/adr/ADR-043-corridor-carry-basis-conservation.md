# ADR-043 — Corridor Carry Basis Conservation (continuation of ADR-041)

**Date**: 2026-07-01
**Status**: Accepted
**Related**: ADR-019 (corridor carry policy), ADR-029 (deterministic CEX corridor basis continuity), ADR-030 (replay accumulator idempotency), ADR-040 (dual Net/Tax cost basis), ADR-041 (Bybit Earn corridor pairing and `:FUND` carry symmetry — **extended by this ADR**), ADR-042 (honor `flow.accountRef` in replay position resolution — *basis symmetry is keyed on the paired carry value, not the drained sub-position; the ADR-042 target redirect is basis-neutral by construction*)
**Implements**: `docs/tasks/avco-corridor-basis-conservation-implementation-plan.md` (RC-0, RC-A, RC-B, RC-D, Task 5 guard extensions)

---

## Context

ADR-041 established that a Bybit intra-account custody round-trip (`FUND ↔ UTA ↔ EARN`) must be
**quantity-conserving** on a single position key and that an Earn principal leg may be excluded from
accounting only if its paired leg is also excluded. Validation against the Bybit "Avg. Cost"
screenshot (replay #10) proved that the **quantity** half held for most assets but the **basis** half
still diverged 5–9× for LINK/LDO/ONDO, LTC quantity was short, and the corridor was still leaking
basis. An independent single-pool AVCO reconstruction from raw `bybit_extracted_events` reproduced the
Bybit screenshot to the coin; the DB differed **only** by the internal-transfer/earn-corridor plumbing.
The residual is therefore a **basis-conservation defect in corridor carry (replay)**, enabled by an
upstream **corridor-pairing window defect (linking)**:

- **RC-0 (linking, upstream).** `BybitEarnPrincipalTransferPairer.MAX_PAIR_DRIFT` was 30 minutes, but
  real Flexible-Savings cycles span 18 h → weeks → a month. `withinDrift()` broke the FIFO match, so
  most cycles never paired and the redeem leg was left asymmetric. Separately, the collapser could
  exclude one leg of a cycle while the pairer only re-correlates non-excluded legs (one-sided
  exclusion), so the inbound EARN leg never materialised for the pairer to correlate.
- **RC-A (replay/cost_basis).** When no paired carry was available the AVCO-re-derivation fallbacks
  (`ContinuityCarryService.syntheticBybitEarnProductCarry`, `resolveEarnPrincipalFallbackAvco`,
  `applyEarnPrincipalLotCarryOverride`) fired **unconditionally** and injected `$0`-cost quantity on
  the IN leg when `avco==null` — diluting AVCO 5–9× and leaking the net lane equally.
- **RC-B (linking→replay, LTC).** LTC's inbound EARN leg was one-sidedly excluded, so its basis landed
  on `:EARN` with `quantityDelta=0` (a `$41.54` ghost) while the quantity sat on the umbrella.
- **RC-D (pricing, DOGE).** A pre-coverage bot-lot inflow was priced from an out-of-range
  `historical_prices` bucket instead of a bounded nearest-valid / event-date market price.

---

## Invariant

Two precise statements — **not** a single-position-key restatement of ADR-041:

> **(a) Per-transfer.** For one subscribe/redeem transfer, `Σ costBasisDelta(OUT + IN) = 0` **and**
> `Σ netCostBasisDelta(OUT + IN) = 0` across the source + destination keys. The paired carry value
> that leaves the OUT leg is the **sole authoritative basis** restored to the matched IN leg, in both
> the tax and net lanes.
>
> **(b) Per-family.** For the Bybit umbrella plus all its subs, `Σ` over all internal
> `INTERNAL_TRANSFER + EARN_*` legs `= 0` in both lanes (±dust). Both legs of an *open* subscribe are
> internal and offset within the family sum, so `Σ = 0` holds even for unredeemed positions **provided
> every OUT has a materialized IN** (RC-B).
>
> A redeem never injects `$0`-cost quantity: the matched IN leg restores exactly the paired-carry
> basis. Accrued interest (`redeem_qty − subscribe_qty > tol`) is out of scope for the matched replay
> branch (see D2, *Interest*) and must be handled upstream when it appears as a distinct credit.

---

## Decisions

### D1 — RC-0: pairing window = the earn-holding horizon, with symmetric exclusion (LINKING)

- `BybitEarnPrincipalTransferPairer` drops the 30-minute `MAX_PAIR_DRIFT` and pairs on the earn-holding
  horizon. FIFO equal-principal matching keyed on `{uid, family, |qty|, redeem-follows-subscribe-in-time}`
  is authoritative; a wide drift ceiling only rejects implausibly stale opens, it no longer breaks
  real multi-week cycles.
- **Co-event sibling pre-pass (amendment, post replay #11 — see below).** Before the cross-event
  subscribe→redeem FIFO, the pairer deterministically pairs the **two legs of one event** (co-event
  siblings) on `{uid, family, |qty| within QTY_TOLERANCE, |Δt| ≤ CO_EVENT_MAX_SKEW (5 s), opposite
  earn/non-earn}`. Bybit emits both legs of a subscribe (or redeem) at the same `blockTimestamp`, so
  pairing them first guarantees **both legs of one event share ONE correlationId** and land in the same
  replay queue (`corr-family:<corrId>:<asset>`). This is what lets an OPEN subscribe credit its `:EARN`
  inbound from its own paired `:FUND`-out carry.
- **Equal-principal (not partial) cross-event FIFO.** The subscribe→redeem hold FIFO that runs on any
  legs left unpaired by the co-event pass uses **equal-principal** matching (`|subQty − redQty| ≤ tol`),
  never partial `min` matching. A `−qty` leg may never consume an open lot whose `|qty|` differs beyond
  tolerance, so an unequal cross-cycle pair is **rejected** (it surfaces as a genuinely-unpaired
  boundary the D4 guard flags) rather than mis-attributed.
- **Paired-exclusion symmetry.** The suppression pass (`suppressCorridorDepositStakeCycles` /
  `BybitStreamAuthorityCollapser` / `bybit-collapsed-v1`) must exclude **both** legs of a cycle or
  **neither**. The inbound EARN leg therefore always exists as a non-excluded normalized tx that the
  pairer can correlate. This unblocks RC-B (LTC).

### D2 — RC-A: paired carry is the sole authoritative basis source (REPLAY)

- **Single seam:** the paired-carry consumption in `TransferReplayHandler`. The queued paired carry
  (`drainCarrySlice` OUT basis → pending queue → IN restore) is the **only** authoritative basis for a
  matched internal leg.
- The AVCO-re-derivation fallbacks (`syntheticBybitEarnProductCarry`, `resolveEarnPrincipalFallbackAvco`,
  `applyEarnPrincipalLotCarryOverride`) are **demoted** to fire only when the queue proves **no** paired
  carry exists (open/unredeemed position or a genuine unpaired boundary). Never inject `$0`-cost
  quantity on a redeem.
- **Net lane:** the matched-carry path routes through the 8-arg `CarryTransfer` constructor so
  `Σ(netCostBasisDelta) = 0` is enforced **independently** and is never inferred by collapsing
  `netCostBasisUsd` into `costBasisUsd`.
- **Interest (NOW IMPLEMENTED in the RC-0 owner — see Amendment A2):** earn-principal legs are *not* corridor transfers, so
  the matched IN leg is resolved by the deterministic quantity-strict matcher
  (`ReplayPendingTransferMatcher.findUniqueCompatibleQueueIndex`, ±0.0001). A redeem whose quantity
  exceeds the subscribed principal by more than that tolerance therefore never reaches the matched
  carry branch, so the excess cannot be booked as a priced `REWARD_CLAIM` inside the replay seam.
  Booking it correctly requires either widening that **shared** bridge/corridor matcher (out of scope —
  blast radius to the protected RC-9 bridge path) or splitting the redeem into a paired principal leg
  (= subscribe qty) plus an interest credit in the pairer (LINKING, RC-0 owner). In practice Bybit
  credits Flexible-Savings interest as a separate row and the reward quantity is < 0.5% of principal,
  so NET ≈ TAX; the split is deferred to the RC-0/linking owner rather than over-reaching the shared
  matcher here.
- **FUND-drain symmetry:** the `:FUND` outbound drain is symmetric for **all** `FUND ↔ UTA` legs, not
  only earn-principal context, preventing the umbrella-phantom class (B-REG-01) from reappearing on
  non-earn legs. ETH is already 0 post-ADR-042 and must not regress.
- **Scope guard:** the change is restricted to the earn/internal carry constructors. It must **not**
  touch `bridgeInboundCarry` / `bridgeSettlementInboundCarry` / pass-through reserves — those carry the
  RC-9 ETH corridor (AMANWETH 3.06 @ ~$2936, correct).
- **Idempotency:** `applyAuthoritativeLateInboundCarryBasis` stays single-application (subtract the
  provisional, add the authoritative) per ADR-030.

### D3 — RC-B: materialize the paired inbound leg, never silently drop inventory (LINKING → REPLAY)

- With D1's exclusion symmetry, LTC's inbound EARN leg exists and the pairer correlates it. A genuinely
  unpaired boundary leg raises a conservation flag (D4) rather than silently dropping inventory.
- **Materialize-then-refine (amendment, post replay #11).** `TransferReplayHandler.enqueuePendingInbound`
  keeps the HEAD "materialize-then-refine" shape for a paired earn-principal inbound: it calls
  `materializePendingInbound(… permitUncovered=false)` FIRST — conserving the covered quantity at market
  when priceable — and only enqueues a deferred **zero-quantity** pending inbound when materialization
  genuinely cannot price the leg (unpriced boundary). `attachLateCarryToPendingInbound` then only
  **REFINES** the basis from the authoritative paired carry (`applyAuthoritativeLateInboundCarryBasis`,
  resolving against `destination.uncoveredQuantity()`); it must **not** re-add `pendingInbound.quantity()`
  (that would double-credit). Replay #11 had instead deferred zero **unconditionally** and relied on a
  paired `CARRY_OUT` that — post the RC-0 co-event mis-pairing — never arrived, dropping the
  OPEN-subscribe `:EARN` inbound and collapsing held quantity (LINK 17.14 → 0.043). The RC-A basis
  demotion (D2) is unchanged: covered basis stays authoritative (no `$0`-cost dilution). This seam
  touches ONLY the earn/internal path — never `bridgeInboundCarry` / `bridgeSettlementInboundCarry` /
  family-custody (protects RC-9).

### D4 — Conservation guards (extend the existing guards; do not create new ones)

- **`CorridorBasisConservationGuard`:** add the Bybit earn-principal / venue-internal queue prefixes to
  `GUARDED_QUEUE_PREFIXES`. A leftover OUT carry with no matched IN is a `CORRIDOR_BASIS_IMBALANCE`,
  detected by the end-of-replay sweep with the shared `$1` dust epsilon and the out-of-scope carve-out.
  There is **no in-replay throw** — open positions legitimately park basis mid-walk. WARN-first; a
  one-line severity flip to `HARD_FAIL` is possible after a clean rebuild.
- **`BybitEarnSubPoolConservationGuard`:** keeps the combined-total vs `bybit_live_balances`
  reconciliation and the `qtyΔ=0 / costBasisΔ>0` ghost check; **soft** for deferred MNT/USDT. LINK/LDO/
  ONDO/LTC controls are wired into its test set.
- **Per-family quantity invariant (amendment, post replay #11 — `CORRIDOR_QTY_IMBALANCE`).**
  `BybitEarnSubPoolConservationGuard` now ALSO asserts Invariant (b)'s quantity half: the signed
  `Σ quantityDelta` over every intra-Bybit internal custody leg (`INTERNAL_TRANSFER`,
  `EARN_FLEXIBLE_SAVING`, `LENDING_DEPOSIT`, `LENDING_WITHDRAW`), grouped by `uid|symbol`, must net to
  **zero** (±dust) — including for OPEN positions, whose subscribe OUT + IN are both internal and
  offset. A non-zero sum means an internal leg's counterpart vanished (e.g. a queue-key divergence
  dropped the paired inbound) and raises `CORRIDOR_QTY_IMBALANCE` (WARN-first) instead of silently
  destroying inventory. Cross-venue `EXTERNAL_TRANSFER_*` / `BRIDGE_*` legs are excluded (their
  counterpart lives outside the family). This closes the gap that hid the replay-#11 regression:
  quantity conservation was previously unguarded.

### D5 — RC-D: bound the pre-coverage bot-lot price (PRICING → REPLAY market authority)

- A pre-coverage inflow (before the asset's first `historical_prices` bucket) resolves to a bounded
  nearest-valid-bucket price (`PriceExternalSourceOrchestrator.resolveBoundedNearestBucket`, window
  `PRE_COVERAGE_NEAREST_WINDOW = 400 d`) instead of an out-of-range value, so a normal pre-coverage
  ACQUIRE lot basis ≈ `qty × market(nearest valid bucket)`. RC-D is isolated (DOGE has no corridor).
- **Bot-lot landing (amendment, post replay #11).** The DOGE `150.591 @ $0.5766/unit` lot does **not**
  originate from an out-of-range `historical_prices` bucket — it is derived by
  `BybitBotTransferCostBasisService` at **normalization** time from net stablecoin consumed
  (`BOT_LEDGER`) and marked CONFIRMED, so it never enters the pricing orchestrator where the bounded
  fallback lives. On a clean rebuild (`--clear-pricing-cache`) `historical_prices` is also empty during
  normalization, so a clamp injected into the bot service could never fire. The clamp therefore lands
  at the **replay market authority** (`ReplayMarketAuthority.resolve`), where `historical_prices` is
  fully populated: a `BOT_LEDGER` flow whose event predates the asset's first cached bucket is clamped
  to the nearest valid bucket via the new pre-coverage-only projection
  (`resolvePreCoverageNearestBucket` → `HistoricalPriceCacheService.findPreCoverageNearestQuote`,
  reusing the same 400-day window). The clamp is narrowly gated (`priceSource == BOT_LEDGER` **and**
  genuinely pre-coverage), so in-coverage bot lots keep their stablecoin-derived price and no other
  flow's valuation is affected. DOGE `150.591 × 0.23246 ≈ $35.0` replaces the out-of-range `$86.833`.

---

## Amendment (post replay #12) — four seam-precise fixes

Replay #12 + an independent single-pool reconstruction confirmed the RC-0/RC-A/RC-B/RC-D *shapes*
were right but four seams still leaked. All four are implemented in their own seam without touching
the protected bridge / pass-through / family-custody carry constructors or the shared bridge matcher.

### A1 — RC-D clamp is invoked from `applyBuy` (was dead for normally-priced acquires)

The RC-D clamp (D5) lived in `ReplayMarketAuthority.resolve()` but was **never reached** for the DOGE
bot lot: `GenericFlowReplayEngine.applyBuy(transaction, flow, position)` short-circuits on
`hasKnownPrice(flow)` (TRUE for a `BOT_LEDGER` lot, whose unit price is stamped at normalization), so
it booked `qty × flow.unitPriceUsd` directly and bypassed the authority entirely — only pending-inbound
materialization consulted `resolve()`. **Fix:** in `applyBuy`, *before* the `hasKnownPrice`
short-circuit, when `flow.getPriceSource() == BOT_LEDGER && transaction != null &&
replayMarketAuthority != null`, route through `replayMarketAuthority.resolve(transaction, flow)` and
book `qty × resolvedUnitPrice`. `resolve()` already clamps a genuinely pre-coverage bot lot to the
nearest valid bucket and returns the FLOW price unchanged for in-coverage bot lots. Blast radius =
`BOT_LEDGER` flows only (ETH/others keep the `hasKnownPrice` short-circuit). DOGE 2025-01-31 lot
`150.591 @ $0.5766 → 150.591 × $0.23246 ≈ $35.0`; the 2025-10-10 in-coverage spot lot
(`priceSource = EXECUTION`) is untouched. Blended DOGE ≈ $0.174.

### A2 — RC-0 interest clause BUILT: principal-based interest-band pairing + priced `REWARD_CLAIM`

The D2 *Interest* deferral is now implemented at its correct owner (LINKING,
`BybitEarnPrincipalTransferPairer`) rather than the shared replay matcher. Subscribe legs are
quantity-mismatched by accrued interest (LINK evidence `:EARN +12.0986` vs `:FUND −12.0198`, diff
`0.0788`), which broke the strict `|Δqty| ≤ 1e-8` guards in **both** passes (`pairCoEventSiblings`,
`findEqualPrincipalOpenLot`), orphaning the genuine subscribe carry. **Fix:** co-event AND hold-FIFO
matching are now **principal-based with an interest tolerance band** (`INTEREST_BAND_FRACTION = 0.25`).
When the `:EARN` INBOUND (subscribe) leg exceeds the FUND/UTA principal within the band, the pair
matches on the FUND principal (`min(|earnQty|, |fundQty|)`), the `:EARN` principal flow is reduced to
that principal, and the positive `earnQty − fundQty` excess is split into a **priced** `REWARD_CLAIM`
(`role = BUY`) booked on the **same `:EARN` sub-account** — never a `$0`-cost quantity bump, never
booked to `:FUND` (keeps LDO `:FUND` phantom = 0). The reward inherits the earn leg's market price
when present, else routes to the pricing stage (`PriceableFlowPolicy.statusAfterContinuityRetag`); its
id is deterministic (`<earnId>:earn-interest-reward`) so re-runs upsert idempotently. The
equal-principal protection (the #11 fix) is preserved: a redeem (earn OUTBOUND) must match the
principal exactly, and any earn/principal gap **beyond** the band is rejected (e.g. `17.1006` vs
`12.0986` ≈ 41% is not interest). Result: the real subscribe carry pairs with its redeem → basis
conserved → LINK $14.79 / LDO $0.767 / ONDO $0.666, and the `:EARN` pool nets to zero over
subscribe + reward + redeem.

### A3 — RC-B late-attach materializes an UNMATERIALIZED deferred pending inbound

D3's materialize-then-refine dropped LTC's `:EARN` quantity: the paired `:EARN` inbound (corr `4f14`)
is UNPRICED and its `blockTimestamp` precedes its `:FUND` outbound, so `enqueuePendingInbound`
materialization missed (`resolve()` empty, `permitUncovered=false`) → the `Optional.empty()` **defer**
branch queued a zero-quantity pending inbound whose `0.75` was never on the position; the refine-only
`attachLateCarryToPendingInbound` then deliberately did not re-add the quantity → `$41.54` ghost with
qty 0. **Fix:** `CarryTransfer` gains a `materialized` discriminator; the defer branch queues a
`pendingInboundUnmaterialized(...)` (`materialized = false`). In `attachLateCarryToPendingInbound`,
when the pending inbound is **unmaterialized**, the authoritative paired carry is **MATERIALIZED** onto
the destination (`restoreToPosition(effectiveCarry.quantity(), dest, peg-floored cost, netCost,
uncovered, avco)`); when it is **materialized** (LINK/LDO/ONDO), the existing refine-only path is kept
(double-credit guard preserved). This is conservation-safe — the quantity was already drained from the
paired FUND/UTA source and the branch is gated on `isBybitEarnPrincipalPaired`, so it cannot resurrect
the ETH inbound-only phantom (B-REG-01). Restores LTC `:EARN` to `0.75 @ $41.54`.

### A4 — `CORRIDOR_QTY_IMBALANCE` scoped to the session/master, not the sub-UID

The D4 per-family quantity invariant keyed `internalQtyDelta` by the per-sub-account UID
(`extractUidFromWallet`), so a cross-sub internal reallocation (e.g. `BYBIT:33625378 ↔
BYBIT:421325298`, distinct subs of one master) looked one-sided on each side and raised a false
`CORRIDOR_QTY_IMBALANCE` (DOGE `+150.591 / −150.591` nets to 0 across subs). **Fix:**
`BybitEarnSubPoolConservationGuard.buildLedgerSnapshot` now keys `internalQtyDelta` by the
SESSION/MASTER `accountingUniverseId` (all `BYBIT:*` subs of one master net together), falling back to
the sub-UID only when `accountingUniverseId` is absent (older points / unit fixtures), preserving
single-sub conservation. A genuine one-sided loss within the master still trips. Guard stays WARN (not
HARD_FAIL) for this cycle; MNT/USDT/USDC/TON remain on the RC-C soft/OOS carve-out.

---

## Amendment (post replay #13b) — interest-band reversal, DI wiring, and two matcher-blocked fixes

Replay #13b + an independent financial-logic audit showed A2's interest-band *synthesis* was
**net-harmful** and that A1's RC-D clamp was **dead code in production**. Two of the three residual
defects are fixed in their own seam; the other two are **blocked** by the protected shared
quantity-strict matcher and are stopped-and-reported here rather than shipped through it.

### A5 — A2 interest-band synthesis is REMOVED (equal-principal matching only) — supersedes A2

A2's `INTEREST_BAND_FRACTION = 0.25` band **mis-classified genuine principal as accrued reward**. The
`earnQty > fundQty` gap at a subscribe is a **consolidation-netting artifact** — Bybit re-subscribes a
rolled balance that already absorbed capitalized interest — **not** a second interest stream. Bybit
already pays Flexible-Savings interest as explicit daily `REWARD_CLAIM` legs (`FUNDING_HISTORY`, e.g.
LDO ~0.0061/day), so the synthesized reward double-counted income while stealing real principal and
pricing it below-pool (LDO got *worse*, not better). Evidence: exactly three `:earn-interest-reward`
rows existed and their quantities equalled the guard imbalance 1:1 (LDO 9.1235, ONDO 12.37768, LINK
0.0788). **Fix (`BybitEarnPrincipalTransferPairer`, LINKING seam):** `INTEREST_BAND_FRACTION` and the
`splitEarnInterestRewardIfPresent` / `buildEarnInterestReward` / `matchPrincipalWithInterestBand`
synthesis are deleted. Both passes (`pairCoEventSiblings`, `findEqualPrincipalOpenLot`) now match on
**equal principal only** (`matchesEqualPrincipal`, `|Δqty| ≤ QTY_TOLERANCE`); any gap is rejected (the
replay-#11 equal-principal protection is preserved). No synthetic `REWARD_CLAIM` is ever written, so
the `AccountingAssetIdentitySupport.replayPositionWalletAddress` XPL `:EARN`→umbrella strip is moot for
this path. Interest continues to arrive solely via the exchange's real daily reward legs.

### A6 — RC-D clamp reactivated: `GenericFlowReplayEngine` DI defect fixed

A1's clamp never fired because `replayMarketAuthority` was **`null` in the production bean**.
`GenericFlowReplayEngine` had **two constructors** — a no-arg `GenericFlowReplayEngine()` and
`GenericFlowReplayEngine(ReplayMarketAuthority)` with `@Autowired` on the **parameter**. Spring only
promotes a constructor for autowiring when the annotation is at **CONSTRUCTOR level**; with a competing
no-arg constructor and no constructor-level `@Autowired`, Spring silently selected the no-arg one and
left the authority null. That made the entire `BOT_LEDGER` clamp branch (A1) **and** the `resolve()`
paths in `materializePendingInbound` / `applyInboundShortfallSpotFallback` dead — DOGE never clamped
across three attempts. **Fix:** the no-arg constructor is removed and the DI constructor is annotated
at CONSTRUCTOR level with `@org.springframework.beans.factory.annotation.Autowired`; tests construct
with an explicit authority (or `null`). No change to `ReplayMarketAuthority` /
`PriceExternalSourceOrchestrator` / keys. A structural guard test
(`beanExposesExactlyOneAutowiredConstructorTakingMarketAuthority`) asserts exactly one
`@Autowired` constructor taking `ReplayMarketAuthority`, so the no-arg footgun cannot reappear.
Blast-radius controls are asserted at the engine level: `applyBuyDoesNotRouteNonBotLedgerLotThroughAuthority`
(only `BOT_LEDGER` routes through the authority), `inboundShortfallPrefersFlowPriceOverMarketAuthority`
and `inboundShortfallDoesNotTouchCoveredCrossAssetUsdcCarry` (the newly-live `resolve()` backstop fires
only on genuinely uncovered/unpriced quantity, leaving priced/covered legs — the XRP/LDO `:FUND`
phantom-0 and RC-9 controls — untouched). **The full-integration protected-control replay (ETH umbrella
≈ 0, RC-9 AMANWETH ≈ $2828, XRP/LDO `:FUND` phantom 0) is verified end-to-end by the parent's replay
migration with the authority live, not in the unit suite (which wires a `null` authority in the big
`AvcoReplayServiceTest` harness).**

### A7 — BLOCKED (stopped, not shipped): running-balance corridor match and UTA→EARN cross-type funding

Two audit fixes cannot be implemented in their pairer seam **without modifying the protected shared
matcher**, so per the change contract they are stopped and reported here:

- **Running-balance corridor match (would recover the zero-basis `:EARN` inbound, e.g. LDO
  `122.3636 @ $0`).** Pairing a netted-consolidation `:EARN` subscribe against **many smaller** FUND/UTA
  legs is a fan-in of **unequal** quantities. Earn-principal correlations are consumed by
  `ReplayPendingTransferMatcher.findUniqueCompatibleQueueIndex` (`TransferReplayHandler` L250/L274),
  which requires the inbound and outbound carry quantities to be **equal within
  `MAX_TOLERANCE = 0.0001`**. A running-balance pairing in the pairer would emit correlated carries the
  matcher rejects; worse, setting the `bybit-earn-principal-v1:` correlation makes
  `isBybitEarnPrincipalPaired` true, which **suppresses** the materialization/backfill fallbacks
  (`TransferReplayHandler` L1426/L1472/L1751) — so the inbound would neither pair **nor** materialize
  (net-harmful vs. leaving it unpaired). Making it pair requires widening the **shared** quantity-strict
  matcher (the protected bridge/corridor matcher) — out of scope.
- **UTA→EARN `INTERNAL_TRANSFER` funding (LTC second subscribe, `:EARN +0.51096338` funded by
  `:UTA INTERNAL_TRANSFER −0.4995`).** The principal (`0.4995`) and the `:EARN` destination (`0.51096`)
  differ by the `0.01146` interest, so even after adding `INTERNAL_TRANSFER` to the pairer candidate
  query, the correlated carries (`0.51096` vs `0.4995`) are `0.01146 ≫ 0.0001` apart and the same shared
  matcher rejects them — with the same `isBybitEarnPrincipalPaired`-suppresses-fallback footgun.

**Mitigation already in place:** A6's DI fix revives `materializePendingInbound` /
`applyInboundShortfallSpotFallback`, so an **unpaired** `:EARN` inbound is now materialized at its
market-at-timestamp price (recovering quantity and approximate basis) instead of entering the pool at
`$0` / being dropped. The exact FUND-pool-basis carry these two fixes targeted still requires the
shared-matcher change and is deferred to an owner with authority over that matcher.

---

## Consequences

- LINK/LDO/ONDO/LTC/DOGE blended AVCO reconciles to the Bybit screenshot in **both** the Net and Tax
  lanes; LTC quantity = 1.26107 (`:EARN` 0.75 + umbrella 0.511); the `$41.54` `:EARN` ghost clears.
- Corridor carry no longer dilutes AVCO with `$0`-cost quantity on a matched redeem; accrued interest
  as a distinct credit remains a deferred upstream (RC-0/linking) split (see D2, *Interest*).
- The RC-9 ETH corridor (AMANWETH 3.06) and the ADR-042 ETH-family residual clears are preserved —
  the matched-carry seam is disjoint from the bridge/pass-through constructors, and the FUND-drain
  symmetry keys on `accountRef` + inventory, never on counterparty/type/`correlationId`.
- MNT/USDT remain **deferred (RC-C)**: this ADR stops the corridor source from growing the phantom but
  does not model cross-venue de-dup or crypto-loan liabilities. Their quantity stays wrong (only
  non-increasing) until a separate workstream.
- Requires a full replay migration to realise the corrected pools (run separately from this change).

---

## Amendment — Cycle/9 S4 superseded: cross-sub-account ETH-family staking fusion (2026-07-05)

**Status:** Accepted (implemented)

### Context

Bybit cross-sub-account ETH-family liquid-staking conversions (e.g. `FUND METH` debit ↔ `EARN CMETH`
credit) land on **two Bybit sub-accounts**. The original Cycle/9 S4 approach emitted each leg as a
separate `INTERNAL_TRANSFER` under a shared correlationId, expecting `FamilyEquivalentCustodyReplayHandler`
to carry basis across the sub-account boundary. This failed because:

1. The continuity bucket used by the family-equivalent handler is **keyed by raw sub-account
   `walletAddress`** — `BYBIT:uid:FUND` and `BYBIT:uid:EARN` are different keys; the two legs never shared
   a bucket.
2. The source basis stranded: a phantom same-asset EARN credit appeared with no real carry.

### Decision (D-S4)

**Fuse** cross-sub-account ETH-family liquid-staking pairs into a single two-flow `STAKING_DEPOSIT`,
booked on the UID umbrella (`BYBIT:<uid>`), using `buildCrossSubAccountStakingPair`:

- The anchor is the **debit (outflow) leg** — deterministic, independent of MongoDB `_id` ordering
  (ADR-041).
- Both flows use the umbrella wallet address so `LiquidStakingReplayHandler` — the same handler used for
  same-sub-account `ETH→METH` conversions — drains source family basis into the received liquid-staking
  token through the standard path.
- `sameBybitSubAccount` branch preserved: if both legs share a sub-account (existing behaviour), the
  original `buildStakingPair` is used unchanged.

### D-S4 implementation

`BybitCanonicalTransactionBuilder.buildCrossSubAccountStakingPair(debit, credit, now)` — called from
`BybitNormalizationService` for both the legacy-raw and extracted-event paths when
`sameBybitSubAccount(row, pair)` is `false` and a liquid-staking counter-leg is found.

### Consequences

- Cross-sub-account ETH-family staking conversions (METH/CMETH/similar) now carry basis correctly via
  `LiquidStakingReplayHandler`, matching the same-sub-account control.
- The old `applyCrossSubAccountStakingLinkage` helper and the INTERNAL_TRANSFER cross-sub-account path
  are deleted.
- Requires `--skip-frontend` (full renormalization) to activate.

---

## Open questions

- **Q1 (guard severity):** `CorridorBasisConservationGuard` `WARN → HARD_FAIL` staged rollout, flipped
  only after a clean full rebuild shows zero corridor orphans.
- **Q2 (RC-C):** full MNT/USDT reconstruction (cross-venue de-dup + BORROW/REPAY liability modeling +
  grid-bot `DISPOSE`-at-`$0`-basis) is a separate ADR/workstream.
