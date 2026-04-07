# ADR-001 Final Naming Polish

Status: Completed
Date: 2026-03-31

Goal:

Close the last low-severity polish residual after architect re-review by
removing the most visible transitional runtime naming that no longer reflects
the final `ADR-001` architecture.

Related inputs:

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [Architect Review Handoff](57-adr001-architect-review-handoff.md)

## Scope

In scope:

- rename the remaining runtime classes whose names still advertise the old
  migration phase rather than their actual responsibility
- rename the shared parity flow helper to a final neutral runtime name
- update runtime comments and task docs so they no longer describe the active
  architecture as "transitional" where the migration is already complete
- keep baseline parity at `0 diff`

Out of scope:

- any behavioral classification or clarification change
- broad package re-layout
- accounting or pricing semantics

## Implemented Changes

- `PreSpamDefaultClassifier` → `PreSpamUnknownClassifier`
- `PreSpamNonEconomicClassifier` → `PreSpamAdminConfigClassifier`
- `TransitionalFlowSupport` → `ParityFlowSupport`
- runtime comments were rewritten from migration-phase wording to stable owner
  wording where needed for the final handoff

## Verification

- targeted compile/tests green
- baseline verify green:
  [verify-v56 row_diff_summary.json](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-31-verify-v56/diff/row_diff_summary.json)

Result:

- `baselineRowCount=5549`
- `currentRowCount=5549`
- `addedCount=0`
- `removedCount=0`
- `changedCount=0`
