# 78 — Run 1 Stat / Replay Closeout

## Context

Live audit `results/stat/run/1` shows:

- `normalization = READY`
- `clarification = READY`
- `pricing = READY`
- `stat validation / AVCO / cost basis / move basis = NOT_READY`

Current live state:

- `normalized_transactions = 5606`
- `PENDING_STAT = 3687`
- `CONFIRMED = 1890`
- `NEEDS_REVIEW = 29`
- `asset_positions = 0`
- `pendingPrice = 0`
- `pendingClarification = 0`
- `blockingNeedsReview = 0`

The current blocker is no longer data acquisition or pricing. It is the
handoff from `PENDING_STAT` into `CONFIRMED` and then into deterministic replay.

## Architect Decision

The architecture stays unchanged:

- event-driven pipeline remains:
  - `backfill`
  - `on-chain normalization`
  - `clarification`
  - `Bybit normalization`
  - `pricing`
  - `accounting replay`
- `PENDING_STAT` is still the contract boundary between pricing and replay
- replay still consumes `normalized_transactions WHERE status=CONFIRMED`

Required architecture-level correction:

- `StatValidationService` must validate only replay-relevant invariants
- it may not require principal market price on continuity-only `TRANSFER` legs
- continuity principal pricing remains replay-derived carry-over, not market
  valuation

## BA Scope / Acceptance Criteria

### DoD

1. `walletradar.costbasis.enabled = true` in runtime config.
2. Continuity-only principal transfer rows may promote from `PENDING_STAT` to
   `CONFIRMED` without `unitPriceUsd` on their principal legs.
3. The following families no longer fail stat validation purely because their
   principal `TRANSFER` legs have no market price:
   - `BRIDGE_*`
   - `LP_*`
   - `LENDING_*`
   - `VAULT_*`
   - `BORROW`
   - `REPAY`
   - async request / settlement continuity rows
4. `SWAP` rows still fail when `BUY` or `SELL` side is missing.
5. `BYBIT uta_derivatives` reward rows with raw `cashFlow` / `change` now
   persist canonical `BUY.quantityDelta`.
6. The `13` known broken ids from `results/stat/run/1` no longer fail
   `STAT_NO_NON_FEE_FLOW`.
7. `PRICE_UNRESOLVABLE` is removed when all replay-required non-fee flows on a
   row are actually priced.
8. After rerun:
   - `PENDING_STAT = 0`
   - no new active non-excluded `NEEDS_REVIEW` rows are created by stat
     validation
   - `asset_positions > 0`
   - unresolved-price rows still replay with incomplete-history flags instead
     of being dropped

### Edge Cases

- Case: `BRIDGE_OUT` has only principal `TRANSFER` and fee flow.
  Expected: stat validation promotes; replay carries basis and prices only fee.
- Case: `LP_ENTRY` contains principal `TRANSFER` plus reward `BUY`.
  Expected: principal transfer may stay unpriced; reward side must still be
  priced or `UNKNOWN`.
- Case: `BORROW` contains debt-marker `TRANSFER` plus underlying `BUY`.
  Expected: debt marker does not require price; underlying acquisition still
  does.
- Case: `uta_derivatives BONUS` row has no `quantityRaw` but has positive
  `cashFlow`.
  Expected: canonical `BUY.quantityDelta = cashFlow`.
- Case: a row still has stale `PRICE_UNRESOLVABLE` after all required prices are
  present.
  Expected: rerun pricing clears the reason.

## Backend Tasks

1. `BE-78-01` Policy-aware stat validation
   - update `StatValidationService`
   - price is required only on flows where `PriceableFlowPolicy.requiresMarketPrice(...)` is true
   - keep structural checks for role / quantity / swap side completeness

2. `BE-78-02` Bybit reward quantity repair
   - update `BybitCanonicalTransactionBuilder`
   - for `REWARD_CLAIM`, map quantity from:
     - `quantityRaw`
     - else `cashFlow`
     - else `change`
   - keep scope narrow to audited reward rows

3. `BE-78-03` Stale unresolved-price cleanup
   - make `PricingResultMapper.finalizePricing(...)` derive final unresolved
     state from replay-required flow reality, not only from step-local boolean

4. `BE-78-04` Enable replay stage
   - set `walletradar.costbasis.enabled = true`
   - ensure session resume / event path can enter `ACCOUNTING_REPLAY`

5. `BE-78-05` Regression tests
   - continuity row with null principal price promotes
   - malformed swap still demotes
   - Bybit bonus reward keeps quantity
   - stale unresolved-price reason clears after successful pricing
   - targeted replay smoke stays green

## Operational Follow-Up

After code lands:

1. clear derived state only
2. preserve raw and historical price cache
3. rerun `normalization -> clarification -> pricing -> accounting replay`
4. re-audit stat readiness

