# WalletRadar AI Assistant Instructions

This document collects the **essential knowledge** an AI coding agent needs to be productive in the WalletRadar monorepo.  It is not a generic Java/Spring guide––focus is on the project's architecture, conventions, and workflows.

---
## Big Picture Architecture

- **Modular monolith**: single Spring Boot JAR with packages enforcing module boundaries via ArchUnit tests.
  - Root package `com.walletradar` with subpackages: `api`, `ingestion`, `costbasis`, `pricing`, `snapshot`, `domain`, `config`, `common`.
  - Modules may only call other modules according to the table in `docs/02-architecture.md` (e.g. `ingestion` cannot depend on `costbasis`).
  - Communication within the JVM uses **Spring `ApplicationEvent`**; there is no message broker in MVP.

- **Data flow**:
  1. **Wallet added** via `api/SyncController` or scheduled backfill job → `WalletAddedEvent` → ingestion component.
  2. **Ingestion**: adapters fetch raw transactions from EVM/​Solana RPC; classifier + normalizer convert them to `EconomicEvent` and write to MongoDB collections (`raw_transactions`, `economic_events`, `sync_status`, `on_chain_balances`).
  3. **Cost-basis engine** (`costbasis.engine.AvcoEngine`) reads ordered events, applies overrides, produces per‑wallet AVCO, realised PnL, etc. Cross‑wallet AVCO is computed on request and **never stored**.
  4. **Pricing** resolves prices using a chain (stablecoin hardcode → swap-derived → CoinGecko) with configurable rates. See `pricing.resolver` for implementations.
  5. **Snapshot** builder runs per-wallet cron job, stores `portfolio_snapshots`; on-request aggregation combines them.
  6. **API layer** is thin: controllers delegate to service interfaces and perform no business logic.

- **External dependencies**:
  - MongoDB 7 (collections defined in `domain` classes).
  - Blockchains via RPC (Ankr/Cloudflare for EVM; Helius for Solana); config under `walletradar.ingestion.network` in `application.yml`.
  - CoinGecko free API for prices; token‑contract overrides in `application.yml` (see ADR‑022).

- **Documentation** lives in `docs/` and `docs/adr` for ADRs. Read the relevant files before making architectural changes.

---
## Build, Test & Run Workflows

- **Gradle backend** only (frontend is Angular under `frontend/`). Use the wrapper from repo root:
  ```sh
  ./gradlew :backend:clean :backend:build
  ./gradlew :backend:test           # unit + integration + ArchUnit
  ./gradlew :backend:bootRun        # start local server
  ```
- Tests include ArchUnit rules (`backend/src/test/java/com/walletradar/architecture`) that enforce package dependencies and naming.
- **Configuration** is controlled via `backend/src/main/resources/application.yml`; add or document every new property there and create a matching `@ConfigurationProperties` class in the module's `config` package (see `.cursor/skills/worker-config-conventions/SKILL.md`).
- **Running locally**: MongoDB connection string is read from `spring.data.mongodb.uri`; the project expects a running Docker container or local instance. Basic `docker-compose.yml` at repo root can spin up Mongo.

---
## Code & Naming Conventions

- **Packages = modules**. Each subpackage contains a limited set of responsibilities; e.g. `ingestion.adapter.evm` for RPC clients, `ingestion.backfill` for orchestrators, `costbasis.override` for manual cost-basis overrides.
- **Domain objects** under `domain` are simple POJOs/records with no logic. Repositories in `domain` are Spring Data Mongo interfaces only; business code never lives there.
- **Service interfaces** are defined adjacent to implementations in each module (e.g. `SnapshotAggregationService` in `snapshot`). Controllers depend on interfaces via constructor injection; never autowire a repository directly.
- **Events** end in `Event` (e.g. `WalletAddedEvent`, `OverrideSavedEvent`). Use `ApplicationEventPublisher` or `@EventListener` for flows.
- **Custom validation annotations** live in `api.validation` (`@WalletAddress`, `@SupportedNetworks`); controllers annotate request DTOs with `@Valid`. Validation errors are handled centrally by `ValidationExceptionHandler`.
- **Retry/Rate limits**: common utilities in `common` package (`RetryPolicy`, `RateLimiter`, `StablecoinRegistry`).
- **ADRs mention important design decisions**; if you change behaviour, update or create a new ADR.

---
## Project-Specific Patterns

- **Snapshot-first reads**: GET endpoints must not perform RPC calls or heavy computation; they should read only Mongo collections populated by background jobs.
- **Deterministic AVCO**: cost-basis computations must give the same result regardless of event ordering; modifications must preserve this property. The engine reads events ordered by `blockTimestamp`.
- **Cross‑wallet AVCO** is computed on demand by `CrossWalletAvcoAggregatorService` using existing per-wallet snapshots; it is explicitly *not* persisted.
- **Backfill jobs** are split into Phase 1 (raw fetch) and Phase 2 (classification). See `ingestion.backfill` package and ADR‑014/ADR‑017 for context.
- **Configuration conventions**: every new `walletradar.*` key should be added to YAML with sensible defaults and documented in code comments.
- **Exception handling**: business exceptions have an error code enum and are translated to HTTP in controllers or global advice; avoid leaking stack traces.

---
## Tips for AI Agents

1. **Read the architecture doc and relevant ADRs first** when planning any cross‑module change.
2. **Search for similar patterns** before adding new features (e.g. if you need to call an external API, see how pricing and ingestion adapters do it).
3. **Respect module boundaries**; failing ArchUnit tests are common if you import from the wrong package.
4. **Update `application.yml`, tests, and docs** when you add configuration, REST endpoints, or change data models.
5. **Use existing utilities** in `common` (rate limiting, retry) and `api/validation` for consistency.
6. **Run the full backend test suite** before committing; many subtle invariants are covered by integration tests.

---

> _After adding or updating these instructions, ask the human reviewer if anything important is missing or unclear._