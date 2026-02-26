# WalletRadar — API Contract

> **Version:** MVP v2.0  
> **Base URL:** `/api/v1`  
> **Auth:** None (no user accounts in MVP)  
> **Format:** JSON. All monetary values serialised as `String` (BigDecimal precision, no floating point loss).  
> **Postman:** [docs/postman/WalletRadar-API.postman_collection.json](postman/WalletRadar-API.postman_collection.json) — import into Postman for all endpoints.

---

## General Conventions

- All `GET` endpoints make **zero RPC calls** and **zero heavy computation**
- All monetary fields (`priceUsd`, `avco`, `pnl`, etc.) are returned as `String`
- Timestamps are `ISO 8601` strings: `"2025-01-15T14:00:00Z"`
- Network IDs: `ETHEREUM` | `ARBITRUM` | `OPTIMISM` | `BASE` | `BSC` | `POLYGON` | `AVALANCHE` | `MANTLE` | `SOLANA`
- `crossWalletAvco` is always **global across all networks** — network filter does not change it
- `202 Accepted` responses include a job or sync ID for polling

---

## Wallets

### Add Wallet

```
POST /api/v1/wallets
```

**Request body:**
```json
{
  "address": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

**Response `202 Accepted`:**
```json
{
  "syncId": "wallet-0xd8dA...-ETHEREUM",
  "message": "Backfill started"
}
```

---

### Get Wallet Sync Status

```
GET /api/v1/wallets/{address}/status?network={networkId}
```

**Query parameters:**
- `network` — **optional**. If **omitted**: return status for **all networks** for this address. If **present**: return status for that network only (or 404 if no sync_status for that pair).

**Response `200 OK` when `network` is present (single network):**
```json
{
  "walletAddress": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
  "networkId": "ETHEREUM",
  "status": "RUNNING",
  "progressPct": 34,
  "lastBlockSynced": 21800000,
  "backfillComplete": false,
  "syncBannerMessage": "Syncing Ethereum: 34% complete"
}
```

**Response `200 OK` when `network` is omitted (all networks for address):**
```json
{
  "walletAddress": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
  "networks": [
    {
      "networkId": "ETHEREUM",
      "status": "RUNNING",
      "progressPct": 34,
      "lastBlockSynced": 21800000,
      "backfillComplete": false,
      "syncBannerMessage": "Syncing Ethereum: 34% complete"
    },
    {
      "networkId": "ARBITRUM",
      "status": "COMPLETE",
      "progressPct": 100,
      "lastBlockSynced": 185000000,
      "backfillComplete": true,
      "syncBannerMessage": null
    }
  ]
}
```

`status` values: `PENDING` | `RUNNING` | `COMPLETE` | `PARTIAL` | `FAILED` | `ABANDONED`  
`syncBannerMessage` is `null` when `status=COMPLETE`  
`ABANDONED` means the network exceeded the maximum retry count (default 5) — user can re-add the wallet to reset.

**Response `404 Not Found`:** No sync status for the given address (or for the given address×network when `network` is specified).

---

### Trigger Manual Sync

```
POST /api/v1/sync/refresh
```

**Request body:**
```json
{
  "wallets": ["0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"],
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

**Response `202 Accepted`:**
```json
{ "message": "Incremental sync triggered" }
```

---

### Trigger Manual Balance Refresh

Current balances are polled automatically every 10 minutes. The user can request an immediate refresh for specific wallets and networks.

```
POST /api/v1/wallets/balances/refresh
```

**Request body:**
```json
{
  "wallets": ["0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"],
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

**Response `202 Accepted`:**
```json
{ "message": "Balance refresh triggered" }
```

Updated on-chain balances appear on the next GET /assets (or dedicated balances endpoint); no polling of job status required.

---

## Assets

### Get Asset List

```
GET /api/v1/assets?wallets=0xA,0xB&network=ARBITRUM
```

`wallets` — comma-separated list (required)  
`network` — optional filter; omit for all networks

**Response `200 OK`:**
```json
[
  {
    "assetSymbol": "ETH",
    "assetContract": "0x0000000000000000000000000000000000000000",
    "networkId": "ARBITRUM",
    "quantity": "1.532000000000000000",
    "derivedQuantity": "1.532000000000000000",
    "onChainQuantity": "1.532000000000000000",
    "balanceDiscrepancy": "0",
    "reconciliationStatus": "MATCH",
    "showReconciliationWarning": false,
    "perWalletAvco": "1850.25",
    "crossWalletAvco": "1723.40",
    "crossWalletAvcoNote": "Across all networks & wallets in session",
    "spotPriceUsd": "2100.00",
    "valueUsd": "3217.20",
    "unrealisedPnlUsd": "382.97",
    "unrealisedPnlPct": "13.49",
    "realisedPnlUsd": "210.50",
    "totalRealisedPnlUsd": "1842.75",
    "hasUnresolvedFlags": false,
    "unresolvedFlagCount": 0,
    "hasIncompleteHistory": false,
    "lastEventTimestamp": "2025-01-10T08:23:41Z"
  }
]
```

Reconciliation fields:
- `derivedQuantity` — quantity from AVCO/economic events (same as `quantity`; explicit for comparison).
- `onChainQuantity` — current balance from `on_chain_balances` at last poll (or `null` if not available).
- `balanceDiscrepancy` — `onChainQuantity − derivedQuantity` (string); zero or null when not applicable.
- `reconciliationStatus` — `MATCH` | `MISMATCH` | `NOT_APPLICABLE` (e.g. no on-chain data, or wallet older than 2 years).
- `showReconciliationWarning` — `true` when status is `MISMATCH` and wallet history is "young" (e.g. &lt; 2 years), to suggest adding a manual compensating transaction.

**Note:** `crossWalletAvco` is always computed across **all networks and all wallets** in the `?wallets=` parameter, regardless of the `?network=` filter.

---

## Transactions

### Get Transaction History (Paginated)

```
GET /api/v1/assets/{assetId}/transactions?cursor={cursor}&limit=50&direction=DESC
```

**Parameters:**
- `cursor` — opaque base64 cursor (omit for first page)
- `limit` — items per page; default `50`, max `200`
- `direction` — `DESC` (newest first, default) | `ASC` (oldest first)

**Response `200 OK`:**
```json
{
  "items": [
    {
      "eventId": "507f1f77bcf86cd799439011",
      "txHash": "0xabc123...",
      "networkId": "ETHEREUM",
      "walletAddress": "0xd8dA...",
      "blockTimestamp": "2025-01-10T08:23:41Z",
      "eventType": "SWAP_BUY",
      "assetSymbol": "ETH",
      "quantityDelta": "0.500000000000000000",
      "priceUsd": "1800.00",
      "priceSource": "SWAP_DERIVED",
      "totalValueUsd": "900.00",
      "gasCostUsd": "3.20",
      "gasIncludedInBasis": true,
      "realisedPnlUsd": null,
      "avcoAtTimeOfSale": null,
      "protocolName": "Uniswap V3",
      "flagCode": null,
      "flagResolved": true,
      "hasOverride": false
    }
  ],
  "nextCursor": "eyJibG9ja1RpbWVzdGFtcCI6IjIwMjUtMDEtMDlUMTI6MDA6MDBaIiwidHhIYXNoIjoiMHhhYmMxMjMifQ==",
  "hasMore": true
}
```

`nextCursor` is `null` when there are no more pages.

---

## Portfolio Snapshots

### Get Portfolio Time-Series

```
GET /api/v1/portfolio/snapshots?wallets=0xA,0xB&range=7D
```

**Parameters:**
- `wallets` — comma-separated (required)
- `range` — `1D` | `7D` | `1M` | `1Y` | `ALL`

**Response `200 OK`:**
```json
{
  "range": "7D",
  "wallets": ["0xA", "0xB"],
  "dataPoints": [
    {
      "timestamp": "2025-01-15T14:00:00Z",
      "totalValueUsd": "12450.30",
      "unrealisedPnlUsd": "1820.45",
      "unresolvedValueUsd": "0.00",
      "unresolvedCount": 0
    }
  ]
}
```

**Note:** `totalValueUsd` is the in-memory aggregate of per-wallet snapshots for the requested `wallets` set. Zero RPC calls. Zero heavy computation on request path.

---

### Get Asset Chart Data

```
GET /api/v1/charts/asset/{symbol}?wallets=0xA,0xB&range=7D
```

**Response `200 OK`:**
```json
{
  "assetSymbol": "ETH",
  "range": "7D",
  "dataPoints": [
    {
      "timestamp": "2025-01-15T14:00:00Z",
      "valueUsd": "3217.20",
      "spotPriceUsd": "2100.00",
      "perWalletAvco": "1850.25",
      "unrealisedPnlUsd": "382.97"
    }
  ]
}
```

---

## Manual Compensating Transaction

Add a synthetic event to reconcile balance and/or AVCO (e.g. after detecting on-chain vs derived mismatch).

```
POST /api/v1/transactions/manual
```

Alternative path: `POST /api/v1/wallets/{address}/manual-transactions` (same semantics, wallet in path).

**Request body:**
```json
{
  "walletAddress": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
  "networkId": "ETHEREUM",
  "assetSymbol": "ETH",
  "assetContract": "0x0000000000000000000000000000000000000000",
  "quantityDelta": "0.5",
  "priceUsd": "2000.00",
  "timestamp": "2025-01-15T12:00:00Z",
  "note": "Reconciliation: airdrop not in history",
  "clientId": "550e8400-e29b-41d4-a716-446655440000"
}
```

- `assetSymbol` or `assetContract` (one required).
- `quantityDelta` (string) — positive = receive, negative = send.
- `priceUsd` (string) — **required when quantityDelta > 0** (for AVCO).
- `timestamp` (optional, ISO 8601) — defaults to "end of timeline" or submission time.
- `clientId` (UUID) — idempotency key; duplicate request with same clientId returns existing event / 200.

**Response `202 Accepted`:**
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa8",
  "message": "Manual transaction saved. Recalculation in progress."
}
```

Poll `GET /api/v1/recalc/status/{jobId}` (same as override) until `status=COMPLETE`.

**Optional:** `DELETE /api/v1/transactions/manual/{eventId}` — remove the manual event and trigger AVCO recalc; response 202 with `jobId`.

---

## Overrides

### Set Manual Price Override

```
PUT /api/v1/transactions/{eventId}/override
```

**Request body:**
```json
{
  "priceUsd": "2500.00",
  "note": "Airdrop — used market price at receipt time"
}
```

**Response `202 Accepted`:**
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "message": "Override saved. Recalculation in progress."
}
```

---

### Revert Override

```
DELETE /api/v1/transactions/{eventId}/override
```

**Response `202 Accepted`:**
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa7",
  "message": "Override reverted. Recalculation in progress."
}
```

---

## Recalculation Jobs

### Get Recalculation Status

```
GET /api/v1/recalc/status/{jobId}
```

Used for both **override** (PUT/DELETE override) and **manual compensating transaction** (POST manual, DELETE manual) recalculations.

**Response `200 OK`:**
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "COMPLETE",
  "newPerWalletAvco": "2104.33",
  "completedAt": "2025-01-15T14:02:05Z"
}
```

`status` values: `PENDING` | `RUNNING` | `COMPLETE` | `FAILED`  
`newPerWalletAvco` is `null` until `status=COMPLETE`  
Poll every 2 seconds. Jobs are auto-deleted 24h after completion.

---

## Error Responses

```json
{
  "error": "WALLET_NOT_FOUND",
  "message": "No sync status found for 0xd8dA... on ETHEREUM",
  "timestamp": "2025-01-15T14:00:00Z"
}
```

| HTTP Code | Error Code | Meaning |
|-----------|-----------|---------|
| `400` | `INVALID_ADDRESS` | Address format invalid |
| `400` | `INVALID_NETWORK` | Network ID not supported |
| `404` | `WALLET_NOT_FOUND` | Wallet not yet added |
| `404` | `EVENT_NOT_FOUND` | Transaction event ID not found |
| `404` | `JOB_NOT_FOUND` | Recalc job ID not found or expired |
| `409` | `OVERRIDE_EXISTS` | Active override already exists for this event |
| `500` | `INTERNAL_ERROR` | Unexpected server error |
