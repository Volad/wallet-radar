# Vault Family Rules

Status: Active family rule scaffold

## Scope

Own custody and vault deposit/withdraw flows that are not better modeled as LP,
lending, or staking families.

## Owned Normalized Types

- [`VAULT_DEPOSIT`](../../../../reference/transaction-types.md#vault-deposit)
- [`VAULT_WITHDRAW`](../../../../reference/transaction-types.md#vault-withdraw)
- [`PROTOCOL_CUSTODY_DEPOSIT`](../../../../reference/transaction-types.md#protocol-custody-deposit)
- [`PROTOCOL_CUSTODY_WITHDRAW`](../../../../reference/transaction-types.md#protocol-custody-withdraw)

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted transfer evidence
- protocol role from registry or protocol-local resources

## Clarification Rules

- clarification is allowed when current evidence cannot distinguish vault
  custody from another protocol-owned lifecycle
- if protocol semantics prove LP, lending, or trading ownership, vault fallback
  must not win

## Correlation Rules

- optional and protocol-specific
- exact async pair linking is required only when the protocol exposes real
  request/settlement lifecycle

## Disallowed Fallbacks

- do not mix vault semantics into generic transfer fallback too early
- do not let vault fallback override clearer LP/lending/trading semantics

## Baseline Expectations

- custody families must remain continuity-safe and not fabricate spot economics
