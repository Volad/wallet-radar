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
- when the address-level registry match is missing, a narrow selector fallback
  may still attach `protocolName = Aave` for `BORROW / REPAY` only when both
  conditions hold:
  - selector resolves to canonical `BORROW` or `REPAY`
  - current movement evidence contains `variableDebt*` or `stableDebt*`
    marker legs
- the current baseline-safe selector groups are:
  - `lendingDeposit`
  - `lendingWithdraw`
  - `borrow`
  - `repay`
- function-name markers remain parity-safe fallback inside the same resource
  contract
- audited Aave wrapped-token gateway selectors now also belong to the same
  `Aave` semantic contract:
  - `zkSync`
    - `0x80500d20` `withdrawETH(address,uint256,address)`
    - `0x02c205f0`
      `supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)`
    - `0x474cf53d` `depositETH(address,address,uint16)`
  - `Base`
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
- address-level registry remains the authoritative source for
  `protocolVersion`
- the selector + debt-marker fallback is allowed to set `protocolName = Aave`
  even when the exact pool address is still missing from the registry, but it
  must not infer `protocolVersion` or contract identity on its own

## Flow Rules

- reserve asset is the economic principal for borrow/repay
- debt token mint/burn remains continuity-only
- settlement or refund dust must not become synthetic economic legs
- simple `LENDING_DEPOSIT / LENDING_WITHDRAW` rows with one reserve-asset leg
  and one receipt-token leg replay as an atomic family-equivalent carry pair
  even when normalized flow order lists the receipt leg first
- audited rebasing WETH receipts on `Aave` require a stricter canonical split
  already during normalization:
  - audited receipt symbols:
    - `aEthWETH`
    - `aArbWETH`
    - `aLinWETH`
    - `aManWETH`
    - `aZksWETH`
  - `LENDING_DEPOSIT`
    - principal outbound remains `TRANSFER`
    - receipt inbound up to the same principal quantity remains `TRANSFER`
    - any positive receipt excess becomes `BUY`
  - `LENDING_WITHDRAW`
    - receipt outbound remains `TRANSFER`
    - underlying inbound up to the same receipt quantity remains `TRANSFER`
    - any positive underlying excess becomes `BUY`
  - this rule materializes rebasing yield at the touched tx instead of letting
    replay treat the whole receipt delta as principal continuity
- minor quantity drift between reserve asset and `aToken` leg is treated as
  quantity drift inside the same continuity family:
  - full source cost basis remains on the destination leg
  - only unmatched destination excess remains uncovered
- for the audited rebasing WETH receipts above, the "unmatched destination
  excess" must be emitted as explicit canonical acquisition, not left implicit
  inside one oversized `TRANSFER` flow
- on `zkSync`, an audited native-alias transfer to the audited system fee sink
  that exactly matches `gasUsed * gasPrice` is fee evidence and must not be
  emitted again as both transfer and fee
- audited gateway-native `ETH` and receipt-token pairs remain custody
  continuity within the audited `ETH` family; they are not unwrap / LP
  lifecycle events:
  - `zkSync`: `ETH <-> aZksWETH`
  - `Base`: `ETH <-> AWETH`

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
  override audited Aave wrapped-token gateway selectors on supported networks
- do not treat `variableDebt*` / `stableDebt*` marker legs alone as universal
  protocol proof
- do not treat bare `borrow(...)` / `repay(...)` selector hits without
  Aave-style debt markers as sufficient to set `protocolName = Aave`

## Baseline and Regression Anchors

- borrow/repay role parity
- debt-marker suppression parity
- `85` current `Aave` normalized rows in the ADR-001 baseline:
  `35` `LENDING_DEPOSIT`, `21` `LENDING_WITHDRAW`, `17` `BORROW`,
  `12` `REPAY`
