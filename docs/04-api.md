# WalletRadar — API Contract

> **Version:** v3 current backend surface
> **Last updated:** 2026-04-07
> **Status:** Active contract for the currently implemented REST endpoints

---

## 1. Scope

This document describes the API that is currently implemented in the backend.

In scope now:

- persisted session management
- persisted session settings and integrations
- wallet backfill creation
- wallet and session backfill-status reads
- session-level transaction-history reads
- session-level asset-ledger timeline reads

Not in scope in this contract:

- generic normalization, pricing, or portfolio-wide transaction-history read APIs
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
      "lastValidatedAt": "2026-04-07T10:00:00Z",
      "lastSyncAt": null,
      "lastError": null
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

Notes:

- `timeline` is aggregated on read across the session's stable
  `accountingUniverseId`, not only the currently visible wallet subset
- `current.totalCostBasisUsd` and `current.avcoUsd` are provable basis values
  for `current.coveredQuantity`
- `events` is the lightweight overlay surface for the UI
- `ledgerPoints` is the raw immutable replay trace for audit/debug
- the request path is datastore-only; it performs no RPC or explorer calls

Errors:

- `404 SESSION_NOT_FOUND`
- `400 INVALID_REQUEST` when `familyIdentity` is blank
## Session Dashboard

- `GET /api/v1/sessions/{sessionId}/dashboard`
- Returns current session-scoped dashboard snapshot backed by `on_chain_balances` and latest `asset_ledger_points`
- current live quantities still come from the session wallet subset and
  `on_chain_balances WHERE sessionId = {sessionId}`
- replay history used for timeline/debug may include broader accounting-universe
  members such as `BYBIT:<uid>`
- `tokenPositions` are current live quantities with conservative provable `avcoUsd` / PnL
- `summary.totalUnrealizedPnlPct` is based on provable covered basis only
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

Example token-position entry:

```json
{
  "familyIdentity": "FAMILY:ETH",
  "symbol": "ETH",
  "name": "Ethereum",
  "quantity": "3.065880428473694856",
  "priceUsd": "1985.3",
  "avcoUsd": "2349.840198149151237779344109742893",
  "unrealizedPnlPct": "-15.51",
  "unrealizedPnlUsd": "-1115.49",
  "realizedPnlUsd": "0",
  "networkId": "MANTLE",
  "walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
  "issue": "yield_accrual"
}
```
