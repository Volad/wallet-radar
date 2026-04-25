# Task 149: Cycle 82 Runtime Audit Implementation Closeout

Status: Implemented and verified
Owner: business-analyst + system-architect
Implementation owners: backend-dev, frontend-dev
Audit basis:

- `auto-loop-handoff/artifacts/cycle/82/scorecard.md`
- `auto-loop-handoff/artifacts/cycle/82/financial-analyst/report.md`
- `auto-loop-handoff/artifacts/cycle/82/financial-analyst/required-changes.md`
- `auto-loop-handoff/artifacts/cycle/82/financial-analyst/comprehensive-implementation-package.md`
- `auto-loop-handoff/artifacts/cycle/82/handoffs/backend-dev.md`

## Goal

Close the cycle 82 runtime audit backlog without weakening the accepted
accounting evidence model:

- keep the mandatory AVCO blocking surface clean;
- preserve the Bybit transfer-shadow and active withdraw/deposit invariants;
- keep unsupported Bybit loan rows out of active inventory accounting;
- expose current quote metadata to dashboard users without making dashboard GET
  calls perform live provider work;
- keep frontend transaction rows stable when hashes, venue references, or
  addresses are long.

The audit is accepted as current-runtime evidence. Older historical-price and
Bybit shadow findings must not be treated as active blockers when the current
runtime proves `current_price_quotes`, `isLiveQuote` / `priceIssue`, and
`excludedFromAccounting` invariants.

## Business Acceptance Criteria

- `blockingNeedsReview = 0`.
- `excludedNeedsReview = 3` unless Bybit liability accounting is explicitly
  scoped later.
- `excludedConfirmed = 91` unless the Bybit source import contract changes.
- `excludedLedgerPoints = 0`.
- Every `BYBIT_TRANSFER_SHADOW_ROW` row remains `CONFIRMED`,
  `excludedFromAccounting = true`, and audit-visible.
- No excluded row participates in AVCO replay or creates `asset_ledger_points`.
- Active chain-aware Bybit `withdraw_deposit` rows remain active
  `EXTERNAL_TRANSFER_IN/OUT` accounting rows.
- Mandatory asset/family `blockingUncovered = 0` for ETH, BTC, MNT, AVAX, USDC,
  and USDT surfaces.
- ETH native gas/yield residuals and BTC family dust remain explicitly reported
  as non-blocking policy lanes unless the owner requires literal zero uncovered.
- Dashboard token rows expose `priceUsd`, `priceSource`, `pricedAt`,
  `stalenessSeconds`, `isLiveQuote`, and `priceIssue`.
- Dashboard visible token table has no extra PnL column after `Price`; exact
  price/source/timestamp metadata is available on hover.
- Transaction rows shorten long hashes, venue references, and addresses while
  keeping the full value available on hover.
- Collapsed transaction flow preview shows unsigned non-fee material quantity;
  signed flows remain available in expanded details.
- Session transaction API maps supported canonical types to displayable
  frontend types and reserves `UNCLASSIFIED` for canonical `UNKNOWN` or missing
  type.

## Edge Cases

- In scope: paired Bybit `fund_asset_changes` shadow with chain-aware
  `withdraw_deposit` sibling. Expected: excluded audit row plus active
  accounting sibling.
- In scope: unpaired Bybit transfer row. Expected: active external transfer,
  not a shadow exclusion.
- In scope: Bybit `BORROW` / `REPAY` rows without spot inventory movement.
  Expected: terminal unsupported audit-only rows, excluded from AVCO inventory.
- In scope: current quote is stale. Expected: dashboard still displays the last
  quote and exposes `stale_price` metadata.
- In scope: dashboard has no current quote. Expected: row remains visible with
  explicit missing or fallback price issue.
- In scope: long tx hash, venue ref, or EVM address in the transaction list.
  Expected: row layout remains stable and full text is available via hover.
- Out of scope: dataset-specific runtime exceptions keyed by live transaction
  hashes, wallet addresses, or hand-curated buckets.
- Out of scope: liability accounting for Bybit loans until a liability module is
  explicitly scoped.
- Out of scope: live RPC or market-provider work inside dashboard GET endpoints.

## Architecture Decision

```text
raw / extracted evidence
   |
   v
normalization + exact Bybit shadow pairing
   |
   | CONFIRMED rows only
   | excludedFromAccounting != true
   v
confirmed replay query
   |
   v
ReplayDispatcher defensive exclusion guard
   |
   v
asset_ledger_points

current quote refresh job
   |
   v
current_price_quotes
   |
   v
dashboard GET snapshot read
```

- Keep the modular monolith, MongoDB, Docker-based runtime, and snapshot-first
  read path.
- Treat `excludedFromAccounting = true` as an accounting boundary at the replay
  query and dispatcher levels.
- Keep Bybit shadow detection evidence-gated. A duplicate
  `fund_asset_changes` row may be excluded only when a chain-aware
  `withdraw_deposit` sibling proves the active accounting row.
- Keep active Bybit `withdraw_deposit` rows as `EXTERNAL_TRANSFER_IN/OUT`;
  the shadow exclusion reason belongs only to duplicate audit rows.
- Read current quote snapshots from Mongo. Refreshing quotes belongs to
  background/session refresh work, not dashboard GET.
- Keep exact asset, family, and final-clean/proof-clean metrics separate.
- Non-blocking residual policy is explicit telemetry, not a substitute for
  exact/family coverage metrics.

## Backend Tasks

1. Add active-accounting filters to confirmed replay repository queries.
2. Keep a defensive excluded-row guard in `ReplayDispatcher`.
3. Preserve Bybit transfer shadow pairing only when a chain-aware sibling exists.
4. Keep active Bybit withdraw/deposit rows accounting-visible.
5. Keep Bybit loan rows in terminal unsupported audit-only scope.
6. Extend phase coverage telemetry with `blockingNeedsReview`,
   `excludedNeedsReview`, `excludedConfirmed`, `excludedLedgerPoints`,
   `blockingUncovered`, and non-blocking residual policy lanes.
7. Persist datastore-backed current quote snapshots in `current_price_quotes`.
8. Extend dashboard DTO/read model with current quote metadata and issue state.
9. Keep supported canonical transaction types displayable through the session
   transaction API.
10. Preserve AVCO carry fixes for current uncovered consumption, liquid staking,
    and audited AVAX receipt aliases.

## Frontend Tasks

1. Remove the dashboard token-table column after `Price`.
2. Show only formatted price in the visible price cell.
3. Put exact price, source, timestamp, freshness, live/stale state, and issue
   metadata in the price hover text.
4. Shorten long transaction hashes, venue refs, and addresses in collapsed row
   layout.
5. Preserve full long references in hover hints.
6. Display collapsed flow preview as an unsigned non-fee material body.
7. Keep full signed flow details available in expanded transaction rows.

## Implementation Status

- Implemented in commit `df111f0 Close AVCO audit backlog`.
- Backend changed replay exclusion, Bybit normalization guards, quote refresh,
  dashboard read model, AVCO carry behavior, canonical asset aliases, and
  phase coverage telemetry.
- Frontend changed dashboard price display/hover metadata, transaction row
  reference shortening, and collapsed material flow preview.
- Documentation updated in domain, accounting, API, Bybit normalization, and
  task documents.

## Verification

Fresh acceptance run completed after `scripts/prod-reset-rebuild-backend.sh` on
`2026-04-25T19:33:29.618Z`.

Commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
```

Observed post-run invariants:

- `ACCOUNTING_REPLAY / COMPLETE`
- `blockingNeedsReview = 0`
- `excludedNeedsReview = 3`
- `excludedConfirmed = 91`
- `excludedLedgerPoints = 0`
- `rawTransactions = 3183`
- `normalizedTransactions = 5818`
- `confirmed = 5815`
- `assetLedgerPoints = 9303`
- `onChainBalances = 210`
- mandatory asset/family `blockingUncovered = 0`
- `BYBIT_TRANSFER_SHADOW_ROW` rows: `91`
- confirmed excluded shadow rows: `91`
- shadow ledger points: `0`
- active Bybit withdraw/deposit rows under `BYBIT_SHADOW_CUSTODY`: `32`
- ETH exact uncovered is explicitly non-blocking native gas residual:
  `0.001101826047118264`
- FAMILY:ETH uncovered is explicitly non-blocking native gas/yield residual:
  `0.010536892986039197`
- FAMILY:BTC uncovered is explicitly non-blocking family-only dust:
  `0.00000008999999999946551`
- dashboard BTC and other market prices come from current quote snapshots when
  available, with staleness explicitly reported.

Residual protocol/counterparty metadata gaps remain metadata-quality backlog,
not mandatory AVCO blockers in this runtime:

- protocol gaps remain concentrated in `SWAP`, `BRIDGE_IN`, `BRIDGE_OUT`,
  vault, lending, reward, and LP rows.
- counterparty gaps remain concentrated in `SWAP`, external transfers, bridges,
  LP, lending, vault, borrow, and repay rows.
