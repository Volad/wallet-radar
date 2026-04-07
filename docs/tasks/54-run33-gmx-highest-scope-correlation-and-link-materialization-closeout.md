# Run 33 GMX Highest-Scope Correlation + Link Materialization Closeout

Goal:

Close the remaining active-lane `GMX / GLV` lifecycle-linking blockers so the
dataset becomes operationally ready for `pricing -> AVCO -> cost basis ->
move basis` without transaction-hash hardcodes and without broad backfills.

This slice is intentionally narrow:

- keep the already-correct `GMX` derivative terminal types
- fix `GLV` entry settlement so it persists the highest-scope protocol request
  key rather than an intermediate deposit-execution key
- deterministically materialize `matchedCounterparty` for exact async
  request/settlement pairs once both rows already exist in Mongo
- preserve the accepted asymmetric `GMX` derivative sibling-stop case

No runtime code may hardcode audited tx hashes.

## Scope

In scope:

- highest-scope `correlationId` selection for `GMX / GLV` LP-entry settlement
- deterministic `matchedCounterparty` materialization for exact async
  request/settlement pairs that already share the same `correlationId`
- preserving accepted asymmetric derivative request / terminal linkage
- using only current raw plus persisted `clarificationEvidence.fullReceipt`

Out of scope:

- broad chain backfills
- new raw document types
- mark-to-market for open derivatives
- venue-generic perpetual accounting outside audited `GMX V2`

## Acceptance Criteria (DoD)

1. A `GMX / GLV` LP-entry settlement receipt that contains both an intermediate
   deposit-executed key and a higher-scope GLV deposit-executed key must persist
   the higher-scope `correlationId`.

2. The request-side `LP_ENTRY_REQUEST` row and the later
   `LP_ENTRY_SETTLEMENT` row must materialize a visible bidirectional
   `matchedCounterparty` link once both rows already exist and share the same
   deterministic `correlationId`.

3. The same bidirectional `matchedCounterparty` rule must also hold for exact
   async `LP_EXIT_REQUEST -> LP_EXIT_SETTLEMENT` pairs once both rows already
   exist and share the same deterministic `correlationId`.

4. `GMX` derivative terminal keeper rows must keep the already-correct
   `DERIVATIVE_POSITION_INCREASE / DERIVATIVE_POSITION_DECREASE` typing.

5. The accepted asymmetric derivative sibling case must remain valid:
   a sibling stop-order request may keep its own request key while still
   pointing to the shared terminal keeper tx through `matchedCounterparty`.

6. No runtime code path may special-case audited tx hashes for `GMX`, `GLV`, or
   any other protocol lifecycle.

7. `on-chain NEEDS_REVIEW = 0` must remain true after rerun.

## Normalization Rules To Update

1. `correlationId` must persist the highest-scope protocol lifecycle key proven
   by current persisted evidence, not the first matching event key encountered
   in receipt-log order.
   - For audited `GMX / GLV` LP-entry settlement, `GlvDepositExecuted` outranks
     intermediate `DepositExecuted` when both are present in the same receipt.

2. `matchedCounterparty` is not optional for exact async lifecycle pairs.
   - When current raw plus persisted clarification evidence already proves one
     exact same-wallet same-network request/settlement pair, normalization /
     clarification must materialize bidirectional linkage on both rows.

3. `matchedCounterparty` remains asymmetric only for accepted multi-key
   derivative terminal cases.
   - Example shape: one keeper receipt executes the primary request key and
     auto-cancels a sibling stop-order key in the same terminal tx.

4. Current raw plus persisted `fullReceipt` are already sufficient for this
   slice. No broad backfill is required.

## Edge Cases

- Exact pair, same `correlationId`, exactly two rows in group:
  must link both directions.
- Exact pair discovered after request normalized earlier:
  later settlement normalization or clarification must still backfill the
  request-side `matchedCounterparty`.
- Terminal keeper receipt with extra sibling request key:
  terminal row must keep the primary executed request key as its own
  `correlationId`; sibling request still links to terminal via
  `matchedCounterparty`.
- `GMX` non-GLV deposit settlement:
  must keep current `DepositExecuted` correlation behavior.

## Task Breakdown

1. `BE-07CU` Highest-scope GMX settlement correlation
   - make `GMX / GLV` settlement correlation selection protocol-scope aware
   - prefer higher-scope GLV execution keys when both GLV-level and
     intermediate deposit keys are present

2. `BE-07CV` Deterministic async pair linkage
   - make lifecycle linking explicit for exact async request/settlement pairs
   - preserve accepted asymmetric derivative sibling linking

3. `BE-07CW` Regression lock
   - add classifier regression for GLV settlement with dual execution keys
   - add linker regression for exact async pair linking
   - add linker regression for accepted asymmetric derivative sibling case

## Risk Notes

- Do not collapse all multi-log `GMX` receipts onto the last seen key. The
  selector must follow protocol lifecycle scope, not receipt order.
- Do not widen `matchedCounterparty` linking into many-to-many groups. Exact
  async pairs and accepted asymmetric derivative terminal cases are the only
  intended shapes in this slice.
- Do not regress already-correct `GMX` derivative typing or `CoW`
  request/settlement parity while fixing pool lifecycle linkage.
