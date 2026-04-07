# ADR-001 Refactor Execution Backlog

Goal:

Execute the full `ADR-001` refactor of on-chain classification + clarification
until the legacy structure is completely removed and the resulting behavior
matches the approved Mongo baseline row-by-row.

This document is the executor-ready backlog for `backend-dev`.

Related inputs:

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [Migration Plan](55-adr001-classification-clarification-strangler-migration-plan.md)
- [Normalization Rules Index](../normalization/README.md)

## Current Progress Snapshot

- Baseline export/diff/verify harness is implemented and authoritative Mongo
  baseline is fixed at `5549` normalized rows.
- `OnChainClassifier` is already orchestration-only.
- Staged insertion-point execution is active and baseline-safe.
- Runtime-extracted families already include:
  - `AdminConfig`
  - `Wrap/Unwrap`
  - documented `UNKNOWN` stop conditions
  - pre-spam unknown/non-economic sinks
  - `Spam`
  - post-spam `NonEconomic`
  - reward-route / reward-claim early families
  - bridge settlement/start/method-aware families
  - `LP_FEE_CLAIM`
  - `Swap` clarified-economic paths
  - `Trading` for `CoW` + `GMX` derivatives
  - `Lending` for clarified Euler `batch(...)` loop/deposit/withdraw families
  - `Staking` for `Resolv` withdraw request/settlement
  - `GMX LP` async request/settlement lifecycle
  - split registry-backed owners for special-handler, swap, LP, lending,
    vault, and explicit direct-type fallback after post-spam review
  - resolved-warning `ADMIN_CONFIG` protocol-tail rule
- Post-non-economic fallback is now split into:
  - selector-backed fallback stage
  - function-name fallback stage
  - generic heuristic fallback stage
- Active runtime no longer falls back to legacy after the post-non-economic tail;
  extracted families are terminal for non-failed rows.
- Failed-execution terminal handling is now owned by an extracted family, so
  `OnChainClassifier` no longer depends on `LegacyFallbackClassifier` in active
  runtime orchestration.
- `LegacyFallbackClassifier` has been fully removed from the classification
  codebase; active runtime now executes only extracted family classifiers.
- `ClarificationPolicyService` now also owns full-receipt clarification
  eligibility and failure decision shaping for `OnChainReceiptClarificationService`.
- Clarification workflow now has a shared `ClarificationFailureHandler` for
  metadata/full-receipt attempt recording and failure transitions, so
  `OnChainClarificationService` and `OnChainReceiptClarificationService` no
  longer own duplicated `markFailure/safeAttempts` logic locally.
- Clarification rebuild/save/link flow now also has a shared
  `ClarificationReclassificationHandler`, so clarification jobs no longer own
  direct `builder + normalized repository + lifecycle link/discovery` mutation
  paths locally.
- Clarification jobs now also use a shared `ClarificationPreparationHandler`
  for raw lookup, receipt-fetch preparation, and receipt-eligibility checks, so
  `OnChainClarificationService` and `OnChainReceiptClarificationService` no
  longer own duplicated preparation-stage orchestration locally.
- Clarification workflow execution now also uses dedicated metadata and
  full-receipt workflow handlers, so `OnChainClarificationService` and
  `OnChainReceiptClarificationService` are reduced to thin service facades over
  shared batch + per-row orchestration.
- `OnChainClarificationJob` now also uses a shared batch drainer instead of
  owning duplicated metadata/full-receipt `while(true)` loops locally.
- Manual test-only constructors have been removed from clarification services;
  behavioral ownership now lives in workflow handlers, while services are
  strict delegates.
- Dedicated classification architecture ArchUnit tests now enforce that:
  - family classifiers do not depend on jobs, clarification pipeline, Mongo, or repositories
  - protocol semantic classifiers do not depend on jobs, clarification pipeline, Mongo, or repositories
  - clarification job/services do not depend on repositories or clarification gateways
- Protocol semantic runtime slices are now active for:
  - `Resolv` unstake request / settlement
  - `CoW` async request / settlement
  - `GMX` derivative request / execution lifecycle
  - `GMX` LP async request / settlement lifecycle
  - `Euler` clarified batch lending / loop lifecycle
- Each additional extraction must keep row-level diff at zero versus the fixed
  baseline unless an approved semantic fix is explicitly documented.
- Post-review remediation is tracked in
  [58-adr001-post-review-boundary-remediation.md](58-adr001-post-review-boundary-remediation.md)
  and the `ProtocolRegistryClassifier` catch-all has now been removed in favor
  of split registry-backed owners plus stricter boundary tests.
- Final residual cleanup after that split is now tracked in
  [60-adr001-registry-support-and-special-handler-finalization.md](60-adr001-registry-support-and-special-handler-finalization.md).
- `classification.special.*` and `ProtocolSpecialHandlerClassifier` are removed
  from the codebase; special-handler registry entries now resolve through
  protocol semantics plus family-owned semantic adapters or an explicit
  unsupported-method review sink.
- family semantic adapters no longer inspect registry `specialHandler` state
  directly; they react only to generic protocol semantic hints.
- protocol semantic classifiers for `Balancer`, `Pendle`, `Morpho`, and
  `GMX exchange-router` now consume `ProtocolDiscoveryResult` as the
  authoritative special-route source.

## Acceptance Criteria (Definition of Done)

- [x] A reproducible Mongo baseline package exists and includes row-level
      snapshots for every normalized transaction.
- [x] A deterministic export + diff tool exists for baseline vs rerun
      classification parity.
- [x] Protocol-local resource loading exists for the new architecture.
- [x] Centralized reason and clarification requirement contracts exist.
- [x] `OnChainClassifier` is reduced to orchestration-only responsibility.
- [x] Family classifiers own final type selection.
- [x] Protocol semantic classifiers own protocol-specific lifecycle semantics.
- [x] All raw reads happen only through `OnChainRawTransactionView`.
- [x] Family and protocol rule documents exist and are updated together with the
      implementation.
- [x] Every phase is validated against the baseline before the next phase starts.
- [x] Legacy classification and clarification path is fully removed from code.
- [x] Final output matches the approved baseline row-by-row except explicitly
      approved semantic fixes.
- [x] On-chain `NEEDS_REVIEW` does not increase because of the refactor.

Current note:

- Execution backlog is complete baseline-safe.
- Final architect review passed and `ADR-001` is accepted.

## Edge Cases (with scope)

- Case: count-level parity hides row-level drift | Scope: In | Expected behavior:
  phase fails until row-level diff is resolved.
- Case: protocol resource file is missing for a protocol already in scope |
  Scope: In | Expected behavior: protocol stays on legacy or extracted fallback
  path until resource and rule doc are in place.
- Case: rule is clear in legacy code but not documented | Scope: In | Expected
  behavior: developer must write/update the rule doc before extraction.
- Case: a migration phase improves behavior vs baseline | Scope: In | Expected
  behavior: diff must be explicitly approved as a semantic fix and documented.
- Case: clarification behavior diverges while classification stays the same |
  Scope: In | Expected behavior: phase fails because clarification policy is part
  of baseline parity.
- Case: protocol family is partially extracted | Scope: In | Expected behavior:
  `LegacyFallbackClassifier` still covers unresolved branches.
- Case: a protocol cannot be safely genericized yet | Scope: In | Expected
  behavior: keep dedicated protocol semantic classifier; do not force it into
  registry-only discovery.
- Case: `SPAM` remains emitted as `UNKNOWN` during transition | Scope: In |
  Expected behavior: allowed only with documented compatibility note and zero
  unapproved row-level drift.
- Case: developer wants to skip baseline tooling and start extraction directly |
  Scope: Out | Expected behavior: not allowed.

## Supported vs Unsupported

- Supported:
  - full strangler migration of on-chain classification and clarification
  - Mongo baseline export and diff tooling
  - protocol-local resources under `resources/protocols/*.json`
  - per-family and per-protocol rule documentation
  - incremental extraction with legacy fallback
- Unsupported:
  - big-bang rewrite
  - hash-based hardcodes
  - skipping phase gates
  - changing pricing/AVCO policy as part of this refactor
  - introducing new paid services or external rule engines

## Test Scenarios

- Happy path: Phase 0 baseline export run twice on the same DB yields identical
  manifests and row snapshots.
- Happy path: Extracted family keeps zero row-level diff against baseline.
- Edge: one approved semantic fix produces a bounded diff and is documented in
  the rule doc plus phase report.
- Edge: protocol-local resource is loaded and used without leaking selectors into
  generic family classifiers.
- Negative: extracted family changes row membership or `correlationId` silently;
  row-diff harness must fail the phase.
- Negative: clarification begins reading raw directly instead of through
  `OnChainRawTransactionView`; arch tests must fail.

## Task Breakdown (Executor-ready)

### Phase 0 — Baseline Harness and Rule-Doc Skeleton Lock

1. `BE-ADR001-00A` Baseline export service
   Outcome: implement a deterministic exporter for aggregate baseline artifacts
   and full `normalized_row_snapshot.ndjson/csv`. Depends on: none.

2. `BE-ADR001-00B` Row-level diff service
   Outcome: implement deterministic diff outputs for added/removed/changed rows,
   including field-level change summaries. Depends on: `BE-ADR001-00A`.

3. `BE-ADR001-00C` Manual tool runner
   Outcome: add a guarded manual backend runner for `export`, `diff`, and
   phase-gate `verify` modes so developers can generate artifacts without
   custom ad hoc scripts. Depends on: `BE-ADR001-00A`, `BE-ADR001-00B`.

4. `BE-ADR001-00D` Baseline regression tests
   Outcome: add unit tests for canonical row serialization, flow sorting,
   fingerprint generation, and diff semantics. Depends on: `BE-ADR001-00A`,
   `BE-ADR001-00B`.

5. `BE-ADR001-00E` Rule-doc synchronization checkpoint
   Outcome: validate that the `docs/normalization/**` skeleton is complete and
   referenced from the migration docs. Depends on: none.

### Phase 1 — Core Contracts Without Behavior Change

6. `BE-ADR001-01A` Introduce context and decision contracts
   Outcome: add `OnChainClassificationContext`, context factory,
   `ClassificationDecision`, and mapper contracts with no runtime behavior
   changes. Depends on: Phase 0 complete.

7. `BE-ADR001-01B` Add legacy fallback wrapper
   Outcome: move current monolith behind `LegacyFallbackClassifier` while public
   runtime output stays identical. Depends on: `BE-ADR001-01A`.

8. `BE-ADR001-01C` Arch tests for raw access boundaries
   Outcome: enforce that new classifiers operate on context/view only, not raw
   documents directly. Depends on: `BE-ADR001-01A`.

### Phase 2 — Discovery Layer Extraction

9. `BE-ADR001-02A` Structured discovery result
   Outcome: introduce `ProtocolDiscoveryService`, `ProtocolDiscoveryResult`, and
   `ProtocolMatch` with `0..N` matches. Depends on: Phase 1 complete.

10. `BE-ADR001-02B` Protocol-local resource loader
    Outcome: add loader contracts for `resources/protocols/*.json` and wire
    them to protocol semantic packages, not to generic family classifiers.
    Depends on: `BE-ADR001-02A`.

11. `BE-ADR001-02C` Discovery parity report
    Outcome: produce a report that proves discovery output matches legacy visible
    protocol tagging before any family extraction. Depends on:
    `BE-ADR001-02A`, `BE-ADR001-02B`.

### Phase 3 — Centralized Clarification and Reason Contracts

12. `BE-ADR001-03A` Centralize reason enums/constants
    Outcome: replace stringly-typed missing-data reasons with centralized
    reason/requirement contracts. Depends on: Phase 2 complete.

13. `BE-ADR001-03B` Centralize clarification policy
    Outcome: add `ClarificationPolicyService` / equivalent so persisted status
    transitions stay centralized. Depends on: `BE-ADR001-03A`.

14. `BE-ADR001-03C` Clarification parity tests
    Outcome: prove that centralization keeps existing clarification behavior
    unchanged on the baseline dataset. Depends on: `BE-ADR001-03A`,
    `BE-ADR001-03B`.

### Phase 4 — Spam and Non-Economic Extraction

Execution note:

- Staged insertion-point orchestration now exists and should be extended
  instead of reintroducing a single global family head.
- `ADMIN_CONFIG` is safe to extract before `Spam/NonEconomic`.
- `DefaultClassifier` may be extracted incrementally before the final end-of-chain
  shape exists, as long as each extracted `UNKNOWN` sink rule is inserted at the
  exact legacy parity point and baseline row parity remains zero.
- `Spam` and post-spam non-economic rules must not be moved to the global
  classifier head until all earlier legacy branches that currently precede them
  are also extracted or the orchestrator supports exact insertion-point
  parity. Otherwise row-level drift appears even if individual rule code stays
  unchanged.

15. `BE-ADR001-04A` Extract `SpamClassifier`
    Outcome: isolate spam recognition into its own family with no unapproved
    drift. Depends on: Phase 3 complete.

16. `BE-ADR001-04B` Extract `NonEconomicClassifier`
    Outcome: isolate `APPROVE` and other non-economic setup families while
    keeping `ADMIN_CONFIG` as reason code in target design. Depends on:
    `BE-ADR001-04A`.

17. `BE-ADR001-04C` Update family rule docs and fixtures
    Outcome: document exact evidence and add focused regression fixtures.
    Depends on: `BE-ADR001-04A`, `BE-ADR001-04B`.

### Phase 5 — Swap and Bridge Extraction

18. `BE-ADR001-05A` Extract `SwapClassifier`
    Outcome: move `SWAP/WRAP/UNWRAP` into family ownership without protocol
    literal leakage. Depends on: Phase 4 complete.

19. `BE-ADR001-05B` Extract `BridgeClassifier`
    Outcome: move bridge start/settlement semantics into family ownership while
    keeping protocol-specific bridge hints out of generic code. Depends on:
    `BE-ADR001-05A`.

20. `BE-ADR001-05C` Parity gate for swap/bridge
    Outcome: row-level parity proof and fixture coverage. Depends on:
    `BE-ADR001-05A`, `BE-ADR001-05B`.

### Phase 6 — LP, Vault, and Staking Extraction

21. `BE-ADR001-06A` Extract `LpClassifier`
    Outcome: move LP lifecycle and async request/settlement handling into family
    ownership. Depends on: Phase 5 complete.

22. `BE-ADR001-06B` Extract `VaultClassifier`
    Outcome: isolate vault and protocol custody families. Depends on:
    `BE-ADR001-06A`.

23. `BE-ADR001-06C` Extract `StakingClassifier`
    Outcome: isolate classic staking and staking withdraw request/settlement
    semantics. Depends on: `BE-ADR001-06B`.

24. `BE-ADR001-06D` LP/Vault/Staking parity gate
    Outcome: prove row-level parity for flow roles, async linkage, and
    `correlationId/matchedCounterparty`. Depends on:
    `BE-ADR001-06A`, `BE-ADR001-06B`, `BE-ADR001-06C`.

### Phase 7 — Lending Extraction

25. `BE-ADR001-07A` Extract `LendingClassifier`
    Outcome: move `BORROW/REPAY/LENDING_*` ownership into family layer with loop
    lifecycle preserved. Depends on: Phase 6 complete.

26. `BE-ADR001-07B` Add initial protocol semantic adapters for lending-heavy
    protocols
    Outcome: create protocol-owned semantic adapters where generic registry
    discovery is not enough, starting with Euler and Aave. Depends on:
    `BE-ADR001-07A`.

27. `BE-ADR001-07C` Lending parity gate
    Outcome: row-level parity for lending and loop families. Depends on:
    `BE-ADR001-07A`, `BE-ADR001-07B`.

### Phase 8 — Trading Extraction

28. `BE-ADR001-08A` Extract `TradingClassifier`
    Outcome: move `DEX_ORDER_*` and derivative lifecycle final typing into
    trading family ownership. Depends on: Phase 7 complete.

29. `BE-ADR001-08B` Implement `GmxProtocolSemanticClassifier`
    Outcome: move GMX request/terminal/pool lifecycle semantics into protocol
    package + resource-backed support. Depends on: `BE-ADR001-08A`.

30. `BE-ADR001-08C` Implement `CowSwapProtocolSemanticClassifier`
    Outcome: move CoW request/settlement semantics into protocol package +
    resource-backed support. Depends on: `BE-ADR001-08A`.

31. `BE-ADR001-08D` Trading parity gate
    Outcome: row-level parity for GMX/CoW lifecycle, cashflow reconstruction,
    and async linking. Depends on: `BE-ADR001-08A`, `BE-ADR001-08B`,
    `BE-ADR001-08C`.

### Phase 9 — Transfer and Default Extraction

32. `BE-ADR001-09A` Extract `TransferClassifier`
    Outcome: move generic transfer/income fallback into final late family
    ownership. Depends on: Phase 8 complete.

33. `BE-ADR001-09B` Extract `DefaultClassifier`
    Outcome: move final `UNKNOWN` sink into explicit default ownership. Depends
    on: `BE-ADR001-09A`.

34. `BE-ADR001-09C` Final family parity gate
    Outcome: prove no unapproved row-level drift remains outside approved
    semantic fixes. Depends on: `BE-ADR001-09A`, `BE-ADR001-09B`.

### Phase 10 — Legacy Removal and Finalization

35. `BE-ADR001-10A` Remove legacy fallback and dead branches
    Outcome: delete the old monolithic fallback path and migration-only bridges.
    Depends on: all earlier phases green.

36. `BE-ADR001-10B` Final arch test and package-boundary enforcement
    Outcome: enforce the final package structure and prohibit legacy re-entry.
    Depends on: `BE-ADR001-10A`.

37. `BE-ADR001-10C` Final baseline rerun and sign-off package
    Outcome: regenerate baseline diff, prove row-level parity, and attach final
    manifest showing legacy path removal. Depends on: `BE-ADR001-10A`,
    `BE-ADR001-10B`.

## Risk Notes / Assumptions / Open Questions

- Risk: developer extracts families before tooling exists | Mitigation: Phase 0
  is mandatory and blocks further work.
- Risk: row-level parity is expensive to check manually | Mitigation: export and
  diff tooling must be executable in one command.
- Risk: family docs drift from code | Mitigation: every extraction task includes
  rule-doc update as part of DoD.
- Risk: protocol-specific logic leaks back into generic families | Mitigation:
  protocol-local resource files and package-boundary tests.
- Assumption: current Mongo corpus is sufficient baseline authority for
  migration parity.
- Assumption: protocol-local resources will remain small and human-reviewable.
- Open question delegated to implementation: whether baseline tooling should run
  via guarded `ApplicationRunner`, dedicated admin endpoint, or another manual
  backend-safe mechanism. The solution must remain disabled by default and have
  no impact on normal runtime.
