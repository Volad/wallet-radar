# Lending Family Rules

Status: Active family rule scaffold

## Scope

Own lending deposit/withdraw flows, borrow/repay, and lending loop lifecycle.

## Owned Normalized Types

- [`LENDING_DEPOSIT`](../../../../reference/transaction-types.md#lending-deposit)
- [`LENDING_LOOP_OPEN`](../../../../reference/transaction-types.md#lending-loop-open)
- [`LENDING_LOOP_REBALANCE`](../../../../reference/transaction-types.md#lending-loop-rebalance)
- [`LENDING_LOOP_DECREASE`](../../../../reference/transaction-types.md#lending-loop-decrease)
- [`LENDING_LOOP_CLOSE`](../../../../reference/transaction-types.md#lending-loop-close)
- [`LENDING_WITHDRAW`](../../../../reference/transaction-types.md#lending-withdraw)
- [`BORROW`](../../../../reference/transaction-types.md#borrow)
- [`REPAY`](../../../../reference/transaction-types.md#repay)

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted transfer evidence
- protocol semantic hints from protocol-local classifiers
- clarification evidence for composite `batch(...)` / bundle lifecycles

## Clarification Rules

- clarification is allowed when current raw evidence cannot distinguish simple
  deposit/withdraw from composite borrow-backed loop behavior
- clarification must not fabricate internal lifecycle steps that are not
  reconstructable from production evidence

## Flow Rules

- reserve-asset principal is the economic leg for `BORROW` / `REPAY`
- debt marker mint/burn and settlement refund legs remain continuity
- loop families are composite and must not be flattened into plain spot swaps

## Correlation Rules

- correlation is protocol-specific and optional
- exact pairing is needed only when the protocol lifecycle is explicitly async

## Receipt identity (read model + normalization)

`LendingReceiptIdentityService` is the shared source of truth for mapping a receipt token `(networkId, contractAddress)` to `{ protocol, underlyingSymbol, side }`. See [ADR-035](../../../adr/ADR-035-lending-receipt-identity-resolver.md).

Resolution priority:

1. **Derived index** — `lending_receipt_identity` Mongo collection, built from normalized deposit/withdraw/borrow/repay pairs (`receipt leg` + `underlying leg` + `protocolName`).
2. **Protocol registry** — pool/vault/Comet contract hit (`protocol-registry.json`).
3. **Grammar fallback** — consolidated rules in `LendingAssetSymbolSupport` (Aave/Euler indexed/Morpho/Fluid/Compound/Silo).

Normalization calls the same resolver to (a) index receipt contracts after classification and (b) fill `protocolName` when only a known receipt contract is touched. The lending UI (`SessionLendingQueryService`) indexes history on read so cycles reconcile without requiring a separate backfill job for already-normalized data.

Shared **lifecycle underlying** keys prevent false `unresolved_principal_exit` when deposits use underlying symbols (e.g. `WBTC`) and live balances use receipt tickers (e.g. `eWBTC-1`).

## Disallowed Fallbacks

- do not classify proven loop bundles as plain `LENDING_DEPOSIT`
- do not emit synthetic spot `BUY` / `SELL` for debt markers

## Baseline Expectations

- row-level parity is required for debt-marker suppression and loop lifecycle
  typing
- clarified Euler `batch(...)` loop families are runtime-owned by
  `LendingClassifier` during the strangler migration
