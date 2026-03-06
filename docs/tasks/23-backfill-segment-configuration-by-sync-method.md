# 23 — Backfill Segment Configuration by Sync Method

## T-051 — Move segment tuning to `backfill.segments` with `defaults` and `by-rpc` profiles

- **Module(s):** `ingestion/config`, `ingestion/job/backfill`, `docs`
- **Roles:** system-architect direction implemented by backend worker

---

## Worker Handoff Command

Use worker skill for this feature implementation:

```text
$worker implement docs/tasks/23-backfill-segment-configuration-by-sync-method.md (T-051) end-to-end with tests
```

---

## Product Decisions (Locked)

1. Segment-related knobs must be grouped under:
   - `walletradar.ingestion.backfill.segments.defaults`
   - `walletradar.ingestion.backfill.segments.by-rpc`
2. Both `defaults` and `by-rpc` must use the same model class:
   - `BackfillSegmentConfiguration`
3. `BackfillSegmentConfiguration` must contain exactly these fields:
   - `segment-stale-after-ms`
   - `parallel-segments`
   - `parallel-segment-workers`
4. Runtime resolution in backfill execution:
   - `RPC` sync method -> use `by-rpc` with field-level fallback to `defaults`
   - any non-`RPC` sync method -> use `defaults`
5. Segment configuration stays in **backfill** domain (do not move to `evm-rpc` / `explorer` sections).

---

## Target YAML Contract

```yaml
walletradar:
  ingestion:
    backfill:
      segments:
        defaults:
          segment-stale-after-ms: 180000
          parallel-segments: 2
          parallel-segment-workers: 2
        by-rpc:
          segment-stale-after-ms: 120000
          parallel-segments: 6
          parallel-segment-workers: 4
```

Notes:

- Keep existing non-segment backfill keys (`window-blocks`, `worker-threads`, retries, etc.) as-is.
- Existing top-level segment keys under `backfill` are replaced by this nested structure.

---

## Worker Implementation Scope

### 1) Config model refactor

- Introduce:
  - `BackfillSegmentsConfiguration`
  - `BackfillSegmentConfiguration`
- Add to `BackfillProperties`:
  - `private BackfillSegmentsConfiguration segments`
- Remove old direct fields from `BackfillProperties`:
  - `parallelSegments`
  - `parallelSegmentWorkers`
  - `segmentStaleAfterMs`

### 2) Resolution logic in executor

- In `BackfillNetworkExecutor`, replace direct reads from `BackfillProperties` with resolved segment profile:
  - resolve network sync method from `IngestionNetworkProperties`
  - choose `by-rpc` for `SyncMethod.RPC`, otherwise `defaults`
  - apply field-level fallback to `defaults` when `by-rpc` value is absent/non-positive
- Keep existing hard safety caps already present in executor logic:
  - max segments cap (`365`)
  - workers limited by planned segments / executable segments

### 3) Configuration migration

- Update `backend/src/main/resources/application.yml` to new `backfill.segments.*` schema.
- Ensure startup behavior remains deterministic if `by-rpc` block is absent (defaults still work).

### 4) Validation and safety

- Enforce non-zero positive values for:
  - `segment-stale-after-ms`
  - `parallel-segments`
  - `parallel-segment-workers`
- Keep runtime clamping in executor as a defensive fallback.

---

## Required Tests

Backend tests must cover:

1. **Config binding**
   - `BackfillProperties` binds `segments.defaults.*` and `segments.by-rpc.*` correctly.
2. **Resolution by sync method**
   - `BackfillNetworkExecutor` uses `by-rpc` values for an RPC network.
   - `BackfillNetworkExecutor` uses `defaults` for a non-RPC network.
3. **Fallback behavior**
   - when `by-rpc` is missing/invalid, executor falls back to `defaults` values.
4. **Regression**
   - existing segment execution semantics (plan creation, stale recovery, completion/failure transitions) remain intact.

Run at minimum:

- `./gradlew :backend:test`

---

## Acceptance Criteria (DoD)

1. Segment tuning knobs are no longer top-level in `backfill`; they are nested under `backfill.segments`.
2. `BackfillSegmentConfiguration` is used for both `defaults` and `by-rpc`.
3. Executor selects segment profile by network sync method exactly as defined above.
4. Backfill behavior for non-RPC networks remains equivalent to current defaults.
5. Tests cover binding, resolution, fallback, and no-regression execution behavior.
