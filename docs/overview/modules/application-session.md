# Application session (`application.session`)

## Purpose

Owns **user session lifecycle**, accounting-universe scope, wallet/integration membership, pipeline state coordination, and session-scoped orchestration helpers consumed by portfolio, lending, LP, and pipeline jobs. Does **not** own normalization, linking, replay writes, or raw ingestion.

## Public surface (`application.session`)

| Service / port | Contract |
|----------------|----------|
| `AccountingUniverseService` | Resolves accounting-universe scope, member refs, classify cache |
| `SessionPipelineStateService` | Persists per-session pipeline stage/status |
| `SessionPipelineActivityService` | In-memory stage heartbeats for watchdog deferral |
| `SessionPipelineResumeScheduler` | Recovery watchdog re-emits pipeline events when work is pending |
| `AccountUniverseSyncPlannerService` | Plans on-chain universe sync off HTTP threads |

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `user_sessions` | Session wallets, integrations, pipeline state |
| `accounting_universes` | Stable owner scope for replay and dashboard reads |

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `domain.session` | `UserSessionRepository`, `AccountingUniverseRepository` | Session and universe persistence |
| `application.linking` | `LinkingDataGateService` | Resume watchdog linking gate |
| `domain.sync` | `SyncStatusRepository`, `BackfillSegmentRepository` | Backfill completion gates |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.session.application` | Universe scope, pipeline resume, sync planning |
| `application.session.config` | Session support configuration properties |
| `api.session` | REST controllers for session CRUD and pipeline status |

## Allowed dependencies

- `domain` (session, sync, events)
- `application.linking.job` / query gates (narrow read ports)
- `platform` (scheduling, telemetry)

Forbidden: direct normalization or replay writes from session query paths.

## Extension seams

- `SessionPipelineResumeScheduler.ResumeGateSnapshot` — batched Mongo existence gates per watchdog tick
- `AccountingUniverseService` classify cache — invalidate on universe membership changes

## Worked example

1. User adds wallets; `UserSession` persisted with `PipelineStage` pending backfill.
2. `SessionBackfillCompletedEvent` advances normalization; `SessionPipelineStateService` marks stage transitions.
3. Dashboard/lending/LP query services call `AccountingUniverseService.resolveScope(session)` for member refs.

## Microservice extraction

Session + universe service owns `user_sessions` and `accounting_universes`. Publishes domain events for pipeline stages; consumers remain event-driven workers.
