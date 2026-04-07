# ADR-001 Post-Review Boundary Remediation

Goal:

Close the architect-review findings raised after the initial `ADR-001`
implementation stream while preserving exact row-level parity against the
approved Mongo baseline.

Related inputs:

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [Execution Backlog](56-adr001-refactor-execution-backlog.md)
- [Architect Review Handoff](57-adr001-architect-review-handoff.md)

## Review Findings To Close

1. Protocol-specific GMX / CoW semantics still leak into family classifiers.
2. The remaining registry stage is still too transitional and must be reduced to
   split owners or explicit residual follow-ups before final-state completion
   claims can be accepted.
3. Architecture tests do not yet fully enforce the intended raw-view and
   family-vs-protocol boundaries.

## Required Outcomes

- Family classifiers must not import protocol-specific classifier classes or
  protocol-specific support utilities.
- Family classifiers must consume generic semantic hints only.
- Protocol semantic classifiers must own protocol selectors, topics, calldata
  pattern matching, and protocol lifecycle correlation rules.
- `RawTransaction` must not be exposed through classification or protocol
  semantic contexts; downstream classification logic must read through
  `OnChainRawTransactionView` only.
- Architecture tests must fail if protocol-specific code leaks back into family
  classifiers.
- Existing baseline parity must remain `0 diff`.

## Task Breakdown

1. `BE-ADR001-R1` Reopen backlog and handoff status
   Outcome: mark previous sign-off as premature and point to this remediation
   slice as the new gating work item.

2. `BE-ADR001-R2` Generic semantic-hint contract
   Outcome: extend protocol semantic hints so family classifiers can consume
   family-owned suggestions without importing protocol-local constants.

3. `BE-ADR001-R3` Remove protocol-specific leakage from family layer
   Outcome: refactor `TradingClassifier`, `GmxLpClassifier`, `StakingClassifier`,
   and `LendingClassifier` to consume generic semantic hints and stop importing
   protocol-local classifier classes or protocol-owned support utilities.

4. `BE-ADR001-R4` Raw-view boundary hardening
   Outcome: remove `RawTransaction` from classification/protocol semantic
   contexts and tighten discovery/semantic APIs to accept the typed view only.

5. `BE-ADR001-R5` Boundary test hardening
   Outcome: extend ArchUnit rules so family classifiers cannot depend on:
   - `..classification.onchain.protocol..`
   - `..classification.special..`
   - protocol-specific support classes such as `*Gmx*`, `*Cow*`, `*Euler*`,
     `*Resolv*`
   Depends on: `BE-ADR001-R2`, `BE-ADR001-R3`, `BE-ADR001-R4`.

6. `BE-ADR001-R6` Re-verify docs vs code
   Outcome: only after code changes land, re-evaluate whether any registry
   residual remains blocking for architect sign-off or should stay as an
   explicit follow-up.

## Acceptance Criteria

- [x] `TradingClassifier` no longer imports `CowSwapProtocolSemanticClassifier`,
      `GmxProtocolSemanticClassifier`, `CowSwapSupport`, or
      `GmxEventTopicSupport`.
- [x] `GmxLpClassifier` no longer imports `GmxProtocolSemanticClassifier` or
      `GmxEventTopicSupport`.
- [x] `StakingClassifier` no longer imports `ResolvProtocolSemanticClassifier`.
- [x] `LendingClassifier` no longer imports `EulerProtocolSemanticClassifier`.
- [x] `OnChainClassificationContext` no longer exposes `RawTransaction`.
- [x] `ProtocolSemanticContext` no longer exposes `RawTransaction`.
- [x] `ProtocolDiscoveryService` no longer requires `RawTransaction` input.
- [x] ArchUnit coverage fails on any future family-to-protocol leakage.
- [x] Baseline `VERIFY` remains zero-diff.

## Completion Status

The former `ProtocolRegistryClassifier` catch-all has been removed from active
runtime. Its branches are now split between:

- family-owned registry-backed classifiers for `Swap`, `LP`, `Lending`, and
  `Vault`
- protocol/registry adapter classifiers for special-handler dispatch,
  method-aware unsupported review, and explicit direct-type fallback

Baseline parity remains `0 diff` after the split.

This remediation slice is complete.

The follow-up residual tracked in
[60-adr001-registry-support-and-special-handler-finalization.md](60-adr001-registry-support-and-special-handler-finalization.md)
has now also been implemented baseline-safe, so this document remains as a
historical record of the first remediation pass only.
