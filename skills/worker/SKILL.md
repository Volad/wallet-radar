---
name: worker
description: "WalletRadar Worker/Executor. Implement tasks from docs/tasks; follow domain, architecture, accounting, and API specs; never assume requirements; request clarifications from system-architect; add unit/integration tests; respect module boundaries; use Gradle (not Maven). Trigger on requests like 'implement feature', 'fix bug', 'add tests', 'finish task {ID}', or 'make change per docs/tasks'."
---

# Worker — WalletRadar

Implement functionality exactly as specified in `docs/tasks`, verified by tests, and aligned with WalletRadar rules.

## Quick Start
1. Confirm the task/feature and its ID from `docs/tasks/` (and current status in `docs/tasks/mvp-implementation-tasks.md`).
2. Read only relevant sections from:
   - `docs/01-domain.md`, `docs/02-architecture.md`, `docs/03-accounting.md`, `docs/04-api.md`
3. If anything is unclear, pause and ask the `system-architect` skill for clarification.
4. Implement in the correct module(s), respecting dependency rules and invariants.
5. Add/extend unit tests and, when crossing modules or persistence/API, integration tests.
6. Run Gradle tests; iterate until green. Prepare small, focused changes.
7. Update `docs/tasks/mvp-implementation-tasks.md` (Completed tasks, Current step) after merge.

## Commands (Backend)
- Run tests: `./gradlew :backend:test` or `./gradlew test` (multi-project root)
- Build app: `./gradlew :backend:build`
- Run locally: `./gradlew :backend:bootRun`

## Implementation Rules
- Source of truth: `docs/tasks/*` — implement only what’s listed; do not add scope.
- Respect module boundaries: `api`, `ingestion`, `costbasis`, `pricing`, `snapshot`, `domain`, `config`, `common` (see architecture doc for allowed deps).
- Accounting invariants: strict chronological AVCO, realised P&L computed atomically, `crossWalletAvco` never stored, Decimal types for money, zero-RPC GETs.
- Idempotency and ordering rules per domain docs must never be broken.
- Use Lombok and configuration conventions per repo rules where applicable.

## When Blocked
- Post a short clarification request to `system-architect` with the exact ambiguity and proposed options.

## Deliverables Checklist
- Code changes scoped to the task and correct module(s)
- Unit tests (and integration tests when needed)
- Passing Gradle build/tests
- Notes for `docs/tasks/mvp-implementation-tasks.md` (Completed tasks, Current step)

## References
- Criteria summary: [worker-criteria.md](references/worker-criteria.md)
- Work plan template: [WORK_PLAN_TEMPLATE.md](references/WORK_PLAN_TEMPLATE.md)
- Project docs under `docs/` and ADRs under `docs/adr/`.

