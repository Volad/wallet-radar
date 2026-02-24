# Foundation (T-001, T-002, T-003)

Infrastructure tasks with no separate user-facing feature. DoD serves as acceptance.

---

## T-001 — Domain model and value objects

- **Module(s):** domain
- **Description:** Implement core domain types: `EconomicEvent`, `AssetPosition`, `PortfolioSnapshot`, `AssetSnapshot`; enums `EconomicEventType`, `PriceSource`, `FlagCode`, `NetworkId`; value objects used across modules. All monetary fields use `BigDecimal`. No business logic in domain package.
- **Doc refs:** 01-domain (Core Entities, Event Types, Price Sources, Flag Codes), INV-06
- **DoD:** Implement types and enums; unit tests for value object behaviour and invariants (e.g. quantity sign).
- **Dependencies:** —

**Acceptance criteria**
- All types and enums exist per 01-domain; monetary fields are BigDecimal.
- Unit tests cover value object behaviour and invariants.

---

## T-002 — MongoDB collections and indexes

- **Module(s):** config (Mongo), domain (repositories)
- **Description:** Define collections: `raw_transactions`, `economic_events`, `asset_positions`, `portfolio_snapshots`, `cost_basis_overrides`, `sync_status`, `recalc_jobs`, `on_chain_balances`. Configure Decimal128 codec for all monetary/quantity fields. Create indexes per 02-architecture.
- **Doc refs:** 02-architecture (System Context, Mongo collections), 01-domain (entity fields), ADR-002
- **DoD:** MongoConfig + index creation (or migration); integration test that persists and reads documents with Decimal128.
- **Dependencies:** T-001

**Acceptance criteria**
- All collections and indexes exist; Decimal128 codec configured.
- Integration test: persist and read documents with Decimal128.

---

## T-003 — Application config: caches, async, scheduler

- **Module(s):** config
- **Description:** Configure Caffeine caches (spotPrice 5min/500, historical 24h/10k, snapshot 60min/200, crossWalletAvco 5min/2k, tokenMeta 24h/5k). Configure async executor and named pools: `backfill-executor`, `recalc-executor`, `sync-executor`, `scheduler-pool` per 02-architecture.
- **Doc refs:** 02-architecture (Thread Pool Strategy, Cache Configuration), ADR-005
- **DoD:** Config classes; unit or integration test that executors and caches are created and used.
- **Dependencies:** T-001

**Acceptance criteria**
- Caches and thread pools configured per 02-architecture.
- Test: executors and caches are created and used.
