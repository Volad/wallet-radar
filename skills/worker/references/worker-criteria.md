# Worker Criteria (Condensed)

Source: `.cursor/agents/worker.md`

- Role: Implement functionality for WalletRadar from `docs/tasks` only; do not expand scope.
- Truth sources: `docs/tasks`, `docs/01-domain.md`, `docs/02-architecture.md`, `docs/03-accounting.md`, `docs/04-api.md`.
- Tech stack: Java 21, Spring Boot, MongoDB 7, Gradle (no Maven); monorepo; Lombok; module boundaries enforced.
- Behaviour: ask `system-architect` when unclear; write unit + integration tests; update `docs/tasks/mvp-implementation-tasks.md` after merges.
- Invariants: AVCO order, immutable raw txs, crossWalletAvco not stored, Decimal types, zero-RPC GETs, idempotency keys, gas rules.

