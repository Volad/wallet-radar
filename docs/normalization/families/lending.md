# Lending Family Rules

Status: Active family rule scaffold

## Scope

Own lending deposit/withdraw flows, borrow/repay, and lending loop lifecycle.

## Owned Normalized Types

- `LENDING_DEPOSIT`
- `LENDING_LOOP_OPEN`
- `LENDING_LOOP_REBALANCE`
- `LENDING_LOOP_DECREASE`
- `LENDING_LOOP_CLOSE`
- `LENDING_WITHDRAW`
- `BORROW`
- `REPAY`

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

## Disallowed Fallbacks

- do not classify proven loop bundles as plain `LENDING_DEPOSIT`
- do not emit synthetic spot `BUY` / `SELL` for debt markers

## Baseline Expectations

- row-level parity is required for debt-marker suppression and loop lifecycle
  typing
- clarified Euler `batch(...)` loop families are runtime-owned by
  `LendingClassifier` during the strangler migration
