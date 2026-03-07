# WalletRadar — API Contract (Code-Aligned)

> **Version:** Current implementation  
> **Base URL:** `/api/v1`  
> **Auth:** None  
> **Format:** JSON

This document lists only endpoints that are currently implemented in code.

---

## General Conventions

- `POST` trigger endpoints return `202 Accepted`.
- `GET` endpoints are read-only and return persisted data.
- Supported networks:
  - `ETHEREUM`, `ARBITRUM`, `OPTIMISM`, `POLYGON`, `BASE`, `BSC`, `AVALANCHE`, `MANTLE`, `LINEA`, `UNICHAIN`, `ZKSYNC`, `SOLANA`

---

## Wallets

### Add wallet

`POST /api/v1/wallets`

Request:

```json
{
  "address": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

Notes:

- `address` is required and validated.
- `networks` is optional; null/empty means all supported networks.

Response `202`:

```json
{
  "syncId": "wallet-0xd8dA6BF26-ETHEREUM",
  "message": "Backfill started"
}
```

### Get wallet sync status

`GET /api/v1/wallets/{address}/status?network={networkId}`

Behavior:

- If `network` is provided: returns single-network status object.
- If `network` is omitted: returns `networks[]` list for the wallet.
- Returns `404` when no status exists.

Single-network response `200`:

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

All-networks response `200`:

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
    }
  ]
}
```

Status values:

- `PENDING`, `RUNNING`, `COMPLETE`, `PARTIAL`, `FAILED`, `ABANDONED`

---

## Sessions

### Add or replace session wallets

`POST /api/v1/sessions`

Request:

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "wallets": [
    {
      "address": "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
      "label": "Wallet 1",
      "color": "#22d3ee",
      "networks": ["ETHEREUM", "ARBITRUM", "OPTIMISM", "POLYGON", "BASE", "BSC", "AVALANCHE", "MANTLE", "LINEA", "UNICHAIN", "ZKSYNC"]
    }
  ]
}
```

Behavior:

- Full payload validation (all-or-nothing).
- Repeated `POST` with same `sessionId` replaces stored wallets/settings.
- Triggers async backfill for each wallet×network.

Response `202`:

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "message": "Session saved, backfill started"
}
```

### Get session settings

`GET /api/v1/sessions/{sessionId}`

Response `200`:

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "wallets": [
    {
      "address": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
      "label": "Wallet 1",
      "color": "#22d3ee",
      "networks": ["ETHEREUM", "ARBITRUM"]
    }
  ]
}
```

### Get session backfill status (polling)

`GET /api/v1/sessions/{sessionId}/backfill-status`

Response `200`:

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "status": "RUNNING",
  "overallProgressPct": 35,
  "totalTargets": 8,
  "completedTargets": 2,
  "wallets": [
    {
      "address": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
      "label": "Wallet 1",
      "color": "#22d3ee",
      "networks": [
        {
          "networkId": "ETHEREUM",
          "status": "RUNNING",
          "progressPct": 35,
          "lastBlockSynced": 21800000,
          "backfillComplete": false,
          "syncBannerMessage": "Raw fetch ETHEREUM: 2/6 segments complete"
        }
      ]
    }
  ]
}
```

Returns `404` when session is not found.

### Rebuild session transactions projection

`POST /api/v1/sessions/{sessionId}/transactions/rebuild`

Behavior:

- Rebuilds CHAIN-sourced `session_transactions` from `normalized_transactions` (`status=CONFIRMED`) for wallets in the session.
- Replaces previous CHAIN projection for that session idempotently.

Response `202`:

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "projectedTransactions": 42,
  "message": "Session transactions rebuilt"
}
```

Returns `404` when session is not found.

### Get session transactions (phase 1 timeline read)

`GET /api/v1/sessions/{sessionId}/transactions?limit=50`

Parameters:

- `limit`: optional, default `50`, max `200`

Response `200`:

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "items": [
    {
      "id": "549b0aba-a9af-4789-b125-ebb86314a3f1:CHAIN:normalized-1",
      "sourceType": "CHAIN",
      "txHash": "0xabc...",
      "networkId": "BSC",
      "walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
      "blockTimestamp": "2026-03-01T10:00:00Z",
      "type": "SWAP",
      "bridgeStatus": null,
      "realisedPnlUsdTotal": 12.34,
      "avcoSnapshotVersion": null,
      "flows": [
        {
          "role": "SELL",
          "assetContract": "0x...",
          "assetSymbol": "USDC",
          "quantityDelta": -1,
          "unitPriceUsd": 1.01,
          "valueUsd": -1.01,
          "priceSource": "SWAP_DERIVED",
          "logIndex": 7
        }
      ]
    }
  ]
}
```

Returns `404` when session is not found.

---

## Sync

### Trigger incremental sync

`POST /api/v1/sync/refresh`

Request:

```json
{
  "wallets": ["0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"],
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

Response `202`:

```json
{
  "message": "Incremental sync triggered"
}
```

---

## Balances

### Trigger manual balance refresh

`POST /api/v1/wallets/balances/refresh`

Request:

```json
{
  "wallets": ["0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"],
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

Response `202`:

```json
{
  "message": "Balance refresh triggered"
}
```

Validation:

- `wallets` must be non-empty.
- `networks` are validated against supported values.

---

## Transaction History

### Get asset transaction history (cursor pagination)

`GET /api/v1/assets/{assetId}/transactions?cursor={cursor}&limit=50&direction=DESC`

Parameters:

- `assetId`: asset symbol or asset contract
- `cursor`: optional opaque cursor
- `limit`: optional, default `50`, max `200`
- `direction`: `DESC` (default) or `ASC`

Response `200`:

```json
{
  "items": [
    {
      "eventId": "67d4...:0",
      "txHash": "0xabc123...",
      "networkId": "ARBITRUM",
      "walletAddress": "0xd8dA...",
      "blockTimestamp": "2025-01-10T08:23:41Z",
      "eventType": "SWAP_BUY",
      "assetSymbol": "ETH",
      "assetContract": "0x0000000000000000000000000000000000000000",
      "quantityDelta": "0.500000000000000000",
      "priceUsd": "1800.00",
      "priceSource": "SWAP_DERIVED",
      "totalValueUsd": "900.00",
      "realisedPnlUsd": null,
      "avcoAtTimeOfSale": null,
      "status": "CONFIRMED",
      "hasOverride": false
    }
  ],
  "nextCursor": "ZXhhbXBsZQ",
  "hasMore": true
}
```

Data source rules:

- Reads only from `normalized_transactions`.
- Returns only `status=CONFIRMED`.

---

## Not Implemented (Removed From Active Contract)

The following endpoint groups are intentionally not part of the current active API contract:

- `GET /api/v1/assets` (asset list)
- portfolio snapshots / chart endpoints
- override endpoints
- manual compensating transaction endpoints
- recalc status endpoints
