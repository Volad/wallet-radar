# WalletRadar — Requirements Task Index (Code-Aligned)

This folder contains task documents.  
Only tasks listed in **Active Requirements** are considered current requirements.

## Active Requirements

These files are aligned with the current backend implementation and are the only source of truth for worker execution:

- `15-normalized-transactions-pipeline.md`
- `16-evm-explorer-ingestion-v2.md`
- `17-scam-filter-hardening.md`
- `18-lp-lifecycle-disambiguation.md`
- `19-lp-v3v4-entry-economic-completeness.md`
- `20-explorer-selective-enrichment-policy.md`
- `21-classifier-fast-path-coverage-and-enrichment-optimization.md`

## Archived (Not Current Requirements)

The following files remain for historical context only.  
They may reference removed or renamed components (`economic_events`, `IdempotentEventStore`, `RawTransactionClassifierJob`, `InternalTransferReclassifier`) and must not be used as implementation requirements without explicit reactivation:

- `00-foundation.md`
- `00-ingestion-core.md`
- `00-cost-basis-engine.md`
- `00-pricing.md`
- `01-add-wallet-backfill.md`
- `02-wallet-sync-status.md`
- `03-incremental-sync.md`
- `04-current-balance.md`
- `05-override-recalc.md`
- `06-manual-compensating-transaction.md`
- `07-reconciliation-get-assets.md`
- `08-transaction-history.md`
- `09-portfolio-snapshots-charts.md`
- `10-backfill-refactoring.md`
- `11-inline-swap-price.md`
- `12-heuristic-swap-detection.md`
- `13-split-raw-fetch-classification.md`
- `14-deferred-price-cron-refactor.md`

## Process Rule

New requirements are added only by explicit direction from product owner and then documented in a new task file or as an update to one of the active files above.
