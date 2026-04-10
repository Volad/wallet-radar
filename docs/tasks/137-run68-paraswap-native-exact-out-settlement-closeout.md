# Run 68 Closeout: ParaSwap Native Exact-Out Settlement Normalization

## Problem

`BASE` ParaSwap exact-out rows with native destination were still normalized from
wallet-scoped explorer transfers only.

Audited root-cause row:

- `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`

Persisted explorer transfers showed only:

- source-token spend `USDC -853.605286`
- source-token refund `USDC +0.842509`

No positive native leg was materialized, even though calldata deterministically
proved:

- `swapExactAmountOut(...)` selector `0x7f457675`
- `dstToken = native sentinel`
- default beneficiary (`0x0`), which resolves to caller wallet semantics
- embedded wrapped-native withdraw selector `0x2e1a7d4d`
- unique exact-out unwrap amount `0.286202 ETH`

That left canonical normalization as:

- `SELL USDC -853.605286`
- `BUY USDC +0.842509`
- no `ETH` buy leg

Replay then carried zero basis into downstream on-chain `ETH` corridors, and
the inherited uncovered tail later surfaced on Mantle as `AMANWETH`.

## Decision

Treat audited ParaSwap exact-out native settlement as a normalization-stage
economic closeout, not a replay repair and not a clarification-only family.

Normalization policy:

- exact-out native settlement may be recovered from current raw calldata alone
  when all audited conditions hold:
  - selector `0x7f457675`
  - `dstToken = native sentinel`
  - beneficiary is either tracked wallet or zero/default beneficiary
  - calldata contains one unique embedded wrapped-native withdraw amount
  - that unwrap amount equals the decoded exact-out amount
- same-asset source refund must net against the outbound source spend
- the refund may not survive as a separate `BUY` leg
- canonical result must become:
  - net `SELL` of the source asset
  - `BUY` of the native asset
  - `FEE` of network gas

This is normalization-first and rerun-safe.

## Business Acceptance Criteria

- `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`
  no longer materializes as `USDC sell + USDC buy`.
- The row must normalize to:
  - `SELL USDC -852.762777`
  - `BUY ETH +0.286202`
  - `FEE ETH < 0`
- The fix must reduce current `FAMILY:ETH uncovered` materially from the
  pre-fix `~0.31625`.
- Source-token refund continuity must not become a synthetic acquisition.

## Architecture Constraints

- Keep the fix in on-chain normalization, before pricing and replay.
- Do not depend on explorer page labels or UI summaries.
- Do not invent native settlement quantity from heuristic slippage math.
- Use only production-available evidence:
  - canonical raw calldata
  - extracted token transfers already persisted on the raw row
- Do not widen generic router rules beyond the audited ParaSwap exact-out
  native-settlement envelope.

## Implementation

### Backend

- Added `ParaSwapNativeSettlementSupport` in movement extraction.
- The support:
  - detects exact-out native settlement from calldata
  - recovers the exact native settlement quantity from the embedded unwrap
    selector payload
  - nets the same-asset source refund into the source sell leg
  - appends the recovered positive native leg before family classification
- Wired the support into `MovementLegExtractor` after existing 1inch native
  settlement enrichment.

### Tests

- Replaced the old ParaSwap exact-out regression with a real native-settlement
  fixture that asserts:
  - net `USDC` sell
  - recovered `ETH` buy
  - `PENDING_PRICE` swap status

## Explicit Non-Goals

- This slice does not close generic `Universal Router` native-output rows such
  as `0xa83342f55770ddbfd2186215d522a400410a41c09a86cb84da8502a790251c4e`.
  Those rows prove unwrap recipient from calldata, but actual native output
  quantity still needs receipt-side proof because calldata only carries
  `amountMin`, not final settlement amount.
- This slice does not add a new clarification family for those router rows.

## Risks

- Over-matching non-wallet native settlement if ParaSwap calldata routes native
  output to a third party.
  Current mitigation:
  - `dstToken` must be native sentinel
  - beneficiary must be wallet or zero/default beneficiary
  - unwrap amount must be unique and equal exact-out amount
- Incorrectly preserving source refund as an economic acquisition.
  Current mitigation:
  - same-asset refund is netted into the source sell before flow shaping

## Operational Notes

This slice changes normalization semantics, so a full on-chain rerun is
preferred over ad-hoc repair:

- stop backend
- reopen on-chain raw rows to `PENDING`
- remove `normalized_transactions{source=ON_CHAIN}`
- clear downstream `asset_ledger_points`
- clear downstream `on_chain_balances`
- rebuild backend and rerun Docker backend

## Verification

Required:

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.pipeline.classification.OnChainClassifierTest.paraSwapExactAmountOutWithNativeSettlementNetsRefundAndRestoresNativeBuyLeg'`
- live Mongo verification that the audited row rebuilds with native `ETH` buy
  instead of source-token refund `BUY`
- live API verification that `FAMILY:ETH uncovered` improves from the pre-fix
  `~0.31625`

Verified on live rerun:

- `user_sessions[5e448c6b-b71a-45d3-b16b-738ac19f76af]` reached
  `ACCOUNTING_REPLAY / COMPLETE` at `2026-04-10T11:01:37.425Z`
- pipeline counts after rerun:
  - `rawPending = 0`
  - `normalizedOnChain = 3107`
  - `normalizedBybit = 2578`
  - `assetLedgerPoints = 9114`
  - `onChainBalances = 212`
- audited row
  `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`
  rebuilt as:
  - `type = SWAP`
  - `status = CONFIRMED`
  - `SELL USDC -852.762777`
  - `BUY ETH +0.286202`
  - `FEE ETH -0.0000003142837236`
- `FAMILY:ETH` improved from:
  - before rerun:
    - `quantity = 3.089688892056111455`
    - `covered = 2.773438220580906633`
    - `uncovered = 0.316250671475204822`
  - after rerun:
    - `quantity = 3.089690119798414777`
    - `covered = 3.046117581932837398`
    - `uncovered = 0.043572537865577379`
- uncovered reduction:
  - `-0.272678133609627443 ETH`
