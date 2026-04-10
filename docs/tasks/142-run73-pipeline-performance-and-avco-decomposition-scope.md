# 142 — Pipeline Performance and AVCO Decomposition Scope

Status: planned
Owner: backend
Scope type: architecture + delivery scope
Priority: P0 for phase 1, P1 for phase 2

## 1. Goal

Define the next implementation scope for WalletRadar in three ordered phases:

1. `Phase 1` — performance and pipeline-structure improvements
   - parallel source classification where safe
   - dedicated linking phase
   - batched pricing improvements
2. `Phase 2` — AVCO replay decomposition
3. `Phase 3` — recommended cleanup and legacy removal after phases 1 and 2 stabilize

This document is a delivery scope, not an implemented-state architecture update.

## 2. Current State and Problem Statement

Current live-session order remains:

1. `BACKFILL`
2. `ON_CHAIN_NORMALIZATION`
3. `ON_CHAIN_CLARIFICATION`
4. `BYBIT_NORMALIZATION`
5. `PRICING`
6. `ACCOUNTING_REPLAY`

Current structural problems:

- `ON_CHAIN_NORMALIZATION` and `BYBIT_NORMALIZATION` do not use safe source-level parallelism.
- Linking responsibilities are spread across multiple stages:
  - on-chain clarification post-processing
  - Bybit normalization bridge correlation
  - replay-time continuity repair assumptions
- `PricingJobService` processes rows one by one and persists one by one.
- `AvcoReplayService` is too large for safe maintenance (`4163` LOC at the time of writing).
- Session progress semantics are harder to reason about because raw acquisition completion and full-pipeline completion are conceptually different but historically surfaced through one status contract.

Financial correctness concern:

- Canonical chronology is not defined by stage order; it is defined only by the merged confirmed stream ordered by `blockTimestamp ASC`, then `transactionIndex ASC`, then canonical id.
- Therefore performance work must preserve that merged replay order and must not let partial source ordering leak into replay.

## 3. Non-Negotiable Financial Invariants

These invariants apply to every phase:

1. Replay order remains `blockTimestamp ASC -> transactionIndex ASC -> id ASC`.
2. Parallel source classification must not mutate cross-source continuity metadata directly.
3. Linking must happen only after the relevant source evidence exists in canonical normalized form.
4. Pricing must operate on the linked canonical stream, never on a partially linked stream.
5. AVCO refactoring must preserve deterministic output on the same canonical input.
6. Existing source collections remain authoritative:
   - `raw_transactions`
   - `integration_raw_events`
   - `bybit_extracted_events`
   - `normalized_transactions`
7. Clarification remains evidence enrichment, not a generic source-to-source matcher.

## 4. Target Pipeline After Phase 1

```text
BACKFILL
   |
   +--> ON_CHAIN_CLASSIFICATION  ----+
   |                                 |
   +--> INTEGRATION_CLASSIFICATION --+--> LINKING --> PRICING --> ACCOUNTING_REPLAY
                     ^
                     |
         ON_CHAIN_CLARIFICATION
         (depends only on on-chain classification,
          may overlap with integration classification)
```

Operational rule:

- `LINKING` may start only when:
  - on-chain classification finished for the active window
  - on-chain clarification finished for the active window
  - integration classification finished for the active window

This keeps source classification parallel while preserving one deterministic barrier before pricing and replay.

## 5. Phase 1 — Performance Improvements

### 5.1 Workstream A — Parallel Source Classification

#### Objective

Allow on-chain classification and integration classification to run in parallel when their source windows are ready, without letting either stage mutate the other source's canonical documents.

#### Required design rule

Source classification must be source-local only.

Allowed in source classification:

- build canonical rows from source evidence
- set source-local status
- set source-local protocol/type/flow fields
- set source-local clarification requirements

Forbidden in source classification:

- mutating the counterparty source row
- setting cross-source `correlationId` as a side effect of source classification
- setting cross-source `matchedCounterparty` as a side effect of source classification
- marking source-crossing continuity as final

#### Required code changes

1. Keep on-chain classification source-local.
2. Keep Bybit normalization source-local.
3. Remove direct cross-source mutation from:
   - `backend/src/main/java/com/walletradar/ingestion/job/bybit/BybitNormalizationService.java`
   - specifically `correlateBridge(...)` style mutations of existing on-chain canonical rows
4. Introduce source-fan-out orchestration barrier:
   - on-chain classification job and integration classification job may run independently
   - downstream linking waits for both
5. Keep on-chain clarification dependent only on on-chain classification.
6. Session progress reporting must remain understandable during overlap.

#### Developer tasks

`P1-A1`
- Define one normalization fan-out coordinator contract.
- It may remain inside the modular monolith and may stay event-driven.
- It must not create a second ordering source for replay.

`P1-A2`
- Make `BybitNormalizationService` fully source-local.
- Replace direct on-chain row updates with source-local correlation candidates or linking hints only.

`P1-A3`
- Ensure on-chain clarification can run independently of integration classification.
- Clarification must not own cross-source matching anymore.

`P1-A4`
- Add one explicit ready-for-linking barrier condition:
  - on-chain classified
  - on-chain clarified
  - integration classified

#### Acceptance criteria

1. On-chain and Bybit classification can overlap in wall-clock time.
2. Neither classifier updates the other source's canonical row directly.
3. The same source input produces the same canonical rows regardless of overlap timing.
4. Replay input set is unchanged versus the sequential baseline except for fields intentionally moved into linking.
5. No canonical row regresses to an earlier source-stage status because of parallel overlap.

#### Testing

Required:

- integration test where on-chain classification and Bybit classification overlap
- regression test proving no cross-source row mutation during classification
- session pipeline test proving linking starts only after both source prerequisites are complete
- golden replay regression on a known session after the phase-1 changes

Out of scope for this workstream:

- new provider support
- replay semantics changes
- UI redesign of stage progress

### 5.2 Workstream B — Dedicated Linking Phase

#### Objective

Create one explicit `LINKING` phase that owns deterministic pair/link/correlation work after source classification and before pricing.

#### Responsibilities of the new linking phase

- bridge pair linking
- same-universe internal transfer linking
- Bybit <-> on-chain transfer continuity repair
- continuity metadata promotion:
  - `correlationId`
  - `matchedCounterparty`
  - `continuityCandidate`
- any deterministic cross-source or cross-row pair repair that depends on already normalized source rows

#### Responsibilities explicitly removed from clarification

Clarification should remain limited to:

- receipt-safe evidence enrichment
- allowlisted full-receipt enrichment
- protocol-name enrichment
- optional reclassification caused by newly persisted clarification evidence

Clarification should no longer own generic linking/post-link mutation.

#### Likely services to move or regroup

Current candidates for the dedicated linking phase:

- `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/InternalTransferPairLinkService.java`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/BybitTransferContinuityRepairService.java`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/LiFiBridgePairLinkService.java`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/AcrossBridgePairLinkService.java`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/MayanCctpBridgePairLinkService.java`
- the corresponding wrapper in `ClarificationPostProcessingHandler`

The final package may be:

- `ingestion/job/linking/*`
- `ingestion/pipeline/linking/*`

#### Developer tasks

`P1-B1`
- Add explicit `LINKING` pipeline stage.

`P1-B2`
- Create `LinkingJob` as a dedicated stage driver.

`P1-B3`
- Move deterministic pair/link services out of clarification post-processing into linking.

`P1-B4`
- Remove cross-source correlation side effects from Bybit normalization and let linking own them.

`P1-B5`
- Ensure pricing starts only after linking is complete for the active window.

#### Acceptance criteria

1. All deterministic pair/link repairs run in one dedicated phase.
2. Clarification can finish without also performing cross-source linking.
3. Bybit normalization does not directly persist cross-source continuity onto on-chain rows.
4. Pricing sees already linked canonical rows.
5. Existing audited bridge/Bybit continuity cases still resolve correctly after the stage move.

#### Testing

Required:

- regression tests for:
  - internal transfer promotion
  - bridge pair linking
  - Bybit transfer continuity repair
- stage-order integration test:
  - pricing never starts before linking is complete
- replay regression:
  - no change to confirmed ledger output on golden session inputs

### 5.3 Workstream C — Pricing Batch Throughput

#### Objective

Increase pricing throughput by loading, resolving, and persisting transactions in larger batches, with batched external quote prefetch and batched writes.

#### Current bottleneck

Current `PricingJobService`:

- loads one ordered batch
- resolves each transaction individually
- persists each transaction individually with `save(...)`

This is easy to reason about but wasteful for:

- repeated external price fetches
- repeated historical quote persistence
- repeated Mongo writes

#### Required target behavior

1. Load `200-300` pending pricing rows per batch.
2. Precompute the unique external quote needs for the batch.
3. Fetch/store quotes in bulk where the external provider path supports it.
4. Persist priced normalized rows in batch (`saveAll` or `BulkOperations`).
5. Internal parallelism of `2` worker lanes is allowed only if the batch is already fully prefetched and each lane processes a disjoint transaction subset.

#### Required design rule

Pricing parallelism is transaction-level only, not flow-level.

Why:

- flow pricing inside one canonical transaction may depend on event-local fixed-point propagation
- that work should stay within the same transaction boundary

#### Required code changes

1. Refactor `PricingJobService` into:
   - candidate load
   - external request aggregation
   - bulk quote resolution
   - transaction pricing application
   - bulk persistence
2. Introduce batch-capable external resolver contract where meaningful.
3. Persist historical prices in batches.
4. Add one pricing batch size config default in the `200-300` range.
5. If enabling two local worker lanes:
   - prefetch external quotes once per batch
   - split only the already loaded batch into two disjoint transaction slices
   - merge and persist results deterministically

#### Developer tasks

`P1-C1`
- Split pricing into planner/apply/persist substeps.

`P1-C2`
- Add external quote grouping by asset/time/source key.

`P1-C3`
- Replace per-row `save(...)` with bulk persistence.

`P1-C4`
- Add optional two-lane local batch execution behind config.

`P1-C5`
- Add throughput telemetry:
  - batch size
  - unique external requests
  - external hits/misses
  - write count
  - duration per batch

#### Acceptance criteria

1. Default pricing batch size is in the `200-300` range.
2. One pricing batch produces fewer database writes than the row-by-row baseline.
3. One pricing batch produces fewer external quote calls than the row-by-row baseline.
4. Two-lane local pricing, if enabled, does not duplicate external requests inside the same batch.
5. Priced outputs remain identical to baseline on the same canonical input.

#### Testing

Required:

- unit tests for batch quote aggregation
- unit tests for grouped historical price persistence
- regression tests proving identical pricing outputs on golden rows
- performance benchmark on a representative pricing queue

### 5.4 Phase 1 Definition of Done

Phase 1 is done only if all of the following are true:

1. On-chain and integration classification can overlap safely.
2. Linking is a dedicated stage between classification/clarification and pricing.
3. Pricing processes `200-300` rows per batch and persists in batch.
4. Golden replay output for a reference session is unchanged.
5. Session progress remains understandable while the pipeline is running.
6. No financial-audit regression appears in bridge, custody, or Bybit transfer continuity.

## 6. Phase 2 — AVCO Decomposition

### 6.1 Objective

Decompose `AvcoReplayService` into small, testable services with strict responsibilities without changing replay semantics.

### 6.2 Current problem

`backend/src/main/java/com/walletradar/costbasis/application/AvcoReplayService.java`
is currently `4163` lines and mixes:

- replay orchestration
- transaction-family dispatch
- carry accounting
- transfer/bridge carry logic
- async lifecycle handling
- settlement allocation
- pass-through corridor planning
- state containers
- ledger-point materialization

This makes change review, test isolation, and defect localization too expensive.

### 6.3 Recommended decomposition map

#### A. Replay orchestration

Responsibility:

- iterate ordered confirmed canonical rows
- initialize per-run state
- delegate transaction handling
- collect ledger points and end-of-run snapshots

Candidate class:

- `ReplayCoordinator`

#### B. Transaction family handlers

Responsibility:

- transaction-type-specific replay logic

Suggested handlers:

- `GenericFlowReplayHandler`
- `SimpleCustodyReplayHandler`
- `LiquidStakingReplayHandler`
- `BridgeReplayHandler`
- `AsyncLifecycleReplayHandler`
- `GmxLpReplayHandler`
- `EulerLoopReplayHandler`

#### C. Carry and position engine

Responsibility:

- maintain position quantity, covered quantity, uncovered quantity, AVCO
- consume and restore carries
- apply buy/sell/fee/transfer primitives

Candidate classes:

- `PositionStateService`
- `CarryTransferService`
- `QuantityConsumptionService`

#### D. Settlement allocator

Responsibility:

- allocate settlement basis by quantity / value / replay-known value

Candidate classes:

- `SettlementAllocationService`
- `SettlementRestoreService`

#### E. Corridor and reservation planning

Responsibility:

- build pass-through corridor plan
- maintain request-scoped reserves
- handle reserved carry consumption

Candidate classes:

- `PassThroughCorridorPlanner`
- `ReservedCarryService`

#### F. Ledger point materialization

Responsibility:

- create immutable `AssetLedgerPoint`
- centralize basis-effect mapping and snapshot recording

Candidate classes:

- `LedgerPointFactory`
- `LedgerPointCollector`

#### G. Replay support types

Responsibility:

- move nested records and support structures into dedicated package-local types

Candidate package:

- `costbasis/application/replay/model`

### 6.4 Required implementation rules

1. Phase 2 is semantic-preserving only.
2. No business-rule rewrite is allowed in the same PR as structural extraction unless explicitly approved.
3. Every extraction step must keep a replay baseline green.
4. Public replay entrypoints should remain stable while the internals are decomposed.

### 6.5 Developer tasks

`P2-01`
- Create replay package structure with clear ownership boundaries.

`P2-02`
- Extract nested data carriers and helpers first.

`P2-03`
- Extract pure planners/allocators next.

`P2-04`
- Extract transaction-family handlers next.

`P2-05`
- Extract carry/position engine last, because it is the highest-risk semantic core.

`P2-06`
- Keep one golden replay baseline suite and compare:
  - ledger points
  - uncovered quantities
  - realized PnL
  - AVCO snapshots

### 6.6 Acceptance criteria

1. `AvcoReplayService` becomes an orchestration shell rather than a multi-thousand-line rules container.
2. Core replay responsibilities are split into dedicated services/classes with narrow APIs.
3. Replay output on the golden dataset is unchanged.
4. New unit tests exist for extracted planners/allocators/handlers.
5. Reviewability improves because each replay rule lives in a named component.

### 6.7 Phase 2 non-goals

- new accounting semantics
- new supported protocol families
- UI changes
- performance parallelization of replay itself

## 7. Phase 3 — Recommended Cleanup and Legacy Removal

Phase 3 is optional after phases 1 and 2 stabilize.

Recommended items:

1. Remove semantically dead legacy paths once the new stages are proven stable.
2. Delete compatibility-only code that no longer affects runtime behavior.
3. Reorganize packages to match the real pipeline:
   - `backfill`
   - `normalization/onchain`
   - `normalization/integration`
   - `clarification`
   - `linking`
   - `pricing`
   - `costbasis/replay`
4. Remove naming drift where current class names no longer match behavior.
5. Collapse duplicate Bybit legacy normalization surfaces if `bybit_extracted_events` becomes the sole authoritative staging path.
6. Revisit status DTO naming so pipeline status and acquisition status are not conflated.

Phase 3 must happen only after the new phase-1 and phase-2 baselines are stable.

## 8. Delivery Order

### First pass

Deliver `Phase 1` only.

Recommended order inside phase 1:

1. dedicated linking stage
2. remove cross-source mutation from source classification
3. enable parallel source classification
4. pricing batch improvements
5. regression/performance test pass

Why this order:

- linking separation is the correctness prerequisite for parallel classification
- pricing batching is independent enough to ship after stage separation

### Second pass

Deliver `Phase 2` later, as a dedicated replay-tech-debt slice.

### Third pass

Consider `Phase 3` cleanup only after the new control flow is stable in production-like reruns.

## 9. Mandatory Test Strategy

Phase 1 minimum:

- source fan-out orchestration tests
- linking-stage regression tests
- pricing batching regression tests
- one end-to-end golden session replay comparison
- wall-clock benchmark before/after on:
  - normalization throughput
  - pricing throughput

Phase 2 minimum:

- replay baseline snapshot tests
- extracted component unit tests
- no-output-diff replay comparison

## 10. Risks and Mitigations

### Risk 1 — hidden cross-source mutation still lives in source jobs

Mitigation:

- enforce the rule that source classification writes only source-owned canonical rows
- move all cross-source metadata promotion into linking

### Risk 2 — pricing batching changes semantics

Mitigation:

- batch only transport and persistence
- keep per-transaction event-local pricing logic intact
- compare priced outputs against a baseline session

### Risk 3 — replay refactor accidentally changes AVCO

Mitigation:

- no semantic rewrites in phase 2
- baseline comparison on every extraction step

### Risk 4 — pipeline progress becomes harder to understand during parallel work

Mitigation:

- keep acquisition progress and full-pipeline progress conceptually separate
- surface stage-specific progress explicitly

## 11. Explicit Out of Scope

Not in this scope:

- new external providers beyond Bybit
- new accounting semantics
- paid infrastructure
- Kubernetes or microservice split
- replay parallelization
- historical window policy changes
