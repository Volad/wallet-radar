# WalletRadar MVP — Active Requirement Waves

This file tracks only requirement waves that are consistent with the current codebase.

## Scope Baseline (Implemented Foundations)

Current implementation baseline:

- Canonical pipeline is `raw_transactions -> normalized_transactions`
- Raw processing uses `normalizationStatus` (`PENDING`, `COMPLETE`)
- Canonical normalized status flow:
  - `PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED`
  - with fallback `NEEDS_REVIEW`
- Classification entrypoint is `RawTxNormalizationJob` + `ClassificationProcessor`
- Canonical upsert store is `IdempotentNormalizedTransactionStore`

## Active Requirement Waves

- **T-034..T-038** — `15-normalized-transactions-pipeline.md`
- **T-039..T-044** — `16-evm-explorer-ingestion-v2.md`
- **T-045** — `17-scam-filter-hardening.md`
- **T-046** — `18-lp-lifecycle-disambiguation.md`
- **T-047** — `19-lp-v3v4-entry-economic-completeness.md`
- **T-048** — `20-explorer-selective-enrichment-policy.md`
- **T-049** — `21-classifier-fast-path-coverage-and-enrichment-optimization.md`
- **T-050** — `22-session-controller-and-backfill-status.md`
- **T-051** — `23-backfill-segment-configuration-by-sync-method.md`

## Current Step

- **T-051** — Planned for implementation (backend config/executor refactor + tests).

## Removed From Active Requirements

The previous monolithic MVP index (T-001..T-033 and old terminology around `economic_events`) is no longer an active requirement source.  
Historical details remain in per-file docs for reference only and must be explicitly re-approved before execution.

## Owner Rule

Product direction is set explicitly by owner requests.  
No autonomous activation of archived requirements.
