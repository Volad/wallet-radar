# Application CEX (`application.cex`)

## Purpose

Owns **centralized exchange** acquisition, extraction, and normalization into the same canonical schema as on-chain rows. Currently implements Bybit (master/sub streams, earn corridors, live balances). Must **not** own AVCO replay or dashboard read-model assembly.

## Public port (`application.cex.port`)

Design-ready SPI (Track B1) — stable venue boundary before full multi-CEX wiring:

| Interface | Role |
|-----------|------|
| `CexLedgerSource` | Identifies a venue stream and pages immutable ledger evidence |
| `CexVenueProfile` | Declares supported account kinds, streams, and symbol mapping policy |
| `CexLedgerEvent` | Normalized extracted-row view before canonical builder mapping |

See [add-an-integration](../../reference/extensibility/add-an-integration.md) and [capability-behavior-spi](../../reference/capability-behavior-spi.md#cex-ledger-spi-b1).

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `integration_raw_events` | Immutable API payloads per venue stream |
| `bybit_extracted_events` | Typed extracted ledger rows (Bybit today) |
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
| `application.cex.acquisition.venue.bybit` | API client, extraction, live balance |
| `application.cex.normalization.venue.bybit` | Pairing, canonical builder, stream collapse |
| `application.cex.job.bybit` | `BybitNormalizationJob`, batch service |

## Allowed dependencies

- `canonical.correlation`
- `platform.common` (job support)
- `domain` entities for extracted/raw persistence
- `application.backfill` segment contracts (not on-chain normalization internals)

## Extension seams

- `CexLedgerSource` + `CexVenueProfile` — register a new venue without touching costbasis
- `BybitCanonicalTransactionBuilder` — reference implementation for canonical mapping
- Pairing primitives (`BybitInternalTransferPairingPrimitives`) — reuse patterns for other venues

## Worked example

1. User adds Bybit API keys → `BybitExtractionService` pulls `integration_raw_events`.
2. Mapper emits `bybit_extracted_events` with `canonicalType` hints.
3. `BybitNormalizationService` pairs IT/earn/trade rows, upserts `normalized_transactions`.
4. `BybitNormalizationCompletedEvent` triggers linking; replay consumes `CONFIRMED` rows only.

## Microservice extraction

CEX worker owns raw + extracted collections and normalization through `CONFIRMED`. Wire contract: `CexLedgerEvent` stream + canonical upsert API. Costbasis reads normalized rows via port, never Bybit API.
