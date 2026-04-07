# Pendle Protocol Rules

Status: Active protocol rule scaffold

## Scope

Cover Pendle router bundles, LP-style lifecycle, and reward/bundle cases where
continuity and economic reward legs coexist in one tx.

## Protocol-Local Resources

Planned resource file:

- `backend/src/main/resources/protocols/pendle.json`

Expected contents:

- router selector hints
- zap and bundle lifecycle hints
- clarification lookup hints

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted transfer evidence
- persisted full receipt when the bundle lifecycle requires it

## Lifecycle Shapes

- `LP_ENTRY`
- `LP_EXIT`
- `LP_FEE_CLAIM`
- `REWARD_CLAIM`
- bundle-specific handoff into `LendingClassifier` or `TradingClassifier` only
  when the approved rule doc says so

## Clarification Rules

- clarification is allowed for zap and bundle routes that current raw evidence
  cannot fully separate
- reward side-flow may remain economic while principal marker churn remains
  continuity-only

## Family Handoff

- primary ownership usually goes to `LpClassifier`
- reward side-flows may be emitted under `TransferClassifier` ownership where
  the approved family contract says so

## Disallowed Fallbacks

- do not collapse composite bundle rows into plain `REWARD_CLAIM`
- do not let marker churn remain synthetic `BUY` / `SELL`

## Baseline and Regression Anchors

- zap bundle parity
- reward/principal split parity
