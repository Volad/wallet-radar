# Trading Family Rules

Status: Active family rule scaffold

## Scope

Own async spot-order lifecycles and derivative order / position lifecycles.

## Owned Normalized Types

- [`DEX_ORDER_REQUEST`](../../../../reference/transaction-types.md#dex-order-request)
- [`DEX_ORDER_SETTLEMENT`](../../../../reference/transaction-types.md#dex-order-settlement)
- [`DERIVATIVE_ORDER_REQUEST`](../../../../reference/transaction-types.md#derivative-order-request)
- [`DERIVATIVE_ORDER_EXECUTION`](../../../../reference/transaction-types.md#derivative-order-execution)
- [`DERIVATIVE_ORDER_CANCEL`](../../../../reference/transaction-types.md#derivative-order-cancel)
- [`DERIVATIVE_POSITION_INCREASE`](../../../../reference/transaction-types.md#derivative-position-increase)
- [`DERIVATIVE_POSITION_DECREASE`](../../../../reference/transaction-types.md#derivative-position-decrease)

## Authoritative Evidence

- `OnChainRawTransactionView`
- saved calldata and subcall decode derived from top-level input
- persisted receipt logs
- related lifecycle evidence persisted by clarification
- protocol semantic hints from protocol-owned classifiers

## Clarification Rules

- clarification may fetch full receipts and related lifecycle txs when current
  production evidence requires terminal lifecycle closure
- clarification must remain bounded and protocol-aware

## Correlation Rules

- request/settlement or request/terminal pairing requires deterministic
  `correlationId`
- exact async spot pairs require bidirectional `matchedCounterparty`
- accepted derivative sibling-request asymmetry must be documented explicitly

## Disallowed Fallbacks

- do not let generic `EXTERNAL_TRANSFER_*` capture order requests or keeper
  executions
- do not let pool lifecycle fallback capture derivative order lifecycle

## Baseline Expectations

- row-level parity is required for:
  - terminal type selection
  - wallet-visible cashflows
  - `correlationId`
  - `matchedCounterparty`

## Current Runtime Scope

- CoW EthFlow request -> `DEX_ORDER_REQUEST`
- CoW GPv2 settlement -> `DEX_ORDER_SETTLEMENT`
- GMX derivative request -> `DERIVATIVE_ORDER_REQUEST`
- GMX derivative execution / cancel lifecycle -> protocol-semantic hint +
  trading family ownership
- GMX position increase / decrease rows -> `DERIVATIVE_POSITION_INCREASE` /
  `DERIVATIVE_POSITION_DECREASE`
