# Run 29 GMX Terminal Lifecycle + CoW Settlement Closeout

Goal:

Close the `run/29` active-lane blockers without widening the raw-source model:

- `GMX` derivative rows must resolve to the precise lifecycle already proved by
  current full-receipt evidence
- missing real keeper txs inside audited `GMX` derivative lifecycles must be
  discovered and persisted during clarification from the same explorer-source
  family, not left outside Mongo
- `CoW` request / settlement rows must resolve as one explicit async spot-order
  lifecycle and may not leak into generic `EXTERNAL_TRANSFER_*`

This slice keeps `1 real tx = 1 raw doc = 1 normalized doc`. It does not
materialize helper subcalls and does not introduce a generic derivative indexer.

## Scope

In scope:
- keep `raw_transactions` plus persisted clarification evidence as the only
  canonical evidence layer
- expose already-persisted full-receipt clarification evidence even when the
  explicit attempt counter is absent or stale
- decode `GMX` keeper lifecycle from persisted EventEmitter logs
- discover missing real `GMX` keeper txs from the same explorer family by
  wallet-visible transfer search plus receipt-log validation
- persist those discovered txs back into `raw_transactions` and normalize them
  immediately so one rerun pass stays operationally complete
- keep `GM / GLV` pool request / settlement parity intact
- keep `CoW` settlement correlation deterministic from current request calldata
  plus persisted `Trade(...)` receipt logs
- keep on-chain `NEEDS_REVIEW = 0`

Out of scope:
- a generic derivative PnL engine across venues other than the audited `GMX V2`
  family
- helper-subcall materialization as child raw docs
- broad chain backfill
- unrealized derivative mark-to-market

## Acceptance Criteria (DoD)

1. `0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7`
   resolves to `DERIVATIVE_POSITION_INCREASE`, not generic
   `DERIVATIVE_ORDER_EXECUTION`.
2. The classifier must use persisted receipt evidence even when
   `clarificationEvidence.fullReceipt` exists without a clean
   `fullReceiptClarificationAttempts > 0` counter.
3. `0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0`
   resolves to `DEX_ORDER_SETTLEMENT`, not `EXTERNAL_TRANSFER_IN`.
4. `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`
   remains `DEX_ORDER_REQUEST` and shares the same deterministic
   `correlationId` with `0xd7abb...`.
5. Missing real `GMX` keeper txs that are discoverable from current source
   evidence are persisted as new `raw_transactions` docs during clarification.
6. That discovery path must use:
   - current wallet
   - current network
   - current source family
   - bounded explorer token / internal transfer scans
   - receipt-log validation before persistence
7. The audited missing close-side keeper tx
   `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
   becomes persisted raw evidence when the source family returns it.
8. Newly discovered keeper txs are normalized immediately in the same
   clarification pass; they do not require a second manual rerun to become
   visible in `normalized_transactions`.
9. `GM / GLV` pool request / settlement parity does not regress.
10. On-chain `NEEDS_REVIEW = 0` after rerun. If evidence is receipt-derivable
    or same-source-transfer-derivable, clarification must fetch it.

## Normalization Rules To Update

1. Persisted clarification evidence is authoritative whenever it exists in the
   canonical raw shape. Runtime must not ignore it only because an attempt
   counter is stale or missing.
2. `GMX executeOrder(...)` classification priority:
   - `PositionIncrease` → `DERIVATIVE_POSITION_INCREASE`
   - `PositionDecrease` → `DERIVATIVE_POSITION_DECREASE`
   - `OrderCancelled` without execution → `DERIVATIVE_ORDER_CANCEL`
   - `OrderExecuted` without position evidence → `DERIVATIVE_ORDER_EXECUTION`
3. `CoW GPv2 settlement` rows with persisted `Trade(...)` logs and a matching
   digest must resolve to `DEX_ORDER_SETTLEMENT`.
4. Clarification is allowed to fetch related real txs for audited async
   lifecycles when current raw already proves the protocol family but the
   terminal keeper tx is absent from Mongo.
5. Related-tx clarification must persist a real raw doc for that tx, not a
   synthetic lifecycle projection.

## Edge Cases

- Case: persisted `fullReceipt` exists but the attempt counter was lost during a
  prior reset.
  - Expected: runtime still consumes the evidence.

- Case: GMX keeper tx contains both `PositionDecrease` and sibling
  `OrderCancelled(AUTO_CANCEL)` evidence.
  - Expected: the tx resolves to the most specific executed lifecycle,
    `DERIVATIVE_POSITION_DECREASE`.

- Case: same-source explorer scan returns unrelated wallet-visible txs in the
  same block range.
  - Expected: only txs that reclassify into explicit audited `GMX` lifecycle
    families are persisted by discovery.

- Case: the same related tx was already imported earlier for the same
  `wallet + network + txHash`.
  - Expected: discovery is idempotent and does not duplicate raw docs.

## Task Breakdown

1. `BE-07CA` persisted-clarification evidence exposure closeout
   - consume canonical persisted full-receipt evidence even when the attempt
     counter is stale
   - tighten GMX event-name decode to use explicit decoded fields before ASCII
     fallback

2. `BE-07CB` CoW settlement terminal closeout
   - ensure audited settlement rows resolve to `DEX_ORDER_SETTLEMENT`
   - keep request / settlement `correlationId` parity deterministic

3. `BE-07CC` GMX related-lifecycle discovery closeout
   - add bounded same-source explorer scan for related wallet-visible tx hashes
   - fetch missing tx details + receipt + transfers by tx hash
   - persist only audited `GMX` lifecycle candidates

4. `BE-07CD` immediate normalize-on-discovery closeout
   - newly discovered GMX keeper txs must be normalized in the same pass
   - avoid requiring a second manual rerun

5. `BE-07CE` rerun pack + regression lock
   - add classifier / clarification / normalization tests for the audited hashes
   - rerun from preserved raw evidence only

## Risk Notes

- Do not hardcode the audited `0x53...` hash as the only acceptable terminal
  keeper tx. The runtime rule must stay protocol-evidence-first.
- Do not widen the scope into generic explorer tx mining for every protocol.
  This discovery path is audited and bounded to the current `GMX` family.
- Do not regress `GM / GLV` pool request / settlement semantics while opening
  missing derivative keeper discovery.
