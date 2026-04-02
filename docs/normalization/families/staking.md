# Staking Family Rules

Status: Active family rule scaffold

## Scope

Own classic staking deposit/withdraw lifecycle and staking-related request /
settlement families.

## Owned Normalized Types

- `STAKING_DEPOSIT`
- `STAKING_WITHDRAW_REQUEST`
- `STAKING_WITHDRAW`

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
- explicit reward side-flows may remain economic
- liquid staking may still be economic where the approved accounting contract
  says so

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
