# WalletRadar — API Contract

> **Version:** v3 current backend surface
> **Last updated:** 2026-03-19
> **Status:** Active contract for the currently implemented REST endpoints

---

## 1. Scope

This document describes the API that is currently implemented in the backend.

In scope now:

- persisted session management
- wallet backfill creation
- wallet and session backfill-status reads

Not in scope in this contract:

- normalization, pricing, AVCO, or transaction-history read APIs
- portfolio snapshots
- override or manual compensating transaction APIs

---

## 2. Conventions

- Base path: `/api/v1`
- Content type: `application/json`
- `sessionId` is client-generated and persisted server-side in `user_sessions`
- Session endpoints accept EVM networks only
- Wallet endpoints accept supported backend `NetworkId` values; `null` or empty `networks` means "all supported networks"

Supported EVM `NetworkId` values for session payloads:

- `ETHEREUM`
- `ARBITRUM`
- `OPTIMISM`
- `POLYGON`
- `BASE`
- `BSC`
- `AVALANCHE`
- `MANTLE`
- `LINEA`
- `UNICHAIN`
- `ZKSYNC`
- `KATANA`
- `PLASMA`

---

## 3. Error Model

All handled API errors return:

```json
{
  "error": "INVALID_ADDRESS",
  "message": "Invalid wallet address format",
  "timestamp": "2026-03-19T12:34:56Z"
}
```

Error codes currently produced by validation and controller guards:

- `INVALID_ADDRESS`
- `INVALID_NETWORK`
- `INVALID_SESSION_ID`
- `INVALID_LABEL`
- `INVALID_COLOR`
- `INVALID_REQUEST`
- `SESSION_NOT_FOUND`
- `STATUS_NOT_FOUND`

Typical status codes:

- `202 Accepted` for async creation/start operations
- `200 OK` for reads
- `400 Bad Request` for validation and malformed input
- `404 Not Found` for missing session or sync-status resources

---

## 4. Session Endpoints

### `POST /api/v1/sessions`

Creates or refreshes a persisted session wallet set and triggers backfill orchestration through the session command path.

Request:

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "wallets": [
    {
      "address": "0x1234567890abcdef1234567890abcdef12345678",
      "label": "Main wallet",
      "color": "#4F46E5",
      "networks": ["ETHEREUM", "ARBITRUM", "BASE"]
    }
  ]
}
```

Validation rules:

- `sessionId` is required and must pass server-side session-id validation
- `wallets` must be non-empty
- each wallet requires:
  - EVM address
  - non-blank `label`
  - `color` in `#RRGGBB` format
  - non-empty EVM-only `networks`

Response: `202 Accepted`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "message": "Session accepted"
}
```

Note:

- the exact response message is service-defined; clients should not branch on its text

### `GET /api/v1/sessions/{sessionId}`

Returns the persisted session wallet set.

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "wallets": [
    {
      "address": "0x1234567890abcdef1234567890abcdef12345678",
      "label": "Main wallet",
      "color": "#4F46E5",
      "networks": ["ETHEREUM", "ARBITRUM", "BASE"]
    }
  ]
}
```

Errors:

- `404 SESSION_NOT_FOUND`

### `GET /api/v1/sessions/{sessionId}/backfill-status`

Returns session-level backfill status aggregated across wallets and networks.

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "status": "IN_PROGRESS",
  "overallProgressPct": 67,
  "totalTargets": 6,
  "completedTargets": 4,
  "wallets": [
    {
      "address": "0x1234567890abcdef1234567890abcdef12345678",
      "label": "Main wallet",
      "color": "#4F46E5",
      "networks": [
        {
          "networkId": "ETHEREUM",
          "status": "COMPLETE",
          "progressPct": 100,
          "lastBlockSynced": 21999999,
          "backfillComplete": true,
          "syncBannerMessage": null
        }
      ]
    }
  ]
}
```

Errors:

- `404 SESSION_NOT_FOUND`

---

## 5. Wallet Endpoints

### `POST /api/v1/wallets`

Starts wallet backfill creation through the wallet command path.

Request:

```json
{
  "address": "0x1234567890abcdef1234567890abcdef12345678",
  "networks": ["ETHEREUM", "ARBITRUM"]
}
```

Rules:

- `address` is required
- `networks` may be omitted or empty; that means "all supported networks"
- wallet validation accepts supported backend address formats

Response: `202 Accepted`

```json
{
  "syncId": "wallet-0x12345678-ETHEREUM",
  "message": "Backfill started"
}
```

Note:

- `syncId` is a convenience response field, not a canonical resource identifier

### `GET /api/v1/wallets/{address}/status?network={NETWORK_ID}`

Returns backfill status for one wallet on one network.

Response: `200 OK`

```json
{
  "walletAddress": "0x1234567890abcdef1234567890abcdef12345678",
  "networkId": "ETHEREUM",
  "status": "IN_PROGRESS",
  "progressPct": 67,
  "lastBlockSynced": 21999999,
  "backfillComplete": false,
  "syncBannerMessage": "Backfill is running"
}
```

Errors:

- `400 INVALID_ADDRESS`
- `400 INVALID_NETWORK`
- `404 STATUS_NOT_FOUND`

### `GET /api/v1/wallets/{address}/status`

Returns backfill status for all known networks for one wallet.

Response: `200 OK`

```json
{
  "walletAddress": "0x1234567890abcdef1234567890abcdef12345678",
  "networks": [
    {
      "networkId": "ETHEREUM",
      "status": "COMPLETE",
      "progressPct": 100,
      "lastBlockSynced": 21999999,
      "backfillComplete": true,
      "syncBannerMessage": null
    },
    {
      "networkId": "ARBITRUM",
      "status": "IN_PROGRESS",
      "progressPct": 67,
      "lastBlockSynced": 318888888,
      "backfillComplete": false,
      "syncBannerMessage": "Backfill is running"
    }
  ]
}
```

Errors:

- `400 INVALID_ADDRESS`
- `404 STATUS_NOT_FOUND`

---

## 6. Compatibility Notes

- This contract intentionally documents only the endpoints that exist in the current backend.
- Future normalization, pricing, AVCO, reconciliation, and portfolio APIs must be added here when implemented.
- GET endpoints remain datastore-only; no RPC calls are part of the request path contract.
