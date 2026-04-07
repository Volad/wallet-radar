# Non-Economic Family Rules

Status: Active family rule scaffold

## Scope

Own explicitly non-economic on-chain actions that must not enter pricing or
basis replay.

## Owned Normalized Types

- `APPROVE`

Compatibility note:

- current runtime still materializes `ADMIN_CONFIG` as a compatibility output
  type
- longer-term architecture still prefers `ADMIN_CONFIG` as a reason code, not a
  family output type

## Current Runtime Rules

- fee-bearing claim admin action before promo-spam fallback

## Authoritative Evidence

- `OnChainRawTransactionView` tx-level calldata and method name
- persisted token/internal transfer arrays
- contract role from protocol registry or protocol-local resources

## Clarification Rules

- clarification is optional and only needed when a tx is ambiguous between
  non-economic setup and real economic action
- when ambiguity remains, classifier must not silently promote to an economic
  family

## Correlation Rules

- `correlationId` is normally absent
- `matchedCounterparty` is normally absent

## Disallowed Fallbacks

- do not classify pure allowance/setup/admin calls as `EXTERNAL_TRANSFER_*`
- do not emit `BUY` / `SELL` flows for zero-effect setup rows
- do not let address-only protocol family fallback override recovered
  selector-level `APPROVE` evidence when saved calldata already proves
  `approve(address,uint256)` and economic movement is absent

## Baseline Expectations

- zero drift in existing `APPROVE` behavior unless an approved semantic fix is
  documented
