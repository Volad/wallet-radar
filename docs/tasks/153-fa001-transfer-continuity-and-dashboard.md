# Task 153 — FA-001 transfer continuity (increment 1) + Bybit dashboard

## Goal

Close the first implementation increment of financial audit **FA-001 R3** without hash-keyed production rules.

## Backend — done in increment 1

1. **D1:** `InternalTransferPairLinkService` admits pairing when **any `user_sessions` document** lists both wallet addresses (case-insensitive), OR existing `AccountingUniverse` pairwise membership.
2. **D2:** `BybitTransferContinuityRepairService` uses **relative qty tolerance ≤ 5×10⁻⁴**; on successful repair, **strip unit/value/price/PnL** from on-chain principal flows and set **TRANSFER** role (Bybit row unchanged).
3. **D3:** Add **Hyperlane / LI.FI Mantle proxy** entries to `protocol-registry.json`; `RegistryBridgeInboundTypeCorrectionService` promotes misclassified **inbound bridge receipts** (e.g. `VAULT_WITHDRAW` → `BRIDGE_IN`) when the interacted contract is registry-marked `BRIDGE_IN`.
4. **Dashboard:** `SessionDashboardQueryService` adds **token rows** for enabled **`BYBIT:*`** integrations from latest `asset_ledger_points` when `on_chain_balances` has no row.

## Backend — follow-up (increment 2, ADR-003)

- Mongo collection **`transfer_links`** (schema §4.3 of FA-001), idempotent writers for D1/D2/D3, cost-basis join, D2 **qty+time fallback** against `bybit_extracted_events`, cross-chain bridge linker §3.5.C.

## Frontend

- No API shape change required for increment 1; `tokenPositions` may include `walletAddress` values starting with `BYBIT:` — ensure UI shows them (labels optional).

## Verification

- Run `./gradlew :backend:test`.
- Full prod renormalization: `scripts/prod-reset-rebuild-backend.sh` (resets derived state per `scripts/avco/reset-derived.sh`).
