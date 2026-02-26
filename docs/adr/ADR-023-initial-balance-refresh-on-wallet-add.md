# ADR-023: Initial Balance Refresh Triggered on Wallet Add

**Date:** 2026-02  
**Status:** Proposed  
**Deciders:** Product Owner, System Architect

---

## Context

`POST /api/v1/wallets` currently starts backfill and returns `202 Accepted`.  
Current balances are tracked independently via:
- periodic poll (every 10 minutes), and
- manual refresh (`POST /api/v1/wallets/balances/refresh`).

This can leave a gap right after wallet add where users do not yet see current on-chain quantity, even though the wallet was accepted and backfill started.

## Decision

Keep `POST /api/v1/wallets` lightweight and non-blocking, but trigger an **asynchronous initial balance refresh** immediately after wallet add.

- `POST /wallets` still returns `202` quickly; it does not wait for RPC balance calls.
- On wallet add, publish an internal event (for example, `BalanceRefreshRequestedEvent`) for the same wallet+network set.
- A dedicated balance refresh worker/service executes RPC balance fetch using the same logic as:
  - periodic 10-minute poll, and
  - manual refresh endpoint.
- Persist/upsert results to `on_chain_balances` (`walletAddress`, `networkId`, `assetContract`, `quantity`, `capturedAt`).
- Failures in initial balance refresh must not fail wallet add or backfill; retries follow existing retry/backoff policy.

## Rationale

| Concern | Only periodic/manual refresh | Initial async refresh on wallet add |
|--------|-------------------------------|-------------------------------------|
| Fast `POST /wallets` UX | Yes | Yes |
| User sees current quantity soon after add | Not guaranteed (up to next poll/manual action) | Yes, typically within seconds/minutes |
| Coupling between backfill and balances | Low | Low (still independent) |
| RPC cost impact | Baseline | Small bounded increase (one extra refresh per add) |

This preserves ADR-007 principles (independent balance tracking) while improving first-use UX.

## Consequences

- Add event-driven initial refresh path in addition to existing poll/manual paths.
- Reuse one shared balance refresh service to avoid logic divergence.
- Optional UX metadata can be added to `sync_status` (for example, `balanceLastCapturedAt`, `balanceLastError`) to show freshness state.
- No changes required for `GET` read-path invariants: still zero RPC on request path.

