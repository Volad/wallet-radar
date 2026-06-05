# Staking Family Rules

Status: Active family rule scaffold

## Scope

Own classic staking deposit/withdraw lifecycle and staking-related request /
settlement families.

## Owned Normalized Types

- [`STAKING_DEPOSIT`](../../../../reference/transaction-types.md#staking-deposit)
- [`STAKING_WITHDRAW_REQUEST`](../../../../reference/transaction-types.md#staking-withdraw-request)
- [`STAKING_WITHDRAW`](../../../../reference/transaction-types.md#staking-withdraw)

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted transfer evidence
- protocol-owned contract identity and lifecycle hints

## Clarification Rules

- clarification is allowed for request/settlement or burn-only unbonding flows
- clarification must not be used to reclassify liquid staking into classic
  staking if the current raw evidence already proves economic mint/redeem

## Flow Rules

- classic stake custody keeps principal as continuity `TRANSFER`
- liquid staking principal conversions keep both base-asset and derivative
  principal legs as continuity `TRANSFER` when current raw proves the same
  audited base-asset family
- explicit reward side-flows may remain economic
- do not realize disposal-style `SELL` / `BUY` on audited liquid staking
  conversions by default

## Correlation Rules

- request/settlement staking lifecycles require deterministic pairing when
  current evidence proves it

## Disallowed Fallbacks

- do not let classic staking principal become synthetic `SELL`
- do not let burn-only withdraw request become finalized withdraw before
  settlement is proven

## Baseline Expectations

- classic staking continuity and async request/settlement behavior must remain
  row-level stable
- current runtime scope explicitly includes audited `Resolv` unstake request /
  settlement lifecycle through protocol semantics plus staking family ownership
