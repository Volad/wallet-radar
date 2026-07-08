# Extensibility & module-isolation refactor — implementation plan

Tracked execution plan for Track A (decoupling, isolation, guardrails) and design-ready Track B.
Authoritative design: Cursor plan `extensibility_refactor_architecture` (not edited in-repo).

## Track A phases

| Phase | Goal | Status |
|-------|------|--------|
| A0 | Guardrails, financial snapshot, Checkstyle, doc scaffolding | Done |
| A1 | `canonical` + cut `costbasis → ingestion` | In progress |
| A2 | Relocate CEX + split god classes | Pending |
| A3 | `platform` layer + split `ingestion` | Done |
| A4 | Read-model + BFF + ports | Done |
| A5 / A5p | God-class burn-down, NetworkRegistry, ReplayHandler registry, ProtocolDescriptor | Done |
| A6 | Gradle subprojects | MVP done — `:backend:domain`, `:backend:canonical`, `:backend:core`, `:backend` launcher ([a6-gradle-modules.md](a6-gradle-modules.md)); vertical app splits pending |
| A-docs | Documentation consolidation (layered architecture, module pages, extensibility guides, doc-coverage test) | Done |

## Track B — design-ready stubs

| Item | Deliverable | Status |
|------|-------------|--------|
| B1 | `CexLedgerSource`, `CexVenueProfile`, `CexLedgerEvent` in `application.cex.port` + [add-an-integration](../reference/extensibility/add-an-integration.md) | Done (interfaces + doc) |
| B2 | `NetworkFamily` in `platform.networks` + [add-a-network](../reference/extensibility/add-a-network.md) (TON/Solana) | Done (interface + doc) |
| B3 | `AbstractProtocolCapabilityContractTest` kit + [add-a-protocol](../reference/extensibility/add-a-protocol.md) cross-link | Done (test stub + doc) |

Full wiring (registry beans, TON adapter, multi-CEX) remains **Pending** — stubs are compile-time contracts only.

## Verification (every data-affecting phase)

1. Named `mongodump` → `.tmp-backups/<phase>-<UTC>/`
2. `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
3. Account universe intact (`user_sessions.wallets`, `tracked_wallets`)
4. `./scripts/avco/capture-financial-snapshot.sh` → compare to baseline (exact BigDecimal)
5. Conservation guards HARD_FAIL (default; `-Dwalletradar.conservation.guards.hard-fail=false` to soften)
6. `./gradlew :backend:test`

## Collection ownership matrix

| Collection | Write owner | Read via |
|------------|-------------|----------|
| `raw_transactions` | `application.backfill` | port |
| `integration_raw_events`, `bybit_extracted_events`, `external_ledger_raw` | `application.cex` | port |
| `normalized_transactions` | `application.normalization` / `application.cex` | read port (not `@Document` in consumers) |
| `asset_ledger_points`, `counterparty_basis_pools`, `lp_receipt_basis_pools`, `borrow_liabilities` | `application.costbasis` | port |
| `historical_prices`, `current_price_quotes` | `application.pricing` | port |
| `lp_position_snapshots`, `lp_earning_points`, `lp_pool_depth_cache` | `application.liquiditypools` | port |
| `lending_*` snapshots / receipt identity | `application.lending` | port |
| `on_chain_balances` | `application.portfolio` (refresh job) | port |
| `user_sessions`, `tracked_wallets`, `accounting_universes` | `application.session` | read-only elsewhere |

Account universe is never dropped by `reset-derived.sh` — only `pipelineState` is cleared.

## DB backup policy

- No in-code migrations (no Mongock/Flyway).
- Backup-first: `mongodump` to `.tmp-backups/` (gitignored).
- Prefer rebuild from raw via `reset-derived.sh`.
- Temp migration scripts only under `.tmp-migrations/` (gitignored).

## Checkstyle god-class burn-down

Suppressions in `backend/config/checkstyle/suppressions.xml` — remove entries as classes split below 1000 lines (target ≤600).

## Documentation map (A-docs)

| Doc | Path |
|-----|------|
| Layered architecture | [03-architecture.md](../overview/03-architecture.md) |
| Module pages | [overview/modules/](../overview/modules/) |
| Protocol descriptor | [protocol-descriptor.md](../reference/protocol-descriptor.md) |
| Capability SPI | [capability-behavior-spi.md](../reference/capability-behavior-spi.md) |
| Extensibility guides | [reference/extensibility/](../reference/extensibility/) |
