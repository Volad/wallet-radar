# Feature 10: GET transaction history (paginated)

**Tasks:** T-025

---

## T-025 — TransactionController

- **Module(s):** api
- **Description:** Implement `GET /api/v1/assets/{assetId}/transactions`: cursor-based pagination (cursor, limit, direction=DESC|ASC). Return event list with nextCursor and hasMore. Include hasOverride per event.
- **Doc refs:** 04-api (Get Transaction History)
- **DoD:** Controller; integration test: pagination and direction.
- **Dependencies:** T-008, T-002

---

## Acceptance criteria

- `GET /api/v1/assets/{assetId}/transactions?cursor=&limit=50&direction=DESC` returns `200` with `items[]`, `nextCursor`, `hasMore`.
- Each item includes at least: `eventId`, `txHash` (null for manual), `eventType`, `quantityDelta`, `priceUsd`, `priceSource`, `totalValueUsd`, `gasCostUsd`, `gasIncludedInBasis`, `realisedPnlUsd`, `avcoAtTimeOfSale`, `flagCode`, `flagResolved`, `hasOverride`.
- Default sort is `DESC` (newest first); `ASC` supported when specified.
- Cursor-based pagination: same query with `nextCursor` returns the next page; no RPC on request path.
- Manual compensating events appear in the list with `eventType=MANUAL_COMPENSATING` and no or synthetic `txHash`.

## User-facing outcomes

- User can browse transaction history for an asset with stable pagination and correct financial fields.

## Edge cases / tests

- Empty history → `items` empty, `nextCursor` null, `hasMore` false.
- Exactly `limit` items → `nextCursor` non-null, `hasMore` true; next page returns the rest.
- Event with override → `hasOverride === true`; displayed `priceUsd` can be override value (or original per API contract).
