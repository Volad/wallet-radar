# Run 26 GMX V2 Derivative Lifecycle Closeout

> Superseded by [48-run27-gmx-in-scope-derivative-accounting-closeout.md](./48-run27-gmx-in-scope-derivative-accounting-closeout.md). Kept only as historical context for the old exclusion contract.

Goal:

Close the newly discovered GMX V2 lifecycle misclassification that sits outside
the audited `run/26` spot/LP/lending stream:

- `OrderVault + OrderCreated` rows still leak as LP / custody-like accounting
  families
- `executeOrder(...)` rows are not decoded into a deterministic derivative
  lifecycle
- `GMX` helper multicalls still rely on broad transfer-pattern fallback instead
  of explicit order-lifecycle semantics

The current milestone does **not** open derivative / perp accounting. It only
normalizes the GMX V2 order / execution lifecycle deterministically and moves
that family into explicit audit-visible exclusion so it cannot contaminate
spot AVCO / cost basis / move basis.

## Scope

In scope:
- keep current `raw_transactions` + persisted clarification evidence as the
  only source of truth
- decode GMX helper multicall intent from saved `rawData.input`
- keep `1 tx = 1 normalized doc`
- add explicit derivative lifecycle types for GMX V2 order flows
- persist those rows as `excludedFromAccounting=true`
- keep Mongo rerun prep limited to derived state only

Out of scope:
- derivative / perp accounting engine
- realized / unrealized PnL for GMX positions
- new raw backfill jobs or trace-indexer dependency
- storing multicall subcalls as separate raw documents

## Acceptance Criteria (DoD)

1. `0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7`
   no longer persists as `LP_ENTRY_REQUEST`.
2. That row resolves to explicit `DERIVATIVE_ORDER_REQUEST`.
3. It persists with:
   - `excludedFromAccounting=true`
   - `accountingExclusionReason=GMX_DERIVATIVE_ACCOUNTING_OUT_OF_SCOPE`
   - `correlationId=0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106`
4. `0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7`
   resolves to `DERIVATIVE_POSITION_INCREASE`, not LP / swap / custody
   fallback.
5. `0xf8d8a2aaa743285f35f88c1477e8de37c4095b44c60964139799f033ada0ba51`
   and `0x2c4627b7e358257d06b5da0c367ef76e19f9c348462ba21838b0789db18393b9`
   resolve to `DERIVATIVE_ORDER_REQUEST`.
6. `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
   resolves to `DERIVATIVE_POSITION_DECREASE` even though the same tx also
   carries sibling `OrderCancelled(AUTO_CANCEL)` evidence.
7. Direct `createOrder(CreateOrderParams)` and helper `multicall(bytes[])`
   create-order paths are aligned under the same derivative lifecycle family.
8. No GMX V2 order-family row remains active priceable `LP_*`,
   `EXTERNAL_TRANSFER_*`, `SWAP`, or `PROTOCOL_CUSTODY_*`.
9. GMX helper multicall subcalls are decoded from saved `rawData.input` through
   projection / decoder logic only; they are not materialized as separate
   `raw_transactions` docs.
10. Mongo rerun prep resets only derived collections and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: direct `createOrder(...)` has no persisted full receipt yet.
  - Scope: In
  - Expected: still resolve to `DERIVATIVE_ORDER_REQUEST`; `correlationId` may
    stay null until clarification persists the order key.

- Case: helper `multicall(bytes[])` has `sendWNT + sendTokens` but the outbound
  transfer target is GMX deposit custody rather than `OrderVault`.
  - Scope: In
  - Expected: remain on the existing async LP-entry path, not derivative order
    path.

- Case: `executeOrder(...)` has `OrderExecuted` but no `PositionIncrease` /
  `PositionDecrease`.
  - Scope: In
  - Expected: resolve to generic `DERIVATIVE_ORDER_EXECUTION`, still excluded
    from accounting.

- Case: `executeOrder(...)` contains only `OrderCancelled`.
  - Scope: In
  - Expected: resolve to `DERIVATIVE_ORDER_CANCEL`, excluded from accounting.

## Task Breakdown

1. `BE-07BI` GMX V2 derivative canonical types closeout
   - add explicit derivative order / execution / position lifecycle types
   - persist them as deterministic exclusion rows, not generic `UNKNOWN`

2. `BE-07BJ` GMX request path unification closeout
   - unify direct `createOrder(...)` and helper `multicall(bytes[])` create-order
     paths
   - use `rawData.input` subcall decoding plus `OrderVault` transfer evidence
     to distinguish order request from LP deposit request

3. `BE-07BK` GMX execute-order receipt decode closeout
   - decode persisted GMX EventEmitter logs into:
     - `DERIVATIVE_POSITION_INCREASE`
     - `DERIVATIVE_POSITION_DECREASE`
     - `DERIVATIVE_ORDER_EXECUTION`
     - `DERIVATIVE_ORDER_CANCEL`
   - keep sibling bracket auto-cancel as secondary evidence inside the same
     canonical execution tx

4. `BE-07BL` exclusion propagation + rerun prep
   - propagate on-chain `excludedFromAccounting/accountingExclusionReason`
     through normalization builder
   - add regression tests
   - reset derived collections and processing status only

## Risk Notes

- Do not reuse LP-entry or protocol-custody accounting semantics for GMX V2
  derivative orders.
- Do not split helper multicall subcalls into separate raw documents.
- Do not let direct `createOrder(...)` fall back to the old generic special
  handler contract.
- Do not treat `PositionDecrease` execution as a spot `SELL/BUY` event inside
  the current spot accounting milestone.
