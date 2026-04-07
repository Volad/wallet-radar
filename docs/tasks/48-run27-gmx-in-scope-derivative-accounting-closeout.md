# Run 27 GMX In-Scope Derivative Accounting Closeout

Goal:

Close the stale GMX exclusion contract and finish the normalization /
clarification layer so that audited GMX V2 lifecycle rows no longer leak into
`EXTERNAL_TRANSFER_*`, `LP_*`, or blocker review.

This slice is about deterministic on-chain semantics only. It does **not**
introduce a new raw source class or a trace indexer. If evidence is missing for
GMX / GLV request or keeper execution rows, the runtime must fetch it during
clarification from the same source family and persist the full receipt.

## Scope

In scope:
- keep `raw_transactions` plus persisted clarification evidence as the only
  source of truth
- decode GMX helper `multicall(bytes[])` intent from saved `rawData.input`
- keep `1 tx = 1 normalized doc`
- keep GM / GLV pool async request / settlement lifecycle on the explicit
  `LP_ENTRY_REQUEST` / `LP_ENTRY_SETTLEMENT` path
- move GMX derivative order / execution lifecycle into active accounting scope
- ensure on-chain `NEEDS_REVIEW = 0` after rerun
- keep Mongo rerun prep limited to derived state only

Out of scope:
- new raw backfill jobs or new external indexers
- materializing helper subcalls as separate `raw_transactions`
- generic derivative support for venues other than the audited GMX V2 family
- unrealized PnL engine for all derivative venues

## Acceptance Criteria (DoD)

1. `0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7`
   resolves to `DERIVATIVE_ORDER_REQUEST`, not `LP_ENTRY_REQUEST`,
   `EXTERNAL_TRANSFER_OUT`, or exclusion-only review.
2. The same row persists with:
   - `excludedFromAccounting = false`
   - `correlationId = 0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106`
   - `status = PENDING_PRICE` once the request key is already present in saved
     receipt evidence
3. `0xf8d8a2aaa743285f35f88c1477e8de37c4095b44c60964139799f033ada0ba51`
   and `0x2c4627b7e358257d06b5da0c367ef76e19f9c348462ba21838b0789db18393b9`
   resolve to `DERIVATIVE_ORDER_REQUEST`, not `EXTERNAL_TRANSFER_OUT`.
4. `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`
   resolves to in-scope `DERIVATIVE_ORDER_REQUEST`; missing order-key evidence
   is handled through `PENDING_CLARIFICATION`, not blocking review.
5. `0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7`
   resolves to `DERIVATIVE_POSITION_INCREASE`, not `EXTERNAL_TRANSFER_IN`.
6. `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
   resolves to `DERIVATIVE_POSITION_DECREASE` even though the same keeper tx
   also contains sibling `OrderCancelled(AUTO_CANCEL)` evidence.
7. GMX keeper execution rows that arrive without top-level `from/to/methodId`
   context still enter full-receipt clarification and reclassify from persisted
   EventEmitter logs.
8. GMX / GLV pool request / settlement parity does not regress:
   - `0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28`
     remains `LP_ENTRY_REQUEST`
   - `0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab`
     remains `LP_ENTRY_SETTLEMENT`
9. No audited GMX row remains in on-chain `NEEDS_REVIEW` after rerun; if the
   missing evidence is receipt-derivable, clarification must fetch it.
10. Mongo rerun prep resets only derived collections and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: helper `multicall(bytes[])` contains `sendWNT + sendTokens + createOrder`
  but explorer token transfers are absent.
  - Scope: In
  - Expected: request still resolves to `DERIVATIVE_ORDER_REQUEST` by decoding
    saved `rawData.input`; if the order key is not yet persisted, status is
    `PENDING_CLARIFICATION`.

- Case: keeper execution tx has only small wallet-visible refund movement and no
  top-level selector / function metadata in the explorer payload.
  - Scope: In
  - Expected: clarification fetches full receipt; classifier resolves the row
    from GMX EventEmitter logs instead of `EXTERNAL_TRANSFER_IN`.

- Case: GMX pool helper `multicall(bytes[])` funds GM / GLV deposit custody
  instead of `OrderVault`.
  - Scope: In
  - Expected: it stays on the existing async LP-entry request / settlement path,
    not derivative order lifecycle.

- Case: a suspected GMX-looking tx is not proven by saved raw or clarified
  evidence.
  - Scope: In
  - Expected: do not hardcode it into GMX rules unless the current persisted
    evidence proves GMX semantics.

## Task Breakdown

1. `BE-07BQ` GMX derivative in-scope request / execution closeout
   - remove the old exclusion-only contract from GMX derivative families
   - keep request / execution / cancel / position increase / decrease in active
     normalization scope

2. `BE-07BR` GMX helper multicall calldata decoder closeout
   - decode real ABI `bytes[]` subcalls from saved `rawData.input`
   - distinguish GMX helper order requests from GM / GLV pool deposit requests
     using decoded helper targets plus protocol role evidence

3. `BE-07BS` GMX clarification evidence parity closeout
   - allow `PENDING_CLARIFICATION` GMX request / settlement rows into bounded
     full-receipt clarification
   - allow candidate keeper-side `EXTERNAL_TRANSFER_*` rows into clarification
     when they are near GMX requests and the raw shape lacks top-level tx
     metadata

4. `BE-07BT` GMX / GLV pool lifecycle parity lock
   - keep `createDeposit/createGlvDeposit` plus `executeDeposit/executeGlvDeposit`
     on the async LP-entry family
   - do not let derivative rules capture GM / GLV pool request / settlement rows

5. `BE-07BU` on-chain review-zero rerun pack
   - add regression coverage for the audited live hashes above
   - rerun normalization / clarification on preserved raw evidence
   - verify `blocking NEEDS_REVIEW = 0` on the on-chain lane

## Risk Notes

- Do not treat GMX derivative open / close as spot `BUY / SELL` of the market
  underlying.
- Do not split helper multicall subcalls into separate raw documents.
- Do not trust explorer transfer patterns alone when full receipt logs or saved
  calldata can prove the lifecycle.
- Do not regress GM / GLV pool request / settlement semantics while opening the
  derivative family.
