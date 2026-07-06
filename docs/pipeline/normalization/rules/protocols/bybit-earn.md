# Bybit Earn / Flexible-Savings / Launchpool Corridor Rules

Status: Active protocol rule slice

## Scope

Owns the intra-account custody corridor between Bybit sub-pools (`:FUND`, `:EARN`, `:UTA`) for Earn
products: Flexible-Savings, Launchpool, and staking-style subscribe ↔ redeem lifecycles. This is a
custody reallocation **inside one Bybit umbrella account** — never an acquisition or disposal.

It does not redefine `SWAP`, `REWARD_CLAIM`, `BORROW`, or `REPAY` semantics, and it does not own the
`Bybit → on-chain` withdraw/deposit continuity boundary (see `bybit.md`). It owns only the
subscribe ↔ redeem pairing and the `FUND ↔ UTA` / `FUND ↔ EARN` carry-symmetry contract.

## Runtime Ownership

- Earn classification: `BybitCanonicalTransactionBuilder.resolveEarnLifecycleCanonicalType`
- Principal pairing: `BybitEarnPrincipalTransferPairer`
- Replay carry routing: `TransferReplayHandler.resolveCarrySourcePosition`,
  `AccountingAssetIdentitySupport.replayPositionWalletAddress`
- Conservation guard: `BybitEarnSubPoolConservationGuard`

## Authoritative Evidence

- `bybit_extracted_events` (raw subscribe/redeem legs; `EARN_FLEXIBLE_SAVING`, `FUNDING_HISTORY`,
  `TRANSACTION_LOG`)
- `normalized_transactions`
- `bybit_live_balances.{fundQty, earnQty}` (per sub-pool reconciliation truth)

## Classification Rules

### Subscription is symmetric with redemption

- A **principal** Flexible-Savings subscription is canonical `EARN_FLEXIBLE_SAVING`, the same custody
  type as redemption (direction by flow sign). It must not fall through to `INTERNAL_TRANSFER`.
- Match **principal** rows only. Auto-compounded interest / yield rows stay `REWARD_CLAIM` and must not
  be swallowed by principal pairing.
- Launchpool and staking-style (`STAKING_DEPOSIT`, `LENDING_DEPOSIT`, `VAULT_DEPOSIT`) principal legs
  share the same custody semantics: principal leaves `:FUND` into the product/receipt.

### Exclusion symmetry (no one-sided exclusion)

- An Earn principal leg may be marked `excludedFromAccounting=true` **only if its paired leg is also
  excluded**. One-sided exclusion (subscribe demoted-and-excluded while redeem stays booked) is a defect:
  it re-adds the principal to `:FUND` on every cycle.

## Pairing Rules

### Running-balance (FIFO) principal model

- Corridor key: `{universe, uid, asset, earnProduct}`, modelled as a **running principal balance** —
  subscribe increments, redeem decrements, redeem consumes the earliest open principal (FIFO /
  time-ordered).
- Bybit allows `1 subscribe : N partial redeems` and open positions, so equal-`principalQty` keying is
  insufficient; `principalQty` equality is retained only as a closed-cycle assertion.
- The correlation key is derived solely from `{uid, family, |qty|, blockTimestamp.epochSecond}` —
  **never** from Mongo `_id` or import order, so rebuild and incremental refresh produce bit-identical
  keys.

### Pairing window = the earn-holding horizon (ADR-043, RC-0)

- Real Flexible-Savings cycles span **18 h → weeks → a month** (see `results/blockers.md` L523–536). A
  30-minute drift ceiling broke the FIFO match and left most cycles unpaired, feeding the corridor
  basis leak. The pairing window is the **earn-holding horizon**: FIFO equal-principal matching keyed
  `{uid, family, |qty|, redeem-follows-subscribe-in-time}` is authoritative; a wide drift ceiling only
  rejects implausibly stale opens, it must never break a multi-week closed cycle.
- **Acceptance:** every closed cycle pairs (`redeem_qty = subscribe_qty ± tol`); no one-sided-excluded
  leg survives.

### Co-event sibling pre-pass, then equal-principal FIFO (ADR-043, RC-0 amendment)

`BybitEarnPrincipalTransferPairer.pairCorridorFifo` runs **two ordered passes**:

1. **Co-event siblings first.** Bybit emits both legs of ONE subscribe (or redeem) at the same
   `blockTimestamp` — one `:EARN` leg and one `:FUND`/`:UTA` leg, opposite sign, equal principal. These
   are paired FIRST on `{uid, family, |qty| ≤ QTY_TOLERANCE, |Δt| ≤ CO_EVENT_MAX_SKEW (5 s), opposite
   earn/non-earn}`, so **both legs of one event share ONE `correlationId`** and land in the same
   `corr-family:<corrId>:<asset>` replay queue. This is what lets an OPEN subscribe credit its `:EARN`
   inbound from its own paired `:FUND`-out carry.
2. **Equal-principal cross-event FIFO** over legs left unpaired by pass 1: subscribe→redeem matched by
   **equal principal** (`|subQty − redQty| ≤ tol`), never partial `min`, keeping the drift ceiling. A
   `−qty` leg may never consume an open lot whose `|qty|` differs beyond tolerance — an unequal
   cross-cycle candidate is **rejected** (it surfaces as a genuinely-unpaired boundary the guard flags).

**Equal-principal only — NO interest band, NO synthetic reward (ADR-043 Amendment A5, supersedes A2).**
Both passes match on **equal principal only** (`matchesEqualPrincipal`, `|Δqty| ≤ QTY_TOLERANCE`); any
gap is rejected. The earlier `INTEREST_BAND_FRACTION = 0.25` band and the
`splitEarnInterestReward` / `buildEarnInterestReward` synthesis are **removed** — they were
net-harmful. The `earnQty > fundQty` gap at a subscribe is a **consolidation-netting artifact** (Bybit
re-subscribes a rolled balance that already absorbed capitalized interest), **not** a second interest
stream; the band stole real principal and priced it below-pool (LDO got *worse*). Bybit pays
Flexible-Savings interest as explicit daily `REWARD_CLAIM` legs (`FUNDING_HISTORY`), so the pairer
**never** synthesizes a reward. A redeem (earn OUTBOUND) still must match the principal exactly, and any
gap beyond tolerance is rejected (e.g. `17.1006` vs `12.0986` ≈ 41% is not principal).
- **Why (replay #11 regression):** a greedy partial-`min` FIFO over the 400-day window paired the two
  legs of one subscribe with legs of two *different* cycles, giving them different `correlationId`s. The
  open subscribe's `:FUND`-out went to `corr-family:…` while its `:EARN`-in went to the unpaired
  `bybit-earn-carry:…` queue; they never matched and the `:EARN` inbound was dropped (LINK 17.14 → 0.043).

### Subscribe ↔ redeem symmetric basis (ADR-043, RC-A)

- A subscribe → redeem cycle is **basis-symmetric**: the redeem IN leg inherits exactly the subscribe
  OUT leg's carry basis (the paired carry is the sole authoritative source), in both the Tax and Net
  lanes. A redeem never injects `$0`-cost quantity. Interest is **not** synthesized by the pairer
  (Amendment A5): it arrives solely as Bybit's own daily `REWARD_CLAIM` (`FUNDING_HISTORY`) legs, so the
  earn-principal pairing never reduces or splits the principal quantity.

> **Matcher-blocked (ADR-043 Amendment A7).** A netted-consolidation subscribe (one large `:EARN`
> inbound funded by many smaller FUND/UTA legs) and a UTA→EARN `INTERNAL_TRANSFER`-funded subscribe both
> require pairing **unequal** carry quantities, which the shared
> `ReplayPendingTransferMatcher.findUniqueCompatibleQueueIndex` (`±0.0001`) rejects. These are **not**
> paired here (a forced correlation would set `isBybitEarnPrincipalPaired` and suppress the
> materialization fallback → net-harmful). Such unpaired `:EARN` inbounds are instead materialized at
> market-at-timestamp by the (now DI-wired, Amendment A6) replay market authority.

## Replay Carry Rules

### Basis follows quantity onto one key

- A principal subscribe moves **basis and quantity together** onto the same position key. Booking basis
  with `quantityDelta=0` onto `:EARN` (stranding it on later dust rewards) is forbidden.

### Materialize-then-refine for a paired earn inbound (ADR-043, RC-B amendment)

- `TransferReplayHandler.enqueuePendingInbound` materialises a paired earn-principal inbound's covered
  quantity + provisional basis at market FIRST (`permitUncovered=false`); the later authoritative paired
  `CARRY_OUT` only **REFINES** the basis in `attachLateCarryToPendingInbound` (via
  `applyAuthoritativeLateInboundCarryBasis`) and never re-adds `pendingInbound.quantity()` (no
  double-credit). A deferred **UNMATERIALIZED** pending inbound is enqueued ONLY when the leg is
  genuinely unpriceable at that instant.
- **Late-attach materializes an unmaterialized deferred inbound (ADR-043 Amendment A3).** `CarryTransfer`
  carries a `materialized` discriminator. When the paired `CARRY_OUT` attaches to an **unmaterialized**
  pending inbound (its quantity was never added — the `Optional.empty()` defer branch, LTC shape), the
  authoritative carry is **MATERIALIZED** onto the destination (`restoreToPosition(carry.quantity(), …)`)
  rather than refined-only; a **materialized** pending inbound (LINK/LDO/ONDO shape) keeps the refine-only
  path (double-credit guard preserved). This restores the LTC `:EARN` `0.75 @ $41.54` that the earlier
  refine-only defer dropped as a qty=0 ghost. Conservation-safe: the quantity was already drained from
  the paired FUND/UTA source and the branch is gated on `isBybitEarnPrincipalPaired`.
- This conserves quantity for the OPEN-subscribe path while keeping the RC-A basis demotion authoritative
  (no `$0`-cost dilution). It replaced the replay-#11 unconditional zero-defer that relied on a paired
  carry which — post the RC-0 mis-pairing — never arrived, dropping the inbound leg.

### `:FUND` carry symmetry (RC-2)

- The inbound and outbound legs of a `FUND ↔ UTA` / `FUND ↔ EARN` round-trip resolve to the **same**
  replay position key and net to zero. A closed subscribe→redeem cycle leaves no phantom and produces no
  `quantityShortfall`.
- The `:FUND` venue-internal drain redirect applies **only** when principal legitimately sits on `:FUND`
  and is leaving into a product/receipt: earn-principal / on-chain-fund corridor correlations, or a
  principal-deposit transaction type (`STAKING_DEPOSIT`, `LENDING_DEPOSIT`, `EARN_FLEXIBLE_SAVING`,
  `VAULT_DEPOSIT`).
- Plain `bybit-collapsed-v1:` `FUND ↔ UTA` `INTERNAL_TRANSFER` round-trips are **excluded** from the
  redirect — both legs net on the umbrella key. Redirecting them to `:FUND` created inbound-only phantom
  pools.

## Conservation Invariant (guard)

`BybitEarnSubPoolConservationGuard`, after replay and before publication:

1. **Round-trip net:** `Σ subscribe_qty − Σ redeem_qty == open_principal ≈ live earnQty`.
2. **Sub-pool reconciliation:** ledger `:FUND` and `:EARN` each reconcile to
   `bybit_live_balances.{fundQty, earnQty}` within tolerance — not merely the umbrella sum.
3. **Exclusion symmetry:** a leg is excluded only if its paired leg is excluded.
4. **Idempotency:** recycling the same principal N times yields net 0, never `+N · principal`.
5. **Per-family quantity conservation (`CORRIDOR_QTY_IMBALANCE`):** the signed `Σ quantityDelta` over
   every intra-Bybit internal custody leg (`INTERNAL_TRANSFER`, `EARN_FLEXIBLE_SAVING`,
   `LENDING_DEPOSIT`, `LENDING_WITHDRAW`), grouped by the **session/master `accountingUniverseId`**
   (ADR-043 Amendment A4; falls back to the sub-UID only when the universe id is absent), must net to
   **zero** (±dust) — even for OPEN positions (subscribe OUT + IN are both internal and offset). Keying
   by the master lets a cross-sub reallocation (`BYBIT:33625378 ↔ BYBIT:421325298`, DOGE
   `+150.591 / −150.591`) net out instead of raising a false one-sided breach, while a genuine one-sided
   loss within the master still trips. A non-zero sum means a paired internal leg's counterpart vanished
   (e.g. a queue-key divergence) and is flagged instead of silently destroying inventory. Cross-venue
   `EXTERNAL_TRANSFER_*` / `BRIDGE_*` legs are excluded.

Rollout: `WARN` first (structured breach log), promote to hard-fail after a clean rebuild shows zero
breaches.

## Disallowed Fallbacks

- do not leave a principal Flexible-Savings subscription as `INTERNAL_TRANSFER`
- do not exclude one leg of a subscribe→redeem pair without excluding the other
- do not inject basis onto `:EARN` with `quantityDelta=0`
- do not redirect plain `FUND ↔ UTA` `INTERNAL_TRANSFER` round-trips to the `:FUND` key
- do not derive the corridor correlation key from Mongo `_id` or import order

## Regression Anchors

- **LINK / LDO / ONDO** OPEN Flexible-Savings: the OPEN subscribe's `:EARN` inbound is credited (both
  legs share one `correlationId`); held quantity is conserved (LINK ≈ 17.14, not 0.043) and
  `Σ internal Δqty = 0` per family (no `CORRIDOR_QTY_IMBALANCE`).
- **MNT** Flexible-Savings: `108.776` subscribed, `5.0` redeemed → `103.776` open = live EARN balance.
- **XRP** (zero Earn activity): `FUND ↔ UTA` round-trips net to `4.0697 ≈ live 4.0533`; `:FUND` must be 0.
- **LTC**: no `:EARN` point with `quantityDelta=0` and `costBasisDelta>0`; combined ≈ live `1.26`.
- **ETH-family**: Bybit ledger quantity reconciles to live ≈ 0; combined `FAMILY:ETH` AVCO ≈ $2.2k.
- Clean controls: **XPL** `3.704`, **USDC** ≈ 0 must not change.
