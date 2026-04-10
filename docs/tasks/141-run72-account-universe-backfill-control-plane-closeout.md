# Closeout — Account Universe Backfill Control Plane Refactor

Date: 2026-04-10

## Scope

Refactored the session backfill control plane so that:

- account-topology changes enter through `AccountUniverseChangedEvent`
- source windows are planned in `SourceSyncPlanner`
- segment creation is owned by `BackfillJobPlanner`
- `BackfillJobRunner` executes already-planned segments instead of creating
  them on the fly

This slice removes the old wallet-only orchestration trigger and aligns
initial backfill and refresh into one window-based sync model.

## Delivered

### Event model

- removed `WalletAddedEvent`
- added `AccountUniverseChangedEvent`
- added `AccountUniverseChangedEventHandler`

### Source planning

- added source-level window fields to `SyncStatus`
- added `lastSyncedAt` for time-based provider checkpoints
- added `SourceSyncPlanner`
- session create/settings/integration changes now publish account-universe
  change events instead of directly scheduling wallet/integration backfills

### Segment planning

- added `BackfillJobPlanner`
- on-chain execution no longer computes historical windows at runtime
- refresh/backfill now rely on the persisted source window before creating
  `backfill_segments`

### Execution

- `BackfillJobRunner` now dispatches only already-planned sources
- `BackfillNetworkExecutor` waits for planner-created segments and only
  executes them

## Invariants

- `sync_status` remains the stable source-level controller
- refresh/backfill differ only by the planned source window
- planner logic is no longer mixed into the segment runner
- historical raw / canonical rows are not deleted by this refactor
- derived session-scoped accounting outputs may be cleared on universe changes

## Verification

Successful checks:

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:compileTestJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.wallet.command.WalletBackfillServiceTest' --tests 'com.walletradar.session.application.SessionIntegrationCommandServiceTest' --tests 'com.walletradar.session.application.SessionRefreshCommandServiceTest' --tests 'com.walletradar.session.application.SessionSettingsCommandServiceTest' --tests 'com.walletradar.ingestion.job.backfill.BackfillJobRunnerTest'`

## Follow-up

- add dedicated unit tests for `SourceSyncPlanner`
- add dedicated unit tests for `BackfillJobPlanner`
- add dedicated unit tests for `AccountUniverseChangedEventHandler`
- run one live refresh against restored session data and confirm that existing
  `raw_transactions` no longer regress to `normalizationStatus=PENDING`
