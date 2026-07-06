# Swap Family Rules

Status: Active family rule scaffold

## Scope

Own direct asset conversion and wrapper continuity at the swap-family level.

## Owned Normalized Types

- [`SWAP`](../../../../reference/transaction-types.md#swap)
- [`WRAP`](../../../../reference/transaction-types.md#wrap)
- [`UNWRAP`](../../../../reference/transaction-types.md#unwrap)

## Authoritative Evidence

- `OnChainRawTransactionView` tx-level calldata and method recovery
- persisted token transfers
- persisted internal/native transfers
- wrapper-contract identity from registry or protocol-local resources

## Clarification Rules

- clarification is allowed for routed/container txs when current raw evidence is
  insufficient to reconstruct the real buy/sell legs
- clarification must never invent `SWAP` if persisted evidence still proves only
  routed outbound custody

## Flow Rules

- `SWAP` requires deterministic sell-side and buy-side wallet-visible economic
  legs
- exact-out source-token refunds reduce the source `SELL`; they must not
  survive as independent `BUY` legs when the same tx already materializes the
  real destination asset
- `WRAP` and `UNWRAP` are continuity-preserving asset transforms, not generic
  swaps

## Correlation Rules

- no async pairing by default
- `correlationId` and `matchedCounterparty` are normally absent

## Disallowed Fallbacks

- do not let generic router presence force `SWAP`
- do not treat explicit bridge-start routes as `SWAP`
- do not let outbound-only routed txs remain `SWAP`

## Baseline Expectations

- `SWAP` must preserve wallet-visible buy/sell completeness
- `WRAP` / `UNWRAP` must preserve continuity semantics and never double-count

## Current Strangler Scope

- extracted runtime rules:
  - `paraswap exactAmountOut` with outbound movement -> `SWAP`
  - `paraswap exactAmountOut` with native destination, default/wallet
    beneficiary, and one unique embedded unwrap amount -> net source refund
    into `SELL` and recover native `BUY`
  - clarified `routeSingle` with distinct net inbound/outbound assets -> `SWAP`
  - clarified same-asset refund pattern on `paraswap exactAmountOut` -> `SWAP`
