# ADR-002: MongoDB as Primary Data Store

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

WalletRadar stores:
- Raw transactions from 9 heterogeneous networks (EVM variants + Solana) — schema varies significantly per network
- Economic events normalised to a common shape with optional per-type fields
- Asset positions (derived, updated frequently)
- Hourly portfolio snapshots containing variable-length asset arrays
- Small metadata collections (sync status, recalc jobs, overrides)

The read pattern is predominantly:
- Fetch all events for `(walletAddress, assetSymbol)` sorted by `blockTimestamp` — for AVCO replay
- Fetch per-wallet snapshots by time range — for charts
- Fetch asset positions by wallet set — for portfolio view

Two primary options considered:
- **Option A:** PostgreSQL (relational, JSONB for variable fields)
- **Option B:** MongoDB 7 (document, native JSON, flexible schema)

## Decision

**Option B — MongoDB 7, self-hosted in Docker.**

## Rationale

| Concern | PostgreSQL | MongoDB |
|---------|-----------|--------|
| Schema flexibility (9 networks, variable tx shape) | JSONB possible but awkward | Native — each document is independent |
| AVCO replay query | JOIN-heavy across event rows | Single collection scan with covered index |
| Snapshot storage (variable asset arrays) | Normalised → many rows + JOIN | Embedded array — single document fetch |
| `Decimal128` native type | Requires `NUMERIC` type, serialisation careful | Native `Decimal128` — no precision loss |
| Self-hosted simplicity | `pg` Docker image, mature | `mongo` Docker image, equally mature |
| Horizontal read scaling | Read replicas (Phase 2) | Secondary preference reads (Phase 2) |
| Cost (managed) | AWS RDS ~$50/mo | MongoDB Atlas M10 ~$57/mo |
| Cost (self-hosted Docker) | $0 | $0 |

The primary AVCO replay pattern — load all events for `(walletAddress, assetSymbol)` ordered by `blockTimestamp` — maps directly to a single MongoDB collection scan with a compound index. No joins required.

`Decimal128` native type eliminates a class of precision bugs that are common when storing monetary values in PostgreSQL `FLOAT` columns (a frequent mistake) or require careful `NUMERIC` discipline.

## Consequences

- **Positive:** No schema migrations for adding per-network fields to `raw_transactions`.
- **Positive:** Snapshot documents with embedded asset arrays are fetched in a single read — no N+1 queries.
- **Positive:** `Decimal128` enforced at storage layer — impossible to accidentally store a float.
- **Negative:** No multi-document ACID transactions by default. Mitigated by: idempotent upserts on `(txHash, networkId, walletAddress, assetContract)` UNIQUE sparse index for on-chain events (see ADR-013); AVCO is always fully replayed (not incrementally patched).
- **Negative:** MongoDB's aggregation pipeline is less expressive than SQL for ad-hoc analytics. Acceptable — WalletRadar's query patterns are narrow and well-defined.
- **Negative:** No referential integrity enforcement. Mitigated by application-layer invariants and ArchUnit tests.

## Index Strategy

All read patterns have a corresponding covered compound index. No collection scans in production. See `docs/02-architecture.md` and SAD v2.0 Section D for full index definitions.

## Review Trigger

Reconsider if:
- Complex aggregation queries emerge that require SQL window functions
- Multi-document atomicity becomes a frequent requirement
