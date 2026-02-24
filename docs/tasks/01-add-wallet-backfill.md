# Feature 1: Add wallet + backfill

**Tasks:** T-009, T-023

---

## T-009 — Backfill job and WalletAddedEvent

- **Module(s):** ingestion (job), api (wallet)
- **Description:** On `WalletAddedEvent` (e.g. after POST /wallets), run `BackfillJobRunner` on `backfill-executor`: per-network parallel backfill (2 years). Per network: fetch via adapter → classify → normalize → resolve historical price (chain) → flag → IdempotentEventStore.upsert → trigger AVCO recalc for affected wallet×asset. Update `sync_status` (progressPct, syncBannerMessage, lastBlockSynced). On all networks complete: set status COMPLETE, run InternalTransferReclassifier.
- **Doc refs:** 02-architecture (Data Flow 1 — Initial Wallet Backfill), 00-context (2-year backfill)
- **DoD:** BackfillJobRunner, event listener; unit tests with mocks; integration test: add wallet → backfill runs and sync_status progresses (can use small window).
- **Dependencies:** T-004, T-005, T-006, T-007, T-008, T-015 (AvcoEngine), T-017 (RecalcJob/event), pricing chain (T-020)

## T-023 — WalletController and SyncController

- **Module(s):** api
- **Description:** Implement `POST /api/v1/wallets` (body: address, networks): validate address and networks; upsert sync_status PENDING; publish WalletAddedEvent; return 202 with syncId/message. Implement `GET /api/v1/wallets/{address}/status?network=`: return sync status, progressPct, syncBannerMessage. Implement `POST /api/v1/sync/refresh` (body: wallets, networks): trigger incremental sync for given set; return 202.
- **Doc refs:** 04-api (Wallets, Trigger Manual Sync), 02-architecture (api)
- **DoD:** Controllers; integration tests: POST wallets → 202 and status RUNNING; GET status; POST sync/refresh → 202.
- **Dependencies:** T-009, T-010, T-011, T-014

---

## Acceptance criteria

- Given a valid EVM (0x…) or Solana (Base58) address and a list of supported networks, `POST /api/v1/wallets` returns `202` with a `syncId` and message that backfill has started.
- After the call, `GET /api/v1/wallets/{address}/status?network={networkId}` returns `status` in `PENDING` or `RUNNING` and a non-null `syncBannerMessage` until backfill for that wallet×network is complete.
- When backfill for a wallet×network finishes, `status` is `COMPLETE`, `syncBannerMessage` is `null`, and `backfillComplete` is `true` for that network.
- Raw transactions are stored with **idempotency** on `(txHash, networkId)`; re-ingestion of the same tx does not create duplicate raw or economic events.
- Economic events are produced and stored (via classifiers + normalizer); at least SWAP, TRANSFER, and INTERNAL_TRANSFER (when both wallets in session) are classified per domain.
- For each affected (wallet, asset), AVCO is recalculated in **strict `blockTimestamp` ASC** order; `asset_positions` reflect `quantity`, `perWalletAvco`, `totalCostBasisUsd`, `totalRealisedPnlUsd`.
- When the **chronologically first** event for an asset is a SELL or transfer-out, `asset_positions.hasIncompleteHistory` is `true`.
- When a second wallet is added and it is the counterparty of existing events, `InternalTransferReclassifier` reclassifies those events to `INTERNAL_TRANSFER` and AVCO is replayed for affected assets so that internal transfers do not change AVCO (INV-03).
- Invalid address or unsupported network returns `400` with a clear error code (`INVALID_ADDRESS` / `INVALID_NETWORK`).

## User-facing outcomes

- User can add a wallet and see backfill progress (banner, progress %) until completion, then a full portfolio for that wallet×network.
- User can add another wallet and see internal transfers between the two wallets classified correctly and portfolio aggregated.

## Edge cases / tests

- **Wallet with no transactions in 2-year window:** backfill completes quickly; `sync_status` is COMPLETE; no economic events; GET /assets returns empty or only positions from other networks.
- **First event for an asset is SELL:** `hasIncompleteHistory === true`; AVCO is computed from available events only; realised P&L is still computed for the SELL.
- **Duplicate tx (same txHash + networkId) ingested again:** no duplicate in `economic_events`; idempotent upsert behaviour.
- **PRICE_UNKNOWN:** event is stored with flag; quantity delta is applied in AVCO; `hasUnresolvedFlags` / `unresolvedFlagCount` reflect it; no crash.
- **Internal transfer:** both wallets in session → event(s) reclassified to INTERNAL_TRANSFER; source wallet quantity decreases (AVCO unchanged); destination quantity increases at source AVCO (or weighted average if dest already held the asset).
