# Aave Protocol Rules

Status: Active protocol rule doc and active runtime profile

## Scope

Cover Aave-style deposit, withdraw, borrow, and repay lifecycles, including
debt-marker and chain-specific settlement/refund legs.

## Protocol-Local Resources

Active runtime profile:

- `backend/src/main/resources/protocols/aave.json`

Current intended runtime ownership:

- pool method selectors
- gateway method selectors for chain-specific native-ETH entry / exit paths
- protocol-wide function-name markers
- debt-token / aToken marker groups
- event-name groups for `Supply / Deposit / Withdraw / Borrow / Repay`
- clarification hints

Current active runtime usage:

- `LendingRegistryClassifier` reads `aave.json` before generic selector
  fallback for `LENDING_DEPOSIT / LENDING_WITHDRAW / BORROW / REPAY`
- the current baseline-safe selector groups are:
  - `lendingDeposit`
  - `lendingWithdraw`
  - `borrow`
  - `repay`
- function-name markers remain parity-safe fallback inside the same resource
  contract
- audited `zkSync` gateway selectors now also belong to the same `Aave`
  semantic contract:
  - `0x80500d20` `withdrawETH(address,uint256,address)`
  - `0x02c205f0`
    `supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)`
  - `0x474cf53d` `depositETH(address,address,uint16)`

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted transfer evidence
- protocol-local support for debt-marker recognition

## Lifecycle Shapes

- `LENDING_DEPOSIT`
- `LENDING_WITHDRAW`
- `BORROW`
- `REPAY`

## Clarification Rules

- clarification is optional and used mainly for composite or containerized calls
- simple pool interactions should remain classifiable from current raw evidence

## Flow Rules

- reserve asset is the economic principal for borrow/repay
- debt token mint/burn remains continuity-only
- settlement or refund dust must not become synthetic economic legs
- on `zkSync`, an audited native-alias transfer to the audited system fee sink
  that exactly matches `gasUsed * gasPrice` is fee evidence and must not be
  emitted again as both transfer and fee
- gateway-native `ETH` and receipt-token `aZksWETH` remain custody continuity
  within the audited `ETH` family; they are not unwrap / LP lifecycle events

## Family Handoff

- current active runtime still routes most Aave rows through registry-backed
  lending pool classification
- protocol resource markers are allowed to support that path as long as baseline
  parity stays zero
- a future dedicated `AaveProtocolSemanticClassifier` is allowed, but only with
  explicit parity verification

## Disallowed Fallbacks

- do not emit debt-marker `BUY` / `SELL`
- do not let generic transfer fallback override proven Aave semantics
- do not let generic `UNWRAP`, `LP_EXIT`, or residual heuristic fallback
  override audited `zkSync` Aave gateway selectors

## Baseline and Regression Anchors

- borrow/repay role parity
- debt-marker suppression parity
- `85` current `Aave` normalized rows in the ADR-001 baseline:
  `35` `LENDING_DEPOSIT`, `21` `LENDING_WITHDRAW`, `17` `BORROW`,
  `12` `REPAY`
