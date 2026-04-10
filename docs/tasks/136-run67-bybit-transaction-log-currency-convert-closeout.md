# Run 67 Closeout: Bybit Transaction-Log Currency Convert Normalization

## Problem

`Bybit` transaction-log rows with:

- `bybitType = CURRENCY_BUY`
- `bybitType = CURRENCY_SELL`

were still extracted as `canonicalType = UNKNOWN_CEX`.

This left deterministic provider-native convert lifecycles outside canonical
normalization, so replay never transferred basis from the sold asset into the
acquired asset.

Audited consequence:

- `CMETH -0.66931648 -> ETH +0.70215876` on `2025-04-17T12:08:56.725Z`
  stayed outside canonical replay
- the later same-universe `Bybit -> wallet` withdrawal
  `0x1a706ffebbc4a99a657cc10f38911b895da7f8656b9d1e0d67642640cfa6ab56`
  carried zero basis into the wallet
- the inherited uncovered tail later surfaced as `MANTLE / AMANWETH`

## Decision

Treat `TRANSACTION_LOG / CURRENCY_BUY` and `TRANSACTION_LOG / CURRENCY_SELL` as
audited provider convert legs.

Normalization policy:

- extraction must emit `canonicalType = SWAP`
- staging must preserve the original Bybit enum value
- deterministic normalization must cluster convert-family rows by:
  - same account
  - same source file type
  - tight bounded time window
  - case-insensitive convert-family enum set, not exact single enum equality
- the cluster must materialize as canonical `SWAP`
- missing opposite-side convert clusters remain explicit `NEEDS_REVIEW` with
  `BYBIT_CONVERT_CLUSTER_INCOMPLETE`

This fix is normalization-first and rerun-safe. It is not a replay-only repair.

## Business Acceptance Criteria

- `CURRENCY_BUY` and `CURRENCY_SELL` no longer persist as `UNKNOWN_CEX`.
- A deterministic pair such as:
  - `CMETH -0.66931648`
  - `ETH +0.70215876`
  materializes as one canonical `SWAP`.
- The same-universe withdrawal
  `0x1a706ffebbc4a99a657cc10f38911b895da7f8656b9d1e0d67642640cfa6ab56`
  must no longer carry zero source basis when its upstream acquired inventory
  came from an audited convert.
- The fix must improve current `ETH` and/or `USDC` coverage without changing
  unrelated bridge, liquid-staking, or transfer semantics.

## Architecture Constraints

- Keep the fix inside the existing Bybit extraction/normalization path.
- Do not add a new clarification stage.
- Do not introduce a replay-only synthetic backfill for convert rows.
- Prefer a rerun of affected Bybit staging and downstream replay over ad-hoc
  ledger mutation.

## Implementation

### Backend

- Extended `BybitExtractionService.extractTransactionLog(...)` so
  `CURRENCY_BUY` and `CURRENCY_SELL` become `SWAP` staging rows.
- Preserved the original Bybit enum in `bybitType` and marked the rows as
  `Currency convert`.
- Expanded convert-cluster loaders in both:
  - `BybitExtractedTradePairer`
  - `BybitTradePairer`
  so `Convert`, `CURRENCY_BUY`, and `CURRENCY_SELL` participate in one audited
  convert family regardless of raw enum casing.
- Updated `BybitNormalizationService` so transaction-log convert rows go through
  the convert-cluster path, not the UTA directional-trade path.

### Tests

- Added extraction regression for `TRANSACTION_LOG / CURRENCY_BUY`.
- Added pairer regressions proving the convert-cluster query includes
  `CURRENCY_BUY` and `CURRENCY_SELL`.
- Added normalization regression proving extracted transaction-log convert rows
  build one aggregated `SWAP`.

## Risks

- Over-grouping multiple independent convert operations that land in the same
  tiny time window.
  Current mitigation:
  - same account only
  - same source file type only
  - existing bounded convert window
- Some `CURRENCY_*` rows may represent UTA loan-liquidation semantics instead
  of a user-initiated manual convert. This is acceptable for current policy
  because both remain asset-changing canonical `SWAP` lifecycles from the
  account ledger perspective.

## Operational Notes

This slice requires a rerun:

- reopen affected `bybit_extracted_events` convert-family rows
- reopen legacy `external_ledger_raw` convert-family rows if still present
- remove affected `normalized_transactions{source=BYBIT}`
- clear downstream `asset_ledger_points` and `on_chain_balances`
- rerun pricing and accounting replay

## Verification

Required:

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.integration.bybit.BybitExtractionServiceTest'`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.integration.bybit.BybitExtractedTradePairerTest'`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.pipeline.bybit.BybitTradePairerTest'`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.job.bybit.BybitNormalizationServiceTest'`
- live Mongo verification that the audited convert pair no longer stays
  `UNKNOWN_CEX`
- live API verification that `FAMILY:ETH` uncovered improves from the pre-fix
  `~0.3053`
