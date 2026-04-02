# Run 44 Across Bridge Continuity Closeout

Status: Done

Goal:

Close the remaining `run/44` accounting blocker by deterministically pairing the
audited high-confidence same-wallet `Across` bridge rows that already normalize
into one-sided `BRIDGE_OUT` / `BRIDGE_IN` legs but still miss
`correlationId` / `matchedCounterparty`.

Related inputs:

- [run/44 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/44/clarification-readiness-audit.md)
- [run/44 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/44/audit_summary.json)
- [run/44 bridge correlation summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/44/bridge_correlation_summary.json)
- [run/44 bridge candidate pairs](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/44/bridge_candidate_pairs.json)
- [Bridge family rules](../normalization/families/bridge.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

`run/44` shows that the previous `LI.FI` and `Bybit external custody` blockers
are no longer the main accounting risk. The live blocker moved to same-wallet
bridge continuity:

1. `39` bridge rows still miss `correlationId`
2. `6` candidate same-wallet bridge pairs were found by audit
3. `5` of those are already high-confidence `Across`-style pairs:
   - same tracked wallet
   - `BRIDGE_OUT` source already protocol-proven as `Across`
   - same asset family (`USDC`)
   - different networks
   - very tight timestamp window (`1-4 sec`)
   - tiny quantity drift (`0.01%-0.03%`)

Because the pair is not materialized, replay still cannot carry move basis
deterministically across those bridge legs.

## Scope

In scope:

- high-confidence same-wallet `Across` bridge pairing
- deterministic post-clarification reconciliation sweep
- optional immediate runtime linking when a qualifying source or destination row
  is normalized
- accounting/docs updates for deterministic same-wallet bridge continuity
- rerun preparation

Out of scope:

- fuzzy bridge discovery
- low-confidence / long-gap bridge guesses
- automatic pairing for non-`Across` bridge families without protocol proof
- baseline refresh

## Acceptance Criteria (DoD)

1. A protocol-proven `Across` `BRIDGE_OUT` row may be paired with a same-wallet
   `BRIDGE_IN` row only when all of the following are true:
   - both rows are `ON_CHAIN`
   - source is `BRIDGE_OUT`
   - destination is `BRIDGE_IN`
   - source `protocolName` proves `Across`
   - wallet address is identical
   - networks differ
   - both rows expose exactly one principal non-fee flow
   - principal asset family is identical under
     `BridgeAssetFamilySupport.continuityIdentity(...)`
   - timestamp delta is within a narrow audited window
   - quantity drift is within a narrow audited tolerance
   - there is exactly one qualifying destination candidate

2. When such a pair is proven:
   - both rows receive the same deterministic `correlationId`
   - both rows receive `matchedCounterparty`
   - `continuityCandidate` is set from current canonical flow evidence only
   - no type promotion/demotion happens outside the proven bridge pair

3. The bounded sweep is rerun after clarification and can repair previously
   materialized one-sided `Across` bridge rows without a new backfill.

4. Negative safeguards remain intact:
   - ambiguous multiple candidates do not auto-link
   - same-network rows do not auto-link
   - low-confidence long-gap pairs do not auto-link
   - non-`Across` bridge rows do not enter this pairing path

5. After rerun preparation:
   - raw backfill evidence remains intact
   - derived collections are cleared
   - raw normalization state is reset to pending

## Edge Cases

- source already has `matchedCounterparty`, destination is still missing
- destination already exists with a different `correlationId`
- multiple `BRIDGE_IN` candidates land in the same narrow time window
- family-equivalent stable wrappers still follow current bridge asset-family
  policy
- low-value medium-confidence pairs remain unresolved and visible to audit

## Task Breakdown

1. `BE-R44-01` Add deterministic same-wallet `Across` bridge pair linker
2. `BE-R44-02` Wire the linker into clarification post-processing and immediate
   lifecycle linking
3. `BE-R44-03` Lock regression coverage for:
   - high-confidence `Across` pair materialization
   - ambiguous candidate rejection
   - clarification job post-processing integration
4. `BE-R44-04` Update bridge/accounting docs with audited same-wallet bridge
   continuity rules
5. `BE-R44-05` Prepare rerun
   - clear derived state
   - reset raw normalization statuses
   - keep valid raw backfill evidence intact

## Risk Notes

- This task intentionally closes only the audited high-confidence `Across`
  subset from `run/44`.
- The remaining medium-confidence bridge candidate stays unresolved on purpose
  until stronger evidence exists; replay must not widen into fuzzy matching.

## Completion Notes

- Added a bounded `AcrossBridgePairLinkService` for deterministic same-wallet
  bridge pairing.
- Clarification post-processing now reconciles both `LI.FI` and `Across`
  bridge tails.
- Accounting docs now explicitly allow audited same-wallet bridge continuity
  without official route ids only when protocol proof, asset family, time
  window, and quantity tolerance all agree.
