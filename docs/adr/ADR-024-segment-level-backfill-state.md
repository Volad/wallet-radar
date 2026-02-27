# ADR-024: Segment-Level Persistent Backfill State (`backfill_segments`)

**Date:** 2026-02-26  
**Status:** Accepted  
**Deciders:** Product Owner, System Architect, Backend Engineering

---

## Context

Backfill currently tracks progress primarily in `sync_status` at wallet×network level.
When a range is split into parallel segments and one segment fails (or the application restarts mid-run),
there is no durable per-segment execution state. This creates ambiguity:
- which block sub-ranges are already complete,
- which sub-ranges failed,
- how to resume without losing block coverage.

The requirement is to ensure no block-range gaps for segment mode, preserve retry behavior via `sync_status`,
and expose user-friendly progress.

## Decision

Introduce a new collection: **`backfill_segments`** (linked to `sync_status` by `syncStatusId`).

For each wallet×network sync:
- Persist segment plan once (no recalculation on retries/restarts).
- Store one document per segment with:
  - `segmentIndex`, `fromBlock`, `toBlock`
  - `status` (`PENDING`, `RUNNING`, `COMPLETE`, `FAILED`)
  - `progressPct`, `lastProcessedBlock`
  - `retryCount`, `errorMessage`, timestamps
- Segment plan cap: max **365** segments per sync.
- Segment stale recovery: `RUNNING` segment becomes stale after **3 minutes** without updates and is reset to `PENDING`.
- Segment execution runs only `PENDING/FAILED` segments.

Progress semantics:
- **Segment progress:** by blocks inside `[fromBlock..toBlock]`.
- **Sync progress:** by completed segments count (`completeSegments / totalSegments`).

Retry semantics:
- Retry orchestration remains on `sync_status` scheduler.
- In segment mode (`backfill_segments` exists), retries are **unbounded** (no `ABANDONED` transition).
- Legacy non-segment mode keeps existing max-retry/`ABANDONED` behavior.

## Rationale

- Prevents silent loss of block ranges after partial failure or restart.
- Gives deterministic resume point without re-planning ranges.
- Preserves existing operational retry flow (`sync_status` as global holder) while enabling fine-grained visibility.
- Supports UX progress that users can understand and monitor.

## Consequences

Positive:
- Durable per-segment state and resumability.
- Clear diagnostics for failed/stuck segments.
- Better progress reporting and observability.

Trade-offs:
- Additional writes in MongoDB for segment progress/state updates.
- Extra collection and indexes to maintain.

## Data Model / Indexes

Collection: `backfill_segments`

Indexes:
- unique `(syncStatusId, segmentIndex)`
- `(syncStatusId, status, updatedAt)`
- `(walletAddress, networkId, status)`

## Notes

- This ADR applies to segment-based backfill execution path.
- No backward-compatibility behavior is required for old segment plans.
