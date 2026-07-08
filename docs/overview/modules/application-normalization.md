# Application normalization (`application.normalization`)

## Purpose

Transforms raw on-chain and integration evidence into `normalized_transactions`: classification, scam filtering, LP materialization, and on-chain clarification handoff.

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `normalized_transactions` | Canonical economic events (on-chain path) |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.normalization.job` | Scheduled normalization and clarification jobs |
| `application.normalization.pipeline.classification` | On-chain classifiers and family rules |
| `application.normalization.pipeline.onchain` | Raw transaction views and parsing support |
| `application.normalization.config` | Normalization/scam/Bybit property bindings |

## Allowed dependencies

- `canonical`, `domain`, `accounting.support`
- `session.application.TrackedWalletLookupService` (installation universe)
- `platform` utilities only where transport is not required

## Extension seams

- Classifier registry and protocol family handlers under `pipeline.classification`
- `OnChainRawTransactionView` — shared parse/normalize helpers
