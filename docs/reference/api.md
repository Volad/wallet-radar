# WalletRadar — API Contract

> **Version:** v3 current backend surface
> **Last updated:** 2026-07-08
> **Status:** Active contract for the currently implemented REST endpoints

---

## 1. Scope

This document describes the API that is currently implemented in the backend.

In scope now:

- **authentication** — Google SSO (OAuth2 Authorization Code), JWT cookie, `/api/v1/auth/me`, `/api/v1/auth/logout`
- persisted session management
- persisted session settings and integrations
- wallet backfill creation
- wallet and session backfill-status reads
- session-level transaction-history reads
- session-level asset-ledger timeline reads

## Security Model

When `walletradar.auth.enabled=true`:

- All `/api/v1/sessions/{sessionId}/**` endpoints require a valid `wr_auth` HttpOnly cookie (HS256 JWT) whose `sessionId` claim matches the path parameter — 401 if no cookie, 403 if wrong session.
- `POST /api/v1/sessions` requires authentication (no ownership check at the security layer).
- `GET /api/v1/auth/me` is always public (returns `{authenticated:false}` without cookie).
- `POST /api/v1/auth/logout` requires authentication.
- All requests must be sent with `withCredentials: true` so the browser includes the cookie.

When `walletradar.auth.enabled=false` (default, local dev): all endpoints are permit-all.

### Auth Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/auth/me` | Public | Returns `{authenticated, provider, email, displayName, pictureUrl, sessionId}` |
| POST | `/api/v1/auth/logout` | Required | Clears `wr_auth` cookie; returns `{authenticated:false}` |

### OAuth2 Flow

1. Frontend: `window.location.href = '/oauth2/authorization/google'`
2. Backend redirects to Google consent page.
3. Google callback: `GET /login/oauth2/code/google` (handled by Spring Security).
4. On success: `wr_auth` cookie set; redirect to `/settings`.
5. Frontend calls `GET /api/v1/auth/me` → receives `sessionId`; stores as canonical session.

Planned next slice, but not implemented yet:

- session-triggered incremental refresh scheduling from the last completed
  source checkpoint to the current time / head block

Not in scope in this contract:

- generic normalization, pricing, or portfolio-wide transaction-history read APIs
- portfolio snapshots
- override or manual compensating transaction APIs

**Operations (not REST):** to force a **full 2-year cold replay** for all on-chain
wallets and all Bybit integrations in a Mongo database (delete raw/integration
artifacts, reset `sync_status`, then reset derived pipeline state), use
`scripts/mongo-prep-full-2yr-backfill.sh` from the repo root (see script header for
scope and safety). Planned admin `POST .../full-rebuild` for a single integration
is specified in cycle/3 `required-changes.md` / `qa-clarifications.md` §7.1 and is
**not** part of this API document until implemented and secured.

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

### `POST /api/v1/sessions/{sessionId}/refresh`

Schedules a bounded incremental refresh cycle for the existing session scope.

Behavior:

- reuses the existing session wallets and enabled integrations
- keeps the existing `sync_status` rows as the stable source identity
- replaces only the orchestration `backfill_segments` for sources that have a
  real delta window
- preserves historical raw and canonical rows
- resumes the existing downstream pipeline after the incremental raw cycle
  completes

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "status": "SCHEDULED",
  "scheduledTargets": 4,
  "skippedTargets": 9,
  "message": "Incremental refresh queued"
}
```

No-op response:

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "status": "UP_TO_DATE",
  "scheduledTargets": 0,
  "skippedTargets": 13,
  "message": "Session is already up to date"
}
```

Errors:

- `404 SESSION_NOT_FOUND`
- `409 SESSION_REFRESH_CONFLICT`

### `GET /api/v1/sessions/{sessionId}/settings`

Returns the persisted session workspace view including wallets and external
integrations. Secret material is never returned.

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
  ],
  "hideSmallAssets": true,
  "showReconciliationWarnings": true,
  "integrations": [
    {
      "integrationId": "bybit-main",
      "provider": "BYBIT",
      "status": "CONNECTED",
      "displayName": "Bybit main",
      "accountRef": "BYBIT:33625378",
      "maskedKey": "zQ1n...A9K2",
      "readOnly": true,
      "capabilities": ["ASSET"],
      "lastValidatedAt": "2026-04-07T10:00:00Z",
      "lastSyncAt": null,
      "lastError": null,
      "totalSegments": 120,
      "completedSegments": 118,
      "failedSegments": 0,
      "progressPct": 98,
      "streamSync": [
        {
          "stream": "TRANSACTION_LOG",
          "lastSegmentCompletedAt": "2026-04-07T09:55:00Z",
          "newestStoredEventAt": "2026-04-07T09:50:12Z"
        },
        {
          "stream": "INTERNAL_TRANSFER",
          "lastSegmentCompletedAt": null,
          "newestStoredEventAt": null
        }
      ]
    }
  ]
}
```

Errors:

- `404 SESSION_NOT_FOUND`

### `PUT /api/v1/sessions/{sessionId}/settings`

Overwrites the persisted session workspace configuration for the provided
session:

- wallets
- visible integration configuration
- general UI settings

Integration secrets remain write-only. If an existing Bybit integration is
included with empty `apiKey` and `apiSecret`, the server preserves the stored
encrypted secret and only updates visible metadata like `displayName`.

Request:

```json
{
  "wallets": [
    {
      "address": "0x1234567890abcdef1234567890abcdef12345678",
      "label": "Main wallet",
      "color": "#4F46E5",
      "networks": ["ETHEREUM", "ARBITRUM", "BASE"]
    }
  ],
  "integrations": [
    {
      "provider": "BYBIT",
      "displayName": "Bybit main",
      "apiKey": "",
      "apiSecret": ""
    },
    {
      "provider": "DZENGI",
      "displayName": "Dzengi main",
      "apiKey": "new-key",
      "apiSecret": "new-secret",
      "color": "#22d3ee"
    }
  ],
  "hideSmallAssets": true,
  "showReconciliationWarnings": true
}
```

Response: `200 OK`

Returns the same shape as `GET /api/v1/sessions/{sessionId}/settings`.

Errors:

- `400 INVALID_SETTINGS_REQUEST`
- `404 SESSION_NOT_FOUND`

### `PUT /api/v1/sessions/{sessionId}/integrations/bybit`

Creates or updates a session-owned Bybit integration and starts initial
backfill planning for the last two years.

> **Note:** Dzengi can also be connected via session settings overwrite
> (`PUT /sessions/{id}/settings` with `provider: "DZENGI"`) or the dedicated
> endpoint below. Both paths persist credentials and schedule backfill.

Backfill is multi-stream and performs provider-side enrichment during
acquisition. Bybit normalization later consumes the extracted staging produced
by that backfill; it does not call Bybit APIs itself.

Request:

```json
{
  "displayName": "Bybit main",
  "apiKey": "example-api-key",
  "apiSecret": "example-api-secret"
}
```

Response: `202 Accepted`

```json
{
  "integrationId": "bybit-main",
  "provider": "BYBIT",
  "status": "BACKFILLING",
  "displayName": "Bybit main",
  "accountRef": "BYBIT:33625378",
  "maskedKey": "zQ1n...A9K2",
  "message": "Bybit integration saved, backfill planned"
}
```

Errors:

- `400 INVALID_REQUEST`
- `404 SESSION_NOT_FOUND`

### `PUT /api/v1/sessions/{sessionId}/integrations/dzengi`

Creates or updates a session-owned Dzengi integration and starts initial
backfill planning for the last two years.

Request:

```json
{
  "displayName": "Dzengi main",
  "apiKey": "example-api-key",
  "apiSecret": "example-api-secret"
}
```

Response: `202 Accepted`

```json
{
  "integrationId": "dzengi-main",
  "provider": "DZENGI",
  "status": "BACKFILLING",
  "displayName": "Dzengi main",
  "accountRef": "DZENGI:12345678",
  "maskedKey": "zQ1n...A9K2",
  "message": "Dzengi integration saved, backfill planned"
}
```

Errors:

- `400 INVALID_REQUEST`
- `404 SESSION_NOT_FOUND`

### `POST /api/v1/sessions/{sessionId}/integrations/test`

Validates API credentials for a supported integration provider **without**
persisting secrets or scheduling backfill. Used by the Settings UI **Test
connection** button before connect/save.

Request:

```json
{
  "provider": "DZENGI",
  "apiKey": "example-api-key",
  "apiSecret": "example-api-secret"
}
```

Supported `provider` values: `BYBIT`, `DZENGI` (others return `400`).

Response: `200 OK`

```json
{
  "provider": "DZENGI",
  "accountRef": "DZENGI:12345678",
  "maskedKey": "zQ1n...A9K2",
  "readOnly": true,
  "message": "Dzengi credentials validated"
}
```

Errors:

- `400 INVALID_REQUEST` — missing credentials, unsupported provider, or venue validation failure
- `404 SESSION_NOT_FOUND`

### `DELETE /api/v1/sessions/{sessionId}/integrations/{integrationId}`

Disables and removes one persisted session integration configuration.

Response: `200 OK`

```json
{
  "integrationId": "bybit-main",
  "message": "Integration removed"
}
```

Errors:

- `404 SESSION_NOT_FOUND`
- `404 INTEGRATION_NOT_FOUND`

### `GET /api/v1/sessions/{sessionId}/backfill-status`

Returns session-level backfill status aggregated across all enabled backfill
targets:

- wallet×network on-chain targets
- enabled integration targets

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

### `POST /api/v1/sessions/{sessionId}/transactions/rebuild`

Refreshes the session transaction read scope and returns the current number of
transaction rows visible for that session. This is a read-through projection
over `normalized_transactions`; no dedicated persisted session-transaction
collection is required in the current implementation.

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "projectedTransactions": 245,
  "message": "Session transactions refreshed from canonical normalized transactions"
}
```

Errors:

- `404 SESSION_NOT_FOUND`

### `GET /api/v1/sessions/{sessionId}/transactions`

Returns the most recent session-visible normalized transactions. The visibility
scope is the session workspace scope, not only the currently selected
on-chain wallets. This allows the session history to include related custody
refs such as `BYBIT:<uid>` when they belong to the same session via connected
integrations.

Query params:

- `limit` optional, default `50`
- accepted range: `1..500`
- `offset` optional, default `0`
- accepted range: `0..`
- `search` optional, case-insensitive match against `txHash` and `flows.assetSymbol`
- `bridgeStatus` optional:
  - `ALL`
  - `BRIDGE_OUT`
  - `BRIDGE_IN`
  - `MATCHED`
  - `REVIEW`
- `spamFilter` optional:
  - `HIDE_SPAM` default
  - `ALL`
  - `SPAM_ONLY`
- `walletId` optional, repeatable
  - filters to the requested wallet refs within the session accounting universe
- `networkId` optional, repeatable
  - filters to the requested networks within the session accounting universe

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "offset": 25,
  "limit": 25,
  "totalCount": 245,
  "hasMore": true,
  "items": [
    {
      "id": "0xabc...|0xdef...",
      "sourceType": "CHAIN",
      "txHash": "0xabc123",
      "networkId": "BASE",
      "walletAddress": "0x1234567890abcdef1234567890abcdef12345678",
      "matchedCounterparty": "BYBIT:33625378",
      "blockTimestamp": "2026-04-06T12:34:56Z",
      "type": "EXTERNAL_TRANSFER_OUT",
      "status": "CONFIRMED",
      "issue": null,
      "bridgeStatus": "MATCHED",
      "realisedPnlUsdTotal": "12.50",
      "avcoSnapshotVersion": null,
      "flows": [
        {
          "role": "SELL",
          "assetContract": null,
          "assetSymbol": "ETH",
          "quantityDelta": "-0.5",
          "unitPriceUsd": "2100",
          "valueUsd": "-1050",
          "priceSource": "COINGECKO",
          "logIndex": 0
        }
      ]
    }
  ]
}
```

Notes:

- `sourceType` currently distinguishes only `CHAIN` and `MANUAL`; a dedicated
  external-ledger source label is not part of the current REST contract.
- `type` is mapped to the existing dashboard transaction taxonomy
  (`EXTERNAL_INBOUND`, `LEND_DEPOSIT`, `STAKE_DEPOSIT`, etc.) so the current
  SPA can render it without an additional taxonomy migration.
- `status` is the current canonical normalized status:
  - `CONFIRMED`
  - `PENDING_PRICE`
  - `NEEDS_REVIEW`
- `matchedCounterparty` is present for continuity-linked transfers such as
  bridge legs and external-ledger custody moves
- `issue` is a lightweight UI hint:
  - `spam` when the row is a spam/phishing artifact returned by `spamFilter=ALL|SPAM_ONLY`,
    including explicit exclusion-tagged spam and narrow review rows such as
    `CLAIM_LIKE_SPAM_OR_AIRDROP`
  - `missing_price` when the row still lacks accounting prices
  - `unconfirmed` when the row is not yet confirmed for another reason
  - `null` otherwise
- `bridgeStatus` values:
  - `BRIDGE_OUT`
  - `BRIDGE_IN`
  - `MATCHED`
  - `REVIEW`
- rows with spam/phishing semantics are omitted unless they are explicitly requested
  through the spam filter; this includes exclusion-tagged spam and normalized rows
  carrying explicit spam-like `missingDataReasons`

Errors:

- `400 INVALID_TRANSACTIONS_QUERY`
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
- Future normalization, pricing, reconciliation, and portfolio APIs must be added here when implemented.
- GET endpoints remain datastore-only; no RPC calls are part of the request path contract.

---

## 7. Asset Ledger Timeline Endpoint

### `GET /api/v1/sessions/{sessionId}/asset-ledger?familyIdentity={FAMILY_ID}`

Returns the session asset-ledger timeline for one accounting family.

Purpose:

- drive the asset detail chart for AVCO / cost-basis history
- provide event overlay markers
- expose raw immutable replay points for audit/debug

Request rules:

- `sessionId` must exist
- `familyIdentity` is required
- the current backend contract expects the exact stored family identity such as:
  - `FAMILY:ETH`
  - `FAMILY:USDC`
  - an exact asset identity fallback such as `0x...contract` or `NATIVE:BASE`

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "familyIdentity": "FAMILY:ETH",
  "current": {
    "quantity": "3.0681",
    "coveredQuantity": "3.0600",
    "uncoveredQuantity": "0.0081",
    "totalCostBasisUsd": "6058.10",
    "avcoUsd": "1974.00",
    "realisedPnlUsd": "0",
    "gasPaidUsd": "0",
    "uncoveredBuckets": [
      {
        "walletAddress": "0x1234...",
        "networkId": "MANTLE",
        "assetSymbol": "AMANWETH",
        "assetContract": "0xea...",
        "quantity": "3.0661",
        "coveredQuantity": "3.0600",
        "uncoveredQuantity": "0.0061",
        "uncoveredReason": "yield_accrual",
        "latestTxHash": "0x3b85...",
        "latestNormalizedType": "LENDING_DEPOSIT",
        "latestBasisEffect": "REALLOCATE_IN",
        "latestProtocolName": "Aave",
        "hasIncompleteHistory": false,
        "hasUnresolvedFlags": false,
        "unresolvedFlagCount": 0
      }
    ],
    "shortfallSources": [
      {
        "walletAddress": "0x1234...",
        "networkId": "BASE",
        "txHash": "0x5532...",
        "blockTimestamp": "2025-12-20T10:23:49Z",
        "normalizedType": "LP_ENTRY",
        "protocolName": "PancakeSwap",
        "quantityShortfall": "0.262015232385361717"
      }
    ]
  },
  "timeline": [
    {
      "blockTimestamp": "2026-03-01T10:00:00Z",
      "txHash": "0xabc...",
      "normalizedTransactionId": "67d0...",
      "normalizedType": "BRIDGE_OUT",
      "lifecycleKind": "BRIDGE",
      "lifecycleStage": "SOURCE",
      "basisEffects": ["CARRY_OUT", "GAS_ONLY"],
      "quantityDelta": "-1.000000000000000000",
      "costBasisDeltaUsd": "-2000.00",
      "realisedPnlDeltaUsd": "0",
      "gasDeltaUsd": "2.50",
      "quantityAfter": "2.0681",
      "coveredQuantityAfter": "2.0600",
      "uncoveredQuantityAfter": "0.0081",
      "totalCostBasisAfterUsd": "4058.10",
      "avcoAfterUsd": "1962.44"
    }
  ],
  "events": [
    {
      "normalizedTransactionId": "67d0...",
      "txHash": "0xabc...",
      "blockTimestamp": "2026-03-01T10:00:00Z",
      "normalizedType": "BRIDGE_OUT",
      "lifecycleKind": "BRIDGE",
      "walletAddresses": ["0x1234..."],
      "networkIds": ["ZKSYNC"]
    }
  ],
  "ledgerPoints": [
    {
      "walletAddress": "0x1234...",
      "networkId": "ZKSYNC",
      "accountingAssetIdentity": "NATIVE:ZKSYNC",
      "accountingFamilyIdentity": "FAMILY:ETH",
      "blockTimestamp": "2026-03-01T10:00:00Z",
      "replaySequence": 144,
      "basisEffect": "CARRY_OUT",
      "quantityBefore": "1.689595",
      "quantityAfter": "1.000000",
      "totalCostBasisBeforeUsd": "3370.00",
      "totalCostBasisAfterUsd": "2000.00",
      "basisBackedQuantityAfter": "1.000000",
      "uncoveredQuantityDelta": "0",
      "hasIncompleteHistoryAfter": false,
      "quantityShortfallAfter": "0",
      "uncoveredQuantityAfter": "0"
    }
  ]
}
```

`current.uncoveredBuckets` is a diagnostic surface for the live current family
state only.

`current.shortfallSources` is a family-level diagnostic surface for historical
coverage debt.

Interpretation:

- `yield_accrual`
  - the current positive bucket is an interest-bearing continuity bucket and
    live quantity drift is passive accrual since the latest clean replay point
- `coverage_gap`
  - the current bucket is only partially covered and does not qualify as clean
    passive accrual
- `history_flags`
  - reserved for parity with dashboard issue semantics; indicates historical
    provenance flags when a future API slice returns a covered-but-flagged
    current bucket
- `missing_replay_point`
  - a current live balance bucket exists, but no matching latest ledger bucket
    was found for the same session scope

This field does not invent basis. It explains why the current family quantity
is still partially uncovered after replay.

## 8. Session Lending Endpoint

### `GET /api/v1/sessions/{sessionId}/lending`

Returns the dedicated lending workspace read model for one session.

Purpose:

- show open and closed lending positions
- show supply, borrow, and loop history
- expose health factor and APY metrics without making them accounting inputs
- keep the read path snapshot-only

Response: `200 OK`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "summary": {
    "totalSuppliedUsd": "10500.00",
    "totalBorrowedUsd": "2500.00",
    "netExposureUsd": "8000.00",
    "openGroups": 2,
    "closedGroups": 1,
    "protocols": 2
  },
  "groups": [
    {
      "id": "aave:mantle:0x1234",
      "protocol": "Aave",
      "networkId": "MANTLE",
      "walletAddress": "0x1234...",
      "status": "OPEN",
      "healthFactor": "2.34",
      "healthStatus": "ESTIMATED",
      "healthSource": "ACCOUNTING_ESTIMATE",
      "supplyUsd": "8000.00",
      "borrowUsd": "2500.00",
      "netExposureUsd": "5500.00",
      "positions": [
        {
          "id": "aave:mantle:0x1234:supply:USDC",
          "side": "SUPPLY",
          "assetSymbol": "aManUSDC",
          "underlyingSymbol": "USDC",
          "quantity": "2903.638038",
          "coveredQuantity": "2893.019792",
          "valueUsd": "2903.64",
          "earnedUsd": "10.62",
          "apyPct": "4.10",
          "metricStatus": "PROTOCOL_SNAPSHOT",
          "metricSource": "AAVE_V3_POOL",
          "protocolSupplyApyPct": "4.21",
          "protocolBorrowApyPct": "6.03",
          "rewardAprPct": null,
          "netProtocolApyPct": "4.21",
          "protocolApyStatus": "PROTOCOL_SNAPSHOT",
          "protocolApySource": "AAVE_V3_POOL",
          "protocolApyCapturedAt": "2026-05-04T10:00:00Z",
          "protocolApyStale": false,
          "rewardAprStatus": "UNAVAILABLE",
          "rewardAprUnavailableReason": "REWARDS_COLLECTOR_NOT_IMPLEMENTED",
          "apyConvention": "PER_SECOND_COMPOUNDING"
        }
      ],
      "history": [
        {
          "id": "0xabc:MANTLE:0x1234",
          "txHash": "0xabc...",
          "blockTimestamp": "2026-04-22T08:33:08Z",
          "type": "LENDING_DEPOSIT",
          "displayType": "Deposit",
          "assetSymbol": "USDC",
          "quantity": "1004.54",
          "valueUsd": "1004.54",
          "loopId": null
        }
      ]
    }
  ]
}
```

Rules:

- `status = OPEN` when a group has a positive current lending receipt or debt
  balance.
- `status = CLOSED` when lending history exists but no positive current
  receipt/debt balance remains in that group.
- History may include canonical `VAULT_DEPOSIT` / `VAULT_WITHDRAW` rows when
  the transferred receipt token is a lending-market token, for example Fluid
  `fUSDC` / `fUSDT`, and `REWARD_CLAIM` rows for known lending protocols such
  as Fluid or Compound. This is a read-model grouping rule only; it does not
  mutate canonical normalized transaction types.
- Receipt-like symbols are not protocol proof. For example, `syrupUSDC` may be
  bought or sold through a plain swap; it must not enter the lending workspace
  unless a protocol target, decoded nested calldata, full receipt logs, or
  existing canonical lending history proves a lending interaction.
- Fluid vault `operate` / `operatePerfect` activity should normally arrive as
  canonical lending lifecycle history (`BORROW`, `REPAY`, `LENDING_DEPOSIT`,
  `LENDING_WITHDRAW`, or `LENDING_LOOP_*`) after normalization. The read API
  should not compensate for those rows as generic swap or external-transfer
  history.
- Fluid operations may be wrapped by Instadapp/DSA entrypoints such as
  `cast(tuple[] actions_)` or ERC-721 `safeTransferFrom(..., bytes data)`.
  Normalization is responsible for decoding enough nested calldata or receipt
  evidence to classify these as Fluid lifecycle rows when the embedded vault is
  registry-owned.
- Plasma Euler EVK batch activity should arrive as Euler lending lifecycle
  history. The Plasma batch router can emit receipt-only loop opens, so the API
  expects normalization to classify those as `LENDING_LOOP_OPEN` when stable and
  collateral EVK receipts are minted or transferred into the tracked account.
- Compound V3 Comet history depends on registry coverage for each market. The
  Unichain `cUSDCv3` market is treated as Compound lending; non-base collateral
  withdrawals are not displayed as borrow rows.
- Compound V3 Bulker `invoke(bytes32[],bytes[])` bundles are classified before
  generic swap heuristics. The API exposes one canonical lifecycle row with
  child timeline/display legs rather than multiple independent top-level
  normalized rows.
- Fluid vault rows use decoded debt/collateral intent before visible token
  direction. When full receipt logs/internal transfers are missing, the API may
  show confirmed wallet-visible flows plus lifecycle metadata, but PnL remains
  unavailable with `pnl_unavailable_missing_full_receipt_logs` where exact
  economic legs are not proven.
- Fluid full-receipt enrichment persists durable Fluid evidence before the
  Lending API reads it. The API must not fetch RPC receipts. Structured
  evidence includes vault address, NFT id when known, wrapper kind, market key,
  position/lifecycle key, evidence completeness, decoded debt/collateral
  intent, and receipt/log references.
- Historical lending rows may build closed/open lifecycle cycle history, but
  current open supply or debt positions require current-state evidence. For
  vault/NFT/account-based debt protocols such as Fluid and Compound, confirmed
  `BORROW - REPAY` history alone is not enough to create a current open borrow
  position. If resolver/protocol state is unavailable and no current debt
  balance exists, the API must not synthesize an open position; historical
  cycles remain visible.
- For tokenized debt protocols such as Aave variable debt tokens, open debt may
  be shown only when the current debt-token balance is visible in snapshots.
- A lending cycle closes only when supply/collateral, debt, vault/account
  position, and receipt/debt-token state are all zero or resolver-proven absent.
  A `REPAY` event closes debt only and must not close a cycle while collateral
  remains supplied.
- Fluid vault/NFT accounts, Euler EVK/EVC loops, and Compound Comet accounts
  are parent lifecycles keyed by protocol, network, wallet, and account/vault or
  market identity. Their token movements are sub-events inside the parent
  lifecycle, not independent asset-symbol cycles.
- Reward claims attach to an active matching cycle. When there is no matching
  active cycle, they remain standalone protocol reward history and must not
  start a position lifecycle.
- A closed cycle with current-state-zero proof but missing principal exit
  evidence may be returned only with an explicit warning/reason such as
  `unresolved_principal_exit`; clients must display that reason. A closed
  current-state-zero cycle with incomplete Fluid receipt evidence uses
  `pnl_unavailable_missing_full_receipt_logs`.
- `healthFactor`, `healthStatus`, and `healthSource` describe lending analytics
  only. They do not mutate canonical transactions, pricing, AVCO, or ledger
  state.
- `metricStatus` and `metricSource` on positions distinguish protocol-derived
  metrics from accounting-based estimates.
- `apyPct` is a backward-compatible display alias only. Clients should prefer
  the explicit protocol APY fields.
- `protocolSupplyApyPct`, `protocolBorrowApyPct`, `netProtocolApyPct`,
  `protocolApyStatus`, `protocolApySource`, `protocolApyCapturedAt`, and
  `apyConvention` describe current protocol market-rate snapshots. They are
  read-model analytics only and never mutate accounting state.
- `protocolApyStatus=PROTOCOL_SNAPSHOT` means a fresh persisted protocol
  snapshot exists. `FALLBACK_ESTIMATE` means the old estimator was used and must
  not be presented as authoritative protocol APY. `UNAVAILABLE` must carry a
  reason.
- `rewardAprPct` is separate from native lending APY. During the Aave P0 slice
  rewards may be returned as `rewardAprStatus=UNAVAILABLE` and
  `rewardAprUnavailableReason=REWARDS_COLLECTOR_NOT_IMPLEMENTED`.
- Cycle `factualApy` describes the user's actual annualized lifecycle return or
  debt cost from time-weighted cashflows. It must not be synthesized from
  protocol APY. Open cycles can expose it only as `ESTIMATED`; unresolved cycles
  expose `UNAVAILABLE` with `apyUnavailableReason`.
- Lifecycle-aware clients should prefer `groups[].cycles[]` over the legacy
  flat `groups[].history[]` list.
- `marketKey` is the deterministic protocol market lane. For Morpho vault-like
  rows it is based on receipt/share/vault identity, so `gtUSDCc` and
  `syrupUSDC` are separate market lanes under protocol `Morpho`.
- `cycleId` is deterministic within `protocol + network + wallet + marketKey`
  and changes when a closed position is later reopened.
- Cycle close uses exact zero first, then deterministic dust tolerance:
  `10^-min(decimals,12)` or `1e-12` when decimals are unknown, with a `$0.01`
  cap when price is available.
- `assetDeltas` expose exact asset-level principal, borrow, repay, withdrawal,
  reward, fee, and net cash quantities. USD PnL fields must carry
  `EXACT`, `ESTIMATED`, or `UNAVAILABLE` precision.
- `assetDeltas` are reported by lifecycle asset, not protocol receipt/debt
  token label. For example `variableDebtAvaGHO` is exposed as `GHO`, and
  `WETH/ETH` aliases are exposed as `ETH` for cycle continuity and PnL.
- `pnlAssetBreakdown` is the derived asset-level earnings contract. It exposes
  `supplyIncomeByAsset`, `borrowCostByAsset`, `rewardsByAsset`, `gasByAsset`,
  `netIncomeByAsset`, `precisionByAsset`, and `reasonByAsset`.
- `observedFlowsByAsset` is the cycle-level evidence contract for visible
  flows that explain cash movement but are not authoritative PnL. It is used
  when `pnlBreakdown.precision=UNAVAILABLE` or a per-asset precision is
  unavailable. Each item carries asset symbol, optional contract, quantity,
  source transaction hash, source kind, `isAuthoritativeForPnl`, and optional
  unavailable reason.
- Clients must not treat `observedFlowsByAsset` as earned PnL unless
  `isAuthoritativeForPnl=true`. When PnL is unavailable, the backend must not
  duplicate the same incomplete observed delta into `supplyIncomeByAsset` or
  `netIncomeByAsset`.
- `pnlAssetBreakdown.gasByAsset` is native gas quantity by asset, not USD.
  For example an Arbitrum gas fee is reported under `ETH`. USD gas remains
  `pnlBreakdown.gasUsd`.
- Asset-level income formulas are:
  `supplyIncomeByAsset = principalOutByAsset - principalInByAsset`,
  `borrowCostByAsset = repaidByAsset - borrowedByAsset`, and
  `netIncomeByAsset = supplyIncomeByAsset + rewardsByAsset -
  borrowCostByAsset - gasByAsset`.
- For open cycles, `supplyIncomeByAsset` is estimated from current position
  quantity minus covered principal quantity. It is not computed as
  `withdrawn - supplied`, because the principal is still inside the protocol.
- Share/vault USD PnL must remain `UNAVAILABLE` until both historical price and
  share-rate evidence are available. Exact asset deltas are still returned.
- Plasma `USDT0` is preserved in transaction/event/display evidence. Aggregate
  accounting/PnL maps may use canonical `USDT` only where the backend
  intentionally aliases Plasma `USDT0` into the USDT family.
- `wstUSR` lending-yield USD PnL must remain unavailable until historical
  `wstUSR -> stUSR/USR` conversion/share-rate and underlying price policy are
  present. Swap-derived acquisition value may support acquisition cost but is
  not sufficient to split Fluid lending yield from wrapper/share-rate or
  underlying price movement.
- `REPAY_WITH_ATOKENS` is represented as canonical `type=REPAY` plus
  `eventSubtype=REPAY_WITH_ATOKENS`; it is not a new canonical transaction type.

Cycle workspace additions:

- Cycles are the primary product surface. The API also returns legacy raw
  `events` and `assetDeltas` for diagnostics, but the Lending page should render
  `txGroups` by default.
- A clean cycle opens only on the first `Supply` / `Deposit` event in context.
  Borrow, repay, withdraw, and reward rows do not start a clean cycle by
  themselves.
- A close-side event for an asset that is not currently open in the target
  cycle remains `AMBIGUOUS_NEEDS_REVIEW`; clients must not treat it as proof
  that an unrelated supply cycle closed.
- Cycle close checks use canonical lifecycle assets. Equivalent wrappers and
  protocol receipt/debt symbols are normalized before the read model decides
  whether supply or debt remains open.
- Interest accrual can make close deltas slightly larger than open deltas. A
  lifecycle is considered flat when no positive supply/debt remainder remains;
  negative over-withdraw/over-repay deltas do not keep a cycle open.
- Aave parent context is `protocol + network + wallet`, but `cycles[]` may
  contain multiple concurrent account-pool strategy cycles when independent
  supply-only collateral and borrow/repay loops overlap. Debt events attach to
  the matching active debt strategy; collateral events attach to the matching
  active collateral strategy.
- Fluid and Morpho context is `protocol + network + wallet + vault/account/market`.
- Euler and Compound context is `protocol + network + wallet + market/account`.
- Fluid `marketKey` should use the resolved vault/account counterparty when it
  exists; generic `VAULT-ACCOUNT` is only fallback for incomplete evidence.
- Vault/account-based protocols such as Fluid, Euler, Morpho, and Compound
  return `warningReason = unresolved principal exit` when a cycle is closed by
  current-state-zero proof but the historical principal exit row is not present.
- Borrow followed by supply/deposit within 24 hours in the same cycle/context is
  grouped as one collapsed `loop` transaction group.
- `txGroups[].type` is one of `open`, `borrow`, `loop`, `mid`, `close`, or
  `reward`.
- `txGroups[].items[]` contains UI-ready transaction items with type, label,
  asset, quantity, USD value, hash, and timestamp.
- Loop groups include `loopSteps`, `loopAssetIn`, and `loopAssetOut`. Asset
  labels may contain comma-separated unique lifecycle assets when one collapsed
  loop group contains multiple borrow/supply pairs.
- `loopSteps` is a step count, not a leverage multiplier. Clients must render it
  as loop step/group copy. A leverage multiplier may be displayed only from a
  dedicated backend field such as `leverageRatio`.
- `observedFlowsByAsset[*].sourceKind` is evidence metadata. User-facing
  clients must map `WALLET_VISIBLE_TRANSFER` to "Observed wallet movement",
  `DECODED_PROTOCOL_EVENT` to "Decoded protocol event", and `RECEIPT_LOG` to
  "Receipt log"; these strings are not economic event types.
- Internal status and reason codes such as `closed/current-state-zero` and
  `pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy` are
  API reason codes. Primary UI must render human labels while preserving the raw
  codes only for diagnostics.
- `pnlBreakdown` exposes `interestEarnedUsd`, `interestPaidUsd`, `gasUsd`,
  `netPnlUsd`, `precision`, `method`, and `reason`.
- `totalValuation` exposes broader cycle valuation fields:
  `principalInUsd`, `principalOutUsd`, `borrowedUsd`, `repaidUsd`,
  `rewardsUsd`, `feesUsd`, `gasUsd`, `totalUsdPnl`, `currentUsdValue`,
  `unrealizedTotalUsdPnl`, `totalUsdPnlPrecision`, `yieldOnlyPnl`,
  `yieldOnlyPnlPrecision`, `valuationMethod`, and `unavailableReason`.
- `totalValuation.totalUsdPnl` is the cycle economic cashflow result:
  `principalOutUsd + borrowedUsd + rewardsUsd - principalInUsd - repaidUsd -
  feesUsd`. For open cycles, `currentUsdValue` is reported separately and
  `unrealizedTotalUsdPnl` adds the current open-position value to realized
  cycle cashflows.
- `totalValuation.yieldOnlyPnl` mirrors the stricter yield-only contract from
  `pnlBreakdown.netPnlUsd`. It remains unavailable when wrapper/share-rate,
  underlying conversion, or unresolved lifecycle evidence prevents
  deterministic lending-yield attribution.
- `EURC` total valuation uses cached ECB EUR/USD policy quotes and must not
  silently use USD stablecoin parity. `deUSD` is stable-like unless
  transaction-local execution evidence proves material parity break. Wrapper
  assets such as `wstETH` and `wstUSR` may have total valuation without
  unlocking yield-only PnL.
- `pnlAssetBreakdown` exposes asset-level realized or running income. If a
  component is not financially safe to derive, the relevant asset is marked in
  `precisionByAsset=UNAVAILABLE` and `reasonByAsset` explains why.
- Cycle PnL is lending yield, not collateral price movement:
  `netPnlUsd = interestEarnedUsd - interestPaidUsd - gasUsd`.
- When lending transfer rows do not already carry `valueUsd`, the read model
  may use cached `historical_prices` for the event timestamp. The lending GET
  endpoint must not perform live price-provider calls.
- For wrapper assets such as `wstETH` and `wstUSR`, total valuation may use the
  nearest cached wrapper quote inside the bounded wrapper lookup window when a
  normal event-time quote is unavailable. Such totals are `ESTIMATED`; this
  does not unlock wrapper/share-rate `yieldOnlyPnl`.
- `assetDeltas.principalOutCashByAsset` is the clean wallet-cash principal-out
  surface for realized total PnL. `assetDeltas.internalReceiptMovementByAsset`
  contains protocol-internal receipt/share/account movements and is evidence
  only.
- Cycles expose `assetDenominatedPnlByAsset`,
  `assetDenominatedPrecisionByAsset`, `assetDenominatedReasonByAsset`, and
  `primaryAssetPnlSummary` as the stable frontend contract for non-stable and
  staked-stable PnL display.
- Large negative closed-cycle total PnL exposes `largePnlReasons[]` and
  `primaryLargePnlReason`. `largePnlReason` is a backwards-compatible alias for
  `primaryLargePnlReason`.
- Euler EVK/EVC account cycles may use `SHARE_RATE_EFFECT` when wallet-cash
  total PnL is available but the dominant loss is protocol share/account
  conversion. `SHARE_RATE_UNAVAILABLE` remains reserved for unavailable strict
  yield-attribution surfaces.
- `peakSupplyUsd`, `peakBorrowUsd`, and `durationDays` are read-model
  convenience fields for compact cycle cards.
- If supply interest cannot be separated from collateral price movement, PnL
  must be `UNAVAILABLE` with a concrete reason rather than a synthetic profit
  figure.
- PnL is also `UNAVAILABLE` for unresolved/orphan lifecycle fragments and for
  cycles with unresolved principal exits.

`current.shortfallSources` rules:

- derived from positive `quantityShortfallDelta` rows inside the same family
- sorted by descending accumulated shortfall quantity
- may include historical sources that were already partially or fully spent
  later; it is an audit hint, not a synthetic exact-parent proof
- `txHash` and `networkId` may be `null` for provider-native rows such as CEX
  inventory events

Notes:

- `timeline` is aggregated on read across the session's stable
  `accountingUniverseId`, not only the currently visible wallet subset
- `timeline` and `events` are display-oriented surfaces; canonical accounting
  truth still lives in underlying normalized rows and immutable ledger points
- `eventGroupId` is the display grouping key for one chart / overlay event
- `memberNormalizedTransactionIds` lists the child canonical rows that belong to
  that grouped display event
- frontend is allowed to apply additional chart-only grouping on top of
  `events`, as long as canonical rows and table/debug surfaces remain separate
- `current.totalCostBasisUsd` and `current.avcoUsd` are provable basis values
  for `current.coveredQuantity`
- `protocolName` / `protocolVersion`, when present on transaction-facing
  surfaces, are canonical best-effort labels sourced from either direct
  normalization-time registry hits or clarification-time protocol enrichment.
  Clarification-time enrichment may use the raw interacted tx recipient even
  when explorer transfer projections suppress `to` for economic classification.
  They are safe for filtering and debugging, but they are not economic status
  gates by themselves.
- `events` is the lightweight overlay surface for the UI
- `ledgerPoints` is the raw immutable replay trace for audit/debug
- the conceptual difference between canonical rows, lifecycle linking,
  display grouping, and `protocolName` enrichment is documented in
  [../pipeline/linking/02-rules-and-repairs.md](../pipeline/linking/02-rules-and-repairs.md)
- the request path is datastore-only; it performs no RPC or explorer calls

Errors:

- `404 SESSION_NOT_FOUND`
- `400 INVALID_REQUEST` when `familyIdentity` is blank
## Session Dashboard

- `GET /api/v1/sessions/{sessionId}/dashboard`
- Returns current session-scoped dashboard snapshot backed by `on_chain_balances` and latest `asset_ledger_points`
- current live quantities still come from the session wallet subset and
  `on_chain_balances WHERE sessionId = {sessionId}`
- `on_chain_balances` and `current_price_quotes` are refreshed by the background
  `PORTFOLIO_SNAPSHOT_REFRESH` pipeline stage after `ACCOUNTING_REPLAY`
  completes; the dashboard endpoint never performs live refresh work itself
- replay history used for timeline/debug may include broader accounting-universe
  members such as `BYBIT:<uid>`
- `tokenPositions` are current live quantities with conservative provable `avcoUsd` / PnL
- `summary.totalUnrealizedPnlPct` is based on provable covered basis only
- Dashboard valuation uses a current quote snapshot read model when available.
  It must not silently present the latest event-time historical AVCO quote as a
  live market price. When only historical fallback evidence exists, the row must
  expose that through quote metadata and issue state.
- `tokenPositions[].issue` is a read-time diagnostic class, not a persisted
  reconciliation collection state

Current dashboard token-position issue values:

- `yield_accrual`
  - live quantity is above covered quantity because an interest-bearing balance
    accrued after the last materialized tx
- `coverage_gap`
  - live quantity is above covered quantity and does not qualify as passive
    accrual
- `history_flags`
  - live quantity is fully covered, but latest replay point still carries
    incomplete-history or unresolved flags
- `missing_replay_point`
  - live balance exists, but no latest replay point exists for the exact bucket
- `missing_price`
  - reserved for price-availability diagnostics
- `stale_price`
  - current quote exists but is outside the dashboard freshness window
- `historical_price_fallback`
  - no current quote snapshot exists and valuation used a historical fallback
    quote for decomposition only

Current dashboard token-position quote fields:

- `coveredQuantity`: current quantity backed by replay evidence
- `marketValueUsd`: `quantity * priceUsd` when a price is present
- `priceSource`: source used for dashboard valuation, if any
- `pricedAt`: quote timestamp, if any
- `stalenessSeconds`: age of the selected quote at response time
- `isLiveQuote`: `true` only for current quote snapshot rows; `false` for
  historical fallback or missing-price rows
- `priceIssue`: price-specific issue state; separate from accounting coverage
  diagnostics in `issue`

Example token-position entry:

```json
{
  "familyIdentity": "FAMILY:ETH",
  "symbol": "ETH",
  "name": "Ethereum",
  "quantity": "3.065880428473694856",
  "coveredQuantity": "3.060000000000000000",
  "priceUsd": "1985.3",
  "marketValueUsd": "6086.69",
  "priceSource": "BINANCE",
  "pricedAt": "2026-04-25T09:00:00Z",
  "stalenessSeconds": 120,
  "isLiveQuote": true,
  "priceIssue": null,
  "avcoUsd": "2349.840198149151237779344109742893",
  "unrealizedPnlPct": "-15.51",
  "unrealizedPnlUsd": "-1115.49",
  "realizedPnlUsd": "0",
  "networkId": "MANTLE",
  "walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
  "issue": "yield_accrual"
}
```

## 9. LP and Lending refresh status

On-demand and background refresh for LP positions and lending groups is **asynchronous**. Clients trigger refresh with `POST`, poll status with `GET`, and reload snapshot read models when items reach `SYNCED`.

### Shared response: `RefreshStatusResponse`

```json
{
  "sessionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "anyActive": true,
  "items": [
    {
      "id": "lp-position:base:0xabc:12345",
      "status": "UPDATING",
      "trigger": "MANUAL",
      "requestedAt": "2026-06-29T19:00:00Z",
      "startedAt": "2026-06-29T19:00:01Z",
      "completedAt": null,
      "lastSyncedAt": "2026-06-29T18:30:00Z",
      "error": null
    }
  ]
}
```

| Field | Meaning |
|-------|---------|
| `id` | LP: `correlationId`. Lending: `groupId`. |
| `status` | `QUEUED`, `UPDATING`, `SYNCED`, `FAILED` |
| `trigger` | `MANUAL`, `BULK`, `SCHEDULED`, `REPLAY` |
| `anyActive` | `true` when any item is `QUEUED` or `UPDATING` |

### LP endpoints

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/api/v1/sessions/{sessionId}/lp/refresh-status` | `200` — `RefreshStatusResponse` |
| `GET` | `/api/v1/sessions/{sessionId}/lp/positions/{correlationId}?scope=active\|closed\|all` | `200` — single position (use after single-position refresh instead of full `/lp`) |
| `POST` | `/api/v1/sessions/{sessionId}/lp/refresh` | `202` — seeds bulk refresh for all open positions |
| `POST` | `/api/v1/sessions/{sessionId}/lp/positions/{correlationId}/refresh` | `202` — seeds single-position refresh |

Snapshot read model unchanged: `GET /api/v1/sessions/{sessionId}/lp?scope=active|closed|all`.

### Lending endpoints

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/api/v1/sessions/{sessionId}/lending/refresh-status` | `200` — `RefreshStatusResponse` (`id` = group key) |
| `POST` | `/api/v1/sessions/{sessionId}/lending/refresh` | `202` — seeds refresh for all open groups |
| `POST` | `/api/v1/sessions/{sessionId}/lending/groups/{groupKey}/refresh` | `202` — seeds single-group refresh |

Snapshot read model unchanged: `GET /api/v1/sessions/{sessionId}/lending`.

### Client polling guidance

- Poll `refresh-status` every **3s** while `anyActive=true`, else every **~25s** keepalive while the workspace page is open.
- On `UPDATING → SYNCED` for an item, re-fetch displayed values: **single** LP position → `GET .../lp/positions/{correlationId}`; bulk LP or lending → full snapshot GET.
- Background scheduled/replay refresh writes the same state rows; no separate client action required.

See [ADR-039](../adr/ADR-039-async-refresh-status.md).
