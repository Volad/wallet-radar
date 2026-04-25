# Task 148: Cycle 81 Runtime Audit Closeout

Status: In implementation
Owner: business-analyst + system-architect
Implementation owners: backend-dev, frontend-dev
Audit basis:

- `auto-loop-handoff/artifacts/cycle/81/financial-analyst/runtime-audit.md`
- `auto-loop-handoff/artifacts/cycle/81/financial-analyst/clarification.md`

## Goal

Close the current runtime blockers from cycle 81 without reclassifying accepted
audit-only rows or using stale audit evidence.

The latest runtime clarification proves that the `91`
`BYBIT_TRANSFER_SHADOW_ROW` rows are correctly excluded duplicate
`fund_asset_changes` rows. The defect is that excluded confirmed rows still
entered AVCO replay and produced `asset_ledger_points`.

## Acceptance Criteria

- `BYBIT_TRANSFER_SHADOW_ROW` rows remain:
  - `status = CONFIRMED`
  - `excludedFromAccounting = true`
  - `accountingExclusionReason = BYBIT_TRANSFER_SHADOW_ROW`
- Active chain-aware Bybit `withdraw_deposit` rows remain visible to accounting
  and replay as `EXTERNAL_TRANSFER_IN/OUT`.
- No `excludedFromAccounting = true` row is returned by confirmed replay
  queries.
- `ReplayDispatcher` defensively skips excluded rows and writes no ledger point
  for them.
- Runtime acceptance after rebuild reports:
  - `shadowRows = 91`
  - `confirmedExcludedShadowRows = 91`
  - `unpairedExcludedShadowRows = 0`
  - `shadowLedgerPoints = 0`
  - `blockingNeedsReview = 0`
  - `excludedNeedsReview = 3`
- The three Bybit loan rows remain terminal unsupported audit-only rows with
  `BYBIT_LOAN_SEMANTICS_UNSUPPORTED`.
- Dashboard token table no longer shows the P&L column after `Price`.
- Dashboard token price cell shows only the formatted price; exact price,
  source, and load timestamp are available by hover.
- Transaction list hashes, venue refs, and addresses are shortened in the row
  layout, with full values available by hover.
- Transaction list flow preview excludes gas/fee flows and displays an unsigned
  material body such as `1,004.54 USDC`; full signed flows remain in the
  expanded details.
- Session transaction API returns every supported canonical
  `NormalizedTransactionType` as a displayable type, using `UNCLASSIFIED` only
  for canonical `UNKNOWN` or missing type.

## Edge Cases

- Paired Bybit `fund_asset_changes` shadow with a chain-aware
  `withdraw_deposit` sibling: excluded, audit-visible, never replayed.
- Unpaired Bybit transfer row: active `EXTERNAL_TRANSFER_IN/OUT`, not shadow
  excluded.
- Bybit transfer with tx hash and network: active chain-aware row, can replay
  and carry basis when continuity evidence exists.
- Bybit loan `BORROW/REPAY`: terminal unsupported review exclusion until
  liability accounting is explicitly scoped.
- BTC family-only dust below materiality remains a separate policy decision; it
  is not resolved by the Bybit replay-surface fix.
- ETH, AVAX, and USDC residual coverage require the separate carry/linking
  fixes described in the audit clarification.
- Family-only BTC dust is accepted as non-blocking only when exact BTC is clean
  and the family residual is below the deterministic scorecard threshold.
- Live receipt-token accrual above replay-carried principal is reported as
  `yield_accrual` when the latest replay point is a clean lending, staking, or
  vault `REALLOCATE_IN`; it is separated from blocking principal gaps.

## Architecture Decision

```text
normalized_transactions
   |
   | status = CONFIRMED
   | excludedFromAccounting != true
   v
ConfirmedReplayQueryService
   |
   v
ReplayDispatcher -- defensive excluded-row guard
   |
   v
asset_ledger_points
```

- Keep the modular monolith and Mongo-backed snapshot read model.
- Treat `excludedFromAccounting = true` as an active accounting boundary in
  every replay entry point.
- Do not reclassify the current `BYBIT_TRANSFER_SHADOW_ROW` rows as external
  transfers; their active chain-aware siblings already carry the external
  transfer meaning.
- Keep the pairer evidence gate deterministic: same uid, source stream,
  asset, direction, Bybit type, bounded time, tx hash, network, and quantity
  tolerance.
- Extend the runtime scorecard surface instead of relying on raw
  `NEEDS_REVIEW` alone.

## Backend Tasks

1. Add active-accounting filters to confirmed replay repository queries.
2. Add a defensive excluded-row skip in `ReplayDispatcher`.
3. Keep Bybit transfer shadow exclusion evidence-gated and add regression tests
   for active siblings and unpaired rows.
4. Update `scripts/avco/phase-coverage.sh` with `blockingNeedsReview`,
   `excludedNeedsReview`, `excludedConfirmed`, and `excludedLedgerPoints`.
5. Return supported canonical transaction types through the session
   transaction API instead of downgrading them to `UNCLASSIFIED`.
6. Carry liquid-staking destination covered principal by absolute source
   principal instead of applying a second source-covered ratio cut.
7. Report `blockingUncovered` and `nonBlockingUncovered` separately in
   `scripts/avco/phase-coverage.sh`, including yield accrual and BTC
   family-only dust policy.
8. Consume mixed replay bucket sell, fee, and unknown outbound events from
   current uncovered quantity before covered AVCO-backed quantity so
   historical tails do not poison current provability after later disposals,
   while preserving the existing covered-first continuity carry contract.
9. Map audited Aave Avalanche receipt aliases (`aAvaWAVAX`, `aAvaSAVAX`) and
   `sAVAX` to the `AVAX` market symbol for historical reward/accrual pricing.
10. Report small terminal native ETH gas-reserve residuals separately as
    non-blocking when they are below the deterministic `0.0015 ETH` threshold
    and have no canonical economic inflow evidence after the replay terminal
    point.
11. Report residual blocking quantity at or below `1e-9` units as
    non-blocking sub-unit dust after explicit scorecard policies have run.
12. Run focused backend tests, then full backend test suite.

## Frontend Tasks

1. Remove the dashboard token-table P&L column after `Price`.
2. Keep only the formatted price visible in the price cell and move exact
   price, source, and load timestamp to the hover title.
3. Add row-safe shortened display for long hashes, venue refs, and addresses in
   the transaction list, while preserving full hover hints.
4. Change collapsed flow preview to unsigned non-fee material quantity display.
5. Add frontend regression tests for shortened refs and unsigned flow preview.

## Verification

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
```
