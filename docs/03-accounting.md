# WalletRadar — Accounting Policy

> **Version:** v3 target
> **Last updated:** 2026-05-29 (staked ETH inclusion, WRAP/UNWRAP AVCO carry, corridor rate rule)
> **Accounting method:** AVCO

**FA-001 (increment 1):** Same-session wallet↔wallet and on-chain↔Bybit paired legs replay as **continuity**
(no phantom realised PnL on those legs). Registry-backed **Hyperlane / LI.FI** endpoints may promote
misclassified inbound bridge receipts to `BRIDGE_IN`. Reversible **`transfer_links`** (full audit contract) —
[ADR-003](adr/ADR-003-transfer-links-fa001.md).

---

## 1. Canonical Accounting Input

WalletRadar computes basis only from canonical documents in:

- `normalized_transactions WHERE status = CONFIRMED`

Canonical documents may originate from:

- `source = ON_CHAIN`
- `source = BYBIT`

Raw collections remain source evidence:

- `raw_transactions`
- `external_ledger_raw`

They are used for reconstruction and audit, but never replayed directly for AVCO.

Rows with `excludedFromAccounting = true` remain persisted for audit and operator
visibility, but are outside the active accounting scope. They never enter pricing
gates or AVCO replay.

Confirmed replay queries must therefore use the active accounting predicate:

```text
status = CONFIRMED
AND excludedFromAccounting != true
```

Replay dispatchers may keep a second defensive guard for excluded rows, but the
primary contract is that excluded rows are not replay input and cannot produce
`asset_ledger_points`.

For session-facing history reads, the active scope is:

- `user_sessions.wallets[].address`
- `user_sessions.integrations[].accountRef`

`user_sessions` is the stable owner scope for:

- move-basis continuity history
- AVCO/cost-basis timeline reads
- custodial refs such as `BYBIT:<uid>`

Dashboard current-holding warnings are read-time diagnostics, not replay-state
fields. They must distinguish:

- `yield_accrual`
- `coverage_gap`
- `history_flags`
- `missing_replay_point`

Economic meaning is derived from backfill-available raw evidence and canonical
flows, not from human-readable explorer page summaries.

Tx-level native `value` participates in accounting only when it comes from
canonical tx-level raw evidence. Token transfer-row amounts must never be
reinterpreted as direct native movement.

---

## 2. Replay Order

Stage preconditions before replay starts:

1. raw backfill complete for the live session scope
2. on-chain normalization complete
3. on-chain clarification complete
4. Bybit normalization complete
5. exact `Bybit <-> on-chain` rematch complete
6. pricing gate green

`move basis` is part of accounting replay itself. It is not a standalone stage
inserted between normalization and pricing.

If replay stops here because the gate is still red, the session stage is
`ACCOUNTING_REPLAY / BLOCKED`, not `COMPLETE`.

Replay must be deterministic:

1. `blockTimestamp ASC`
2. `transactionIndex ASC`
3. `_id ASC` as final tie-breaker

For Bybit canonical rows, `transactionIndex = 0`.

---

## 3. AVCO Rules

### On BUY

```text
newAvco = (currentAvco * currentQty + priceUsd * deltaQty) / (currentQty + deltaQty)
newQty  = currentQty + deltaQty
```

### On SELL

```text
avcoAtSale   = currentAvco
realisedPnl  = (sellPriceUsd - avcoAtSale) * abs(deltaQty)
newQty       = currentQty - abs(deltaQty)
newAvco      = currentAvco
```

### On TRANSFER

- quantity moves
- basis carries forward
- no new acquisition lot is created
- no realised PnL is created

### On Bybit provider convert

- audited Bybit asset-changing convert rows must materialize as canonical
  `SWAP`, not `UNKNOWN_CEX`
- this includes transaction-log families where provider semantics already prove
  convert even if `side = None`, `qty = 0`, or `tradePrice = 0`
- replay must therefore transfer basis from the sold asset into the acquired
  asset before any later same-universe custody withdrawal

### On async LP entry request / settlement

For audited protocol-owned async LP entry families, replay may reserve request
state across a later settlement instead of treating the legs as unrelated
transfers.

Approved audited rule:

- `GMX V2 LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT`

Policy:

- request-side non-native principal outbounds reserve carry for the future share
  token
- request-side native execution-fee reserve is tracked separately from
  principal
- settlement native refund releases that reserve back into the native asset
- settlement `GM` / `GLV` share inflow receives the remaining principal basis
  plus any covered net execution-fee reserve cost consumed by the lifecycle
- uncovered execution-fee reserve must not zero out covered principal
  allocation into the share token

### On `SPONSORED_GAS_IN`

- quantity increases
- `costBasisDeltaUsd = 0`
- canonical flow role stays `TRANSFER`
- the row does not go through market-price acquisition
- replay treats the received native quantity as zero-cost sponsored inventory by
  default

This is intentionally different from generic `EXTERNAL_TRANSFER_IN -> BUY`.
Current raw evidence proves receipt of gas, but not user-paid acquisition cost.

### On FEE

- `FEE` is stored as a separate canonical flow
- fee quantity reduces the fee asset quantity and contributes to `totalGasPaidUsd`
- when policy capitalizes gas into an acquisition, replay may allocate fee USD into the same transaction's BUY basis
- outside such capitalization, `FEE` does not create a new lot and does not create realised PnL for the target asset being acquired or disposed
- standalone venue fees from external integrations, such as Bybit
  `BONUS_RECOLLECT` or funding-account `Repay Interest`, remain canonical
  `FEE` outflows but do not belong to the active basis-opening normalization
  lane
- lending read models expose fees twice for different purposes: native fee
  quantity in `pnlAssetBreakdown.gasByAsset`, and USD fee valuation in
  `pnlBreakdown.gasUsd`. The native quantity map must not be populated with a
  synthetic `USD` asset.

### On PRICE_UNKNOWN

- quantity still updates
- price fields remain null
- `hasIncompleteHistory = true`

---

## Current Holding Diagnostics

Current holding diagnostics are derived from:

- latest exact-bucket replay point
- current on-chain quantity

They are not replay events and must not mutate AVCO state by themselves.

### `yield_accrual`

Use when:

- current quantity is above covered quantity
- latest replay point is clean
- latest replay point belongs to an interest-bearing continuity bucket such as
  `LENDING`, `STAKING`, or `VAULT` with `basisEffect = REALLOCATE_IN`

Interpretation:

- principal basis is intact
- the uncovered tail is passive yield since the last materialized tx

### `coverage_gap`

Use when:

- current quantity is above covered quantity
- the row does not qualify for `yield_accrual`

Interpretation:

- current balance is larger than provable basis-backed quantity
- this is the main current-state basis gap class

### `history_flags`

Use when:

- current quantity is fully covered
- latest replay point still carries `hasIncompleteHistoryAfter` or
  `hasUnresolvedFlagsAfter`

Interpretation:

- current balance may still be economically usable
- but historical provenance remains audit-sensitive
- **does not change displayed AVCO** when `coverage_gap` is absent; header AVCO
  and move-basis timeline use covered-quantity-weighted basis, not flag state
- flags clear only after authoritative replay repair (paired carry, priced
  authority), not via epsilon uncovered thresholds alone

### `missing_replay_point`

Use when:

- live balance exists
- no latest replay point exists for the exact bucket

Interpretation:

- current holding exists without replay materialization
- this is stronger than a generic history warning

---

## 4. Basis Continuity Rules

Basis continuity applies when the economic owner did not dispose of the asset:

Cycle 79 audit rule:

- supported receipt-token and wrapper exits must preserve principal basis before
  AVCO consumes the stream
- if a lending, vault, wrapper, or liquid-staking row contains same-family
  principal out and principal in, the principal portion is continuity even when
  provider/export shape would otherwise look like disposal plus acquisition
- only explicit positive excess over carried principal opens a new acquisition
  lot
- this rule applies to the audited mandatory corridors:
  - `AMANWETH -> ETH`
  - `eUSDC-* -> USDC`
  - Aave receipt/native gateway exits
  - Euler EVK / ERC-4626 redeems
  - `aAvaWAVAX -> AVAX -> sAVAX`
- replay may harden legacy row handling for those supported row types, but the
  primary correctness path remains raw-first normalization plus rerun

- `INTERNAL_TRANSFER`
  Simple same-tx reciprocal on-chain moves between tracked wallets in the same
  owner scope must normalize as `INTERNAL_TRANSFER` once clarification can see
  both canonical wallet-local rows.
  - both rows must share `txHash + networkId`
  - one principal leg must be outbound and one inbound
  - principal family and quantity must match within a tiny transfer tolerance
  - one-sided tracked-counterparty hints are insufficient and remain external
  - if a direct native transfer already proves a same-universe sender and
    recipient but raw coverage is one-sided, normalization should repair the
    missing raw peer first and let clarification upgrade the resulting pair
  - replay must treat the promoted pair as continuity-only movement, not as
    synthetic disposal plus acquisition
- `BRIDGE_OUT -> BRIDGE_IN`
  Basis carries across networks only when the bridge pair is correlated and the
  bridged asset identity is preserved across the pair.
- the destination leg may land on another tracked wallet from the same user
  universe; replay still treats that as tracked-universe continuity rather than
  an external venue exit
- official route ids are not the only admissible continuity proof. Audited
  same-wallet bridge pairs may also carry basis when runtime proves one unique
  deterministic pair from current canonical rows:
  - source bridge row is protocol-proven
  - destination row is already explicit `BRIDGE_IN`
  - tracked wallet is the same
  - networks differ
  - principal asset family matches
  - timestamp delta and quantity drift remain inside a bounded audited window
  - no competing destination candidate exists
- official LI.FI / Jumper status tracking may prove the destination-side pair
  and upgrade an audited inbound row from `EXTERNAL_TRANSFER_IN` to
  `BRIDGE_IN`, but replay must still keep `continuityCandidate = false` unless
  the current canonical legs prove plain same-asset carry
- official LI.FI / Jumper status rows with `sendingTxHash == receivingTxHash`
  are status echoes, not destination settlements; they must never create
  self-linked bridge continuity
- `BRIDGE_OUT(depositV3) -> BRIDGE_IN(fillV3Relay/fillRelay/redeemWithFee/execute302/directFulfill)`
  Destination-side settlement remains bridge continuity, not repay or vault semantics.
- routed `LI.FI / Jumper` same-asset corridors may also carry move basis when
  clarification proves one unique same-wallet Relay payout destination:
  - source row is already route-proven on `LI.FI Diamond`
  - destination top-level sender is registry-backed `Relay` infrastructure
  - same principal asset family
  - bounded audited time and quantity drift
  - no competing destination candidate exists
- official `Mayan/CCTP` source confirmation may prove a fee-bearing settlement
  pair without relying on a short time-gap heuristic:
  - source raw proves `swapAndStartBridgeTokensViaMayan(...)`
  - official Mayan source-tx status is terminal and returns the receiving tx hash
  - destination canonical row belongs to that receiving tx hash and the official
    destination wallet when present
  - destination may be promoted from inbound-only `EXTERNAL_TRANSFER_IN` to
    `BRIDGE_IN`
  - same-family carry remains valid even when destination quantity is lower than
    source quantity
  - source-minus-destination quantity delta is bridge / settlement cost embedded
    in the carried basis, not synthetic sale realization
- for already-linked same-family `BRIDGE_OUT -> BRIDGE_IN` pairs, replay must
  use a bridge-specific carry path rather than the generic correlated-transfer
  quantity matcher
- protocol-owned async LP entry families may also reserve basis across
  `REQUEST -> SETTLEMENT`, but only where audited replay rules explicitly split
  principal carry from execution-fee reserve
  - destination lower than source:
    full source cost basis moves onto the smaller destination quantity
  - destination higher than source:
    full source cost basis still moves, and only the excess destination
    quantity remains uncovered
  - replay must not proportionally scale source cost basis down merely because
    bridge settlement quantity drifted
- audited same-wallet `Across` pairs may receive synthetic pair correlation
  when the source-side `depositV3(...)` bridge start is already protocol-proven
  and the destination `BRIDGE_IN` row is the unique bounded fit under the
  current canonical evidence
- audited same-wallet `Across` pairs may also be source-led when the source tx
  is already `BRIDGE_OUT / Across` but the destination still normalized as
  inbound-only `EXTERNAL_TRANSFER_IN`
  - source-side `BRIDGE_OUT / Across` may be proven from stored calldata route
    parameters plus wallet-boundary funding even when the saved explorer
    transfer list contains only boundary transfers and omits intermediate helper
    / settlement hops
  - if the destination is the unique bounded fit, clarification may promote it
    into `BRIDGE_IN`
  - replay continuity then uses the promoted destination plus
    `correlationId`, `matchedCounterparty`, and `continuityCandidate`
- audited routed `MetaMask Bridge -> LI.FI adapter -> Across settlement`
  corridors may follow the same source-led path even when:
  - source `protocolName` remains `MetaMask Bridge`

Sponsored gas assistance is **not** a continuity lane:

- `SPONSORED_GAS_IN` does not create `continuityCandidate`
- it does not use `matchedCounterparty`
- it does not participate in `EXTERNAL_TRANSFER_OUT -> EXTERNAL_TRANSFER_IN`
  carry pairing
- any later chart/UI grouping to a parent actionable tx is display logic, not
  replay continuity
  - destination has empty input / blank function name
  - settlement proof exists only in `explorer.internalTransfers[].from`
  Accounting must use the promoted `BRIDGE_IN` row plus deterministic pair
  metadata; it must not treat the destination as a market `BUY`.
- the routed fallback is allowed only when clarification proves a unique
  same-wallet cross-network destination under current canonical evidence:
  - one principal source outflow
  - one principal destination inflow
  - same bridge asset family
  - destination quantity not greater than source quantity
  - bounded audited time window
  - bounded audited quantity drift
- asset-changing bridge routes
  A correlated bridge route may still be objective `BRIDGE_OUT -> BRIDGE_IN`
  semantics without qualifying for plain move-basis continuity. If the source
  asset and destination asset differ, replay must not silently carry the same
  lot across the pair as if it were a same-asset bridge.
  - exception: audited bridge-family-equivalent assets may still qualify for
    continuity carry when policy explicitly maps them into one canonical asset
    family and the route evidence proves bridge settlement rather than market
- already-linked asset-changing bridge settlement
  Replay may still restore destination acquisition cost for an already-linked
  `BRIDGE_OUT(source asset) -> BRIDGE_IN(destination asset)` route pair even
  when `continuityCandidate = false`.
  Approved first slice:
  - shared deterministic `correlationId`
  - exact `matchedCounterparty`
  - one principal transfer leg on the source row
  - one principal transfer leg on the destination row
  - no competing pending source or destination candidate under that route key
  Accounting effect:
  - source-side quantity and source-side cost basis leave the source bucket as a
    route-settlement reallocation
  - destination-side quantity is restored with source carried cost basis
  - destination covered quantity is computed from the source covered-share ratio
    (`coveredSourceQty / totalSourceQty`)
  - if source was fully covered, destination is fully covered
  - if source was partially uncovered, destination inherits the same covered /
    uncovered proportion
  - this slice does not synthesize realized PnL for the source disposal; it is
    a conservative book-value settlement repair until canonical route
    settlement pricing is modeled explicitly
  - if the route key is ambiguous, replay must fall back to explicit uncovered
    destination materialization rather than guessing
  - source-side route funding such as tx-level native `value` on audited routed
    bridge starts is route cost, not a second principal transfer leg, when the
    source row already proves one outbound token principal leg plus one native
    leg that exactly matches tx `value`

### 4.1 Replay pass-through corridor policy

Continuity carry may be reserved inside replay when a proven continuity inbound
is followed by one later deterministic outbound/custody source leg and WalletRadar
can prove that the carry should remain isolated from unrelated inventory.

This policy exists to prevent AVCO drift caused by pooled inventory when the
economic owner did not intentionally re-open a fresh mixed lot.

Approved slice:

1. Custodial venue transit
   - `on-chain -> BYBIT:<uid> -> on-chain`
   - the venue-side inbound and later venue-side outbound may reserve the exact
     carried basis when the venue wallet is the same and no competing
     same-bucket negative principal flow appears before the paired outbound

2. Same-wallet immediate custody path
   - `BRIDGE_IN -> LENDING_DEPOSIT`
   - `BRIDGE_IN -> VAULT_DEPOSIT`
   - `BRIDGE_IN -> STAKING_DEPOSIT`
   - `BRIDGE_IN -> PROTOCOL_CUSTODY_DEPOSIT`
   - `BRIDGE_IN -> LP_ENTRY`
   - the bridge-carried principal may be reserved into the custody-source
     outflow before pooled spot inventory can consume it

Deterministic restrictions:

- reservation uses only already-confirmed canonical rows
- reservation never changes canonical transaction types
- reservation is exact-bucket, not portfolio-wide
- an open reservation survives only until the first later principal-affecting
  row in the same scope and asset bucket
- if that first later principal-affecting row is not the paired outbound /
  custody-source leg, replay must discard the reservation and fall back to the
  pooled AVCO position
- if uniqueness is ambiguous, replay must not create a reservation
- unmatched quantity remainder stays in the ordinary pooled position

Accounting effect:

- reserved carry is restored into the inbound bucket as usual
- later paired outbound consumes reserved carry first
- only remaining quantity, if any, falls back to pooled AVCO consumption
- downstream destination/custody leg therefore receives the carried cost more
  accurately than generic pooled replay would allow

Bridge-specific guardrail:

- bridge continuity itself is still handled only by the dedicated
  `BRIDGE_OUT -> BRIDGE_IN` path
- pass-through reservation may isolate a `BRIDGE_IN` only until the first later
  same-scope principal touch
- therefore a later same-wallet same-network `BRIDGE_OUT` must consume the
  pooled source position once an intervening local `BUY`, `SWAP`, reward, LP
  exit, or other principal-affecting row has already mixed that bucket

### 4.1b Bridge late-carry ordering invariant (ADR-020, 2026-05-29)

When a `BRIDGE_IN` arrives in replay **before** its paired `BRIDGE_OUT` (late-carry ordering), the authoritative carry applied by `attachLateBridgeCarryToPendingInbound` must also activate any pre-built pass-through corridor reservation:

- The pending-inbound `CarryTransfer` stores the original `BRIDGE_IN` `FlowRef` (`sourceFlowRef`).
- After applying `bridgeInboundCarry`, replay calls `reservePassThroughCarry(passThroughCorridorPlan, sourceFlowRef, effectiveCarry, ...)`.
- The downstream consumer (`LENDING_DEPOSIT`, `LP_ENTRY`, etc.) **must** call `takeReservedCarry` — never drain the depleted family pool.

**Violation example**: Two LiFi ZKSync BRIDGE_INs (08:27 and 08:40, +0.4997 ETH each) arrived before UNICHAIN BRIDGE_OUTs. Without activation, ZKSync LENDING_DEPOSITs 35 seconds later captured $3.49 and $7.62 instead of $1,598 and $1,343. ETH AVCO dropped to $1,953 from the correct $2,692–$2,828.

**Defensive guard (P0-b)**: `selectWalletScopedInboundCandidate` in `PassThroughCorridorPlanner` rejects candidates whose `networkId` differs from the outbound transaction's `networkId`. This prevents hypothetical cross-network corridor pairings via the wallet-scoped fallback path. Counterparty-matched corridors (e.g. Bybit transit ARBITRUM→MANTLE) are unaffected.

### 4.1a Corridor carry rate rule (ADR-019, 2026-05-29)

For Bybit→on-chain corridor transfers (`BYBIT-CORRIDOR:*` correlationId, `CARRY_OUT`/`CARRY_IN` pair):

- The inbound on-chain bucket's `avcoAfterUsd` **must equal** the outbound venue-slice `avcoBeforeUsd`
  at the moment of the `CARRY_OUT` leg, divided by the moved quantity.
  - Formula: `inboundAvco = carryBasis / movedQty` where `carryBasis = movedQty × outboundSliceAvco`.
- The **residual source-bucket AVCO** after `CARRY_OUT` may legitimately differ (it reflects the
  remaining lot composition) and must not influence the inbound rate.
- Violation example: `0xa5e755…` moved 3.06 ETH at `$5,016` total basis (`$1,639/ETH`) while outbound
  slice `avco = $1,714/ETH`. The correct inbound rate is `$1,714`, not `$1,639`.
- See ADR-019 for the full acceptance check.

### 4.2 Correlated same-family carry ingress policy

Replay may also attach continuity carry across a correlated `CARRY_OUT ->
CARRY_IN` pair even when the destination-side quantity is rounded by an
external venue or provider.

This is a replay repair for already-proven continuity. It is not a
reclassification rule.

Approved use:

- correlated same-family `EXTERNAL_TRANSFER_OUT -> EXTERNAL_TRANSFER_IN`
- typical case: `on-chain -> BYBIT:<uid>` ingress rounded to venue precision
- same policy may apply to any external provider that truncates display/export
  quantity but still preserves deterministic continuity identity

Deterministic restrictions:

- both rows already have the same deterministic `correlationId`
- both rows are already marked `continuityCandidate = true`
- replay matches on the same continuity identity / audited asset family
- replay may ignore tiny provider precision loss only inside a bounded
  near-zero tolerance
- the candidate fit must be unique; if more than one carry candidate fits,
  replay must not attach any carry automatically
- no economic slippage, market conversion, or cross-asset route may be
  normalized through this policy

Accounting effect:

- if the unique correlated carry is found, destination `CARRY_IN` restores the
  same covered/uncovered composition as the source-side carry
- if the fit is not unique or not tolerance-compatible, replay falls back to
  the existing pending inbound / pooled AVCO path

Examples:

- valid: `ETH -> ETH` same-family transfer from `ARBITRUM` into `BYBIT:<uid>`
  where Bybit truncates `0.592974594039008539` into `0.59297459`
- invalid: `USDC -> ETH` bridge route with shared `correlationId`; this is
  still an asset-changing route, not plain carry continuity
  - it may qualify for the separate asset-changing bridge settlement lane, but
    not for same-family correlated carry ingress
- example: `vbUSDC -> USDC` may be treated as one `USDC` family for move
  basis when the audited bridge route proves custody/settlement continuity
  instead of an economic swap
- correlated `EXTERNAL_TRANSFER_OUT -> EXTERNAL_TRANSFER_IN`
  Basis carries only when replay confirms that both sides belong to the same
  live session scope.
- inbound-first continuity ordering
  Replay must be chronology-safe when the destination-side continuity row is
  encountered before the matched source-side carry row in the ordered stream.
  - the inbound row must materialize quantity immediately so later canonical
    outflows can consume the real current holding
  - until source carry is attached, that inbound quantity remains explicit
    incomplete-history inventory
  - when the matched source carry later arrives, replay attaches basis to the
    already-materialized destination position without minting quantity again
  - end-of-replay synthetic quantity backfill for those inbound rows is not a
    valid replacement for chronology-safe replay
- `Bybit <-> external venue (for example MEXC)`
  These rows are not on-chain bridge continuity by default.
  - they may represent off-ledger custody movement between Bybit and an
    untracked external venue
  - they must remain `EXTERNAL_TRANSFER_IN/OUT` unless a tracked-universe leg is
    actually proven
  - the future target model is `external custody / unknown external source`
    inventory
  - until that dedicated move-basis contract exists, normalization must park
    them in an explicit excluded audit lane rather than replay them as synthetic
    `BUY` / `SELL`
  - replay must not silently fabricate same-universe bridge continuity or
    one-to-one move-basis carry
  - automatic basis restoration from that external bucket is allowed only under
    a separate high-confidence policy; without such proof, incoming rows remain
    explicit external-source inventory rather than synthetic `BUY`
- `Bybit -> on-chain`
  Same-universe continuity is replay-derived from `correlationId`, `continuityCandidate`, and matched counterpart evidence.
  - normalization may materialize this continuity late on rerun when an already
    imported Bybit `withdraw_deposit` row still sits in `UNMATCHED`, but the
    exact on-chain leg is now present in persisted Mongo evidence
  - on-chain-only reruns must also support the reverse repair: if the Bybit leg
    already carries deterministic continuity metadata but the rebuilt on-chain
    leg lost it, clarification post-processing must restore the exact
    `BYBIT:<NETWORK>:<txHash>` correlation on the wallet row
  - rows carrying `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` are not move-basis-ready
    until the on-chain leg is actually matched
  - etalon parity alone is not enough here; replay readiness requires real
    continuity materialization or explicit exclusion from accounting scope
- `PROTOCOL_CUSTODY`
  Basis moves into and out of custody without creating BUY/SELL.
- `LENDING_DEPOSIT -> LENDING_WITHDRAW`
  Principal basis moves between spot balance and receipt-token/custody state without realization.
- `VAULT_DEPOSIT -> VAULT_WITHDRAW`
  Basis moves between spot balance and vault-share/custody state without realization.
  - audited family-equivalent receipt-wrapper conversions such as
    `yvvbETH -> vbETH` keep both principal legs as continuity `TRANSFER`
    inside the same base-asset family; replay carries basis forward and leaves
    only any quantity mismatch as uncovered tail
- Matched `Bybit -> on-chain` withdrawals
  The chain-aware `withdraw_deposit` row is the principal continuity leg. Any
  exchange-side `fund_asset_changes` withdrawal shadow row for the same move is
  audit-visible but excluded from accounting and must not remain an active
  realized `SELL`.
- Matched `wallet -> Bybit` deposits and `Bybit -> wallet` withdrawals stay
  canonical `EXTERNAL_TRANSFER_*`, not persisted `INTERNAL_TRANSFER`.
  - replay carries basis only when both sides hold exact same-universe
    continuity metadata
  - protocol-neutral dust / spam transfers with no Bybit leg may not borrow
    this corridor policy
- Async request / execute lifecycles
  Basis may move only when the runtime can deterministically correlate the
  request-side principal with the later settlement-side execution. Request
  multicalls, burn-only unbonding initiations, or settlement-only receipt mints
  without that correlation remain explicit blocker rows rather than inferred
  continuity.
- `LP_ENTRY_REQUEST -> LP_ENTRY_SETTLEMENT`
  Audited async LP-entry families may carry basis through a request-side escrow
  row and later settlement row when both share the same deterministic
  `correlationId`.
  - if current evidence proves a higher-scope lifecycle key than an
    intermediate helper key, the higher-scope key must be the persisted
    `correlationId`
  - once both rows exist, both rows must expose each other via
    `matchedCounterparty`
- `LP_EXIT_REQUEST -> LP_EXIT_SETTLEMENT`
  Audited async LP-exit families may carry burned-share basis through a
  request-side withdrawal intent and later keeper-side payout settlement when
  both share the same deterministic `correlationId`.
  - once both rows exist, both rows must expose each other via
    `matchedCounterparty`
  - same-asset execution-fee refunds restore their own carry first
  - the remaining burned-share basis is then allocated deterministically across
    the principal settlement payout legs
  - `GMX / GLV` withdrawal intent is determined from request-side multicall
    lifecycle shape and burned share asset, not from a top-level router address
    hit alone
  - when `bytes[]` selector decode is incomplete, the same audited withdrawal
    family may still be confirmed from saved raw calldata selector fragments,
    but only together with audited helper selectors and outbound-only burned
    share movement
- `STAKING_WITHDRAW_REQUEST -> STAKING_WITHDRAW`
  Burn-only unstake / cooldown initiation may move basis into a pending
  lifecycle bucket, and the later claim payout restores that basis into the
  received asset.
- `DEX_ORDER_REQUEST -> DEX_ORDER_SETTLEMENT`
  Audited async spot-order families may hold the sold asset in an open order
  bucket first and finalize the economic swap only when the later settlement
  payout arrives with the same deterministic `correlationId`.
- audited Resolv unstake settlement
  The later `withdraw(...)` payout is continuity restoration, not a fresh
  acquisition. The principal `RESOLV` leg stays `TRANSFER` and restores basis
  from the correlated `STAKING_WITHDRAW_REQUEST`.
- classic `STAKING_DEPOSIT -> STAKING_WITHDRAW`
  Basis moves between spot balance and staking custody without realization when
  the tx is a classic stake-contract deposit/withdraw rather than a liquid
  staking asset conversion.
- `REWARD_CLAIM`
  Requires actual inbound reward movement to the tracked wallet. Claim calls with
  no inbound movement stay explicit non-economic / review rows and must not mint
  synthetic basis.
- `BORROW` / `REPAY`
  Only the reserve-asset principal is economic. Debt-marker mint/burn legs and
  chain-specific execution settlement / refund legs remain continuity-only and
  must not create spot basis lots or realized PnL.
- receipt-log-rich batch openings
  Marker-only debt/share evidence is not enough to claim finalized
  `LENDING_DEPOSIT` / `LENDING_WITHDRAW` semantics. If the batch decoder cannot
  reconstruct the real wallet-boundary economic lifecycle, the row remains
  explicit review and never enters pricing.
- Euler simple vault rows stay on the plain lending policy only after
  clarification proves the lifecycle:
  - `stable -> share` is `LENDING_DEPOSIT`
  - native-value EVK `batch(...)` rows are also `LENDING_DEPOSIT` when
    clarification proves positive native funding, share mint to the wallet,
    and one protocol-local wrapper / vault hop
  - `share -> stable` is `LENDING_WITHDRAW`
  - without reliable clarification they remain
    `UNKNOWN / PENDING_CLARIFICATION`
  - they enter receipt clarification before pricing/replay
  - if clarification still cannot prove the lifecycle, they remain
    `NEEDS_REVIEW`
  - replay derives partial vs fully exited state from position continuity, not
    from the normalized type label
- audited Euler loop rows
  WalletRadar models the audited Euler looping family as a share-position
  lifecycle, not as a plain lending deposit/withdraw:
  - `LENDING_LOOP_OPEN` acquires the collateral-share asset with an implied
    event-local unit price derived from the clarified stable-like supply amount
  - `LENDING_LOOP_REBALANCE` moves carried basis between loop-share assets
    without realized PnL; exact same-asset marker churn is ignored, while
    genuine same-asset dust keeps pro-rata basis on the original asset
  - `LENDING_LOOP_DECREASE` partially disposes of that share-position and
    acquires the returned wallet-visible asset
  - `LENDING_LOOP_CLOSE` fully disposes of the remaining share-position into the
    returned wallet-visible asset
  - debt-marker evidence remains source/audit evidence and does not become a
    standalone replay lot
- self-canceling same-tx marker pairs
  Exact same-asset same-quantity inbound and outbound legs inside one tx do not
  create basis by themselves. They are continuity-only marker churn and must
  not remain active `BUY` / `SELL`.

For matched Bybit withdraw/deposit:

- both source sides remain traceable
- canonical replay uses `TRANSFER` semantics only
- no duplicate BUY/SELL is allowed

---

## 5. Asset Identity Rules

- WETH-like wrappers are stored as-is
- wrapper-to-native aliasing is applied only at replay/pricing time when policy allows
- tx-level native `value` must not create a second native movement when the
  same wallet-boundary fact is already present via an audited native-token
  alias transfer
- on native-alias networks, the audited alias transfer that exactly matches the
  tx gas fee to the audited system fee sink is fee evidence, not independent
  principal movement
- on `zkSync`, fee-sink refund legs from
  `0x0000000000000000000000000000000000008001` belong to the same fee
  lifecycle and must also be excluded from principal movement before replay
- replay may use audited accounting families for carry-only continuity even when
  the persisted normalized asset ids remain distinct
- the current audited `ETH` continuity family includes `ETH`, network `WETH`,
  `aEthWETH`, `aArbWETH`, `aLinWETH`, `aManWETH`, `aZksWETH`, `vbETH`,
  `yvvbETH`, `mETH`, and `cmETH`
- gateway-style Aave custody methods on supported networks remain continuity,
  not disposal / reacquisition:
  - `zkSync`
    - `withdrawETH(...)` is `LENDING_WITHDRAW`
    - `supplyWithPermit(...)` is `LENDING_DEPOSIT`
    - `depositETH(...)` is `LENDING_DEPOSIT`
  - `Base`
    - audited `depositETH(...)` with wallet-boundary `ETH -> AWETH` mint
      shape is `LENDING_DEPOSIT`
- generic Aave `BORROW` / `REPAY` rows may still carry `protocol = Aave`
  without an address-level registry hit when the tx proves canonical
  Aave debt-marker continuity (`variableDebt*` / `stableDebt*`) alongside the
  selector. That protocol handoff affects labeling only; reserve/debt flow
  accounting semantics stay unchanged.
- routed native sends with deterministic raw outbound principal on `zkSync`
  must preserve that outbound principal in canonical normalization even when the
  protocol identity is still low-confidence; they may not fall through to a
  hash-specific `UNKNOWN` stop-condition once the movement is proven
- audited routed `Across` sends on `zkSync` must normalize as `BRIDGE_OUT` once
  current raw route evidence proves the helper path and same-wallet destination
  parameters
- `stETH`, `mETH`, `rETH`, `wstETH`, and `cbETH` remain distinct assets
- audited bridge-family-equivalent stable wrappers may map into one canonical
  stable family for move-basis continuity even when the canonical normalized
  asset ids remain distinct
- LP tokens, receipt tokens, vault shares, and custody markers are not treated as new basis lots unless explicitly modeled as economic principal

---

## 6. Pricing Policy

Historical price resolution order:

1. stablecoin parity
2. exact execution price from canonical source evidence
3. swap-derived price
4. wrapper/native mapping
5. ECB EUR/USD FX for euro-backed stablecoins
6. Bybit historical market data
7. Binance historical market data
8. CoinGecko historical fallback
9. unresolved price flag

Implications:

- pricing failure does not remove quantity from replay
- pricing gaps must be visible as warnings or blockers
- euro-backed stablecoins such as `EURC` may price from official ECB EUR/USD FX
- audited Aave Avalanche receipt aliases (`aAvaWAVAX`, `aAvaSAVAX`) and
  `sAVAX` use the `AVAX` market symbol for historical reward/accrual pricing,
  while replay still keeps exact receipt-token buckets separate
- Bybit market data may be used before Binance
- Binance is the primary external market-data source, not the primary overall
  pricing source
- CoinGecko is a limited fallback, not the primary pricing mechanism
- `SWAP_DERIVED` is valid only when the priced canonical asset appears once
  among non-fee swap flows in the same canonical row
- if the same canonical asset appears multiple times in one `SWAP`, tx-local
  ratio pricing must be skipped and the flow must fall back to safer pricing
  sources instead of persisting an impossible synthetic price
- **Multi-sell SWAP price rule (ADR-021, 2026-05-29)**: When a `SWAP` contains multiple SELL
  flows of the same asset (e.g., two USDBC SELL legs routed through an aggregator), the derived
  price for the BUY leg must be computed from the **sum of all SELL flow values**, not just the
  first SELL flow. Using only the first SELL flow causes severe undervaluation of the BUY leg
  (e.g., $9.52/WETH instead of $3,806/WETH when the second SELL carries 99% of the cost).

Transaction-type pricing contract:

- `SWAP`
  - Price from the executed ratio of the canonical wallet-boundary legs.
  - A canonical `SWAP` is valid only when the row contains both wallet-visible
    `SELL` and `BUY` legs.
  - If one side is stablecoin, that ratio is authoritative for the non-stable
    leg.
  - If neither side is stablecoin but one side already has deterministic market
    price, derive the counterpart from the same executed ratio instead of
    pricing both sides independently.
  - Multi-hop or router internals do not create independent pricing legs; use
    the canonical net in/out legs only.
  - Routed bridge-start selectors such as
    `swapAndStartBridgeTokensViaMayan(...)`,
    `swapAndStartBridgeTokensViaStargate(...)`, and
    `swapAndStartBridgeTokensViaSquid(...)` are not wallet-local `SWAP`; they
    remain `BRIDGE_OUT`.
  - Those bridge-start selectors may still require persisted receipt evidence
    for later destination-side pairing, but that evidence requirement must not
    demote the source row into `SWAP`.
  - Aggregator/router rows that prove only outbound wallet movement and no
    wallet-visible `BUY` leg are not wallet-local `SWAP`; they demote to
    owner-agnostic `EXTERNAL_TRANSFER_OUT` or explicit review according to the
    canonical evidence.
  - Time-window proximity to a later inbound on another chain is not enough to
    promote a generic routed outbound row into `BRIDGE_OUT`; protocol-specific
    bridge evidence must exist in current raw or clarification evidence.
- `BUY` / `SELL`
  - Use the exact execution price when canonical evidence already carries it
    (for example Bybit trade fills).
  - Otherwise use the same event-local pricing rules as `SWAP` when the row
    already carries exact countervalue.
- `EXTERNAL_TRANSFER_IN`, `REWARD_CLAIM`, `LP_FEE_CLAIM`
  - Treat as acquisition at receive-time fair market value.
  - Resolve via external market data only when tx-local execution evidence is
    absent.
- Bybit inbound rows are eligible for this rule only when canonical
  normalization has actually materialized them as `EXTERNAL_TRANSFER_IN`.
  Basis-relevant inbound raw evidence may not remain silently
  `UNKNOWN / CONFIRMED`.
- `EXTERNAL_TRANSFER_OUT`
  - Treat as disposal at event-time fair market value unless the row has
    already been normalized into an explicit continuity or pending-request
    family.
- async request / execute families
  - Request-side escrow / burn rows and execution-side settlement rows are not
    priceable while request correlation remains unresolved.
  - Once current raw proves deterministic correlation, they should resolve into
    explicit async lifecycle types rather than stay `UNKNOWN / NEEDS_REVIEW`.
  - Burn-only unbonding initiations such as `stRESOLV initiateWithdrawal(...)`
    are pending requests, not finalized withdraws.
  - Execution-side receipt/share mints such as GMX
    `executeDeposit(...)` / `executeGlvDeposit(...)` are not standalone
    acquisitions until their request-side principal is correlated.
- `WRAP` / `UNWRAP`
  - Principal is continuity, not a new buy/sell.
  - Use the underlying native asset price for valuation and fee accounting
    only.
  - **AVCO carry rule (2026-05-29):** Replay must preserve the source bucket's
    `avcoBeforeUsd` on the destination bucket after WRAP/UNWRAP.
    - `WRAP` (ETH→WETH): WETH bucket `avcoAfterUsd` = source ETH `avcoBeforeUsd` (±0.1%).
    - `UNWRAP` (WETH→ETH): ETH bucket `avcoAfterUsd` = source WETH `avcoBeforeUsd` (±0.1%).
    - If source AVCO is 0 (uncovered), destination inherits 0 — this is correct;
      no synthetic pricing is applied.
  - Replay must treat canonical wrapped/native pairs such as `ETH <-> WETH`
    with the same atomic family-equivalent carry rule already used for simple
    audited custody rows:
    - source principal basis leaves the outbound leg
    - destination wrapped/native leg receives that carried basis
    - inbound-first flow order must not create a synthetic uncovered receipt
      lot
- correlated `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN`,
  correlated `BRIDGE_OUT` / `BRIDGE_IN`,
  `PROTOCOL_CUSTODY`, `LENDING_DEPOSIT` / `LENDING_WITHDRAW`,
  `VAULT_DEPOSIT` / `VAULT_WITHDRAW`, `LP_POSITION_STAKE` /
  `LP_POSITION_UNSTAKE`
  - Principal uses carry-over basis only.
  - Market pricing is not required to continue basis.
  - Only explicit fee or reward side flows are priced.
- `LP_ENTRY`
  - Principal tokens move into LP custody with carry-over basis.
  - Underlying principal outflows inside `LP_ENTRY` must persist as
    `TRANSFER`, not `SELL`.
  - Receipt markers such as `LP token`, `BPT`, or CL position NFT stay
    continuity-only markers and do not become new basis lots.
  - For concentrated-liquidity positions with deterministic
    `lp-position:<network>:<protocol-slug>:<tokenId>` correlation, replay must
    store principal carry in a position-scoped multi-asset bucket rather than a
    wallet-level same-asset bucket.
  - If normalization fails to recover a deterministic token id for direct or
    multicall-embedded `modifyLiquidities`, replay cannot legally infer the
    missing position bucket later. The correct remedy is canonical rebuild, not
    post-hoc basis patching.
  - The pricing stage must not invent a synthetic sale of token0/token1 or a
    synthetic buy of an LP token just to assign USD.
  - Any explicit swap, fee, or reward side-flow inside the same canonical tx is
    priced separately by its own rules.
- `LP_EXIT`
  - Principal returning from LP custody remains basis continuity.
  - Underlying principal inflows inside `LP_EXIT`, `LP_EXIT_PARTIAL`, and
    `LP_EXIT_FINAL` must persist as `TRANSFER`, not `BUY`.
  - Burned LP receipt markers remain continuity-only and do not create disposal
    accounting on their own.
  - Explicit fee/reward delta above principal continuity is priced as
    `LP_FEE_CLAIM` or `REWARD_CLAIM`.
  - **Concentrated-liquidity harvest-only gate (ADR-021, amended 2026-05-29)**: Two distinct
    call patterns must be classified as `LP_FEE_CLAIM` rather than `LP_EXIT` when all non-fee
    inflows are fee-reward tokens only (CAKE, VELO, AERO, or dust stablecoins ≤ $100):

    1. **Multicall `decreaseLiquidity(liquidity=0)` + `collect`** — a no-op principal call
       plus fee collection on the Uniswap V3 NPM. The `decreaseLiquidity` call with `liquidity=0`
       touches no principal; it is used only to trigger the fee accounting path. Detected by:
       `decreaseLiquidity` selector in calldata + `liquidity` parameter = 0 (64 zero hex digits).

    2. **PancakeSwap V3 MasterChef `withdraw(uint256 tokenId, address to)`** — direct call
       (selector `0x00f714ce`) on the MasterChef farm contract. Unstakes the NFT from the farm
       and distributes accumulated CAKE rewards; LP liquidity stays in the Uniswap pool.
       Discriminated from NPM `burn(tokenId)` (which shares the same selector) by the ERC721
       Transfer event direction: MasterChef `withdraw` emits Transfer(MasterChef→wallet) — the
       NFT is returned; NPM `burn` emits Transfer(wallet→0x0) — the NFT is destroyed.
       `LpPositionLifecycleSupport.hasAnyErc721TransferToWallet` is `true` only for the former.

    In both patterns, classifying the transaction as `LP_EXIT` materialises a phantom
    `LP-RECEIPT:-1` burn, drains the position's composite basis bucket, and causes all subsequent
    real principal exits to receive `basisEffect=UNKNOWN`.
  - For position-scoped concentrated-liquidity exits, replay restores
    same-asset carry first and may then reallocate remaining principal basis
    across returned principal assets that belong to the same position bucket.
  - Positive return assets that do not match the original source-asset
    identities are not auto-rewards. They stay as deferred residual principal
    candidates until replay decides whether they are:
    - the only remaining deterministic principal-return path
    - or merely reward-side residuals that must stay `UNKNOWN`
  - Aggregate uncovered quantity across heterogeneous source carries must not
    by itself zero out deterministic residual principal allocation after
    same-asset carry has already been restored.
  - Replay-known-value allocation for residual principal may use:
    - persisted explicit `unitPriceUsd`
    - trusted USD-stable parity for transfer legs such as `USDC`, `USDT`,
      `DAI`, `USDB`, `USDE`, `GHO`, `AUSD`
  - Assets that were never part of the position principal bucket, such as
    out-of-bucket rewards, must not inherit that principal basis automatically.
  - A reward-only or out-of-bucket sideflow `LP_EXIT` must not clear the
    remaining position bucket when no eligible principal-return leg was touched
    in that tx. The sideflow stays `UNKNOWN`, and the bucket remains open for a
    later principal-return exit in the same position correlation.
  - A bundle-aware LP exit may still carry explicit `BUY` reward side-flows in
    the same canonical row when current raw evidence can separate them
    deterministically. Those reward side-flows are priceable; the LP principal
    remains `TRANSFER`.
- `BORROW`
  - Reserve asset received by the wallet is a `BUY`.
  - Debt-marker mint legs and execution-settlement refund legs are `TRANSFER`.
- `LENDING_LOOP_OPEN`
  - The collateral-share asset is the economic acquisition leg.
  - Event-local unit price may already be resolved during normalization from
    clarified stable-like supply evidence.
  - Debt-marker legs are not separate replay lots.
- `LENDING_LOOP_REBALANCE`
  - Outbound old share and inbound replacement share are continuity
    `TRANSFER`, not realized `SELL` / `BUY`.
  - Replay carries remaining basis from the old share asset into the new share
    asset.
  - Exact same-asset same-quantity roundtrip markers are ignored as technical
    churn, not new inventory.
- `LENDING_LOOP_DECREASE` / `LENDING_LOOP_CLOSE`
  - The collateral-share asset is the economic disposal leg.
  - The returned wallet-visible asset is the economic acquisition leg.
  - Event-local ratio between returned asset and share quantity is authoritative
    for the share disposal price when the canonical row already proves it.
- `REPAY`
  - Reserve asset sent by the wallet is a `SELL`.
  - Debt-marker burn legs and execution-settlement refund legs are `TRANSFER`.
- `STAKING_DEPOSIT`
  - Liquid-staking conversions such as `AVAX -> sAVAX` or `ETH -> METH` keep
    principal and derivative legs as continuity `TRANSFER`.
  - Audited Bybit `Earn / On-chain Earn subscription` receipt wrappers such as
    `ETH -> CMETH` follow the same liquid-staking continuity policy.
  - Audited Bybit funding-history `ETH 2.0 / Stake` plus `ETH 2.0 / Mint`
    rows also follow this liquid-staking continuity policy even when the two
    staging rows do not share the same description string.
  - Audited Bybit liquid-staking pairs may settle asynchronously inside one
    official earn lifecycle. Deterministic pairing therefore may use a bounded
    multi-hour window when all of the following hold:
    - same user/account
    - same official Bybit lifecycle description when present
    - opposite signed quantities
    - same audited accounting family
    - exact or nearest absolute quantity match
  - For the audited `ETH 2.0` slice, the shared provider business family is the
    authoritative pairing key; exact description equality is not required
    between `Stake` and `Mint`.
  - Replay carries cost basis from the principal asset into the liquid staking
    derivative and must not realize PnL on the conversion itself.
  - Replay allocates available covered source principal to the destination
    derivative by absolute carried principal, capped by destination quantity.
    It must not multiply the destination quantity by the source covered ratio
    when the derivative quantity is lower than the source principal.
  - If the source principal is only partly covered, the uncovered source
    remainder stays uncovered; coverage is not fabricated. If the destination
    quantity exceeds proven principal, only the explicit excess remains
    uncovered or priced as yield when protocol semantics prove it.
  - Classic stake-contract deposits such as Pancake SmartChef
    `deposit(uint256)` keep staked principal and proof-token markers as
    `TRANSFER`.
  - Any same-tx harvested reward token remains `BUY`.
- `STAKING_WITHDRAW`
  - Liquid-staking unwind keeps derivative burn and principal return as
    continuity `TRANSFER`.
  - Replay restores carried basis from the derivative back into the principal
    asset without realizing PnL on the unwind itself.
  - Classic stake-contract withdraw keeps returned principal and proof-token
    markers as `TRANSFER`.
  - Any same-tx harvested reward token remains `BUY`.
- matched `Bybit withdraw/deposit` plus on-chain leg
  - persisted canonical type stays `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN`
  - same-universe continuity is resolved only during replay
  - do not price the principal twice on both sides once continuity correlation is active
- on-chain wallet-to-wallet moves are stricter than venue rematch
  - clarification may persist `INTERNAL_TRANSFER` only after the reciprocal
    canonical peer row exists
  - until then the row stays conservative as external even if a tracked-wallet
    hint is present
- review telemetry
  - `blockingNeedsReview` means rows that still participate in active pricing /
    replay gating.
  - `excludedNeedsReview` means audit-visible rows that are intentionally kept
    outside active accounting scope via `excludedFromAccounting=true`.
  - Operators must not treat `blockingNeedsReview + excludedNeedsReview` as one
    homogeneous failure count.
- pricing hygiene
  - `PRICE_UNRESOLVABLE` is a current-state signal, not an immutable scar.
  - Once every replay-relevant flow on a row has a resolved price, the stale
    `PRICE_UNRESOLVABLE` reason must be cleared before replay-gate evaluation.
- `UTA_TRADE_PAIR_NOT_FOUND`
  - a residual orphan leg is insufficient evidence, not a priceable acquisition
    or disposal
  - it must remain outside `PENDING_PRICE` unless a second official Bybit
    source reconstructs the missing leg deterministically
  - if the team chooses not to import another official Bybit source, the row
    may remain persisted as `NEEDS_REVIEW + excludedFromAccounting=true`
- pending-request and pending-order families
  - They remain non-priceable until final settlement or cancellation evidence
    exists.
- audited async spot-order families
  - `DEX_ORDER_REQUEST` and `DEX_ORDER_SETTLEMENT` are in-scope canonical rows
    for order-based spot execution such as CoW Eth Flow.
  - request-side principal may not leak into standalone `EXTERNAL_TRANSFER_OUT`.
  - settlement-side payout may not leak into standalone `EXTERNAL_TRANSFER_IN`.
  - replay finalizes the source-asset disposal only when the correlated
    settlement payout exists.
  - once settlement is proved, the request row should expose the terminal tx via
    `matchedCounterparty`.
- GMX V2 derivative order / execution lifecycle
  - `DERIVATIVE_ORDER_REQUEST`, `DERIVATIVE_ORDER_EXECUTION`,
    `DERIVATIVE_ORDER_CANCEL`, `DERIVATIVE_POSITION_INCREASE`, and
    `DERIVATIVE_POSITION_DECREASE` are in-scope canonical lifecycle rows.
  - persisted `GMX` EventEmitter hashed topics are authoritative runtime
    evidence; runtime may not lower-case event names before hashing
  - request-key correlation remains EventEmitter-driven, but same-receipt
    structured lifecycle logs may refine generic terminal execution into
    `DERIVATIVE_POSITION_INCREASE` or `DERIVATIVE_POSITION_DECREASE` once the
    receipt is already proved to belong to `GMX`
  - They must never fall back to `EXTERNAL_TRANSFER_IN`,
    `EXTERNAL_TRANSFER_OUT`, `LP_*`, or spot `SWAP`.
  - They do not represent spot acquisition / disposal of the market underlying.
    AVCO for spot assets must follow collateral, fee, and realized settlement
    flows instead of inventing a synthetic `BUY underlying / SELL underlying`
    path.
  - closing keeper txs that also auto-cancel sibling bracket orders must
    propagate their terminal pairing back onto the affected request rows.
  - the terminal keeper row itself must keep the primary executed request key
    as its own `correlationId`; sibling stop-order requests may keep their own
    request key while still pointing to the terminal tx through
    `matchedCounterparty`
  - Clarification is responsible for fetching order / execution evidence when
    explorer raw omits top-level transaction metadata but full receipt logs can
    still resolve the lifecycle deterministically.
  - Clarification is also responsible for fetching missing real keeper txs when
    current source evidence already proves the audited `GMX` derivative family
    but the terminal keeper tx is absent from Mongo.
  - Keeper execution rows must resolve to the most specific lifecycle that the
    persisted EventEmitter evidence proves. `PositionIncrease` may not remain
    generic `DERIVATIVE_ORDER_EXECUTION`.
  - Sibling stop / bracket requests may stay `DERIVATIVE_ORDER_REQUEST`, but
    the later auto-cancel terminal state must still be persisted once the same
    keeper receipt already proves it.
  - Persisted clarification evidence is authoritative runtime input whenever it
    exists in canonical raw shape; it must not be ignored because an attempt
    counter is stale.

Accounting exclusion policy:

- `excludedFromAccounting = true` is allowed only for deterministic,
  explicitly-documented unsupported families.
- Excluded rows remain visible in Mongo and audit outputs.
- Excluded rows do not block `avcoReady`.
- Excluded rows do not participate in AVCO replay, cost basis, or move-basis carry.
- Current exclusion families:
  - residual Bybit orphan legs with `UTA_TRADE_PAIR_NOT_FOUND`
  - residual Bybit rows with `BYBIT_LOAN_SEMANTICS_UNSUPPORTED`
- Any other `NEEDS_REVIEW` row remains a blocking review item by default.

External market-data source policy:

- Binance is the preferred external source for listed assets because it offers
  free public market data and a public historical archive.
- `EURC` and similar euro-backed stablecoins should prefer official ECB
  EUR/USD reference data over exchange-market pricing.
- Bybit market data may be used before Binance when it offers better coverage
  or fresher market support for the priced asset.
- CoinGecko remains a fallback for assets or historical windows that Binance
  cannot cover, but it must not be treated as the only viable path for a
  two-year DeFi backfill.
- Long-tail DeFi assets should prefer tx-local swap-derived pricing over any
  generic external candle source whenever the canonical tx already proves the
  execution ratio.
- If neither tx-local nor external market data can produce a deterministic
  price, the row remains `PRICE_UNKNOWN` and replay proceeds with incomplete
  history signaling.
- `TRANSFER` flows are never market-priced by themselves, even when they appear
  inside a tx whose canonical type is otherwise priceable or continuity-based.
- Continuity-type rows may still contain explicit `BUY` / `SELL` side-flows.
  Those side-flows are priced normally and replayed as acquisitions /
  disposals, while the `TRANSFER` principal legs continue to carry basis.

---

## 7. Clarification Policy

`Clarification` may enrich receipt-safe metadata:

- execution status
- gas fields
- created contract address

Clarification-adjacent metadata enrichment may additionally fill deterministic
canonical fields on already confirmed on-chain rows when persisted raw evidence
is sufficient:

- `protocolName` / `protocolVersion`
- row-local `counterpartyAddress`

These enrichment passes are part of the normal rerun path. They must not depend
on startup repair sweeps or tx-hash-specific exceptions.

Clarification is allowed only when those receipt-safe fields are actually missing.
Low confidence alone does not move a row into clarification.

Metadata-only clarification may not:

- treat synthetic `rawData.logs[]` as authoritative event evidence
- silently rewrite economic meaning without traceable evidence
- leave clarification-eligible rows without explicit missing receipt-safe reasons
- under-report a currently missing receipt-safe field in `missingDataReasons[]`

Implications:

- ambiguous `EXTERNAL_TRANSFER_IN` vs `REWARD_CLAIM` is not a clarification problem
- plain positive inbound transfer defaults are not a clarification problem
- promo/phishing suppression is not a clarification problem
- wrapped-native `deposit()` / `withdraw(uint256)` is not a clarification problem
- bridge entry / settlement selectors are not a clarification problem
- LP position-manager `multicall` / `modifyLiquidities` routing is not a clarification problem
- concentrated-liquidity mint rows that still lack deterministic position token
  id are a valid receipt-clarification problem and must surface
  `LP_POSITION_CORRELATION_REQUIRED`
- missing `transactionIndex` is a raw-repair problem before normalization, not a clarification retry
- `MISSING_CONTRACT_ADDRESS` is not a generic clarification fallback; it is valid
  only for explicit contract-creation rows
- missing `effectiveGasPrice` remains a clarification reason even when the
  tracked wallet is not the tx fee payer; live clarification rows must reflect
  the actual missing receipt-safe tx metadata
- `CLAIM_WITHOUT_MOVEMENT` is a valid per-wallet terminal outcome when the claim
  signer does not receive the reward transfer in persisted raw evidence

Clarification may additionally use full receipt evidence for an allowlisted
residual-review set:

- it may fetch and persist full receipt logs for that allowlisted set
- it should persist both:
  - the adapted clarification evidence used by runtime classification
  - the raw full receipt payload, when the source exposes it
- if a clarification source call already fetched a receipt payload, that
  source-native payload is persisted in full even when the current row uses
  only metadata-safe clarification semantics
- it must persist that clarification evidence in one canonical raw-level
  location that live Mongo audits and runtime classification both read
- clarification is not complete for a row unless that evidence is persisted on
  the raw document in a deterministic shape
- metadata-safe clarification versus receipt-log-backed clarification is a
  semantic policy only; it must not truncate an already fetched receipt before
  persistence
- it must store those fields separately from synthetic `rawData.logs[]`
- runtime classification and normalization access clarification evidence only
  through `OnChainRawTransactionView` / the canonical raw projection
- it may also persist same-source internal transfers for an allowlisted
  native-bridge subset when those internal legs are required for deterministic
  bridge continuity
- it may rerun classification only when official protocol semantics and the
  fetched receipt evidence together make the row deterministic
- it must fetch clarification evidence from the same source family that produced
  the raw row:
  - RPC-backed raw -> RPC clarification
  - Etherscan-family raw -> Etherscan-family clarification
  - Blockscout-backed raw -> Blockscout clarification
- cross-source fallback is allowed only as an explicit documented fallback, not
  as the default clarification path
- it may narrow a row into an explicit non-economic terminal state when receipt
  proves cleanup/admin behavior but not economic movement
- it may not use traces, explorer page labels, or analyst-only notes as runtime
  evidence
- it must not be used for rows that are already closable from current raw
  evidence, such as claim-family no-movement rows or known handler gaps
- safe current-raw non-economic families should leave `NEEDS_REVIEW` before
  clarification is even attempted, including spam / airdrop clusters,
  explicit `CLAIM_WITHOUT_MOVEMENT`, failed transactions, documented admin /
  governance actions, pending-request / pending-order initiation rows, and
  out-of-scope NFT or attestation mints
- if clarification still leaves only weak protocol identity, zero logs, or
  wrapper-only bookkeeping evidence, the row remains in the documented
  irreducible stop-condition set and is not forced into synthetic economic
  semantics

Clarification success does not by itself make the dataset pricing-ready.
Pricing remains blocked while a live post-rerun audit still shows:

- resolved wrapped-native selector rows leaking into `VAULT_DEPOSIT`,
  `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
- resolved recognized Across `depositV3(...)` rows leaking into `VAULT_DEPOSIT`
- resolved route-tagged bridge-initiation rows leaking into
  `EXTERNAL_TRANSFER_OUT`, including LI.FI / Jumper `callDiamondWith*`
  families and `transferRemote(...)`
- resolved Circle CCTP `redeem(bytes cctpMsg,bytes cctpSigs)` rows leaking into
  `VAULT_WITHDRAW`
- resolved explicit receiver-wallet `claim(...)` / `claimWithSig(...)` payout
  rows leaking into `EXTERNAL_TRANSFER_IN`
- resolved pending `claimSharesAndRequestRedeem(...)` rows leaking into
  priceable `EXTERNAL_TRANSFER_OUT`
- resolved claim-income rows leaking into `EXTERNAL_TRANSFER_IN`, including
  Pancake `harvest(...)` and vesting `release()`
- priceable GMX `createOrder(...)` rows leaking into `EXTERNAL_TRANSFER_OUT`
  before final settlement semantics exist
- GMX V2 `OrderVault + OrderCreated` or `executeOrder(...)` rows leaking into
  LP / custody / spot accounting families instead of the explicit derivative
  exclusion family
- clarification persistence mismatches where normalized clarification counters
  are ahead of persisted raw clarification evidence
- a broad repeatable review-tail family that should already be deterministic
  from current raw or allowlisted clarification evidence

Those are classification-time basis-semantics failures, not clarification-tail
warnings.

Pricing-readiness gate:

- an economic row may move to `PENDING_PRICE` or `CONFIRMED` only when
  persisted raw evidence or persisted clarification evidence proves non-fee
  movement semantics that are sufficient for cost-basis replay
- fee-only rows may remain resolved only when they belong to an explicit
  non-economic family such as `APPROVE`, `ADMIN_CONFIG`, or another documented
  terminal cleanup/admin type
- wrapper continuity, bridge-entry semantics, route-tagged bridge-initiation
  semantics, claim-income semantics, order-initiation demotion, liquidity
  entry/exit semantics, spam / airdrop demotion, and admin/config demotion
  must be correct before pricing/AVCO begins
- pricing/AVCO may start only when the residual review tail has been reduced to
  the documented irreducible stop-condition set; broad repeatable review
  families are still a basis blocker even when clarification is otherwise
  complete
- after `run/13`, normalization/clarification debt is no longer the blocker:
  - `NEEDS_REVIEW = 0`
  - `PENDING_CLARIFICATION = 0`
  - `clarification_persistence_mismatches = 0`
- pricing/AVCO may now begin from the cleaned canonical stream, and the active
  milestone shifts to price resolution and replay rather than further
  clarification closeout
- no new backfill is required to begin pricing; the current raw and normalized
  datasets are sufficient for the next stage

Current audited clarification candidate families for full receipt enrichment:

- Slipstream cleanup-burn, stake-contract, and zero-effect collect families
- Pancake / Infinity concentrated-liquidity exit and `modifyLiquidities`
  families
- selected Euler-style batch rows where full receipt logs reveal real asset
  transfers
- GMX `executeOrder(...)`
- Katana `routeSingle(...)`
- allowlisted native bridge families whose same-source internal transfers are
  required for deterministic bridge continuity
- `BLOCKSCOUT` concentrated-liquidity `LP_EXIT` / `LP_FEE_CLAIM` rows where
  wallet-scoped explorer evidence is missing native settlement transfers that
  tx-level clarification can still recover
  - metadata-only clarification must not consume these rows before the
    transfer-aware full-receipt pass runs
- Pancake / Infinity CL exit containers that prove collect / withdrawal /
  unwrap continuity from same-source clarification evidence
- Pancake / Infinity `modifyLiquidities(...)` rows that prove positive
  liquidity-add or tracked-wallet funding direction from persisted full receipt
- once those receipt-helpful families are closed, any remaining review row must
  either:
  - be a documented safe stop-condition with no basis impact, or
  - remain an explicit basis blocker that still prevents pricing/AVCO

Audited normalization-first exact-out rule:

- ParaSwap `swapExactAmountOut(...)` native-settlement rows such as
  `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`
  are not clarification debt when calldata already proves:
  - destination native asset
  - default/wallet beneficiary semantics
  - exact unwrap quantity
- In that case source-token refund must net into the source `SELL`, and replay
  must receive one positive native `BUY` leg instead of a synthetic same-asset
  refund acquisition.

Current audited normalization blocker after `run/14`:

- `Velora/ParaSwap` RFQ overload rows such as
  `swapOnAugustusRFQTryBatchFill(...)` are not clarification-only debt when
  current raw already proves one outbound wallet leg and one inbound maker leg.
- Those rows must resolve in normalization as `SWAP` from current raw transfer
  evidence and registry-backed router identity.
- Leaving such rows as
  `UNKNOWN / ROUTER_METHOD_OVERLOAD_UNSUPPORTED` is a classification-time
  basis blocker because it prevents replay from materializing at all.

---

## 8. Supported Accounting Scope

On-chain accounting networks:

- `ETHEREUM`
- `ARBITRUM`
- `OPTIMISM`
- `POLYGON`
- `BASE`
- `BSC`
- `AVALANCHE`
- `MANTLE`
- `LINEA`
- `UNICHAIN`
- `ZKSYNC`
- `KATANA`
- `PLASMA`

CEX accounting source:

- `BYBIT`

Out of scope for this milestone:

- additional CEX providers
- tax reporting
- NFTs
- rebase-token quantity tracking

---

## 9. Outputs

The replay layer must be able to produce:

- immutable asset-ledger points
- per-wallet asset quantity
- per-wallet AVCO
- realised PnL
- incomplete-history flags
- reconciliation status against available balance data

All outputs must be derivable again from the same canonical inputs with identical ordering.

For continuity events, replay must move quantity and carried basis without creating realised PnL.

### 9.1 Asset Ledger Timeline

Replay must persist one immutable `AssetLedgerPoint` for every applied state
transition on one exact asset bucket.

That timeline is the primary source for:

- asset-detail AVCO / cost-basis history
- economic-event overlay markers
- operator and audit debugging of the first broken or suspicious replay step

Each point must include:

- exact bucket identity: `accountingAssetIdentity`
- continuity family identity: `accountingFamilyIdentity`
- lifecycle grouping:
  - `normalizedType`
  - `lifecycleKind`
  - `lifecycleStage`
- basis interpretation: `basisEffect`
- deterministic order fields
- `quantityBefore/After`
- `totalCostBasisBeforeUsd/AfterUsd`
- `avcoBeforeUsd/AfterUsd`
- explicit unresolved diagnostics:
  - `quantityShortfallDelta`
  - `quantityShortfallAfter`
  - `uncoveredQuantityDelta`
  - `uncoveredQuantityAfter`
  - `basisBackedQuantityAfter`
  - `hasIncompleteHistoryAfter`
  - `hasUnresolvedFlagsAfter`
  - `unresolvedFlagCountAfter`

### 9.2 Three AVCO surfaces (do not mix at acceptance)

WalletRadar exposes **three independent AVCO metrics**. Comparing them without
context produces false regressions (Cluster E).

| Surface | Source | Typical use |
|---|---|---|
| **Dashboard header** | Live `on_chain_balances` quantity + latest per-bucket ledger basis (venue slices; no bare umbrella when sub-ledgers exist) | Current portfolio AVCO |
| **Family move-basis timeline** | `TimelineAvcoAuthority` on grouped events (`avcoKind=PRIMARY_FLOW` or `UNAVAILABLE`; no `FAMILY_ROLLUP` on the main line) | Historical spot AVCO chart (~$2k–$4k ETH band) |
| **Per-point ledger** | Raw `AssetLedgerPoint.avcoAfterUsd` / `avcoBeforeUsd` per exact bucket | Audit, replay debugging, per-wallet truth |

Timeline rules (ADR-017):

- Spot-family aggregation excludes `LP-RECEIPT:*` (own `FAMILY:LP_RECEIPT` at write).
- On `FAMILY:ETH` pages, **staked/liquid-staking ETH is included** in quantity rollup
  and AVCO authority: `ETH`, `WETH`, `AMANWETH`, `CMETH`, `METH`, `WEETH`, `WSTETH`,
  `STETH`, `RSETH`. Only `BBSOL` is excluded from `FAMILY:ETH`.
  (Amended 2026-05-29 per ADR-017; prior version incorrectly excluded staked ETH symbols.)
- LP receipt basis remains in `lp_receipt_basis_pools` and `FAMILY:LP_RECEIPT`
  ledger rows; it is not summed into ETH share-count denominators.
- Chart `avcoBefore` should follow the previous timeline row of the same
  `accountingAssetIdentity` spot series.

`basisEffect` intent:

- `ACQUIRE`
  economic acquisition; basis increases from priced inbound quantity
- `DISPOSE`
  economic disposal; carried basis decreases and realized PnL may be created
- `CARRY_OUT`
  quantity and carried basis leave the current bucket into a continuity bucket
- `CARRY_IN`
  quantity and carried basis restore from a continuity bucket into the current bucket
- `REALLOCATE_OUT`
  quantity/basis leaves one bucket as part of an async lifecycle or LP/vault custody transition
- `REALLOCATE_IN`
  quantity/basis restores into another bucket from an async lifecycle or settlement bucket
- `GAS_ONLY`
  fee-only replay step; no acquisition lot
- `UNKNOWN`
  replay mutated state conservatively but could not prove the exact economic meaning

`lifecycleStage` intent:

- `SINGLE`
- `REQUEST`
- `SETTLEMENT`
- `SOURCE`
- `DESTINATION`

`lifecycleKind` intent:

- `SPOT`
- `TRANSFER`
- `BRIDGE`
- `CUSTODY`
- `LENDING`
- `STAKING`
- `VAULT`
- `LP`
- `ORDER`
- `LOOP`
- `WRAP`
- `REWARD`
- `DERIVATIVE`
- `MANUAL`
- `UNKNOWN`

Family/lifecycle policy:

- `ETH` family continuity applies to `ETH`, `WETH`, and audited receipt/custody
  wrappers already mapped into one accounting family
- `USDC` family continuity applies to audited stable wrappers already mapped
  into one accounting family
- all other assets default to exact-asset family identity unless explicit
  policy broadens them
- correlated external transfer replay may still use a replay-local canonical
  symbol alias key for already-proven continuity when exact source contract
  identity and destination provider symbol identity differ
- that alias key is bounded to replay matching only and does not expand
  persisted portfolio family aggregation by itself
- lifecycle pairing still comes from canonical normalization and clarification
  (`correlationId`, `matchedCounterparty`, `continuityCandidate`)
- the ledger timeline does not invent lifecycle pairing; it records what replay
  actually did with already-correlated rows
- simple audited family-equivalent custody rows with exactly one principal
  outbound transfer and one receipt inbound transfer replay as one atomic carry
  pair even when normalized flow order is inbound-first
- this rule is required for Aave-style `ETH/WETH <-> aToken` deposits and
  withdraws where canonical transfer ordering may not match economic carry
  direction
- audited rebasing `Aave` WETH receipt rows require one extra normalization
  rule before replay:
  - if receipt-side quantity on deposit is larger than tx-local principal moved,
    replayable canonical flows must be:
    - principal `TRANSFER` out
    - principal-sized receipt `TRANSFER` in
    - receipt excess `BUY`
  - if underlying-side quantity on withdraw is larger than tx-local receipt
    burned, canonical flows must be:
    - receipt `TRANSFER` out
    - receipt-sized underlying `TRANSFER` in
    - underlying excess `BUY`
  - this excess is passive accrued yield materialized at the touched tx, not a
    fresh uncovered principal lot
- the same principal/excess split policy also applies to audited
  family-equivalent `LENDING_*`, `VAULT_*`, and `WRAP / UNWRAP` rows when one
  same-family principal leg is preserved and the destination quantity is larger
  than the moved principal
- canonical replay for those rows must therefore keep:
  - source-side principal `TRANSFER`
  - destination-side principal-sized `TRANSFER`
  - destination excess `BUY`
- for those rows replay keeps full source cost basis on the destination leg and
  leaves only destination excess quantity uncovered

External integration rule:

- provider-specific enrichment must complete during external `BACKFILL`
- Bybit runtime uses:
  - immutable `integration_raw_events`
  - rebuildable `bybit_extracted_events`
  - canonical `normalized_transactions`
- canonical normalization may not call provider APIs
- ambiguous provider rows after full backfill enrichment must remain
  `UNKNOWN / NEEDS_REVIEW`; they do not enter a later provider clarification
  lane

After replay completes, WalletRadar must mark `ACCOUNTING_REPLAY` complete
before refreshing live portfolio evidence. Live evidence refresh runs in the
separate `PORTFOLIO_SNAPSHOT_REFRESH` pipeline stage and refreshes
`on_chain_balances` from the current session wallet subset only, then refreshes
current market quotes for positive live-balance symbols.

This stage is a dashboard/read-model snapshot stage. It must not change
canonical transactions, pricing history, AVCO state, or `asset_ledger_points`.
If it is slow or temporarily fails, the accounting replay result remains the
authoritative replay output; dashboard reads may continue using the latest
persisted snapshot and expose staleness/issue metadata.

Provider-native balances are still native evidence even when the upstream
provider returns `contractAddress = 0x0000000000000000000000000000000000000000`.
That payload must normalize into `NATIVE:<NETWORK>` before reconciliation and
must never be persisted as an ERC-20-like contract identity.

Zero live balances are valid evidence and must remain persisted. Missing
evidence is still `NOT_APPLICABLE`, not synthetic zero.

Current holdings are derived on read:

- `currentQuantity` comes from `on_chain_balances`
- basis and PnL fields come from the latest exact-bucket `AssetLedgerPoint`
- `currentHolding = true` when `currentQuantity > 0`
- replay quantity deficits remain explicit:
  - `quantityShortfall = latest(quantityShortfallAfter)`
  - `uncoveredQuantity = latest(uncoveredQuantityAfter)`
  - `basisBackedDerivedQuantity = max(derivedQuantity - uncoveredQuantity, 0)`
  - `currentCoveredQuantity = min(currentQuantity, basisBackedDerivedQuantity)`
  - `currentUncoveredQuantity = currentQuantity - currentCoveredQuantity`
  - `currentCostBasisProvable = currentUncoveredQuantity == 0`
- `quantityShortfall` is a lifetime audit counter for historical coverage gaps;
  it must not erase basis coverage for later acquisitions once the missing
  quantity is no longer held
- when a later sell, fee, or unknown outbound consumes a mixed replay bucket,
  replay consumes the uncovered current tail before covered AVCO-backed
  quantity. This keeps `uncoveredQuantity` aligned with what is still held,
  while preserving historical `quantityShortfall` as the audit trail.
- continuity carry outflows keep the existing covered-first transfer contract
  so linked destination buckets receive available basis before any unresolved
  transfer tail.
- current live accrual beyond replay-carried principal is uncovered current
  quantity, not silently basis-backed principal
- live-positive rows with zero carried basis must remain explicit uncovered
  current quantity, not synthetic basis
- small native ETH gas-reserve residuals may be scorecard-classified as
  non-blocking only when replay has reached a native `GAS_ONLY` terminal point,
  no later canonical economic flow explains the current balance, and the
  residual is below the deterministic `0.0015 ETH` threshold. This is a
  reporting/materiality policy; it does not synthesize cost basis.
- any residual blocking quantity at or below `1e-9` units after explicit
  scorecard policies is classified as non-blocking sub-unit dust. This is only
  a reporting threshold and never creates carried basis.
- session family asset-ledger reads may expose `shortfallSources` aggregated
  from positive `quantityShortfallDelta` rows
- this is an audit/debug surface for historical provenance debt, not a replay
  input and not a claim that every listed source still maps one-to-one into the
  current uncovered bucket

User-facing holdings and portfolio totals must be computed from derived
`asset_ledger_points + on_chain_balances`, not from a persisted compatibility
snapshot.

User-facing asset history must be computed from session-filtered
`asset_ledger_points`.

Replay lineage rules:

- `asset_ledger_points` is universe-scoped and must carry `accountingUniverseId`
- `on_chain_balances` is session-scoped and must carry `sessionId`
- carry continuity may only be attached within the active accounting universe
- installation-wide tracked-wallet heuristics may still help canonical typing,
  but they must not force basis inheritance across unrelated owner scopes

Session-level asset history rules:

- filter wallet-level points by the session's stable `accountingUniverseId`
- filter by `accountingFamilyIdentity`
- preserve deterministic order
- aggregate `quantityDelta`, `costBasisDeltaUsd`, `realisedPnlDeltaUsd`, and
  `gasDeltaUsd` on read
- recompute session-level AVCO from aggregated post-point state
- overlay economic events from the same ordered trace; do not re-fetch or
  re-derive them from RPC during the request path

External provider policy:

- provider-specific enrichment belongs to external backfill
- normalization must not call provider APIs
- if persisted provider evidence is still insufficient after the provider
  backfill/hydration pass, the row becomes explicit review work rather than a
  late provider-clarification candidate

For authoritative AVCO answers:

- the fully provable current subtotal is the set of rows where
  `currentCostBasisProvable = true`
- rows with `currentUncoveredQuantity > 0` remain live-valid holdings, but
  their full current quantity is not yet fully covered by replay-carried basis
