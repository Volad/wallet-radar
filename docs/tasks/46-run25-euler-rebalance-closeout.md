# Run 25 Euler Rebalance Closeout

Goal:

Close the last audited `run/25` normalization blocker on current persisted raw
and clarification evidence without any new backfill:

- residual Euler `batch(...)` rebalance / restructure row left in blocking
  `UNKNOWN`
- adjacent Euler share-to-share rebalance rows that still persist as generic
  `LP_EXIT`

## Scope

In scope:
- keep the current one-doc-per-tx canonical model
- keep existing raw / clarification evidence as the only source of truth
- add one explicit Euler rebalance lifecycle type for the audited residual
  family
- keep replay deterministic by carrying basis between loop-share assets without
  realized PnL
- keep Mongo rerun prep limited to derived state only

Out of scope:
- new explorer providers, extra RPC calls, or new backfill jobs
- generalized support for every Euler `batch(...)` path beyond the audited
  residual rebalance family
- protocol-wide leverage accounting redesign outside the current audited rows

## Acceptance Criteria (DoD)

1. `0x56ef233104fabcf809fbad26d5956f0450398cfd90a583fadfe6c7613a7bd332`
   no longer persists as `UNKNOWN / NEEDS_REVIEW`.
2. That row resolves to explicit `LENDING_LOOP_REBALANCE`.
3. Its canonical flows keep share-position continuity semantics only:
   - outbound old share stays `TRANSFER`
   - inbound replacement share stays `TRANSFER`
   - tiny same-asset refund dust, when present, stays `TRANSFER`
   - no synthetic `BUY` / `SELL` is fabricated
4. Audited adjacent Euler share-migration rows
   `0x08e6af7e66edbe02311f921fb6f17047e87f43acdcdb1c19526e73f7de46b50a`
   and
   `0xa548b35769c68377b33172370d1a414facd1be4f3c8106d21fcc3940e38ee7a5`
   no longer persist as generic `LP_EXIT`.
5. Those adjacent rows also resolve to `LENDING_LOOP_REBALANCE`.
6. Same-asset roundtrip marker legs inside the audited Euler rebalance family do
   not survive as active replay inputs when they are exact tx-local marker churn
   rather than real wallet inventory return.
7. Replay carries cost basis from the outbound loop-share asset into the inbound
   replacement loop-share asset with no realized PnL.
8. If same-asset dust is returned during rebalance, replay restores that dust to
   the original asset first and only moves the remaining basis into the new
   share asset.
9. No new backfill is required. Existing raw plus clarification evidence is
   sufficient.
10. Mongo rerun prep resets only derived collections and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: Euler `batch(...)` has outbound share plus inbound different share, but
  there is no deterministic share-to-share path on current wallet-boundary raw.
  - Scope: In
  - Expected: remain explicit review; do not fabricate `LENDING_LOOP_REBALANCE`.

- Case: Euler rebalance includes exact same-asset same-quantity in/out marker
  churn together with a new replacement share.
  - Scope: In
  - Expected: the exact roundtrip marker does not become a separate replay lot
    and does not consume the carried basis before the replacement share is
    restored.

- Case: Euler rebalance returns tiny same-asset dust in addition to the new
  share asset.
  - Scope: In
  - Expected: same-asset dust keeps carried basis on the old asset pro-rata;
    remaining basis moves to the replacement share.

## Task Breakdown

1. `BE-07BE` Euler rebalance canonical type closeout
   - add `LENDING_LOOP_REBALANCE`
   - resolve the audited residual `batch(...)` rebalance row from current raw
     share-transfer evidence
   - resolve adjacent audited share-migration rows from the same family

2. `BE-07BF` Euler rebalance flow-shaping lock
   - suppress exact same-asset roundtrip marker legs from canonical rebalance
     flows when they are pure tx-local churn
   - keep genuine same-asset dust refunds as continuity `TRANSFER`

3. `BE-07BG` replay carry-over lock
   - carry basis from outbound loop share to inbound replacement share with no
     realized PnL
   - restore same-asset dust first when present

4. `BE-07BH` regression lock + rerun prep
   - add classifier / pricing / replay regression tests for the audited Euler
     rebalance family
   - reset derived collections and processing status only

## Risk Notes

- Do not leave the residual audited Euler row in `UNKNOWN`; that keeps
  normalization scope formally incomplete.
- Do not reuse generic `LP_EXIT` semantics for share-to-share Euler rebalances.
- Do not create artificial `BUY` / `SELL` legs for rebalance-only marker churn.
- Do not add new backfill dependency in this slice.
