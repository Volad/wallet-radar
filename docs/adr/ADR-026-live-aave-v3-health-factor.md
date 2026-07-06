# ADR-026 — Live Aave V3 health factor storage and refresh

**Status:** Accepted  
**Date:** 2026-06-07  
**Inputs:** `docs/tasks/lending-audit-2026-06-07-implementation-plan.md` (L-HEALTH-ESTIMATE-01)

## Context

Lending group health factor was computed only from accounting exposure (`supplyUsd / borrowUsd` with a protocol liquidation threshold). Active Aave borrow groups on Base and Mantle need live protocol health factor for display accuracy.

## Decision

### D1. Collection

- MongoDB collection: `lending_health_factor_snapshots`
- Document fields: `sessionId`, `protocolKey`, `networkId`, `walletAddress`, `healthFactor`, `source`, `capturedAt`, `blockNumber`, `rawSnapshotRef`
- Index: `(sessionId, protocolKey, networkId, walletAddress, capturedAt desc)`

### D2. Collector

- `LendingAaveV3HealthCollector` calls Aave V3 Pool `getUserAccountData(address)` via `eth_call`
- RPC timeout: **3 seconds**
- `source = LIVE_PROTOCOL` on success

### D3. Refresh schedule

- **Background only** — `LendingHealthFactorRefreshJob` every **10 minutes** (configurable via `walletradar.lending.health-factor.refresh-interval-ms`, default 600000)
- Never inline from `SessionLendingQueryService` query path
- Scope: Aave groups with `borrowUsd > 0` on **Base** and **Mantle** (initial rollout)

### D4. Read path

- `SessionLendingQueryService` reads latest snapshot when fresh (TTL **5 minutes**)
- On hit: display live `healthFactor`, `healthSource = LIVE_PROTOCOL`, `healthStale = false`
- On miss / RPC failure: fall back to `LendingMarketMetricEstimator`, `healthSource = ACCOUNTING_ESTIMATE`, `healthStale = true` when borrow exposure is positive

## Consequences

- Query latency unchanged (no synchronous RPC)
- Stale snapshots degrade gracefully without failing the lending API
- zkSync and other networks remain on accounting estimate until explicitly enabled

## Related

- `LendingAaveV3HealthCollector`
- `LendingHealthFactorRefreshJob`
- `LendingHealthFactorSnapshotService`
