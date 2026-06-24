# ADR-025 — Euler EVK per-vault market key

**Status:** Superseded by [ADR-036](ADR-036-contract-first-lending-market-key-and-live-debt.md) (2026-06-24)  
**Date:** 2026-06-07  
**Inputs:** `docs/tasks/lending-audit-2026-06-07-implementation-plan.md` (L-EULER-CROSS-ASSET-01)

## Context

Euler EVK vault cycles were grouped under a single `evk-account` market key per network. Multiple vault addresses on the same wallet therefore shared one cycle accumulator, mixing USDC and WETH vault flows and contaminating `netCashDeltaByAsset` across vaults.

Fluid already uses a per-vault key (`vault-{address[2..10]}`) derived from `matchedCounterparty`.

## Decision

For `EULER` protocol rows and open-position attachment:

1. **Transactions:** derive `marketAsset` from `matchedCounterparty` when it is a 20-byte hex address:
   - `evk-vault-{address[2..10]}`
2. **Open positions:** derive the same key from `AssetLedgerPoint.matchedCounterparty`, falling back to `OnChainBalance.assetContract` when the ledger point has no counterparty.
3. **Fallback:** when no vault address is available, keep `evk-account`.

`marketKey()` and `positionMarketAsset()` must use the same derivation so open balances attach to the correct vault history.

## Consequences

- CLOSED Euler cycles split by vault address; historical PnL per vault is isolated.
- Cross-vault contamination in a single cycle is eliminated.
- Existing cycles keyed as `evk-account` will regroup after rebuild; acceptance checks compare per-vault PnL within ±1%.

## Related

- `LendingMarketKeyResolver` (see [ADR-036](ADR-036-contract-first-lending-market-key-and-live-debt.md)) — single derivation for history + balance paths
- `SessionLendingQueryService.marketAsset()` delegates to resolver
- `docs/pipeline/normalization/rules/protocols/euler.md`
