# Application backfill (`application.backfill`)

## Purpose

Owns raw on-chain fetch orchestration: segment planning, network executors, sync progress, and the legacy standalone wallet backfill API.

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `raw_transactions` | Fetched on-chain evidence |
| `sync_status`, `backfill_segments` | Per-wallet/network progress windows |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.backfill.job` | `BackfillJobRunner`, `BackfillJobPlanner`, segment executors |
| `application.backfill.wallet.command` | `WalletBackfillService`, `WalletBackfillPlanner` port |
| `application.backfill.query` | `WalletSyncStatusService` (GET sync status) |
| `application.backfill.sync.progress` | `SyncProgressTracker` |
| `application.backfill.config` | `BackfillProperties`, segment configuration |

## Allowed dependencies

- `platform.networks` (RPC/explorer adapters)
- `session.application` (universe scope, source sync planning)
- `domain` repositories for sync/segment persistence

## Extension seams

- `WalletBackfillPlanner` — implemented by `BackfillJobPlanner`
- `BackfillSegmentExecutor` — per-network segment processing
