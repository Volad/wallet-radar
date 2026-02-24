# ADR-007: Current Balance Tracked Independently of Backfill

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Product Owner, Solution Architect

---

## Context

Users need to see **current (on-chain) asset balance** for tracked wallets. This serves:
- Immediate feedback when adding a wallet (before or during backfill)
- Reconciliation with quantity derived from 2-year history (match vs mismatch warning)
- Up-to-date balance regardless of when backfill or incremental sync last ran

Options considered:
- **Option A:** Fetch on-chain balance only once (e.g. after backfill completes) and use it only for reconciliation.
- **Option B:** Track current balance **independently** of backfill: periodic RPC poll + optional manual refresh.

## Decision

**Option B — independent tracking of current balance.**

- **Periodic poll:** every **10 minutes**, for all `(wallet, network)` in `sync_status`, fetch native and token balances via RPC and upsert into `on_chain_balances`.
- **Manual refresh:** user can trigger an immediate refresh for a chosen set of wallets and networks via `POST /api/v1/wallets/balances/refresh`.
- Storage: collection `on_chain_balances` (or equivalent), keyed by `(walletAddress, networkId, assetContract)` with `quantity` and `capturedAt`. Read by API/snapshot for display and reconciliation; **not** gated on backfill completion.

## Rationale

| Concern | Option A (once after backfill) | Option B (independent, 10 min + manual) |
|--------|--------------------------------|----------------------------------------|
| User sees current balance soon after adding wallet | No — only after backfill | Yes — within 10 min or immediately on manual refresh |
| Balance stays up to date between hourly syncs | No | Yes |
| Reconciliation (on-chain vs derived) | Possible only after backfill | Always possible once we have both sources |
| RPC load | Lower | Bounded: 10 min interval + manual; token list can be limited |

Product need: current balance is a **first-class** piece of data, not a one-off snapshot at backfill time. Independent tracking with 10 min poll and manual refresh aligns with that and supports reconciliation UX.

## Consequences

- **New:** `CurrentBalancePollJob` (`@Scheduled(fixedRate=600_000)`), `OnChainBalanceStore`, `POST /wallets/balances/refresh`.
- **MongoDB:** add collection `on_chain_balances` (or fields elsewhere; see 02-architecture).
- **RPC:** additional calls every 10 min per wallet×network (native + token balances); scope of tokens to fetch (e.g. from known assets or config) to be defined to control cost and rate limits.
- **GET /assets** (or dedicated balances endpoint) can expose `onChainQuantity` and `capturedAt` for reconciliation and UI.
