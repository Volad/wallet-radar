# Run 31 — Shared Backfill Dispatch and Session Completion Closeout

## Goal

Finish the integration-first backfill control plane so external integrations use
the same session-level backfill orchestration model as on-chain ingestion.

## Problem

Runs 29 and 30 introduced:

- shared `backfill_segments`
- session-owned integrations
- provider raw and extracted staging for Bybit

But two orchestration gaps remained:

1. Bybit still owned its own scheduled runner instead of participating in the
   shared backfill dispatcher.
2. Session-level raw completion and backfill progress still counted only
   wallet×network on-chain targets.

That left the data model unified, but the runtime behavior partially split.

## Target Policy

1. `backfill_segments` remains the single durable orchestration model.
2. `BackfillJobRunner` remains the single orchestration entrypoint.
3. Shared integration planning owns segment replacement and persistence.
4. Source/provider-specific code may differ only in:
   - segment spec generation
   - segment execution logic
5. Session-level raw completion requires:
   - all on-chain wallet×network targets complete
   - all enabled integration segment sets complete
6. Session backfill progress must aggregate both:
   - on-chain targets
   - enabled integration targets
7. No Mongo cleanup is performed in this slice.

## Scope

In scope:

- shared segment-executor dispatch inside `BackfillJobRunner`
- converting Bybit acquisition from a dedicated runner to a shared segment
  executor
- integration-aware session raw completion
- integration-aware session backfill progress
- integration-aware resume watchdog behavior

Out of scope:

- deleting `external_ledger_raw`
- deleting legacy fallback from Bybit normalization
- providers beyond Bybit

## Acceptance Criteria

1. No provider-owned scheduled runner remains for Bybit acquisition.
2. Shared `BackfillJobRunner` dispatches integration segments through a segment
   executor interface.
3. A newly connected Bybit integration can complete session raw backfill
   without requiring any wallet×network event.
4. `GET /sessions/{id}/backfill-status` counts enabled integrations as shared
   backfill targets.
5. Resume watchdog does not ignore integration-backed sessions when evaluating
   raw completion.
6. Existing Mongo data remains intact.

## Tasks

### BA-100-01 Requirements

1. Freeze the orchestration rule:
   - one shared backfill control plane
   - provider-specific code differs only in segment execution
2. Freeze the completion rule:
   - session raw completion is the conjunction of on-chain and integration
     completion

### SA-100-02 Architecture

1. Keep `backfill_segments` as the single orchestration collection.
2. Move Bybit from a provider-owned runner to a shared segment executor.
3. Keep session-level progress/read APIs aligned with shared targets.

### BE-100-03 Backend

1. Add a shared segment executor interface for `backfill_segments`.
2. Add a shared integration planning service for segment replacement and
   persistence.
3. Move Bybit planning into a provider-specific segment planner that returns
   segment specs only.
4. Move Bybit acquisition into a shared segment executor invoked by
   `BackfillJobRunner`.
5. Make session raw completion integration-aware.
6. Make session backfill progress integration-aware.
7. Make resume watchdog integration-aware.
8. Add targeted regression tests.

### FE-100-04 Frontend

No frontend code change is required in this slice. Existing settings and
session status endpoints consume the same REST surface, but with corrected
shared backfill semantics.

## Expected Outcome

After this slice:

- the backfill control plane is behaviorally shared for on-chain and external
  integrations
- Bybit no longer owns a separate scheduler
- session raw completion and session backfill progress reflect enabled
  integrations correctly
