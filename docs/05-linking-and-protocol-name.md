# Linking And Protocol Name

This document explains how WalletRadar links events and how `protocolName` is
assigned.

The main source of confusion is that the system has more than one kind of
"linking", and they serve different purposes.

If these layers are mixed together, the result is bad:

- accounting starts depending on UI grouping
- chart grouping starts mutating canonical semantics
- `protocolName` is mistaken for an economic fact
- bridge/custody continuity becomes harder to reason about

This document separates those layers.

## 1. The four different linking layers

WalletRadar has four different linking problems:

1. canonical transaction identity
2. lifecycle / continuity linking
3. replay carry linkage
4. display grouping

They are not interchangeable.

## 2. Canonical transaction identity

The first unit of truth is the canonical normalized row.

For on-chain rows, the idempotency key is:

- `(txHash, networkId, walletAddress)`

For external-ledger rows, the id is provider-specific.

A canonical normalized row answers:

- what economic type this row is
- which flows it contains
- whether it is confirmed / pending / needs review

It does **not** answer:

- which other row should be drawn on the same chart marker
- whether this row is an internal transfer for the current user
- whether UI should collapse it with another row

Relevant fields:

- `normalizedTransaction.id`
- `txHash`
- `networkId`
- `walletAddress`
- `type`
- `flows`
- `status`

Rule:

- never mutate canonical economics just to make the chart cleaner

## 3. Lifecycle and continuity linking

The second layer links multiple canonical rows that belong to one economic
lifecycle.

Examples:

- `BRIDGE_OUT -> BRIDGE_IN`
- `LP_ENTRY_REQUEST -> LP_ENTRY_SETTLEMENT`
- `DEX_ORDER_REQUEST -> DEX_ORDER_SETTLEMENT`
- `DERIVATIVE_ORDER_REQUEST -> DERIVATIVE_ORDER_EXECUTION`
- on-chain wallet leg <-> Bybit custody leg

This layer is expressed through:

- `correlationId`
- `matchedCounterparty`
- `continuityCandidate`

Cycle 79 row-local counterparty rule:

- `counterpartyAddress` is not a lifecycle-pair field
- for swaps it is the row-local router/pool/aggregator contract
- for bridge starts it is the origin bridge endpoint or routed bridge helper
- for bridge settlements it is the destination bridge endpoint or settlement
  contract when recoverable
- for lending, vault, staking, and LP rows it is the row-local market, vault,
  staking, pool, or position-manager contract
- for true external transfers it is the unique direct sender/recipient when
  recoverable
- `matchedCounterparty` remains the remote lifecycle peer
- `correlationId` remains the lifecycle grouping key

Cycle 79 protocol-name rule:

- `protocolName` is required metadata whenever raw transaction evidence,
  registry evidence, selector evidence, or lifecycle evidence proves protocol
  identity
- `protocolName` must not become a hidden accounting rule
- missing protocol identity on deterministic rows is an enrichment defect, not
  an AVCO computation defect

Wallet `<-> Bybit` rule:

- keep canonical `type` external: `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN`
- use deterministic correlation id `BYBIT:<NETWORK>:<txHash>`
- restore the wallet-local continuity metadata during clarification
  post-processing if an on-chain-only rerun rebuilt the wallet row without the
  already known Bybit match
- do not apply this repair when no real Bybit canonical row exists for the same
  `txHash + networkId`; dust / spam tx may not self-promote into same-universe
  custody continuity

Routed bridge rule:

- a same-wallet `LI.FI / Jumper` source row and a later Relay / bridge
  settlement row may share `correlationId` and `matchedCounterparty` even when
  the pair is cross-network
- this is lifecycle linking, not chart grouping
- the pair is allowed only from protocol-backed evidence:
  - source is already a proved route-tagged bridge start
  - destination sender is registry-backed settlement infrastructure
  - one unique candidate exists inside the audited bounded window
- do not create the pair from loose timestamp proximity alone

### `correlationId`

`correlationId` is the shared lifecycle key.

Use it when multiple rows belong to the same protocol lifecycle or continuity
corridor.

Examples:

- async LP request and settlement
- concentrated-liquidity position lifecycle
- bridge source and destination pair
- route-tagged bridge lifecycle

What it means:

- "these rows are part of one proved lifecycle"
- for concentrated-liquidity positions, "these rows belong to one NFT-scoped
  position lifecycle", for example
  `lp-position:arbitrum:pancakeswap:196975`

What it does **not** mean:

- "merge these rows into one canonical row"
- "use one row's protocolName for all nearby rows without proof"

### `matchedCounterparty`

`matchedCounterparty` is the explicit row-to-row pair.

Use it when one row has one primary opposite-side counterpart.

Examples:

- exact bridge source tx matched to exact destination tx
- exact Bybit withdraw/deposit match
- async request matched to its exact settlement

What it means:

- "this is the best exact paired counterparty for this row"

What it does **not** mean:

- "all nearby rows should be hidden"

### `continuityCandidate`

`continuityCandidate` answers a narrower accounting question:

- can replay try to carry basis from one row into another?

This is an accounting flag, not a chart flag.

Rule:

- never derive display grouping from `continuityCandidate` alone
- clarification may promote a reciprocal same-tx on-chain pair into canonical
  `INTERNAL_TRANSFER`, but only after an exact peer row is found
- a tracked-counterparty hint without the reciprocal canonical row is still not
  enough to reclassify the row as internal
- if the reciprocal canonical row is missing only because raw coverage is
  one-sided, normalization may first repair the missing same-universe raw peer
  for a simple direct native transfer

## 4. Replay carry linkage

Replay uses canonical rows plus lifecycle links to decide cost-basis carry.

This layer is not exposed as a single user-facing field, but it is where basis
actually moves.

Examples:

- carry basis across canonical `INTERNAL_TRANSFER`
- carry basis across same-family `BRIDGE_OUT -> BRIDGE_IN`
- carry basis across exact custody moves
- preserve uncovered share across linked bridge settlements

Rule:

- replay linkage must remain deterministic and conservative
- UI grouping must never create replay linkage

In other words:

- chart grouping is allowed to be approximate and UX-driven
- replay linkage is not

## 5. Display grouping

Display grouping exists only to make the asset-ledger chart and cards readable.

This is where many users expect "linking", but it is the weakest and safest
layer.

Display grouping should answer:

- which canonical rows should be drawn as one marker
- which rows belong under one expandable card
- which rows should still remain separate in the table

Current backend display surface already exposes:

- `eventGroupId`
- `memberNormalizedTransactionIds`
- `fromAddress`
- `toAddress`

### `eventGroupId`

`eventGroupId` is the display/event overlay key.

It is allowed to collapse more than one canonical row into one chart/event
overlay item.

Examples:

- same-universe custody move drawn as one event corridor
- multi-leg external-ledger execution grouped into one overlay event

What it means:

- "draw these rows together on the timeline / overlay surface"

What it does **not** mean:

- "these rows are one canonical transaction"
- "these rows share one accounting identity"

### `memberNormalizedTransactionIds`

`memberNormalizedTransactionIds` tells the UI which canonical rows are inside
the display event.

Use it for:

- hover details
- expanding one grouped marker into child rows
- highlighting underlying rows in the event log

### Important rule

Display grouping is allowed to be chart-only.

That means:

- graph marker may be grouped
- hover card may be grouped
- event log table may still show child rows separately

This is the right place for:

- gas-topup cluster grouping
- dust/noise grouping
- same-universe custody grouping

It is **not** the right place for:

- deciding protocol economics
- deciding basis carry

## 6. How `protocolName` is assigned

`protocolName` is best-effort metadata on the canonical normalized row.

It exists for:

- filtering
- debugging
- user-facing context

It does **not** by itself define the economic type.

### Stage 1: normalization-time direct hit

During initial classification, the system may assign `protocolName` directly if
there is a deterministic registry hit.

Typical sources:

- exact contract address in `protocol-registry.json`
- method-aware registry match
- protocol semantic classifier hit

This is the strongest and cheapest case.

### Stage 2: clarification-time enrichment

If initial normalization did not assign a reliable `protocolName`, clarification
may enrich it later using persisted raw and clarification evidence.

The dedicated service is:

- `ProtocolNameEnrichmentService`

This stage is allowed to use:

- raw interacted contract address
- clarified contract address
- persisted logs
- source-side sender for bridge / transfer-like families when that source is
  actually relevant

This stage is **not** allowed to:

- change flows
- change accounting semantics
- invent protocol identity from explorer prose

Clarification-time enrichment is the correct place for protocol labels because
by this moment the system may already know more than the initial classifier:

- exact receipt-safe contract identity
- lifecycle links
- related discovered txs

### Stage 3: repair sweep

Historical rows may be repaired later by a protocol-name sweep.

This is useful when:

- registry coverage expands
- canonical branding changes
- new enrichment rules are added

The repair sweep may fill:

- `protocolName`
- `protocolVersion`

without rerunning full normalization, as long as economics do not change.

## 7. What `protocolName` is and is not

`protocolName` is:

- a best-effort canonical label
- safe for filtering and debugging
- allowed to improve over time

`protocolName` is not:

- a lifecycle key
- a pairing key
- a proof of basis continuity
- a reason to reclassify by itself

Bad mental model:

- "same protocolName means same event"

Correct mental model:

- "same protocolName means we believe these rows interacted with the same
  protocol brand or component"

## 8. How to decide what to use

Use this decision table.

### Question: "Are these two rows the same economic lifecycle?"

Use:

- `correlationId`
- `matchedCounterparty`

### Question: "Can basis move from this row to another row?"

Use:

- replay continuity rules
- `continuityCandidate`
- `correlationId`
- `matchedCounterparty`

### Question: "Should these rows draw as one marker on the chart?"

Use:

- `eventGroupId`
- `memberNormalizedTransactionIds`
- optional chart-only frontend grouping

### Question: "Which protocol should I show on the row?"

Use:

- `protocolName`
- `protocolVersion`

### Question: "Can I reclassify just because protocolName changed?"

Answer:

- no
- only reclassify if the newly available evidence changes actual economic
  meaning

## 9. Recommended frontend mental model

For the asset-ledger page, use three UI layers:

1. chart marker layer
2. event card / hover layer
3. event log table

Recommended behavior:

- chart markers may be grouped
- hover cards may show grouped context plus child rows
- event log table may keep original rows separate

This keeps the page readable without destroying auditability.

## 10. Recommended rules for chart-only grouping

Chart-only grouping is appropriate when:

- canonical rows remain valid and auditable
- grouping is mostly for UX clarity

Examples:

- same-account custody transfer
- protocol-funded gas top-up cluster
- dust/noise cluster

Important boundary:

- `SPONSORED_GAS_IN` may be grouped with a nearby parent action on the chart
  later
- but that grouping must not create canonical `correlationId` /
  `matchedCounterparty` by itself

Recommended rule:

- group on the chart only
- keep canonical rows unchanged
- keep event log rows separate
- expose child row ids through `memberNormalizedTransactionIds`

## 11. Anti-patterns

Do not do these:

- do not use `protocolName` as a hidden accounting rule
- do not merge canonical rows just because UI wants one marker
- do not create `correlationId` from loose visual proximity
- do not let frontend grouping mutate replay semantics
- do not assume all rows in one chart cluster share the same protocol

## 12. Practical summary

The shortest correct explanation is:

- canonical rows answer "what happened"
- `correlationId` / `matchedCounterparty` answer "what belongs to the same lifecycle"
- replay continuity answers "where basis may move"
- `eventGroupId` answers "what may be drawn together"
- `protocolName` answers "which protocol label should we show"

If those questions stay separate, the system remains explainable.
