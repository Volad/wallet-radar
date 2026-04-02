# Live Session Event-Driven Pipeline Order

Status: Completed

Goal:

Make the live user-session pipeline start from raw backfill completion instead of
cron timing, so cross-stage ordering is deterministic and `Bybit <-> on-chain`
continuity cannot observe stale on-chain state.

Related inputs:

- [run/38 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/38/clarification-readiness-audit.md)
- [Architecture summary](../02-architecture.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

Run/38 suggests an ordering defect in the live pipeline:

- raw backfill completed
- `BYBIT` normalization likely ran before the on-chain side had finished
  normalization + clarification
- `Bybit` economic rows then fragmented into `NEEDS_REVIEW` / unmatched tails

For a live session, cron timing is not an acceptable primary orchestrator because:

- `Bybit -> on-chain` continuity depends on already materialized on-chain
  canonical rows
- pricing must only start after canonical on-chain + Bybit rows are settled
- `AVCO / cost basis / move basis` depend on a stable canonical event stream

## Approved Runtime Order

For a live session, the approved stage order is:

1. raw backfill
2. on-chain normalization
3. on-chain clarification
4. Bybit normalization
5. cross-universe exact rematch
6. pricing
7. accounting replay

Notes:

- `move basis` is not a separate ingestion stage; it is part of accounting
  replay together with `AVCO`, realized cost basis, and reconciliation.
- the current runtime has no standalone `ledger normalization` job; the
  canonical handoff into replay is represented by the settled normalized rows,
  pricing gate, and stat validation inside accounting replay.

## Architecture Decision Slice

`system-architect` contract for this slice:

1. `backfill` is the primary trigger for the live pipeline.
2. stage schedulers must not orchestrate live-session normalization,
   clarification, Bybit, pricing, or replay.
3. a single session-level watchdog may re-emit session backfill completion when
   raw backfill is already complete but the original completion event was
   missed.
4. stage ordering must be dependency-driven, not time-driven.
5. event-driven execution must remain idempotent and safe under duplicate
   completion signals.
6. the pipeline may stay installation-wide at runtime, but the trigger is
   session-scoped completion.

## Scope

In scope:

- publish session-scoped completion when all wallet×network raw backfills for a
  session are complete
- trigger on-chain normalization from that completion event
- keep clarification event-driven after on-chain normalization
- trigger Bybit normalization after clarification completes
- trigger pricing after Bybit normalization completes
- trigger accounting replay after pricing completes
- document that stage schedulers are removed and only the session-level
  backfill-resume watchdog remains

Out of scope:

- redesigning canonical Bybit trade pairing semantics
- new control-plane / workflow engine
- replacing existing scheduled jobs entirely
- changing accounting rules or replay formulas

## Acceptance Criteria (DoD)

1. A live session with completed raw backfill emits a session completion event.
2. That event triggers on-chain normalization without waiting for the next cron
   tick.
3. Bybit normalization cannot start from the live pipeline until on-chain
   clarification has completed.
4. Pricing cannot start from the live pipeline until Bybit normalization has
   completed.
5. Accounting replay cannot start from the live pipeline until pricing has
   completed.
6. Duplicate completion signals do not break correctness:
   - jobs remain idempotent
   - concurrent duplicate triggers are skipped safely
7. No stage scheduler remains for normalization / clarification / Bybit /
   pricing / accounting replay.
8. If raw backfill is already complete but the session completion event was
   missed, a session-level watchdog re-emits the completion signal and starts
   the pipeline.
9. Docs explicitly describe the approved live-session order and clarify that
   `move basis` belongs to accounting replay, not to an earlier ingestion stage.

## Task Breakdown

1. `BE-LSP-01` Add session-completion event
   - detect when all `sync_status` targets for a session are
     `backfillComplete=true`
   - publish a session-scoped completion event

2. `BE-LSP-02` Trigger on-chain normalization from backfill completion
   - event listener in normalization job
   - publish completion downstream even when the live-session drain is empty

3. `BE-LSP-03` Trigger downstream pipeline from stage completion
   - clarification completion -> Bybit normalization
   - Bybit completion -> pricing
   - pricing completion -> accounting replay

4. `BE-LSP-04` Remove stage schedulers and keep only session resume watchdog
   - explicit event chaining for live-session stages
   - no stage schedulers remain in normalization / clarification / Bybit /
     pricing / accounting replay
   - one session-level watchdog may re-emit `SessionBackfillCompletedEvent`
     from durable session + sync-status state

5. `BE-LSP-05` Regression tests
   - session completion detection
   - job listener wiring
   - duplicate-trigger safety / empty-drain completion behavior

6. `BE-LSP-06` Rerun prep
   - after implementation, clear derived state only
   - keep raw evidence collections intact for the next normalization run

## Completion Notes

Implemented:

- session-scoped raw backfill completion event
- session-level authoritative pipeline state in `user_sessions.pipelineState`
- session-level watchdog scheduler that re-emits backfill completion when raw
  backfill is already complete and pipeline work is still pending
- live-session event chain:
  - session backfill complete -> on-chain normalization
  - on-chain normalization complete -> clarification
  - clarification complete -> Bybit normalization
  - Bybit normalization complete -> pricing
  - pricing complete -> accounting replay
- empty live-session drains still publish downstream completion so the pipeline
  does not stall on a zero-row stage
- stage schedulers were removed from normalization / Bybit / pricing /
  accounting replay
- remaining schedulers are limited to:
  - raw backfill progress / retry infrastructure
  - the session-level backfill-resume watchdog

Verification:

- targeted job and coordinator tests are green
- architecture tests remain green
