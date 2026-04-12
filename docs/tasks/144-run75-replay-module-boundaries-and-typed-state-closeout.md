# 144 — Replay Module Boundaries And Typed State Closeout

Status: in progress
Owner: system-architect + backend
Scope type: architecture + backend execution
Priority: P0

## Goal

Complete the AVCO replay refactor so that `costbasis.application` no longer
contains a legacy monolith or root-level helper sprawl.

Target outcome:

- `AvcoReplayService` becomes a thin coordinator.
- Replay internals move into a dedicated `replay/*` module tree.
- Mutable replay state is accessed through typed stores and typed keys instead
  of root-level raw maps.
- Large handlers are split by responsibility, not only by protocol family.
- No unused legacy replay methods remain in `costbasis.application`.

## Architect Decisions

1. Keep the modular monolith.
2. Keep deterministic replay order unchanged.
3. Introduce subpackages under `costbasis.application.replay`:
   - `query`
   - `dispatch`
   - `handler`
   - `planning`
   - `persistence`
   - `state`
   - `support`
   - `model`
4. Root package `costbasis.application` keeps only application-facing entry
   services and jobs.
5. Replay handlers must depend on typed support services, not on
   `AvcoReplayService`.
6. Future parallelization must target planning and instruction-building before
   mutation execution; this refactor must prepare for that by making state and
   keys explicit.

## Backend Tasks

### BE-144-01 Replay package boundaries

- Move replay-only classes out of root `costbasis.application`.
- Keep `CostBasisReplayJob` and root-facing application services in place.
- Delete obsolete root-level replay helpers after migration.

### BE-144-02 Typed replay state

- Replace root-level raw state maps with typed stores inside replay state.
- Introduce typed correlation / flow / pending-transfer keys where replay
  matching currently uses plain strings.
- Remove direct `Map<String, ...>` usage from handlers.

### BE-144-03 Thin coordinator

- Reduce `AvcoReplayService` to:
  - load confirmed rows
  - build replay state
  - dispatch transactions
  - persist results
- Move flow dispatch, transfer carry logic, settlement allocation, and asset
  identity helpers into dedicated replay collaborators.

### BE-144-04 Handler split by responsibility

- Split the current async lifecycle logic into bounded handlers:
  - generic async lifecycle
  - GMX LP entry lifecycle
  - position-scoped LP lifecycle
- Keep liquid staking, family-equivalent custody, Euler loop, and async spot
  order handlers bounded and dependency-clean.

### BE-144-05 Persistence and support cleanup

- Isolate ledger point materialization / persistence boundaries.
- Remove duplicate allocation / carry / keying logic.
- Remove unused methods and dead code from the replay package.

### BE-144-06 Verification

- Keep replay/costbasis tests green.
- Confirm no replay internals remain as root-level legacy helpers.
- Prepare downstream state for a fresh normalization rerun after the refactor.

## Acceptance Criteria

1. `AvcoReplayService` is orchestration-focused and materially smaller.
2. No replay helper class exceeds clear SRP bounds; target is under `400` LOC
   per class unless explicitly justified.
3. `AsyncLifecycleReplayHandler` no longer contains GMX, generic async, and
   position-scoped LP logic in one file.
4. Replay state access is typed and explicit.
5. Root `costbasis.application` contains no obsolete replay legacy code.
6. Replay-focused tests pass after the refactor.
7. Backend runtime is reset and ready for a fresh normalization pass.
