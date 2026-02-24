# ADR-008: Manual Compensating Transaction and Reconciliation

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Product Owner, Solution Architect

---

## Context

Users need to correct **balance** and **cost basis** when:
- On-chain balance (from RPC / `on_chain_balances`) does not match the quantity derived from economic events (e.g. history before 2-year window, airdrops, or missing txs).
- They want to add a synthetic “adjustment” event without conflating it with a **price-only override** on an existing on-chain event.

The existing **Override** mechanism binds to an existing `eventId` and replaces only `priceUsd` in AVCO replay. It does not add or remove quantity. So a separate concept is needed for “add a synthetic event with quantity (and optionally price) to reconcile balance and/or AVCO”.

---

## Decision

1. **Override stays as-is:** binding to existing `eventId`, replace `priceUsd` only; stored in `cost_basis_overrides`; triggers async AVCO recalc.

2. **Manual compensating transaction** is a **separate concept:**
   - A new **synthetic** economic event with `quantityDelta` (and optionally `priceUsd`; required when `quantityDelta > 0` for AVCO).
   - New event type: `MANUAL_COMPENSATING`.
   - Stored in `economic_events` (same collection, no dedicated store), merged into AVCO replay by loading all events for (wallet, asset) ordered by `blockTimestamp ASC`.
   - Idempotency by `clientId` (UUID); duplicate clientId returns existing event / 200.
   - No `txHash` (or synthetic id for event identity).
   - Async AVCO replay after create/delete uses same `recalc-executor` and `GET /recalc/status/{jobId}` as override.

3. **Reconciliation:**
   - Compare **on-chain balance** (`on_chain_balances` / CurrentBalancePoll) with **derived quantity** (`asset_positions.quantity`).
   - For wallets “younger than 2 years”: if mismatch, show warning on asset and suggest adding a manual compensating transaction.
   - For older wallets: show comparison but do not require correction (`NOT_APPLICABLE` or show without strict warning).

4. **Storage / API:**
   - `asset_positions` may get reconciliation fields (`onChainQuantity`, `onChainCapturedAt`, `reconciliationStatus`) or status is computed at read time from `on_chain_balances` + `asset_positions.quantity`.
   - `GET /assets` returns: `onChainQuantity`, `derivedQuantity`, `balanceDiscrepancy`, `reconciliationStatus` (`MATCH` | `MISMATCH` | `NOT_APPLICABLE`), `showReconciliationWarning` (boolean).
   - New API: `POST /api/v1/transactions/manual` with body (walletAddress, networkId, assetSymbol|assetContract, quantityDelta, priceUsd when delta > 0, timestamp?, note, clientId); response 202 with `jobId` for AVCO recalc polling. Optional `DELETE /transactions/manual/{eventId}`.

---

## Rationale

| Concern | Override only | Separate manual compensating |
|--------|----------------|-------------------------------|
| Semantics | Override = “fix price of this tx”; no way to add quantity | Clear: “add a synthetic event” for balance/AVCO |
| Audit | Override is attached to eventId; manual event is a first-class event in timeline | Clean audit trail: manual events appear in event stream by timestamp |
| AVCO | Override replaces price in replay; no new quantity | Manual event adds quantity (and price when delta > 0) in timestamp order |
| Reconciliation UX | No way to “fix” derived quantity to match on-chain | User can add manual compensating tx when mismatch is shown |

Keeping override and manual compensating as two concepts avoids overloading override with quantity and keeps cost_basis_overrides simple (eventId → priceUsd only).

---

## Consequences

- **New:** Event type `MANUAL_COMPENSATING`; stored in `economic_events` with `txHash` null (or synthetic id); `clientId` for idempotency.
- **New:** `POST /transactions/manual`, optional `DELETE /transactions/manual/{eventId}`; same recalc job flow as override.
- **AVCO engine:** `AvcoEngine.replayFromBeginning` loads all events (on-chain + `MANUAL_COMPENSATING`) ordered by `blockTimestamp ASC`; overrides apply to on-chain events only; manual events carry their own `priceUsd`.
- **GET /assets:** Reconciliation fields added; `showReconciliationWarning` drives UX for “young” wallets with mismatch.
- **No new collection:** Manual events live in `economic_events`; no separate `manual_events` collection.
