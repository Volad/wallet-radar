# Application CEX (`application.cex`)

## Purpose

Owns **centralized exchange** acquisition, extraction, and normalization into the same canonical schema as on-chain rows. Currently implements **Bybit** (master/sub streams, earn corridors, live balances) and **Dzengi** (spot, fiat ledger, derivative settlements). Must **not** own AVCO replay or dashboard read-model assembly.

## Public port (`application.cex.port`) — ADR-052

Full segregated venue SPI — adding a CEX venue requires zero changes post-normalization ([ADR-052](../../adr/ADR-052-venue-capability-spi-walletref-normalization-boundary-invariant.md)):

| Interface / class | Role |
|-------------------|------|
| `VenueDescriptor` | Composes all four capabilities below |
| `VenueIdentity` (extends `CexVenueProfile`) | `venueId`, `providerCode`, stream ownership predicates, `accountKindSuffixes()` (Bybit: `:UTA`/`:FUND`/`:EARN`/`:BOT`) |
| `VenueWalletModel` | `umbrellaKey()`, `expandBackfillRefs()`, `dashboardWalletRefs()`; default no-op for flat venues |
| `VenueLiveBalanceCapability` | `Optional<CexLiveBalancePort> liveBalancePort()` |
| `VenueExternalCapitalPolicy` | Decides at normalization time: external-capital boundary + eligible USD basis |
| `VenueRegistry` | `@Component` holding `List<VenueDescriptor>` — **ingestion-plane only** (normalization, backfill, live-balance routing) |
| `RoutingCexLiveBalancePort` | Routes live-balance refresh to venue's `CexLiveBalancePort` via registry |
| `CexLedgerSource` | Pages immutable extracted evidence for a venue stream |
| `CexLedgerEvent` | Normalized extracted-row view before canonical builder mapping |

See [add-an-integration](../../reference/extensibility/add-an-integration.md) and [capability-behavior-spi](../../reference/capability-behavior-spi.md#cex-ledger-spi-b1--adr-052).

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `integration_raw_events` | Immutable API payloads per venue stream |
| `bybit_extracted_events` | Typed extracted ledger rows (Bybit) |
| `dzengi_extracted_events` | Typed extracted ledger rows (Dzengi) |
| `external_ledger_raw` | Optional external ledger imports |

Writes `normalized_transactions` for CEX-origin rows (shared canonical collection; co-owned with on-chain normalization).

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `application.backfill` | segment planner hooks | CEX backfill segments in unified job |
| `session.application` | integration credentials scope | Venue API keys per session |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.cex.port` | CEX ledger SPI (B1) |
| `application.cex.acquisition.venue.bybit` | Bybit API client, extraction, live balance |
| `application.cex.acquisition.venue.dzengi` | Dzengi API client, extraction, symbol cache |
| `application.cex.normalization.venue.bybit` | Pairing, canonical builder, stream collapse |
| `application.cex.normalization.venue.dzengi` | Dzengi canonical builder |
| `application.cex.job.bybit` | `BybitNormalizationJob`, batch service |
| `application.cex.job.dzengi` | `DzengiNormalizationJob`, batch service |

## Allowed dependencies

- `canonical.correlation`
- `platform.common` (job support)
- `domain` entities for extracted/raw persistence
- `application.backfill` segment contracts (not on-chain normalization internals)

## Extension seams

- `VenueDescriptor` SPI — implement four interfaces and register as a Spring `@Component`; normalization, backfill, and live-balance routing pick it up automatically
- `BybitCanonicalTransactionBuilder` / `DzengiCanonicalTransactionBuilder` — reference implementations for canonical mapping; both stamp the venue-neutral boundary contract onto `NormalizedTransaction`
- Pairing primitives (`BybitInternalTransferPairingPrimitives`) — reuse patterns for other venues with internal sub-accounts

**Invariant:** post-normalization packages (`costbasis`, `portfolio`, `pricing`, `linking`, `api`, frontend) must **never** import `VenueRegistry`, `VenueDescriptor`, or any concrete descriptor — enforced by `ModuleDependencyArchTest` + `VenuePrefixGuardTest`.

## Worked examples

### Bybit

1. User adds Bybit API keys → `BybitExtractionService` pulls `integration_raw_events`.
2. Mapper emits `bybit_extracted_events` with `canonicalType` hints.
3. `BybitNormalizationService` pairs IT/earn/trade rows, upserts `normalized_transactions`.
4. `BybitNormalizationCompletedEvent` triggers linking; replay consumes `CONFIRMED` rows only.

Bybit sub-account topology uses the umbrella key `BYBIT:<uid>` with account-kind suffixes
`:UTA`/`:FUND`/`:EARN`/`:BOT`. The `:BOT` Trading-Bot compartment ([ADR-058](../../adr/ADR-058-bybit-bot-compartment-cost-basis.md))
resolves per-session basis at normalization (`BybitBotTransferCostBasisService`) and collapses to the
`BYBIT:<uid>` umbrella exactly like the other suffixes — observability-only, adding no new replay
position.

### Dzengi

1. User connects Dzengi in Settings (provider chip + test connection) → credentials stored on `user_sessions.integrations`.
2. `DzengiBackfillSegmentPlanner` schedules LEDGER, deposits, withdrawals, trades, exchange info segments.
3. `DzengiExtractionService` emits `dzengi_extracted_events` (leverage/CFD fills excluded).
4. `DzengiNormalizationService` upserts canonical rows; `DzengiNormalizationCompletedEvent` triggers linking.
5. BYN fiat legs price via `PriceSource.DZENGI` ([ADR-050](../../adr/ADR-050-dzengi-fiat-fx-pricing.md)).

## Microservice extraction

CEX worker owns raw + extracted collections and normalization through `CONFIRMED`. Wire contract: `CexLedgerEvent` stream + canonical upsert API. Costbasis reads normalized rows via port, never venue APIs.
