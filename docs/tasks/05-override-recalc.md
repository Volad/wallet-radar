# Feature 5 & 7: Override (set / revert) + async recalc + Recalculation job status

**Tasks:** T-017, T-028

---

## T-017 — RecalcJobService and OverrideService

- **Module(s):** costbasis (override)
- **Description:** Implement `OverrideService`: PUT/DELETE override → upsert/deactivate in `cost_basis_overrides`; create RecalcJob (PENDING); publish OverrideSavedEvent. Implement `RecalcJobService`: consume override/manual events; run `AvcoEngine.replayFromBeginning` async on recalc-executor; set RecalcJob COMPLETE/FAILED. RecalcJob persisted; optional TTL cleanup (e.g. 24h).
- **Doc refs:** 02-architecture (Manual Override + Async Recalculation), 03-accounting (Manual Override), 04-api (Overrides, Recalculation Jobs)
- **DoD:** OverrideService + RecalcJobService; unit tests; integration test: PUT override → poll recalc status → COMPLETE and positions updated.
- **Dependencies:** T-015, T-002

## T-028 — OverrideController, ManualTransactionController, RecalcController

- **Module(s):** api
- **Description:** Implement `PUT /api/v1/transactions/{eventId}/override` and `DELETE .../override`: body/params per 04-api; call OverrideService; return 202 with jobId. Implement `POST /api/v1/transactions/manual`: body with clientId, quantityDelta, priceUsd (when >0), etc.; idempotency by clientId (200 with existing); return 202 with jobId. Optional `DELETE /api/v1/transactions/manual/{eventId}`. Implement `GET /api/v1/recalc/status/{jobId}`: return job status, newPerWalletAvco when COMPLETE.
- **Doc refs:** 04-api (Overrides, Manual Compensating Transaction, Recalculation Jobs)
- **DoD:** All three controllers; integration tests: override flow, manual tx with clientId idempotency, recalc polling.
- **Dependencies:** T-017, T-018

---

## Acceptance criteria (Override + Recalc)

- `PUT /api/v1/transactions/{eventId}/override` with `{ priceUsd, note }` stores an override in `cost_basis_overrides` with `isActive=true` for that event (on-chain only; manual compensating events are not overridden).
- Response is `202` with `jobId`. No synchronous AVCO recalculation on the request path.
- An async job (recalc-executor) runs `AvcoEngine.replayFromBeginning` for the affected (wallet, asset): loads all economic events (on-chain + MANUAL_COMPENSATING) in `blockTimestamp` ASC, applies **only** active overrides to **on-chain** events, recomputes AVCO and `realisedPnlUsd` for every SELL after the overridden event; updates `asset_positions`.
- `GET /api/v1/recalc/status/{jobId}` returns `status` in `PENDING` | `RUNNING` | `COMPLETE` | `FAILED`; when `COMPLETE`, returns `newPerWalletAvco` and `completedAt`.
- `DELETE /api/v1/transactions/{eventId}/override` deactivates the override (e.g. `isActive=false`), triggers the same async replay, returns `202` with `jobId`.
- Overridden event's `priceUsd` is not used in replay; the override's `priceUsd` is. INV-08 and AC-06 hold.
- Invalid `eventId` or event not found → `404` with `EVENT_NOT_FOUND`. If product rule is "one active override per event", duplicate PUT → `409` with `OVERRIDE_EXISTS` (if specified in API).
- Same recalc endpoint is used for jobs created by override and by manual compensating transaction.
- Unknown or expired `jobId` → `404` with `JOB_NOT_FOUND`. Jobs removed or expired after TTL (e.g. 24h).

## User-facing outcomes

- User can correct cost price for any on-chain event and see AVCO and P&L updated after a short delay; user can revert and see values restored; user can poll job status until complete.

## Edge cases / tests

- **Override on event with PRICE_UNKNOWN:** override supplies price; replay uses it; flags can be resolved; AVCO and realised P&L recomputed correctly.
- **Override on first event (BUY):** entire subsequent AVCO and all SELL realised P&L recomputed.
- **Override on middle event:** only events at and after that timestamp are affected in replay.
- **Revert override:** replay uses original price (or PRICE_UNKNOWN again); `asset_positions` and event-level `realisedPnlUsd` consistent.
- **Manual compensating event in same asset:** override does not apply to it; manual event's own `priceUsd` is used in replay (AC-09).
- Poll while PENDING → then RUNNING → then COMPLETE; `newPerWalletAvco` only present when COMPLETE. Poll with invalid jobId → 404. Poll after TTL → 404.
