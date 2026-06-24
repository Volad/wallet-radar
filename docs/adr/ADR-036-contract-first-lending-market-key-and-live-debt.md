# ADR-036 — Contract-first lending market key and live debt-token positions

## Status

Accepted (2026-06-24)

## Context

Phase 3 lending audit found two read-model defects in `SessionLendingQueryService`:

1. **False unresolved cycles (Euler/Morpho/Fluid):** `marketAsset` and `positionMarketAsset` diverged. Euler deposits keyed on underlying USDC while withdraws keyed on eToken contract; Morpho keyed on symbol; Fluid balance path used static `vault-account` while history used per-vault keys. Deposit/withdraw round-trips split into different cycles → `unresolved_principal_exit` / `unresolved_lifecycle`.
2. **Borrow value == principal (no accrued interest):** Live `variableDebt*` balances in `on_chain_balances` were skipped because the balance loop required an `AssetLedgerPoint`. The UI showed synthetic `borrowed − repaid` instead of outstanding debt including interest.

`LendingReceiptIdentityService` (ADR-035) resolves receipt identity but was optional in the query service and not used as the authoritative **market key** source.

## Decision

### 1. Single `LendingMarketKeyResolver`

Extract one resolver used by **both** the transaction history loop and the on-chain balance loop.

**Primary key:** receipt/share-token leg `assetContract` when `LendingReceiptIdentityService` positively identifies a lending receipt (not underlying blocklist).

**Fallback:** `matchedCounterparty` → `counterpartyAddress`, only when validated as a vault and excluding the Euler EVC singleton (`0xddcbe30a761edd2e19bba930a977475265f36fa1`) and underlying flow contracts.

**Encoding (unchanged intent, unified derivation):**

| Protocol | Key format |
|----------|------------|
| Aave | `account-pool` (collapsed) |
| Compound | `comet-base-market` (collapsed) |
| Euler | `evk-vault-{address[2..10]}` |
| Fluid / Morpho | `vault-{address[2..10]}` |
| Loop without vault | `evk-loop-account` / `loop-account` (only after receipt/fallback miss) |

**Multi-receipt txs:** side-scoped selection (SUPPLY cycle from supply receipt, BORROW from debt receipt); tie-break by largest `|quantityDelta|`, then contract lexicographically.

`LendingReceiptIdentityService` is **required** (no nullable constructor fallback).

### 2. Cycle closure refinement

Replace “receipt balance == 0 ⇒ resolved” with **cycle-level economic closure**: `CycleState.isFlat()` plus principal in/out netting at dust tolerance. Receipt balance ≤ dust is corroboration only.

**Closed ≠ PnL reconciled:** do not clear `pnlUnavailableReason` merely because on-chain receipt balance is zero when principal-out evidence is still missing.

### 3. Live debt-token BORROW positions

Materialize open borrow positions from `on_chain_balances` when the balance is a debt receipt (`variableDebt*`, or identity `side=BORROW`):

- `quantity` = live debt-token balance (includes accrued interest)
- **Price the underlying symbol** (USDC/USDE), not the debt-token ticker
- No `AssetLedgerPoint` required for debt
- Exclude debt rows from supply totals; `side=BORROW`
- Synthetic `borrowed − repaid` row only when no live debt balance exists; **zero live debt wins**
- Stale debt snapshot → flag-and-show (`stale` chip), do not hide accrued interest or silently revert to unflagged synthetic

## Consequences

- **Positive:** Euler/Morpho/Fluid deposit+withdraw round-trips share one `marketKey`; false unresolved warnings eliminated; borrow USD reflects live debt with interest.
- **Positive:** Single resolver prevents history/balance drift (ADR-025 parity goal).
- **Trade-off:** Morpho/Fluid cycles may regroup by vault contract after deploy; expected and covered by tests.
- **Trade-off:** Clear `lending_receipt_identity` on deploy recommended (cache reset, not a Mongo migration). Market keys are computed at query time — no renormalization required for correctness.
- **Supersedes mechanism in:** [ADR-025](ADR-025-euler-evk-market-key.md) (`matchedCounterparty`-first derivation replaced by receipt-contract-first resolver).

## Implementation

- `LendingMarketKeyResolver` — `backend/src/main/java/com/walletradar/lending/application/LendingMarketKeyResolver.java`
- `SessionLendingQueryService` — wires resolver; `addLiveDebtPosition`; cycle economic closure
- Tests: `LendingMarketKeyResolverTest`, `SessionLendingQueryServiceTest` (round-trip key parity, live debt vs synthetic, Aave/Compound stability)

## Related

- [ADR-035](ADR-035-lending-receipt-identity-resolver.md) — receipt identity oracle
- [ADR-025](ADR-025-euler-evk-market-key.md) — superseded mechanism
- `docs/frontend/lending-market.md`
- `docs/pipeline/normalization/rules/protocols/euler.md`, `morpho.md`, `fluid.md`
