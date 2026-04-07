# Run 30 GMX Async Exit + Terminal State Closeout

Goal:

Close the active `run/30` blockers without widening the raw-source model:

- `GMX` derivative keeper txs must resolve from persisted EventEmitter topic
  hashes into the most specific lifecycle already proved by current production
  evidence
- `GMX / GLV` async pool exits must gain the same request / settlement parity
  that entry-side lifecycles already have
- `CoW` settlement must resolve from current `fullReceipt` digest evidence even
  when top-level explorer `to/from/functionName` fields are blank
- request rows must gain visible terminal linkage through `matchedCounterparty`
  once the later keeper / settlement tx is already proved by current evidence
- on-chain `NEEDS_REVIEW` must stay at `0`; if the evidence is receivable from
  the same source family, clarification must fetch it

This slice keeps `1 real tx = 1 raw doc = 1 normalized doc`. It does not
materialize helper subcalls and does not introduce a generic derivative indexer.

## Scope

In scope:
- add explicit async LP-exit canonical types:
  - `LP_EXIT_REQUEST`
  - `LP_EXIT_SETTLEMENT`
- classify audited `GMX / GLV` request-side share burns from helper
  `multicall(bytes[])` as `LP_EXIT_REQUEST`
- classify `executeWithdrawal(bytes32 key,tuple oracleParams)` and clarified
  keeper-only withdrawal settlements as `LP_EXIT_SETTLEMENT`
- decode `GMX` EventEmitter lifecycle from hashed event topics when human-readable
  `eventName` / `decodedEvent` fields are absent
- persist terminal sibling-cancel state for audited `GMX` derivative requests
- keep `CoW` request / settlement as one explicit async spot-order lifecycle
- allow clarification / discovery to fetch missing same-source settlement
  receipts for existing `GMX / GLV` exit candidates
- keep `GM / GLV` entry-side lifecycles stable

Out of scope:
- generic perpetual PnL engine across venues other than the audited `GMX V2`
  family
- helper-subcall materialization as child raw docs
- broad chain backfill
- unrealized derivative mark-to-market

## Acceptance Criteria (DoD)

1. `0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7`
   resolves to `DERIVATIVE_POSITION_INCREASE`, not generic
   `DERIVATIVE_ORDER_EXECUTION`.
2. `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
   resolves to `DERIVATIVE_POSITION_DECREASE`, not generic
   `DERIVATIVE_ORDER_EXECUTION`.
3. `0x2c4627b7e358257d06b5da0c367ef76e19f9c348462ba21838b0789db18393b9`
   remains `DERIVATIVE_ORDER_REQUEST`, but the runtime persists its terminal
   sibling auto-cancel state from the later `0x53...` keeper tx instead of
   leaving it looking orphaned.
4. `0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0`
   resolves to `DEX_ORDER_SETTLEMENT`, not `EXTERNAL_TRANSFER_IN`, even when
   top-level `to/from/functionName` fields are blank.
4a. Its request `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`
    must visibly point to the settlement through `matchedCounterparty`.
5. `0x806ccd26c2f11ab6180c8f1f0448df2fda3917ffcc65dc7d661c1ae8d86426ec`
   resolves to `LP_EXIT_REQUEST`, not `EXTERNAL_TRANSFER_OUT`.
6. `0xf3581fb98799bb1d55ec08a72dfb6668ae4009f219434e734e8a9db0388ec374`
   resolves to `LP_EXIT_SETTLEMENT`, not `EXTERNAL_TRANSFER_IN`, after bounded
   same-source clarification fetches the missing receipt.
7. `0xa83cc44b21d5f5a95e61146d486ecabc790f46663d3ed83c0b5c9aeca838e620`
   resolves to `LP_EXIT_REQUEST`, not `EXTERNAL_TRANSFER_OUT`.
8. `0x977474f616af6a4227237ec7680f8c2023b7c626652ffda2349ba71f76cfb00e`
   resolves to `LP_EXIT_SETTLEMENT`, not `VAULT_WITHDRAW`.
9. Async LP-exit replay does not assign the whole burned-share basis to the
   first settlement payout leg. Same-asset fee-refund carry must be restored
   first; the remaining share basis must be allocated across principal payout
   legs deterministically.
10. On-chain `NEEDS_REVIEW = 0` after rerun. If evidence is receipt-derivable
    or same-source-transfer-derivable, clarification must fetch it instead of
    leaving a blocker review row.

## Normalization Rules To Update

1. `GMX` EventEmitter topic identity is authoritative runtime evidence.
   - The runtime must match the hashed event topic in `topics[1]` before ASCII
     fallback.
   - `PositionIncrease` and `PositionDecrease` may not stay generic
     `DERIVATIVE_ORDER_EXECUTION` once their topic hashes are already present in
     persisted `fullReceipt.logs`.

2. `GMX` derivative terminal sibling state must be persisted.
   - A keeper tx that executes one request and auto-cancels a sibling request in
     the same receipt must persist both terminal outcomes.
   - The request row stays `DERIVATIVE_ORDER_REQUEST`, but it may not remain
     operationally orphaned once current production evidence already proves the
     later cancel.
   - `matchedCounterparty` must be backfilled onto the affected request rows from
     the terminal keeper tx.

3. `GMX / GLV` async pool exits are explicit request / settlement lifecycles.
   - helper `multicall(bytes[])` + outbound `GM/GLV` share burn →
     `LP_EXIT_REQUEST`
   - keeper `executeWithdrawal(...)` or clarified `WithdrawalExecuted` /
     `GlvWithdrawalExecuted` tx → `LP_EXIT_SETTLEMENT`
   - generic `EXTERNAL_TRANSFER_OUT`, `EXTERNAL_TRANSFER_IN`, or `VAULT_WITHDRAW`
     are not acceptable fallbacks for these families

4. `CoW` settlement must classify from digest correlation, not explorer transfer
   fallback.
   - If persisted `fullReceipt.logs` already contain the GPv2 `Trade(...)`
     digest matching a request `correlationId`, the row must become
     `DEX_ORDER_SETTLEMENT` even if top-level explorer metadata is weak.
   - If the settlement row is already in the active lane as
     `EXTERNAL_TRANSFER_IN`, clarification must be allowed to fetch the
     missing same-source receipt and reclassify it in-place.

5. Async LP-exit replay must be transaction-level, not single-flow-first.
   - Request-side share burn carry and request-side native execution-fee escrow
     must not be merged blindly into one principal payout leg.
   - Same-asset settlement refunds restore their own carry first.
   - Remaining burned-share basis is then allocated deterministically across the
     principal settlement payout legs.

## Edge Cases

- Case: `GMX` keeper receipt contains only topic hashes and opaque data, without
  human-readable `eventName`.
  - Expected: topic-hash mapping still resolves the precise lifecycle.

- Case: `GMX` async exit settlement row already exists in Mongo as a generic
  inbound transfer but is still missing `fullReceipt`.
  - Expected: pending clarification fetches the same-source receipt and
    reclassifies the row into `LP_EXIT_SETTLEMENT`.

- Case: async LP-exit settlement returns multiple same-asset payout legs plus a
  small same-asset execution-fee refund.
  - Expected: the fee-refund carry is restored first and the remaining
    burned-share basis is split across the principal payout legs.

- Case: `CoW` settlement tx has no top-level `to` but persisted `Trade(...)`
  logs already prove the digest.
  - Expected: the row resolves to `DEX_ORDER_SETTLEMENT`.

## Task Breakdown

1. `BE-07CF` topic-driven GMX terminal lifecycle closeout
   - match `GMX` EventEmitter hashed event topics before ASCII fallback
   - classify keeper txs into `DERIVATIVE_POSITION_INCREASE` /
     `DERIVATIVE_POSITION_DECREASE`
   - persist sibling auto-cancel terminal state for request rows

2. `BE-07CG` async LP-exit type-system closeout
   - add `LP_EXIT_REQUEST` and `LP_EXIT_SETTLEMENT`
   - map request / settlement flows to continuity semantics
   - keep entry-side async lifecycle unchanged

3. `BE-07CH` GMX / GLV exit clarification closeout
   - allow request-side receipt fetch for missing withdrawal request keys
   - allow settlement-side targeted clarification / discovery for existing
     generic inbound candidates such as `0xf358...`
   - classify `executeWithdrawal(...)` keeper rows before generic vault fallback

4. `BE-07CI` CoW settlement runtime closeout
   - relax settlement detection to use persisted `Trade(...)` evidence when
     explorer `to` is missing
   - keep deterministic request / settlement digest parity

5. `BE-07CJ` async LP-exit replay closeout
   - carry burned-share basis from `LP_EXIT_REQUEST`
   - restore same-asset fee-refund carry first
   - allocate remaining share basis across principal settlement outputs
   - regression-test the audited `GMX / GLV` exit families

6. `BE-07CK` rerun pack + regression lock
   - add classifier / clarification / replay tests for the audited hashes above
   - rerun from preserved raw evidence only

## Risk Notes

- Do not treat every `multicall(bytes[])` + share burn as `GMX`. Require the
  current audited `GMX` router shape and helper selector family.
- Do not regress `LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT` while opening exit-side
  parity.
- Do not let async LP-exit replay assign all share basis to the first inbound
  payout leg.
