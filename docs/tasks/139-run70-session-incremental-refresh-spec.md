# Feature/Change Spec — Session Incremental Refresh (Business Analyst + System Architect)

Goal (1 sentence):

Allow a user to press `Refresh` in the frontend and schedule a bounded
incremental raw-sync cycle from the last completed source checkpoint to now,
then resume the downstream normalization/pricing/accounting pipeline without
resetting the full 2-year history.

## Problem

The current control plane supports:

- initial wallet backfill
- integration backfill
- session-level backfill status polling

But it does not support a user-triggered incremental refresh after the initial
2-year history is already loaded.

The missing behavior is:

- user clicks one button
- backend schedules only the delta window since the last completed sync
- segment count scales to that delta window
- existing confirmed canonical history stays in place
- new evidence may still attach deterministic links to existing canonical rows

## Architecture Decision

### A. Stable source status vs refresh cycle

Decision:

- do **not** create a second active `sync_status` document for the same
  wallet×network or integration source
- keep `sync_status` as the stable source-level status row
- model the user action as a new incremental refresh cycle on that source

Rationale:

- current code and indexes already treat `sync_status` as a stable source
  identity
- current read paths expect one current row per source
- introducing parallel active `sync_status` rows would force a much broader
  rewrite of query semantics, uniqueness, and session progress reads

Implication:

- the user-visible concept is still “start a new sync”
- the backend implementation should realize that as a new bounded cycle over
  the existing source status, not as a duplicate active source row

### B. Backfill segment planning

Decision:

- keep `backfill_segments` as the single orchestration model
- refresh creates only the new segments required for the delta window
- segment count must be proportional to the actual delta range

Examples:

- `2 years` initial backfill may produce hundreds of segments
- `1 hour` refresh must produce only the small number of segments actually
  needed for that hour

### C. Canonical history preservation

Decision:

- do not wipe historical `raw_transactions`
- do not wipe historical `normalized_transactions`
- do not reopen the full 2-year normalization backlog
- allow bounded metadata/linkage patching on existing canonical rows if new
  evidence proves a deterministic link

Allowed existing-row updates:

- `correlationId`
- `matchedCounterparty`
- bridge / custody / lifecycle link metadata

Not allowed in this slice:

- blanket reset of historical canonical rows
- global re-normalization because a short refresh was requested

### D. Downstream pipeline strategy

Decision for v1:

- raw acquisition is incremental
- normalization and clarification are incremental
- pricing and accounting replay may be rerun from the downstream pipeline
  entrypoint after refresh scheduling

Rationale:

- this keeps the most expensive destructive work out of the flow
- replay is deterministic and derived from canonical state
- it is materially safer than a full raw + normalization reset

## ASCII Flow

```text
Frontend Refresh Button
        |
        v
POST /api/v1/sessions/{sessionId}/refresh
        |
        v
Refresh planner
  |- load session scope
  |- resolve last completed checkpoints
  |- resolve current heads / current time
  |- create only delta backfill_segments
        |
        v
Raw backfill runners
  |- ONCHAIN block delta
  |- INTEGRATION time delta
        |
        v
Incremental normalization / clarification
  |- insert new canonical rows
  |- patch deterministic links on existing rows when proven
        |
        v
Pricing + accounting replay
        |
        v
GET /backfill-status + existing dashboard polling
```

## Acceptance Criteria (Definition of Done)

- [ ] A session-level refresh API exists and can be called after the initial
      session backfill completed.
- [ ] Refresh scheduling uses the existing session scope:
      - tracked wallets
      - enabled integrations
- [ ] For on-chain sources, refresh computes the lower bound from the last
      completed source checkpoint and the upper bound from the current chain
      head.
- [ ] For integration sources, refresh computes the lower bound from the last
      completed source checkpoint and the upper bound from current time or the
      provider-specific current anchor.
- [ ] Refresh creates only the `backfill_segments` needed for the delta window.
- [ ] A short delta window does not recreate the full original segment count.
- [ ] Refresh does not wipe or reopen the full 2-year raw history.
- [ ] Refresh does not wipe or reopen all historical canonical rows.
- [ ] Existing canonical rows may be updated only when new evidence proves a
      deterministic bounded link such as `correlationId`.
- [ ] If no new range exists for any in-scope source, refresh is accepted as a
      no-op and does not create new segments.
- [ ] Refresh is rejected if the same session already has an active backfill or
      downstream pipeline run.
- [ ] Existing session backfill-status polling remains the frontend source of
      truth during refresh.
- [ ] The frontend exposes one `Refresh` action in the header/topbar area.
- [ ] The frontend disables the button while refresh/backfill/pipeline is
      already running.
- [ ] The frontend surfaces success, no-op, and conflict states from the new
      endpoint.

## Edge Cases

- Case: session has no wallets and no enabled integrations | Scope: In |
  Expected behavior: refresh endpoint returns validation error or no-op; button
  is disabled in UI.
- Case: initial backfill never completed | Scope: In | Expected behavior:
  refresh is not a substitute for first-time backfill; backend rejects or
  routes the user to the existing initial pipeline.
- Case: one wallet×network is already at current head | Scope: In | Expected
  behavior: that source is skipped without creating segments.
- Case: one integration has no delta window but another does | Scope: In |
  Expected behavior: schedule only the source(s) with real delta.
- Case: refresh is clicked while session pipeline is already `RUNNING` |
  Scope: In | Expected behavior: backend returns conflict, frontend keeps the
  button disabled and does not enqueue a duplicate run.
- Case: new rows deterministically match an older bridge / custody / CEX row
  just outside the delta window | Scope: In | Expected behavior: bounded link
  metadata may be patched on the existing canonical row.
- Case: refresh finds new raw rows but downstream pricing/replay later fails |
  Scope: In | Expected behavior: new raw/canonical data remains persisted; the
  session surfaces the failed stage without wiping older confirmed history.
- Case: provider/explorer returns no new rows for the delta range | Scope: In |
  Expected behavior: source is marked up to date without empty historical
  reset.
- Case: late provider corrections appear earlier than the last completed
  checkpoint | Scope: Out for v1 | Expected behavior: no automatic historical
  rewind in this slice; handled by a later replay-safe repair design.

## Supported vs Unsupported

- Supported in this slice:
  - session-level manual incremental refresh
  - proportional on-chain delta segment planning
  - proportional integration delta segment planning
  - bounded linkage patching for existing canonical rows
  - reuse of current backfill-status polling UI
- Not supported in this slice:
  - concurrent refreshes for the same session
  - full historical rewind from the refresh button
  - automatic repair of provider corrections older than the last completed
    checkpoint
  - new onboarding flow
  - new status/read model outside the current session dashboard surfaces

## Task Breakdown

### SA-139-01 Architecture

1. Freeze the control-plane decision that `sync_status` remains source-level and
   refresh is modeled as a new cycle, not as a second active `sync_status`
   document.
2. Freeze the planning decision that `backfill_segments` remains the only
   orchestration store for both initial and incremental cycles.
3. Freeze the preservation rule that historical canonical rows are not globally
   reset; only bounded deterministic link metadata may be patched.
4. Freeze the v1 execution strategy that incremental raw + normalization is
   followed by downstream pipeline resume without full raw reset.

### BA-139-02 Requirements

1. Define the user-visible states for refresh:
   - ready
   - scheduling
   - running
   - conflict
   - no-op / already up to date
   - failed
2. Define when refresh is allowed:
   - only after initial session load completed
   - not while the same session pipeline is active
3. Define what “data must not be affected” means:
   - no full raw wipe
   - no full canonical wipe
   - bounded metadata patching on existing rows is allowed when proven
4. Define the no-op semantics and success messages for the frontend.

### BE-139-03 Backend API and scheduling

1. Add `POST /api/v1/sessions/{sessionId}/refresh`.
2. Add a command service that:
   - loads session scope
   - verifies refresh eligibility
   - computes delta lower/upper bounds per source
   - creates only needed incremental segments
   - marks session pipeline/backfill state as running
3. Return a structured response with at least:
   - `sessionId`
   - `message`
   - `scheduledTargets`
   - `skippedTargets`
4. Reject concurrent refresh attempts for the same session.
5. Implement no-op acceptance when all sources are already up to date.

### BE-139-04 On-chain delta planning

1. Reuse current source checkpoints such as `lastBlockSynced` / completed
   source status.
2. Compute `fromBlock = lastCompletedCheckpoint + 1`.
3. Compute `toBlock = currentHead`.
4. Create only the required `BLOCK_RANGE` segments for that delta.
5. Ensure segment count is proportional to the delta window, not the original
   2-year window.
6. Keep current raw documents intact; append only new raw rows.

### BE-139-05 Integration delta planning

1. Reuse the provider-specific last completed checkpoint for each enabled
   integration.
2. Compute only the new `TIME_RANGE` segments needed from the last completed
   checkpoint to the current anchor time.
3. Keep provider planning provider-specific but orchestration shared.
4. Do not recreate the entire 2-year integration segment tree for a short
   refresh.

### BE-139-06 Incremental normalization and linkage

1. Normalize only new raw rows plus the bounded directly impacted set required
   for deterministic rematch/linking.
2. Do not clear historical `normalized_transactions`.
3. Allow existing canonical rows to receive bounded deterministic linkage
   updates such as:
   - `correlationId`
   - `matchedCounterparty`
4. Ensure the rematch path can see existing canonical rows around the delta
   boundary without reopening the entire history.

### BE-139-07 Pipeline continuation

1. Resume the downstream session pipeline after incremental raw backfill
   scheduling.
2. Keep pricing and accounting replay deterministic.
3. Ensure existing confirmed canonical rows remain available while the refresh
   pipeline runs.
4. Ensure refresh failure does not roll back older confirmed history.

### FE-139-08 Frontend topbar action

1. Add a `Refresh` button to the dashboard header/topbar area.
2. Call the new session refresh endpoint through `WalletApiService`.
3. Disable the button when:
   - there is no active session
   - initial backfill is not complete
   - current backfill/pipeline is already running
4. Reuse the existing backfill-status polling surface after refresh starts.
5. Show user-visible feedback for:
   - refresh scheduled
   - already up to date
   - refresh conflict
   - refresh failure

### FE-139-09 Frontend data contract and tests

1. Add typed DTOs for the refresh request/response.
2. Add unit tests for button enabled/disabled states.
3. Add API-service tests for the new endpoint.
4. Add dashboard/topbar tests for scheduled, no-op, and conflict flows.

## Risk Notes

- The user idea is correct, but the backend should not create a second active
  `sync_status` row for the same source. The stable-source-row model is
  materially safer with the current codebase.
- The biggest correctness risk is bounded linkage across the refresh boundary.
  That must be explicit and deterministic.
- Late provider corrections earlier than the last checkpoint are intentionally
  out of v1; they require a separate rewind/repair design.
- Full raw renormalization is explicitly not part of this feature and should
  not be used as the default implementation shortcut.
