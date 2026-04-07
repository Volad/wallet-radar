# Protocol Resource Runtime Contract and Aave Realization

Goal:

Turn `backend/src/main/resources/protocols/*.json` into real runtime protocol
profiles instead of decorative metadata stubs, and fully populate `Aave` as the
first concrete non-scaffold lending profile.

Related inputs:

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [Post-review remediation](58-adr001-post-review-boundary-remediation.md)
- [Normalization Rules Index](../normalization/README.md)
- [Aave protocol rules](../normalization/protocols/aave.md)

## Problem Statement

Today we have three parallel artifacts:

- `protocol-registry.json` for address-level protocol identity
- `backend/src/main/resources/protocols/*.json` for protocol-local runtime metadata
- `docs/normalization/protocols/*.md` for human-readable rule docs

The second layer exists but is still too thin. In current runtime it mostly
stores aliases/capabilities and therefore does not yet justify its presence as a
separate configuration layer.

## Target Contract

### `protocol-registry.json`

- address-level truth only
- protocol / version / family / role / confidence
- no protocol-wide selectors
- no lifecycle rules

### `backend/src/main/resources/protocols/*.json`

- protocol-level runtime profile
- stable protocol-wide marker groups
- reusable by discovery and protocol semantic classifiers
- no address-level mappings
- no full business rules encoded as JSON

### `docs/normalization/protocols/*.md`

- authoritative human rule docs
- evidence rules
- clarification rules
- correlation rules
- disallowed fallbacks

## Required Runtime Schema

Add a protocol resource schema that supports at least:

- `aliases`
- `capabilities`
- `clarificationHints`
- `markers.methodSelectors`
- `markers.functionNames`
- `markers.eventNames`
- `markers.eventTopics`
- `markers.subcallSelectors`
- `markers.assetMarkers`

All marker groups must be optional and empty-safe.

## Implementation Tasks

1. `BE-PRR-01` Protocol resource schema v2
   Outcome: extend `ProtocolResourceDefinition` to support structured marker
   groups and add loader tests for them.

2. `BE-PRR-02` Runtime helper for protocol resource lookup
   Outcome: add helper accessors so runtime code can safely read method/event/
   asset marker groups without duplicating null checks.

3. `BE-PRR-03` Aave profile real fill
   Outcome: replace `aave.json` scaffold payload with real Aave V3 runtime
   marker groups derived from official docs/repo and current baseline-safe
   selectors.

4. `BE-PRR-04` Aave runtime consumption
   Outcome: make current Aave lending-pool classification consume `aave.json`
   marker groups in active runtime while preserving exact baseline parity.

5. `BE-PRR-05` GMX/CoW/Euler profile completion
   Outcome: migrate current protocol-wide selectors / event names / marker groups
   into `gmx-v2.json`, `cow.json`, and `euler.json` so the resources become real
   runtime inputs, not stubs.

6. `BE-PRR-06` Docs synchronization
   Outcome: update `docs/normalization/protocols/*.md` so each protocol doc
   explicitly distinguishes:
   - address registry layer
   - runtime protocol profile
   - human rule doc

7. `BE-PRR-07` Baseline verify
   Outcome: prove zero row-level drift after each active runtime adoption step.

## Acceptance Criteria

- [x] `ProtocolResourceDefinition` supports structured runtime marker groups.
- [x] `aave.json` contains real Aave V3 selectors and marker groups.
- [x] active runtime consumes `aave.json` for at least one real classification
      path.
- [x] `gmx-v2.json`, `cow.json`, and `euler.json` are no longer decorative-only
      stubs.
- [x] docs clearly explain why all three layers exist and what each one owns.
- [x] baseline verify remains zero-diff.
