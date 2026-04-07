# ADR-001 Registry Support and Special-Handler Finalization

Status: Completed, ready for architect re-review

Goal:

Close the remaining architect-review residuals after the `ProtocolRegistryClassifier`
split so the final `ADR-001` implementation can be handed off for architect
sign-off without overstating completion.

Related inputs:

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [Execution Backlog](56-adr001-refactor-execution-backlog.md)
- [Post-review remediation](58-adr001-post-review-boundary-remediation.md)
- [Protocol resource runtime contract](59-protocol-resource-runtime-contract-and-aave-realization.md)

## Problem Statement

The previous remediation removed the `ProtocolRegistryClassifier` catch-all from
active runtime and preserved exact baseline parity. However, architect review
still identified three residual gaps:

1. `ProtocolRegistryClassificationSupport` now concentrates cross-family
   decision logic for `Swap / LP / Lending / Vault / Bridge`.
2. `ProtocolSpecialHandlerClassifier` still delegates final type selection to
   `classification.special.*` handlers, which bypasses the target
   `protocol semantics -> family mapping` architecture.
3. Architecture tests still do not fully guard the new registry support /
   registry adapter surface against regrowth into another catch-all.

## Required Outcomes

- Family-owned registry-backed behavior must live in family-local owners or
  family-local support, not in a central cross-family support class.
- Protocol-specific special-handler behavior must move to explicit target owners:
  protocol semantic classifiers or explicit family-owned adapters.
- `classification.special.*` must stop being a hidden final-type decision layer.
- `onchain.protocol.registry` must remain a thin registry adapter layer only.
- Boundary tests must fail if:
  - family layer starts depending on `ProtocolRegistryClassificationSupport`
  - registry adapters start re-accumulating family-specific branching
  - special-handler logic leaks back outside the intended owner layer
- Existing baseline parity must remain `0 diff`.

## Acceptance Criteria (DoD)

- [x] `ProtocolRegistryClassificationSupport` no longer contains final-type
      decision helpers for `Swap / LP / Lending / Vault / Bridge`.
- [x] `SwapRegistryClassifier`, `LpRegistryClassifier`,
      `LendingRegistryClassifier`, and `VaultClassifier` own their family-local
      registry heuristics directly or through family-local support.
- [x] `ProtocolSpecialHandlerClassifier` is removed or reduced to a thin
      non-decision adapter that does not receive final normalized type from
      `classification.special.*`.
- [x] `Balancer`, `Pendle`, `Morpho`, and `GMX` special-handler semantics are
      moved into protocol semantics or explicit family-owned adapters.
- [x] `ClassificationArchitectureArchTest` fails if family classifiers depend on
      `ProtocolRegistryClassificationSupport` or if registry adapters depend on
      `classification.special.*` beyond the explicitly approved final shape.
- [x] [56-adr001-refactor-execution-backlog.md](56-adr001-refactor-execution-backlog.md)
      and [57-adr001-architect-review-handoff.md](57-adr001-architect-review-handoff.md)
      reflect the real residual status.
- [x] Baseline `VERIFY` remains zero-diff after each active runtime adoption
      step and for the final merged slice.

## Edge Cases

- In scope: a helper extraction reduces duplication but still centralizes
  cross-family decisions.
  Expected behavior: reject; move the rule to the owning family layer.
- In scope: a special handler cannot be safely translated in one step.
  Expected behavior: keep an explicit follow-up checkpoint and do not mark
  architect sign-off ready.
- In scope: one of the protocol-special flows becomes `UNKNOWN/NEEDS_REVIEW`
  after migration.
  Expected behavior: phase fails unless the diff is explicitly approved as a
  semantic fix.
- In scope: tests stay green but boundary rules no longer protect the new
  registry layer.
  Expected behavior: phase does not pass; architecture tests are part of DoD.
- Out of scope: changing accounting, pricing, AVCO, cost-basis, or move-basis
  semantics while doing this cleanup.

## Task Breakdown

1. `BE-ADR001-R7` Residual registry-support inventory
   Outcome: list every decision-bearing method still living in
   `ProtocolRegistryClassificationSupport` and assign a final owner:
   `Swap`, `LP`, `Lending`, `Vault`, protocol semantic layer, or explicit
   registry adapter.

2. `BE-ADR001-R8` Family-local registry support split
   Outcome: move family-specific helpers out of
   `ProtocolRegistryClassificationSupport` into family-local classes or
   family-local support.
   Includes at minimum:
   - swap-like router shape
   - position-manager / dex-stake LP paths
   - lending-pool direct type resolution
   - vault direct type resolution

3. `BE-ADR001-R9` Special-handler target-owner migration
   Outcome: remove final-type ownership from `classification.special.*`.
   Required protocol slices:
   - `Balancer`
   - `Pendle`
   - `Morpho`
   - any still-active `GMX` special path
   Target shape:
   - protocol semantics emit hints, or
   - explicit family-owned adapters choose the final normalized type

4. `BE-ADR001-R10` Registry adapter narrowing
   Outcome: limit `onchain.protocol.registry` runtime to thin adapter behavior
   only.
   Allowed responsibilities:
   - registry lookup
   - thin unsupported-method review sink
   - explicit direct-type mapping where the type is already fully declared by
     registry data
   Not allowed:
   - family-specific heuristics
   - special-handler final-type branching

5. `BE-ADR001-R11` ArchUnit hardening v2
   Outcome: extend architecture tests so they fail if:
   - family layer depends on `ProtocolRegistryClassificationSupport`
   - family layer depends on `..onchain.protocol.registry..` except through
     approved stable contracts
   - registry adapter layer depends on `classification.special.*` after the
     special-handler migration is complete

6. `BE-ADR001-R12` Docs and handoff correction
   Outcome: update:
   - [56-adr001-refactor-execution-backlog.md](56-adr001-refactor-execution-backlog.md)
   - [57-adr001-architect-review-handoff.md](57-adr001-architect-review-handoff.md)
   - [58-adr001-post-review-boundary-remediation.md](58-adr001-post-review-boundary-remediation.md)
   so the repo no longer claims architect-ready completion prematurely.

7. `BE-ADR001-R13` Final parity bundle for this slice
   Outcome: run:
   - `compileJava`
   - `compileTestJava`
   - targeted classifier / architecture tests
   - baseline `VERIFY`
   and attach the resulting zero-diff artifact package.

## Completion Status

This slice is complete.

Implemented changes:

- removed `ProtocolRegistryClassificationSupport`
- split registry-backed decision helpers into family-local owners/support
- removed `ProtocolSpecialHandlerClassifier`
- removed `classification.special.*` from runtime and from the codebase
- moved `Balancer`, `Pendle`, `Morpho`, and `GMX exchange-router` special paths
  into protocol semantics plus family-owned semantic adapters
- narrowed `onchain.protocol.registry` to thin review/direct-type adapters only
- hardened ArchUnit so family layer cannot depend on
  `..classification.onchain.protocol.registry..`

Verification bundle:

- targeted tests green
- baseline verify green:
  [verify-v55 row_diff_summary.json](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-31-verify-v55/diff/row_diff_summary.json)

Additional post-review cleanup completed after the first re-review:

- family semantic adapters no longer gate on registry `specialHandler` state
- discovery is now authoritative for `Balancer`, `Pendle`, `Morpho`, and
  `GMX exchange-router` special-route semantics
- test-only `OnChainClassifier` wiring now uses the same discovery contract as
  runtime

Final result:

- `baselineRowCount=5549`
- `currentRowCount=5549`
- `addedCount=0`
- `removedCount=0`
- `changedCount=0`

## Supported vs Unsupported

- Supported:
  - architectural cleanup that preserves exact normalized-row parity
  - moving logic between owners without changing accounting semantics
  - new family-local helpers and new protocol semantic hints
  - stronger architecture tests
- Unsupported:
  - hash hardcodes
  - broad semantic rewrites
  - skipping phase gates
  - changing pricing / AVCO / cost-basis policy in this slice

## Risk Notes

- Main risk: replacing one catch-all class with one catch-all support utility.
- Main control: every extraction step must preserve zero row-level diff.
- Main review gate: this slice is not complete until architect re-review passes.
