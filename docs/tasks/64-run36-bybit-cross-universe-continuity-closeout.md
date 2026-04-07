# Run 36 Bybit Cross-Universe Continuity Closeout

Status: In Progress

Goal:

Close the remaining `Bybit <-> on-chain` continuity blocker that still prevents
full `AVCO / cost basis / move basis`, while preserving the already-correct
run/36 fixes:

- keep the current `Euler EVK` semantic improvements
- keep the restored `Velodrome` approve typing
- keep the restored `LI.FI` bridge typing
- resolve or explicitly policy-exclude the remaining `80` unmatched Bybit
  continuity rows

Related inputs:

- [run/36 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/36/clarification-readiness-audit.md)
- [run/36 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/36/audit_summary.json)
- [run/36 normalization rule updates](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/36/normalization_rule_updates.md)
- [Bybit protocol rules](../normalization/protocols/bybit.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

Run/36 confirms:

- all real regressions from run/35 are fixed
- current etalon drift is down to `28` rows:
  - `25` likely approved `Euler` semantic fixes
  - `3` non-blocking `LI.FI` metadata regressions

The dataset is still not ready for full accounting replay because:

- `80` non-excluded `BYBIT` rows still carry `BRIDGE_ON_CHAIN_LEG_NOT_FOUND`
- all `80` still have no `correlationId`
- all `80` still have no `matchedCounterparty`

That means:

- `pricing` can run
- full `AVCO / cost basis / move basis` cannot be considered ready

Important nuance:

- this `80`-row gap is inherited from the current etalon
- therefore “return to baseline” is not sufficient
- the actual requirement is continuity completeness, not just diff equality

## Scope

In scope:

- full inventory of the remaining `80` unmatched Bybit continuity rows
- deterministic grouping by:
  - `network`
  - `asset`
  - `direction`
  - `txHash`
- identification of the exact failure class per row:
  - on-chain leg exists and matcher misses it
  - on-chain leg exists but current rule set rejects it
  - on-chain leg does not exist in current raw coverage
- deterministic matcher improvements for rows whose on-chain leg already exists
- explicit policy decision for rows whose on-chain leg does not exist:
  - keep as active blocker
  - or move to excluded accounting lane
- rerun readiness prep after code changes

Out of scope:

- broad Bybit semantics redesign outside continuity matching
- reclassification of the current `11` excluded Bybit `NEEDS_REVIEW` rows
- reverting accepted `Euler` semantic improvements
- manual tx-hash-specific runtime logic

## Architecture Decision Slice

`system-architect` contract for this closeout:

1. The fix must stay deterministic and snapshot-first.
2. No paid indexer or external runtime dependency may be added.
3. Matching must come from existing Mongo evidence:
   - `external_ledger_raw`
   - `raw_transactions`
   - `normalized_transactions`
4. Runtime must not infer cross-universe continuity from loose time proximity
   alone.
5. If a row cannot be matched from production-available evidence, it must stay
   an explicit blocker or move to an explicit excluded lane.

## Acceptance Criteria (DoD)

1. The current `80` unmatched Bybit rows are fully partitioned into one of:
   - `MATCHABLE_WITH_EXISTING_ONCHAIN_ROW`
   - `RAW_ONCHAIN_COVERAGE_MISSING`
   - `RULESET_REJECTION`

2. For every row in `MATCHABLE_WITH_EXISTING_ONCHAIN_ROW`, the runtime must
   assign:
   - deterministic `correlationId`
   - deterministic `matchedCounterparty`
   on both sides where both normalized rows are in scope.

3. The matching rules must be data-driven and generic.
   - no hardcoded tx hashes
   - no one-off branch by audit-only transaction id

4. Rows in `RAW_ONCHAIN_COVERAGE_MISSING` must not remain ambiguous.
   Product policy must explicitly choose one of:
   - keep as active accounting blocker
   - move to `excludedFromAccounting = true`
   with a documented reason code

5. After rerun:
   - `BYBIT` rows with `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` in active accounting scope
     must be either `0` or formally approved as explicit blockers
   - if non-zero rows remain in active scope, the final report must say
     `readyForAvco = false`, `readyForCostBasis = false`, `readyForMoveBasis = false`
   - if they are fully matched or excluded by approved policy, the final report
     may promote readiness

6. Current good behavior must stay intact:
   - `11` former `Velodrome` approve regressions remain fixed
   - `3` former `LI.FI` bridge regressions remain fixed
   - `GMX` async invariants remain clean
   - current `Euler` semantic improvements remain intact

7. If `Euler` drift is formally approved, the etalon must be refreshed only
   after the Bybit continuity decision is complete, so we do not refresh the
   baseline while the main blocker is still unresolved.

## Edge Cases

- same `txHash` exists on both sides but asset identity differs:
  do not auto-carry same-asset basis
- transfer amount matches but network differs:
  do not correlate
- multiple candidate on-chain rows exist for one Bybit row:
  keep explicit blocker unless deterministic winner exists
- on-chain row exists but is itself excluded or unresolved:
  do not promote the Bybit row into ready continuity
- internal wallet-to-wallet movement inside the same accounting universe:
  keep continuity semantics, do not manufacture BUY/SELL
- unmatched rows that are later judged permanently unmatchable:
  must become explicit excluded policy rows, not silent active transfers

## Task Breakdown

1. `BE-R36-01` Build the 80-row continuity inventory
   - export all current non-excluded Bybit rows with
     `BRIDGE_ON_CHAIN_LEG_NOT_FOUND`
   - group by `network / asset / direction / txHash`
   - classify into:
     - matchable with current on-chain data
     - no on-chain raw coverage
     - ruleset rejection

2. `BE-R36-02` Implement deterministic matcher fixes
   - only for rows where current on-chain evidence already exists
   - materialize `correlationId` + `matchedCounterparty`
   - prove bidirectional materialization where both sides are normalized

3. `BE-R36-03` Define unresolved-row policy lane
   - if on-chain raw coverage is missing or deterministic matching is impossible,
     implement explicit policy:
     - keep as blocker
     - or exclude from accounting with a documented reason

4. `BE-R36-04` Regression-lock current good state
   - keep tests for:
     - `Velodrome` recovered `APPROVE`
     - `LI.FI` route-tagged `BRIDGE_OUT`
     - `GMX` async lifecycle invariants
     - current `Euler` semantic improvements

5. `BE-R36-05` Rerun and re-audit package
   - rerun:
     1. on-chain normalization
     2. bybit normalization
     3. clarification
   - run etalon diff
   - produce a new audit package
   - only after this decide whether the dataset is now accounting-ready

6. `BE-R36-06` Baseline refresh decision
   - if `Euler` semantic drift is formally approved and Bybit continuity blocker
     is resolved by match or exclusion policy, prepare the baseline-refresh task
   - do not refresh the baseline before the Bybit continuity decision is closed

## Current Implementation Notes

Current inventory from the settled post-run/36 dataset:

- `MATCHABLE_WITH_EXISTING_ONCHAIN_ROW = 55`
- `RAW_ONCHAIN_COVERAGE_MISSING = 25`
- `RULESET_REJECTION = 0`

Current backend remediation already implemented:

- Bybit normalization now performs a late exact rematch pass for
  `withdraw_deposit` rows already marked `onChainCorrelation.status = UNMATCHED`
  when the corresponding on-chain leg is now present in current Mongo evidence.
- The rematch remains strict:
  - same `txHash`
  - same `networkId`
  - persisted on-chain raw row exists
  - persisted on-chain normalized row exists

Current policy for the remaining `25` rows:

- keep them as explicit active blockers for now
- do not silently exclude them from accounting scope
- do not broaden matching with time-proximity or fuzzy heuristics

That means this task is only partially complete until rerun + re-audit confirms:

- the `55` stale unmatched rows are actually materialized as matched continuity
- the residual `25` blockers are still reported explicitly

## Risk Notes

- The main risk is fake continuity from overly broad matching. False positive
  correlation is worse than leaving a row blocked.
- A second risk is hiding the blocker by exclusion without explicit policy
  approval. That would improve readiness metrics artificially while losing real
  accounting scope.
- The correct order is:
  1. inventory
  2. deterministic matching
  3. explicit policy for truly unmatched rows
  4. rerun
  5. re-audit
  6. only then baseline refresh
