# ADR-024: Bybit per-stream sync metadata on session settings

> Renumbered from ADR-005 (numbering collision with ADR-005 Cycle 4 Bybit pipeline).

## Status

Accepted

## Context

Bybit ingestion uses multiple API streams (`TRANSACTION_LOG`, executions, `FUNDING_HISTORY`, transfers, convert, earn, deposits, withdrawals). The session settings API exposed only aggregate segment counts and a single `lastSyncAt`, which made it unclear **which** streams had been refreshed after a partial or incremental backfill.

## Decision

Extend `GET /api/v1/sessions/{sessionId}/settings` so each **Bybit** integration entry includes `streamSync`: a fixed list (one row per `BybitIntegrationStream`) with:

- `lastSegmentCompletedAt` — derived from `backfill_segments` (`status=COMPLETE`, max `completedAt` per `stream`).
- `newestStoredEventAt` — derived from `bybit_extracted_events` (max `timeUtc` per `sourceStream`).

Non-Bybit integrations return an empty `streamSync` array.

## Consequences

- **Pros**: Operators and users see per-stream freshness; reduces confusion when using refresh/backfill.
- **Cons**: Two Mongo aggregations on settings load for Bybit (bounded, indexed paths).
- **Cost**: No extra RPC; read-only Mongo aggregations on existing collections.

## When

2026-05-10 (implementation batch tied to task 155 / audit `results/required-changes.md`).
