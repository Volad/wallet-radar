# ADR-039: Async refresh status for LP and Lending

**Status:** Accepted  
**Date:** 2026-06-29

## Context

LP and Lending pages support on-demand and scheduled on-chain refresh (TVL, fees, health factor, protocol APY). Previously:

- Refresh endpoints were **synchronous** — the HTTP call blocked until RPC enrichment finished.
- Frontend tracked `Updating…` only in local component state; status cleared when the POST returned, not when enrichment actually completed.
- Background jobs (`LpPositionRefreshJob`, post-replay refresh) were **invisible** to the UI.
- Debounce/TTL and pool depth cache used **in-memory** maps, lost on restart and invisible across instances.

Users need per-item `Updating…` until data is actually refreshed, including server-initiated bulk/scheduled refresh, without blocking the API thread on long RPC batches.

## Decision

1. **Async refresh orchestration** — `POST .../refresh` returns `202 Accepted` immediately; work runs on `PIPELINE_STAGE_EXECUTOR`.
2. **Mongo-backed refresh state** — collections `lp_position_refresh_state` (key = `correlationId`) and `lending_group_refresh_state` (key = `groupId`) store `QUEUED | UPDATING | SYNCED | FAILED`, trigger, timestamps, and error.
3. **Status polling API** — `GET .../lp/refresh-status` and `GET .../lending/refresh-status` return `{ items[], anyActive }`.
4. **Frontend adaptive polling** — shared `RefreshStatusPollerService`: 3s while `anyActive`, 25s keepalive while page is open; reload snapshot GET when an item transitions `UPDATING → SYNCED`.
5. **Pool depth cache** — Mongo collection `lp_pool_depth_cache` is authoritative (no long-lived in-memory layer); TTL via `depth-interval-ms` (default 6h).

Manual, bulk, scheduled, and replay-triggered refresh all write the same state rows.

## Consequences

**Positive**

- UI shows accurate per-item sync state for manual and background refresh.
- No RPC work on GET read paths; status reads are Mongo-only.
- State survives restarts; suitable for multi-instance deployment.
- Symmetric LP + Lending contract.

**Negative**

- Extra Mongo writes per refresh item.
- Frontend polls while pages are open (same pattern as backfill status).
- Clients must migrate from expecting full `SessionLpResponse` on refresh POST to `RefreshStatusResponse` + polling.

## Alternatives considered

- **SSE/WebSocket push** — rejected; no existing infra; polling matches backfill UX.
- **Synchronous POST with longer timeout** — rejected; does not solve background job visibility or RPC storm UX.

## References

- [docs/reference/api.md](../reference/api.md) — refresh-status endpoints
- [docs/frontend/liquidity-pools.md](../frontend/liquidity-pools.md)
- [docs/frontend/lending-market.md](../frontend/lending-market.md)
- [docs/liquidity-pools/enrichment.md](../liquidity-pools/enrichment.md)
