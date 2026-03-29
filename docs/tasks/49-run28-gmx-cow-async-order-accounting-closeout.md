# Run 28 GMX Precise Keeper Decode + CoW Async Spot Order Closeout

Goal:

Close the active `run/28` normalization / clarification blockers without adding
new raw source classes:

- `GMX` keeper execution rows must resolve to the precise derivative lifecycle
  proved by persisted EventEmitter receipt logs
- `CoW Swap ETH Flow` request / settlement rows must no longer leak into the
  `GMX` branch or generic `EXTERNAL_TRANSFER_*`
- if receipt-level evidence is missing for those families, clarification must
  fetch it from the same source family and persist the full receipt

This slice keeps `1 tx = 1 normalized doc` and does not introduce a trace
indexer.

## Scope

In scope:
- keep `raw_transactions` plus persisted clarification evidence as the only
  source of truth
- decode `CoW Swap ETH Flow createOrder(EthFlowOrder.Data)` directly from saved
  `rawData.input`
- derive deterministic `correlationId` for the audited CoW request / settlement
  family from protocol evidence already available in raw + full receipt
- keep `GMX / GM / GLV` request / settlement families explicit and in-scope
- keep on-chain `NEEDS_REVIEW = 0` after rerun
- keep Mongo rerun prep limited to derived state only

Out of scope:
- new raw backfill jobs or trace indexers
- materializing helper subcalls as separate `raw_transactions`
- generic support for every intent-based DEX beyond the audited CoW Eth Flow
  family
- derivative unrealized PnL engine

## Acceptance Criteria (DoD)

1. `0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7`
   resolves to `DERIVATIVE_POSITION_INCREASE`, not generic
   `DERIVATIVE_ORDER_EXECUTION`, `EXTERNAL_TRANSFER_IN`, or any LP family.
2. `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
   remains `DERIVATIVE_POSITION_DECREASE` even though the same keeper tx also
   carries sibling `OrderCancelled(AUTO_CANCEL)` evidence.
3. `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`
   no longer resolves through the GMX derivative branch.
4. The same `0xb6...` row resolves to explicit in-scope spot-order request
   semantics:
   - canonical type `DEX_ORDER_REQUEST`
   - `protocolName = CoW Swap`
   - deterministic `correlationId` derived from protocol order evidence
   - no `GMX_*` missing-data reason remains
5. `0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0`
   no longer persists as `EXTERNAL_TRANSFER_IN`.
6. The same `0xd7abb...` row resolves to explicit in-scope spot-order
   settlement semantics:
   - canonical type `DEX_ORDER_SETTLEMENT`
   - same `correlationId` as `0xb6...`
   - `protocolName = CoW Swap`
7. If a CoW settlement candidate lacks persisted full-receipt logs, it enters
   `PENDING_CLARIFICATION` and bounded full-receipt clarification fetches the
   same-source receipt before the row can remain unresolved.
8. `GMX / GM / GLV` pool request / settlement parity does not regress:
   - `0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28`
     remains `LP_ENTRY_REQUEST`
   - `0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab`
     remains `LP_ENTRY_SETTLEMENT`
9. On-chain `NEEDS_REVIEW = 0` after rerun. If evidence is receipt-derivable,
   clarification must fetch it instead of leaving a blocker review row.
10. Mongo rerun prep resets only derived collections and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: selector `0x322bba21` appears on a non-GMX contract.
  - Scope: In
  - Expected: selector collision is resolved by contract / calldata shape, not
    by a blind GMX rule.

- Case: CoW settlement tx has weak top-level explorer metadata but persisted
  full receipt includes `Trade(...)` and `Settlement(...)` logs.
  - Scope: In
  - Expected: classification resolves through the CoW settlement branch rather
    than generic `EXTERNAL_TRANSFER_IN`.

- Case: GMX keeper tx has no top-level `methodId/functionName/input` in raw but
  persisted full receipt contains EventEmitter `PositionIncrease`.
  - Scope: In
  - Expected: classifier resolves `DERIVATIVE_POSITION_INCREASE`.

- Case: CoW request exists without later settlement inside the current dataset.
  - Scope: In
  - Expected: request remains explicit `DEX_ORDER_REQUEST`; replay keeps the
    carry in an open async-order bucket until settlement or cancellation
    arrives.

## Task Breakdown

1. `BE-07BV` GMX precise keeper execution closeout
   - resolve `executeOrder` keeper rows from persisted EventEmitter evidence
   - promote generic `DERIVATIVE_ORDER_EXECUTION` into
     `DERIVATIVE_POSITION_INCREASE` / `DERIVATIVE_POSITION_DECREASE` when the
     receipt already proves the precise lifecycle

2. `BE-07BW` CoW Eth Flow request / settlement family closeout
   - add explicit canonical async spot-order types for the audited CoW family
   - derive deterministic request / settlement `correlationId` from current
     protocol evidence
   - stop routing selector `0x322bba21` blindly into GMX

3. `BE-07BX` CoW clarification evidence parity closeout
   - allow CoW settlement candidates into bounded full-receipt clarification
     when protocol correlation depends on receipt logs
   - keep the request-side decoder on saved `rawData.input`

4. `BE-07BY` async spot-order replay closeout
   - treat `DEX_ORDER_REQUEST -> DEX_ORDER_SETTLEMENT` as one economic swap
     lifecycle
   - keep request-side principal off-wallet until settlement
   - realize source-asset disposal at settlement using the correlated carry,
     not as a standalone external transfer

5. `BE-07BZ` rerun pack + regression lock
   - add regression coverage for the audited live hashes above
   - rerun normalization / clarification on preserved raw evidence
   - verify `blocking NEEDS_REVIEW = 0` on the on-chain lane

## Risk Notes

- Do not treat `CoW Swap ETH Flow` request rows as GMX just because the selector
  collides.
- Do not materialize helper subcalls or child lifecycle steps as separate raw
  documents.
- Do not let CoW settlement rows fall back to standalone `EXTERNAL_TRANSFER_IN`
  once protocol logs already prove order settlement.
- Do not regress `GMX / GM / GLV` pool request / settlement semantics while
  opening the CoW family.
