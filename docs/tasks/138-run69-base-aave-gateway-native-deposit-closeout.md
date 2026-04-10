# Run 69 Closeout: Base Aave Gateway Native Deposit Normalization

## Problem

`BASE` Aave wrapped-token gateway native deposits were still normalizing as
heuristic `SWAP ETH -> AWETH` instead of `LENDING_DEPOSIT`.

Audited root-cause rows:

- `0x6b252bf689d6fbd0afde82b633dd5079210422d1de4e75bb109a42fb2d73865b`
- `0x909dd8771eae8a057516d94a4a3152cd31ac2df3f3704bdaf068805b70dd8285`
- `0xca27a0be7e9288a152ac6df7aa02a6a4aba076ee91117318ba1735911c4b840a`

Persisted raw evidence on every row already proved:

- top-level `rawData.methodId = 0x`
- calldata selector `0x474cf53d`
- `depositETH(address,address,uint16)` explorer function marker
- positive native `value`
- wallet-boundary mint of `AWETH`

Because canonical normalization stayed on the generic `SWAP` lane, replay
treated the native `ETH` leg as economic disposal. That recorded avoidable
`ETH` shortfall on Base and carried the debt into later bridge / LP / Mantle
paths.

This slice also revalidated a previously suspected replay issue on
`lp-position:base:pancakeswap:938761`: that lane was not silently dropping
carry. It was faithfully surfacing earlier Base `ETH` shortfall created before
the LP position. The actionable defect is the Aave gateway deposit
misclassification, not the LP replay path.

## Decision

Treat audited Base `depositETH(...)` gateway rows as normalization-stage
`LENDING_DEPOSIT`, using the same method-aware Aave gateway override family as
the already supported `zkSync` selectors.

Policy:

- keep the fix in normalization, before pricing and replay
- support only the currently audited Base gateway lane:
  - selector `0x474cf53d`
  - network `BASE`
  - outbound wallet-boundary native `ETH`
  - inbound wallet-boundary `AWETH`
- keep `withdrawETH(...)` / `supplyWithPermit(...)` support limited to
  `zkSync` until additional Base evidence is formally audited
- do not widen unsupported Base selectors such as the unresolved
  `0xe74f7b85` debt-loop lane in this slice

## Business Acceptance Criteria

- The three audited Base rows above no longer normalize as heuristic `SWAP`.
- They must normalize as `LENDING_DEPOSIT / Aave / V3`.
- Canonical non-fee flows must become:
  - outbound `ETH`
  - inbound `AWETH`
- Replay must stop recording avoidable `ETH` disposal / shortfall on those
  rows.
- Live `FAMILY:ETH uncovered` must improve from the pre-fix
  `0.043572537865577379`.

## Architecture Constraints

- Use only production-available evidence already persisted in raw rows:
  - calldata selector recovery through `OnChainRawTransactionView`
  - tx-level native `value`
  - stored wallet-boundary token transfer evidence
- Do not depend on explorer page summaries as canonical proof.
- Do not patch replay to compensate for wrong normalization.
- Do not generalize unsupported Aave gateway selectors beyond the audited
  network / receipt-token envelope.

## Implementation

### Backend

- Broadened the existing Aave gateway classifier to support Base
  `depositETH(...)` rows in addition to the previously audited `zkSync`
  selectors.
- Kept the override network-scoped and shape-scoped:
  - `zkSync`
    - `withdrawETH(...)`
    - `supplyWithPermit(...)`
    - `depositETH(...)`
  - `Base`
    - `depositETH(...)` only
- The classifier now accepts Base rows only when current movement evidence
  proves native `ETH` outbound plus minted `AWETH` inbound.

### Tests

- Added a regression that mirrors the real Base storage shape:
  - top-level `methodId = 0x`
  - selector recovered from calldata
  - tx-level native `value`
  - explorer mint of `AWETH`
- The regression asserts `LENDING_DEPOSIT` instead of heuristic `SWAP`.

## Explicit Non-Goals

- This slice does not classify unresolved Base gateway selector `0xe74f7b85`.
- This slice does not change replay logic for PancakeSwap LP positions.
- This slice does not add new protocol-registry addresses; selector + shape
  proof is sufficient for the audited rows.

## Risks

- Over-matching non-Aave rows that reuse the same selector on unsupported
  networks.
  Current mitigation:
  - support remains network-scoped
  - Base support is limited to `depositETH(...)`
  - wallet-boundary `ETH -> AWETH` shape is mandatory
- Accidentally widening unsupported Base gateway semantics.
  Current mitigation:
  - no change to Base `withdrawETH(...)` or `supplyWithPermit(...)`
  - no change to unresolved debt-loop selectors

## Operational Notes

This slice changes canonical on-chain normalization semantics, so a full
on-chain rerun is preferred:

- stop backend
- reopen on-chain raw rows to `PENDING`
- remove `normalized_transactions{source=ON_CHAIN}`
- clear downstream `asset_ledger_points`
- clear downstream `on_chain_balances`
- rebuild backend and rerun Docker backend

## Verification

Required:

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.pipeline.classification.OnChainClassifierTest.baseAaveDepositEthResolvesToLendingDepositFromCalldataSelectorFallback'`
- live Mongo verification that the three audited Base rows rebuild as
  `LENDING_DEPOSIT / Aave / V3`
- live API / Mongo verification that `FAMILY:ETH uncovered` improves from the
  pre-fix `0.043572537865577379`

Verified on live rerun:

- backend was stopped and the full on-chain slice was reopened in Mongo:
  - `raw -> PENDING`: `3107`
  - deleted `normalized_transactions{source=ON_CHAIN}`: `3107`
  - cleared `asset_ledger_points`: `3354`
  - cleared `on_chain_balances`: `212`
- backend was rebuilt and restarted in Docker
- targeted and full classifier tests passed:
  - `:backend:compileJava`
  - `:backend:test --tests 'com.walletradar.ingestion.pipeline.classification.OnChainClassifierTest.baseAaveDepositEthResolvesToLendingDepositFromCalldataSelectorFallback'`
  - `:backend:test --tests 'com.walletradar.ingestion.pipeline.classification.OnChainClassifierTest'`
- all three audited rows now rebuild as:
  - `type = LENDING_DEPOSIT`
  - `classifiedBy = METHOD_ID`
  - `protocolName = Aave`
  - `protocolVersion = V3`
  - non-fee flows `ETH -> AWETH`
- stable replay completion was observed with:
  - `normalizedOnChain = 3107`
  - `normalizedBybit = 2578`
  - `assetLedgerPoints = 9114`
  - `onChainBalances = 212`

Actual accounting outcome:

- semantic normalization fix is verified
- `FAMILY:ETH uncovered` did **not** improve materially:
  - before: `0.043572537865577379`
  - after: `0.0435781671273238`
  - delta: `+0.000005629261746421`
- the three Base gateway rows still carry the same local `ETH`
  `quantityShortfallDelta`, so the remaining tail is upstream provenance, not
  this canonical labeling defect

Follow-up:

- keep this fix; it is semantically correct and required
- next slice must trace the source `ETH` basis feeding these Base gateway
  deposits instead of revisiting `SWAP -> LENDING_DEPOSIT`
