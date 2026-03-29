# Run 32 GMX Terminal Position + Async Exit Parity Closeout

Goal:

Close the remaining active-lane `GMX / GLV` classification gaps so the dataset
becomes operationally ready for `pricing -> AVCO -> cost basis -> move basis`
without transaction-hash hardcodes and without broad raw backfills.

This slice must make the audited `GMX` scope financially usable end-to-end:

- terminal derivative keeper txs must resolve to the most specific lifecycle
  already proved by current persisted receipt evidence
- async `GM / GLV` withdrawal requests must gain the same request / settlement
  parity that audited entry-side lifecycles already have
- clarification must enrich existing same-source settlement rows in place when
  the request row already proves the family
- request / terminal / settlement pairing must materialize into normalized rows
  through deterministic `correlationId` and `matchedCounterparty`

No runtime code may hardcode audited tx hashes.

## Scope

In scope:

- classify `GMX executeOrder(...)` keeper txs from any persisted lifecycle log
  shape already available in `clarificationEvidence.fullReceipt.logs`
- keep `OrderExecuted` generic only when current production evidence proves
  execution but still does not prove `PositionIncrease` or `PositionDecrease`
- classify audited `GM / GLV` helper `multicall(bytes[])` share-burn exits as
  `LP_EXIT_REQUEST` even when `bytes[]` selector decoding is incomplete
- allow request-side typed rows to trigger targeted clarification of existing
  raw settlement candidates that still lack `fullReceipt`
- keep current `GM / GLV` entry-side lifecycles stable
- keep `CoW` request / settlement parity stable

Out of scope:

- broad backfill across whole chains
- synthetic helper-subcall raw docs
- unrealized derivative mark-to-market
- venue-generic perpetual accounting outside the audited `GMX V2` scope

## Acceptance Criteria (DoD)

1. A `GMX executeOrder(...)` keeper tx with persisted same-receipt
   `PositionIncrease` evidence resolves to `DERIVATIVE_POSITION_INCREASE`, not
   generic `DERIVATIVE_ORDER_EXECUTION`.

2. A `GMX executeOrder(...)` keeper tx with persisted same-receipt
   `PositionDecrease` evidence resolves to `DERIVATIVE_POSITION_DECREASE`, not
   generic `DERIVATIVE_ORDER_EXECUTION`, even when the receipt also contains a
   sibling `OrderCancelled(AUTO_CANCEL)` event.

3. Generic `DERIVATIVE_ORDER_EXECUTION` remains allowed only when current
   persisted production evidence proves execution but does not prove position
   direction.

4. An audited `GM / GLV` async pool exit request resolves to `LP_EXIT_REQUEST`,
   not `EXTERNAL_TRANSFER_OUT`, from the following combined evidence:
   - top-level `multicall(bytes[])`
   - helper funding selectors present
   - audited withdrawal-request selector family present either from decoded
     `bytes[]` subcalls or from saved raw calldata fragments
   - wallet-visible movement is outbound-only
   - outbound principal includes a burned `GM` or `GLV` share asset

5. A typed async exit request must make an existing raw settlement candidate
   eligible for targeted clarification when that settlement tx already exists in
   Mongo but is still missing `fullReceipt`.

6. The later keeper / settlement tx must materialize visible lifecycle linkage
   back to the request row through deterministic `correlationId` and
   `matchedCounterparty`.

7. No runtime code path may special-case audited tx hashes for `GMX`, `GLV`, or
   `CoW` lifecycle classification or clarification.

8. `on-chain NEEDS_REVIEW = 0` remains true after rerun.

## Normalization Rules To Update

1. `GMX` terminal derivative typing is receipt-evidence driven.
   - If the persisted receipt already proves `PositionIncrease`, the keeper tx
     must resolve to `DERIVATIVE_POSITION_INCREASE`.
   - If the persisted receipt already proves `PositionDecrease`, the keeper tx
     must resolve to `DERIVATIVE_POSITION_DECREASE`.
   - `DERIVATIVE_ORDER_EXECUTION` is a bounded fallback, not the preferred
     steady state.

2. `GMX` lifecycle detection may not depend only on EventEmitter-decoded human
   labels.
   - EventEmitter logs remain authoritative for request-key correlation.
   - Same-receipt structured lifecycle logs may refine terminal typing when the
     receipt already contains audited `GMX` EventEmitter evidence.

3. `GM / GLV` async exit request typing is protocol-shape-driven.
   - The runtime should first use decoded `bytes[]` subcall selectors.
   - If selector decode is incomplete, the runtime may fall back to saved raw
     calldata fragments for the audited withdrawal-request selector family.
   - This fallback is valid only together with the audited helper-funding
     selectors, outbound-only movement, and burned `GM/GLV` share principal.

4. Existing same-source settlement rows must be enriched in place.
   - If a settlement tx already exists in `raw_transactions` but lacks
     `fullReceipt`, clarification must fetch the missing receipt for that row.
   - Related lifecycle discovery is only for genuinely absent txs, not as a
     replacement for in-place enrichment.

5. Readiness checks must keep validating active-lane lifecycle precision.
   - `on-chain NEEDS_REVIEW = 0` is insufficient while `GMX` keeper txs or
     `GM/GLV` exit requests still persist as the wrong active type.

## Task Breakdown

1. `BE-07CQ` GMX terminal lifecycle precision
   - widen keeper lifecycle detection from current persisted receipt evidence
   - keep request-key correlation on authoritative EventEmitter logs
   - cover increase / decrease / generic execution fallback with
     production-like receipt shapes

2. `BE-07CR` GMX / GLV async exit request parity
   - keep current decoded-subcall path
   - add raw-calldata selector-fragment fallback for the audited withdrawal
     request family
   - require outbound-only movement plus burned `GM/GLV` share principal

3. `BE-07CS` clarification handoff
   - ensure typed request rows expose existing settlement candidates for
     targeted full-receipt enrichment
   - keep same-source bounded behavior and do not introduce broad scans

4. `BE-07CT` regression lock
   - add classifier tests for:
     - structured `GMX` position-increase / decrease keeper receipts
     - async exit request with incomplete `bytes[]` selector decode
   - rerun relevant normalization / clarification suites on preserved raw
     evidence only

## Risk Notes

- Do not widen the raw-calldata fallback into a generic `multicall + burned
  share` heuristic. The audited helper selector family and withdrawal-request
  selector family must still be required.
- Do not regress current `LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT` handling while
  opening exit-side parity.
- Do not let generic transfer fallback win for `GMX` terminal keeper rows once
  current persisted receipt evidence already proves the lifecycle.
