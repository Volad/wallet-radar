# Feature 8 & 9: Reconciliation (on-chain vs derived) + GET /assets with cross-wallet AVCO

**Tasks:** T-024, T-029

---

## T-024 — AssetController with reconciliation fields

- **Module(s):** api
- **Description:** Implement `GET /api/v1/assets?wallets=...&network=`: load asset_positions for wallets (and optional network filter). For each position compute or read: onChainQuantity (from on_chain_balances), derivedQuantity (= quantity), balanceDiscrepancy (onChainQuantity − derivedQuantity), reconciliationStatus (MATCH | MISMATCH | NOT_APPLICABLE), showReconciliationWarning (true if MISMATCH and wallet history within 2 years). Attach spotPrice (from cache), crossWalletAvco (CrossWalletAvcoAggregatorService). Return DTOs with all 04-api fields including reconciliation.
- **Doc refs:** 04-api (Get Asset List), 01-domain (Reconciliation), 02-architecture (Data Flow 6)
- **DoD:** Controller and DTOs; integration tests: GET returns onChainQuantity, derivedQuantity, balanceDiscrepancy, reconciliationStatus, showReconciliationWarning; no RPC on GET.
- **Dependencies:** T-015, T-016, T-012, T-020

## T-029 — Reconciliation status and showReconciliationWarning

- **Module(s):** costbasis or api (read path), domain
- **Description:** Ensure reconciliation is implemented for GET /assets: compute or store reconciliationStatus (MATCH / MISMATCH / NOT_APPLICABLE) by comparing on_chain_balances.quantity with asset_positions.quantity (tolerance ε). Set showReconciliationWarning true when MISMATCH and wallet history is within 2 years (e.g. oldest event within backfill window). GET /assets returns onChainQuantity, derivedQuantity, balanceDiscrepancy, reconciliationStatus, showReconciliationWarning.
- **Doc refs:** 01-domain (Reconciliation table), 04-api (Get Asset List — reconciliation fields), 02-architecture (Data Flow 6)
- **DoD:** Logic in read path or position update path; unit tests for status and warning; integration test in AssetController.
- **Dependencies:** T-012, T-015, T-024

---

## Acceptance criteria (Reconciliation)

- For each (wallet, network, asset) with both an `asset_positions` row and an `on_chain_balances` row (or equivalent), the system computes or stores: `derivedQuantity` (= quantity from AVCO/events), `onChainQuantity` (from last balance poll), `balanceDiscrepancy` = `onChainQuantity − derivedQuantity`, and a **reconciliation status**.
- **Reconciliation status** is one of: **MATCH** (|discrepancy| < tolerance ε), **MISMATCH** (|discrepancy| ≥ ε), **NOT_APPLICABLE** (e.g. no on-chain data, or wallet considered "older than 2 years" / incomplete history so correction not required).
- **showReconciliationWarning** is `true` only when status is **MISMATCH** and wallet history is "young" (e.g. first/last activity within the 2-year window), to suggest adding a manual compensating transaction.
- Tolerance ε is defined (e.g. config or constant) and applied consistently; documented for tests.
- "Wallet older than 2 years" (NOT_APPLICABLE for warning): e.g. oldest event for that wallet×network is older than 2 years from now, or `hasIncompleteHistory` for that asset; then show comparison but do not set `showReconciliationWarning` to true for mismatch.

## Acceptance criteria (GET /assets)

- `GET /api/v1/assets?wallets=0xA,0xB&network=...` returns `200` with an array of asset position DTOs.
- Each DTO includes at least: `assetSymbol`, `assetContract`, `networkId`, `quantity` (derived), `derivedQuantity`, `onChainQuantity`, `balanceDiscrepancy`, `reconciliationStatus`, `showReconciliationWarning`, `perWalletAvco`, `crossWalletAvco`, `spotPriceUsd`, `valueUsd`, `unrealisedPnlUsd`, `realisedPnlUsd` / `totalRealisedPnlUsd`, `hasUnresolvedFlags`, `unresolvedFlagCount`, `hasIncompleteHistory`, `lastEventTimestamp`.
- **crossWalletAvco** is computed **on-request** for the exact set of wallets in the query, across **all networks** (network filter does not change crossWalletAvco). INV-04, INV-05, AC-08.
- **Zero RPC and zero heavy computation** on the GET request path (INV-10); data comes from stored positions, balance store, and in-memory/cached cross-wallet computation.
- Optional `network` filter only filters which positions are returned; it does not change the value of `crossWalletAvco` for an asset.
- Monetary fields are returned as strings (no float); timestamps in ISO 8601.

## User-facing outcomes

- User sees for each asset whether on-chain and derived quantities match; if they don't and history is young, user gets a clear warning and can add a manual compensating transaction.
- User sees a single asset list for the session with per-asset reconciliation, AVCO (per-wallet and cross-wallet), P&L, and flags; no loading delay from RPC.

## Edge cases / tests

- **Wallet with all activity within 2 years + mismatch:** status MISMATCH, `showReconciliationWarning === true`.
- **Wallet with oldest activity older than 2 years (or hasIncompleteHistory) + mismatch:** status can be MISMATCH or NOT_APPLICABLE per product rule; `showReconciliationWarning === false`.
- **No on-chain balance yet:** reconciliation status NOT_APPLICABLE or equivalent; no crash; `onChainQuantity` null or omitted.
- **Exact match:** status MATCH, `showReconciliationWarning === false`, `balanceDiscrepancy` 0 or within ε.
- **Internal transfer between session wallets:** both wallets' derived quantities are correct; reconciliation is per wallet; no double-count.
- **Single wallet:** `crossWalletAvco` equals `perWalletAvco` for that wallet.
- **Two wallets, same asset:** `crossWalletAvco` is the weighted average over the merged event timeline (excluding INTERNAL_TRANSFER).
- **Network filter:** response only includes positions for the chosen network; `crossWalletAvco` still computed across all wallets (and all their networks) in the request.
- **Asset with PRICE_UNKNOWN events:** `hasUnresolvedFlags` true, `unresolvedFlagCount` > 0; quantities and AVCO still present where computable.
- **Asset with hasIncompleteHistory:** `hasIncompleteHistory === true`; AVCO and P&L are from available events only.
