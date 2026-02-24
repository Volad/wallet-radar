# Feature 6: Manual compensating transaction (add / delete) + recalc

**Tasks:** T-018, T-028 (POST /transactions/manual, DELETE /transactions/manual/{eventId})

---

## T-018 — Manual compensating transaction (write path)

- **Module(s):** costbasis (event), ingestion (store)
- **Description:** Implement creation of manual compensating events: validate (assetSymbol or assetContract, quantityDelta, priceUsd when quantityDelta > 0). Idempotency by clientId: if event with clientId exists return 200 and existing event/jobId. Insert into economic_events (eventType=MANUAL_COMPENSATING, txHash null or synthetic id, clientId). Create RecalcJob and publish event for RecalcJobService. Optional DELETE manual event by id → remove event and trigger replay.
- **Doc refs:** 01-domain (Manual Compensating Transaction, INV-13, INV-14), 03-accounting (Manual Compensating Transaction), 02-architecture (Data Flow 5), ADR-008
- **DoD:** Service + IdempotentEventStore support for clientId; unit tests (idempotency, validation); integration test: POST with clientId twice → single event; DELETE triggers recalc.
- **Dependencies:** T-008, T-015, T-017

## T-028 (partial) — POST /transactions/manual, DELETE /transactions/manual/{eventId}

- **Module(s):** api
- **Description:** Implement `POST /api/v1/transactions/manual`: body with clientId, quantityDelta, priceUsd (when >0), walletAddress, networkId, asset; idempotency by clientId (200 with existing); return 202 with jobId. Optional `DELETE /api/v1/transactions/manual/{eventId}`. (Full T-028 in 05-override-recalc.md.)
- **Doc refs:** 04-api (Manual Compensating Transaction)
- **DoD:** Controller; integration tests: POST creates manual event and returns jobId; duplicate clientId → 200; DELETE triggers recalc.

---

## Acceptance criteria

- `POST /api/v1/transactions/manual` with required fields (walletAddress, networkId, asset identifier, `quantityDelta`, and `priceUsd` when `quantityDelta > 0`, optional `timestamp`, `clientId`) creates a synthetic economic event with `eventType=MANUAL_COMPENSATING`, no on-chain `txHash` (or synthetic id), stored in `economic_events`.
- **Idempotency:** when `clientId` is supplied and an event with that `clientId` already exists, the API returns **200** with the existing event (or id) and does **not** create a second event or a new recalc job (INV-14).
- Response for a new insert is `202` with `jobId`. Async job runs AVCO replay including the new event in `blockTimestamp` order; manual event participates in AVCO (BUY/SELL formula); when `quantityDelta > 0`, `priceUsd` is required and used.
- `DELETE /api/v1/transactions/manual/{eventId}` (if implemented) removes the manual event and triggers the same async replay; response `202` with `jobId`.
- Manual events are **not** subject to `cost_basis_overrides`; they use their own `priceUsd` (AC-09).
- Validation: missing `priceUsd` when `quantityDelta > 0` → `400`; invalid wallet/network/asset → appropriate `400`/`404`.

## User-facing outcomes

- User can add a manual compensating transaction (positive or negative) to align balance and AVCO; duplicate submit with same `clientId` does not create duplicates; user can remove a manual event and see AVCO updated.

## Edge cases / tests

- **Positive manual compensating (quantityDelta > 0):** `priceUsd` required; AVCO increases by weighted average; `quantity` increases; no crash if `priceUsd` omitted → 400.
- **Negative manual compensating (quantityDelta < 0):** quantity decreases; AVCO unchanged (same as SELL); no `priceUsd` required for AVCO.
- **Idempotency `clientId`:** two identical POSTs with same `clientId` → second returns 200 and does not create a second event or duplicate recalc job.
- **Manual event with timestamp in the middle of history:** replay orders by `blockTimestamp` ASC; AVCO and subsequent SELL realised P&L consistent with that order.
- **Manual event at "end of timeline":** default timestamp behaviour; replay places it last; AVCO and quantity correct.
