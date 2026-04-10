# Run 65 Closeout: Uniswap V4 `modifyLiquidities` Position Correlation

## Problem

Audited `Unichain / Uniswap V4` concentrated-liquidity rows were already
classifying as `LP_ENTRY` / `LP_EXIT`, but most direct
`modifyLiquidities(bytes unlockData,uint256 deadline)` rows still lacked
deterministic position-scoped `correlationId`.

Observed live impact:

- `43` audited `Unichain / Uniswap V4` `LP_ENTRY` / `LP_EXIT*` rows
- `39` rows missing `correlationId`
- later exits such as
  `0x628c00477816388891363093f75f6ad8740ec2e919685a1ed843c82068238a0c`
  and
  `0x8ff3c3cec0a8a1ab22576d2b7f16eaf312ebefff21aef8ee0d8cf14d36fd59d2`
  arrived in replay as zero-basis `REALLOCATE_IN`
- this older uncovered carry later surfaced in the current
  `MANTLE / AMANWETH` bucket

The root cause was a normalization decode gap, not a replay allocation defect:

- raw calldata already contained the position token ids
- `modifyLiquidities` nested `unlockData` params were not decoded
- the old parser then read action params as if they had a 4-byte selector,
  producing shifted garbage token ids

## Decision

Treat this as a canonical normalization defect and fix it in
`LpPositionCorrelationSupport`.

Do not attempt downstream repair.

Preferred operational path:

1. stop backend
2. reopen full on-chain normalization slice
3. rebuild canonical on-chain rows
4. rebuild pricing and replay state from that corrected canonical source

## Implementation

### Code

- Added generic ABI helpers for dynamic bytes and selectorless tuple decoding in
  `CalldataDecodingSupport`.
- Extended `LpPositionCorrelationSupport` to:
  - decode top-level `unlockData` from direct `modifyLiquidities`
  - decode nested `(bytes actions, bytes[] params)`
  - pair action bytes with action params
  - extract `tokenId` from existing-position operations
  - apply the same logic to multicall-embedded `modifyLiquidities`

### Regression Tests

Added audited regression coverage for real `Unichain` calldata shapes:

- token id `42775`
- token id `44341`

The classifier now emits:

- `lp-position:unichain:uniswap:42775`
- `lp-position:unichain:uniswap:44341`

for the corresponding direct `modifyLiquidities` entry/exit rows.

## Acceptance Criteria

- Direct `modifyLiquidities(bytes unlockData,uint256 deadline)` rows for
  existing Uniswap V4 positions emit deterministic
  `lp-position:<network>:<protocol>:<tokenId>` correlation during
  normalization.
- Multicall-embedded `modifyLiquidities` subcalls use the same token-id
  extraction rule.
- Existing-position `LP_EXIT*` rows no longer fall back to zero-basis
  `REALLOCATE_IN` purely because `correlationId` is missing.
- Full on-chain rerun materially reduces the older `ETH` uncovered tail tied to
  the audited `Unichain` position lifecycle.

## Risks

- Mis-reading selectorless tuple params would silently create wrong token ids.
  The regression tests specifically guard against the observed offset bug.
- New mint-position flows still rely on NFT mint log / clarification when the
  raw calldata does not expose an existing token id. This slice does not change
  that rule.

## Verification

Required:

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.pipeline.classification.OnChainClassifierTest'`
- full on-chain rerun
- live verification of the audited rows and the current `ETH` uncovered bucket
