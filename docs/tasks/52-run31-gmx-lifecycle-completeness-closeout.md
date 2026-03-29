# Run 31 GMX Lifecycle Completeness Closeout

Goal:

Close the remaining active-lane `GMX / GLV / CoW` lifecycle gaps so the on-chain
dataset becomes ready for `pricing -> AVCO -> cost basis -> move basis` without
transaction-hash hardcodes.

This slice must finish the audited `GMX` scope end-to-end:

- `GMX V2` derivative requests, increases, decreases, cancels
- `GM / GLV` async LP-entry and LP-exit request / settlement lifecycles
- `CoW` async spot-order request / settlement parity
- request/settlement / request/terminal pairing materialized into normalized
  docs through `correlationId` and `matchedCounterparty`
- clarification must fetch same-source missing receipts whenever current raw is
  already sufficient to prove the protocol family but not yet sufficient to
  prove the terminal lifecycle

No broad backfill is allowed. No runtime code may hardcode audited tx hashes.

## Scope

In scope:

- fix `GMX` EventEmitter topic matching so persisted production receipt logs
  resolve the most specific keeper lifecycle already proved by the evidence
- classify `GMX / GLV` withdrawal requests from helper `multicall(bytes[])`
  shape plus burned share asset, even when the top-level `to` is not in the
  protocol registry
- materialize request-side and terminal-side linkage from normalized
  `correlationId` plus same-receipt sibling order keys
- reuse the same linking logic from normalization, clarification, and related
  lifecycle discovery
- remove `GMX` receipt-clarification hash allowlists and replace them with
  rule-driven eligibility
- keep `GM / GLV` entry-side lifecycle stable
- keep `CoW` settlement parity stable

Out of scope:

- generic perpetual PnL across venues other than the audited `GMX V2` family
- new raw-source collections or child raw docs for helper subcalls
- mark-to-market / unrealized derivative accounting

## Acceptance Criteria (DoD)

1. A keeper tx with persisted `GMX` EventEmitter `PositionIncrease` topic
   resolves to `DERIVATIVE_POSITION_INCREASE`, not generic
   `DERIVATIVE_ORDER_EXECUTION`, even when there is no human-readable
   `eventName` / `decodedEvent` in the receipt.

2. A keeper tx with persisted `GMX` EventEmitter `PositionDecrease` topic
   resolves to `DERIVATIVE_POSITION_DECREASE`, not generic
   `DERIVATIVE_ORDER_EXECUTION`, even when the receipt also contains a sibling
   `OrderCancelled` event.

3. `GMX / GLV` helper `multicall(bytes[])` + outbound `GM` or `GLV` share burn
   resolves to `LP_EXIT_REQUEST`, not `EXTERNAL_TRANSFER_OUT`, without relying
   on a top-level router address registry hit.

4. Missing same-source keeper receipts for existing raw settlement candidates
   are fetched during clarification once the request row already proves the
   audited `GMX / GLV` async exit family.

5. A request row and its later settlement / terminal keeper row must gain
   visible `matchedCounterparty` linkage once they share the same deterministic
   `correlationId`.

6. `GMX` terminal keeper receipts that execute one derivative order and
   auto-cancel a sibling order in the same receipt must materialize both
   outcomes:
   - executed request -> later keeper tx visible via `matchedCounterparty`
   - sibling auto-cancelled request -> later keeper tx visible via
     `matchedCounterparty`

7. No runtime code path may special-case audited tx hashes to classify or
   clarify `GMX`, `GLV`, or `CoW` lifecycles.

8. `on-chain NEEDS_REVIEW = 0` remains true after rerun.

## Normalization Rules To Update

1. `GMX` EventEmitter topic identity is case-sensitive and canonical.
   - Runtime topic matching must hash canonical event names such as
     `OrderExecuted`, `OrderCancelled`, `PositionIncrease`,
     `PositionDecrease`, `WithdrawalCreated`, `WithdrawalExecuted`,
     `GlvWithdrawalCreated`, and `GlvWithdrawalExecuted`.
   - Runtime may not lower-case the event name before hashing.
   - Hashed topic identity is authoritative even when the receipt has no
     human-readable event label.

2. `GMX / GLV` async withdrawal request classification is shape-driven.
   - helper `multicall(bytes[])`
   - contains helper funding selectors
   - contains audited withdrawal-request subcall selectors
   - wallet-visible movement is outbound-only
   - outbound principal includes a `GM` / `GLV` share burn
   - this is sufficient for `LP_EXIT_REQUEST`
   - top-level `to` registry lookup may raise confidence, but it may not be a
     required condition

3. Request / settlement linkage is a shared runtime concern.
   - once a normalized row has a deterministic `correlationId`, the runtime
     must look for same-wallet same-network on-chain rows with the same
     correlation and materialize `matchedCounterparty`
   - `GMX` terminal keeper receipts may contribute additional sibling
     `correlationId`s from the same receipt
   - this linkage must run after normalization, after clarification
     reclassification, and after related lifecycle discovery

4. Clarification eligibility must be rule-driven.
   - `GMX` lifecycle clarification may be triggered by:
     - request-side `PENDING_CLARIFICATION` with explicit lifecycle reason
     - existing generic settlement candidate selected from a typed request
     - derivative execution candidate with missing receipt context
   - tx-hash allowlists are not an acceptable runtime contract

5. Same-source settlement clarification remains bounded.
   - if the raw settlement tx already exists in Mongo but lacks full receipt,
     clarification must enrich that row in-place
   - if the settlement tx is absent from Mongo, related lifecycle discovery may
     fetch and normalize it from the same source family

## Task Breakdown

1. `BE-07CL` GMX EventEmitter topic closeout
   - canonical topic hashing for `GMX` event names
   - hashed-topic-only production receipt coverage for increase / decrease /
     cancel / withdrawal events
   - regression coverage using production-like receipt shapes

2. `BE-07CM` GMX / GLV exit request parity closeout
   - request detection from multicall shape + burned share asset
   - remove dependence on top-level router registry hit
   - keep entry-side request logic stable

3. `BE-07CN` shared lifecycle linker
   - extract request/settlement / terminal linking into one shared service
   - run it from normalization, clarification, and related discovery
   - materialize sibling auto-cancel linkage from `GMX` terminal receipts

4. `BE-07CO` rule-driven clarification eligibility
   - remove `GMX` tx-hash allowlists
   - keep `CoW` and `GMX / GLV` clarification selection deterministic
   - ensure existing raw settlement candidates are fetched when request-side
     typing already proves the family

5. `BE-07CP` rerun lock
   - regression tests for:
     - hashed-topic-only `GMX` increase / decrease
     - `GMX / GLV` exit request without top-level router registry hit
     - lifecycle linking from normalization path
     - lifecycle linking from related discovery path
   - rerun from preserved raw evidence only

## Risk Notes

- Do not widen `GMX` request detection into a generic multicall + burned-share
  heuristic. Require the audited helper selector family and withdrawal subcall
  family.
- Do not regress `LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT`.
- Do not let request / terminal linkage depend on which stage saw the tx first.
- Do not introduce new tx-hash allowlists while removing the old `GMX` one.
