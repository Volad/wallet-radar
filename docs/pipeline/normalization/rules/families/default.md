# Default Family Rules

Status: Active family rule scaffold

## Scope

Own the final sink when no earlier family or protocol rule can classify the
transaction safely.

## Owned Normalized Types

- [`UNKNOWN`](../../../../reference/transaction-types.md#unknown)

## Authoritative Evidence

- all earlier family and protocol decisions must have already passed or requested
  clarification

## Clarification Rules

- `UNKNOWN` is acceptable only when current raw plus permitted clarification
  evidence are still insufficient
- on-chain steady-state `NEEDS_REVIEW` is not acceptable for in-scope protocol
  families
- documented terminal stop conditions may remain `UNKNOWN + CONFIRMED` when the
  approved rule explicitly says the transaction is a non-economic dead-end and
  no further protocol lifecycle is expected

## Correlation Rules

- `UNKNOWN` normally has no lifecycle linking

## Current Extracted Rules

- documented terminal stop conditions:
  - zero-effect modify-liquidities
  - zero-log multicall
  - non-movement reward call
  - wrapper-only dead-end
  - unverified routed send dead-end
- claim-like inbound-only airdrop dead-end before promo-spam fallback
- pending redeem request dead-end before promo-spam fallback

## Disallowed Fallbacks

- do not use `UNKNOWN` to hide protocol-owned work that should be resolved by
  clarification
- do not use `UNKNOWN` to preserve lazy baseline parity when approved semantic
  fixes are already documented

## Baseline Expectations

- row-level drift in `UNKNOWN` counts or membership must be treated as a blocker
  unless explicitly approved
