# ADR-001 Architect Review Handoff

Status: Architect review completed, ADR accepted
Date: 2026-03-31
Related ADR: [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
Execution backlog: [56-adr001-refactor-execution-backlog.md](56-adr001-refactor-execution-backlog.md)

## Review Goal

Confirm that the implemented `ADR-001` refactor reached the intended target
state without classification or clarification drift against the approved Mongo
baseline.

## Current Outcome

- on-chain classification now runs through `OnChainClassifier` as an
  orchestration-only entry point
- protocol semantic runtime slices are active for the currently in-scope
  protocol families
- special-handler routing for `Balancer`, `Pendle`, `Morpho`, and
  `GMX exchange-router` is driven from `ProtocolDiscoveryResult`, not from
  direct registry lookup inside protocol semantic classifiers
- family classifiers now own final type selection through family-local logic or
  generic semantic-hint mapping
- legacy classification path is deleted from runtime and from code
- clarification workflow is decomposed into shared preparation, failure,
  reclassification, workflow, and batch-drain handlers
- clarification services are thin delegates and no longer own repository or
  gateway wiring
- architecture boundaries are enforced with dedicated ArchUnit coverage for
  family-vs-protocol, family-vs-registry-adapter, and raw-view guards

## Baseline Evidence

Authoritative baseline package:

- [2026-03-29-baseline](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-29-baseline)

Key artifacts:

- [normalized_row_snapshot.ndjson](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-29-baseline/normalized_row_snapshot.ndjson)
- [normalized_row_snapshot.csv](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-29-baseline/normalized_row_snapshot.csv)
- [manifest.json](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-29-baseline/manifest.json)

Latest verification after remediation work:

- [verify-v55 row_diff_summary.json](/Users/vladislavkondratenko/projects/wallet-radar/backend/results/refactor-baseline/adr-001/2026-03-31-verify-v55/diff/row_diff_summary.json)

Final verify result:

- `baselineRows=5549`
- `currentRows=5549`
- `added=0`
- `removed=0`
- `changed=0`

## Acceptance Criteria Status

Acceptance criteria were reopened after the first architect review and are now
implemented through the two remediation slices:
- [58-adr001-post-review-boundary-remediation.md](58-adr001-post-review-boundary-remediation.md)
- [60-adr001-registry-support-and-special-handler-finalization.md](60-adr001-registry-support-and-special-handler-finalization.md)

Key preserved items:

- reproducible baseline export + diff + verify tooling
- protocol-local resources under `resources/protocols/*.json`
- centralized reason and clarification policy contracts
- orchestration-only `OnChainClassifier`
- protocol semantic classifiers for `Resolv`, `CoW`, `GMX`, `Euler`,
  `Balancer`, `Pendle`, and `Morpho`
- shared clarification workflow handlers and removal of legacy clarification path
- baseline-safe classification and clarification decomposition
- row-level parity preserved against the approved baseline
- removal of `classification.special.*` and the old special-handler runtime

## Review Focus

The architect review should focus on:

1. target package structure vs implemented package structure
2. boundary quality between orchestrator, protocol semantics, family
   classifiers, and clarification workflow handlers
3. whether any remaining transitional names or comments should be cleaned up in
   a follow-up polish pass
4. whether the remediation slices are sufficient to promote `ADR-001` from
   `Proposed` to an accepted/final status

## Suggested Review Inputs

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [55-adr001-classification-clarification-strangler-migration-plan.md](55-adr001-classification-clarification-strangler-migration-plan.md)
- [56-adr001-refactor-execution-backlog.md](56-adr001-refactor-execution-backlog.md)
- [README.md](../normalization/README.md)
- [OnChainClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/OnChainClassifier.java)
- [ClassificationArchitectureArchTest.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/test/java/com/walletradar/ClassificationArchitectureArchTest.java)
- [OnChainClarificationJob.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/job/clarification/OnChainClarificationJob.java)

## Final Outcome

- final architect review passed
- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md) is now
  accepted
- final sign-off package:
  [62-adr001-final-architect-signoff.md](62-adr001-final-architect-signoff.md)
