# 143 — Run 74 AVCO Replay Decomposition and Audit Loop

Status: planned
Owner: backend
Scope type: architecture + business requirements + delivery backlog
Priority: P0

## Goal

Refactor `AvcoReplayService` into explicit replay components that are:

- financially identical on the same canonical input
- easier to maintain and extend with new protocol families
- faster or at least not slower than the current implementation
- auditable after every intermediate slice through a full
  `normalization -> clarification -> linking -> pricing -> cost basis` rerun

This task does **not** authorize temporary repair jobs over existing derived
data. The primary path remains deterministic replay from a fresh normalized
stream.

---

## 1. Context

Current state:

- `backend/src/main/java/com/walletradar/costbasis/application/AvcoReplayService.java`
  is `4175` LOC.
- `backend/src/test/java/com/walletradar/costbasis/application/AvcoReplayServiceTest.java`
  is `1580` LOC and already contains many golden semantic assertions.
- `ConfirmedReplayQueryService` currently loads the full confirmed stream into
  memory before replay.
- `CostBasisReplayJob` delegates almost all accounting semantics to one service.

Current risks:

1. One class owns orchestration, state, protocol-specific lifecycle handling,
   corridor planning, settlement allocation, basis math, and ledger
   materialization.
2. Adding support for new protocol families is high-risk because new semantics
   are inserted into the same monolith.
3. Performance tuning is blocked because data loading, replay math, and writes
   are not isolated.
4. Auditability is weak because replay responsibilities are not separated into
   inspectable units.

Business target:

- keep AVCO accuracy above `99%` across all active asset families
- preserve deterministic replay and current supported semantics
- improve protocol onboarding throughput by turning replay-family logic into
  bounded components instead of new branches in one file

---

## 2. Non-Negotiable Invariants

The following rules apply to every slice:

1. Replay input remains:
   - `normalized_transactions`
   - `status = CONFIRMED`
2. Replay order remains:
   - `blockTimestamp ASC`
   - `transactionIndex ASC`
   - `_id ASC`
3. No slice may change pricing, classification, linking, or clarification
   semantics unless the audit explicitly proves the current replay contract
   cannot represent an already approved canonical family.
4. No slice may rely on repair jobs over existing `asset_ledger_points` or
   mutated derived rows.
5. Canonical fresh rerun is the default validation path.
6. `move basis` remains a replay concern, not a pre-replay stage.
7. `accounting replay` remains deterministic on the same canonical input.
8. A slice is incomplete if the full rerun does not pass:
   - normalization
   - clarification
   - linking
   - pricing
   - cost basis
9. Pricing cache may be preserved between reruns only when doing so does not
   hide a replay-semantic regression.

---

## 3. Target Component Breakdown

Target outcome after this refactor:

```text
CostBasisReplayJob
    |
    v
AvcoReplayCoordinator
    |- ConfirmedReplayQueryService
    |- ReplayContextFactory
    |- PassThroughCorridorPlanner
    |- ReplayTransactionRouter
    |    |- GenericFlowReplayHandler
    |    |- BridgeCarryReplayHandler
    |    |- AsyncLifecycleReplayHandler
    |    |- AsyncSpotOrderReplayHandler
    |    |- LiquidStakingReplayHandler
    |    |- FamilyEquivalentCustodyReplayHandler
    |    |- EulerLoopReplayHandler
    |- LedgerPointCollector / LedgerPointWriter
    |- ReplayPersistenceService
```

Required design rules:

- `Coordinator` owns ordering, lifecycle, and persistence boundaries only.
- `Replay handlers` own one bounded semantic family each.
- `Replay state` is explicit and shared through a typed context, not hidden in
  one method.
- `Ledger point materialization` is isolated from replay math.
- `Query/persistence throughput` can be optimized without rewriting financial
  rules.

---

## 4. Business Acceptance Criteria (Definition of Done)

- [ ] The monolithic `AvcoReplayService` no longer owns all replay semantics in
      one file; responsibilities are split into bounded components.
- [ ] The same canonical input produces the same replay output:
      - `asset_ledger_points`
      - flow-level `avcoAtTimeOfSale`
      - flow-level `realisedPnlUsd`
      - covered / uncovered quantities
- [ ] Full rerun after every accepted slice completes:
      `normalization -> clarification -> linking -> pricing -> cost basis`.
- [ ] `financial-logic-auditor` produces a rerun report under
      `results/stats/{N}` for every accepted slice.
- [ ] AVCO accuracy stays above `99%` on the audited dataset.
- [ ] Movement coverage and priced-value coverage do not regress.
- [ ] No accepted slice introduces a repair job over already materialized
      replay outputs.
- [ ] Replay runtime does not regress materially; target is neutral-to-better.
- [ ] New protocol family onboarding becomes structurally simpler because
      family-specific replay logic can be added in bounded handlers.
- [ ] Review by `system-architect` is completed after each accepted slice.

---

## 5. Edge Cases

- Case: inbound-first carry attachment for bridge or async settlement
  | Scope: In
  | Expected behaviour: quantity materializes once, later carry attaches without
  duplication.
- Case: same-family linked bridge with quantity drift
  | Scope: In
  | Expected behaviour: existing dedicated bridge carry rules remain unchanged.
- Case: asset-changing bridge settlement
  | Scope: In
  | Expected behaviour: existing conservative carried-basis settlement rule
  remains unchanged.
- Case: simple family-equivalent custody (`deposit -> receipt`)
  | Scope: In
  | Expected behaviour: no false uncovered receipt inventory is introduced by
  handler extraction.
- Case: async LP request / settlement with execution-fee reserve
  | Scope: In
  | Expected behaviour: reserve accounting remains isolated from principal
  carry.
- Case: replay over empty confirmed set
  | Scope: In
  | Expected behaviour: no failure, no stale ledger points remain for the active
  accounting universe.
- Case: ambiguous continuity candidate or multiple competing pending carries
  | Scope: In
  | Expected behaviour: replay refuses to guess and preserves the unresolved
  path.
- Case: protocol family not yet modeled in a dedicated handler
  | Scope: In
  | Expected behaviour: the generic handler path still preserves current
  semantics until a dedicated handler is introduced and audited.
- Case: use of repair job over existing ledger points
  | Scope: Out
  | Expected behaviour: rejected for this initiative.

---

## 6. Supported vs Unsupported for This Refactor

Supported:

- decomposition of replay logic
- replay performance improvements
- new handler seams for protocol families
- additional audit helper scripts
- protocol documentation snapshots or hints used by the auditor
- Mongo/query/persistence optimizations that preserve semantics

Unsupported:

- changing AVCO accounting policy
- replacing AVCO with FIFO/LIFO
- introducing a second replay ordering source
- one-off data repair jobs to “fix” existing derived output
- protocol reclassification changes unless audit proves replay cannot represent
  an already approved canonical family

---

## 7. Delivery Strategy

The refactor must be executed as ordered slices. Do **not** attempt one giant
PR.

### Slice A0 — Golden Replay Harness and Audit Baseline

Outcome:

- freeze a stable replay baseline before code motion begins
- make every later slice provable against the same output contract

Tasks:

1. `AVCO-A0-01` Snapshot current replay outputs and runtime metrics on the
   working dataset.
2. `AVCO-A0-02` Add or extend golden tests around:
   - bridge carry
   - asset-changing route settlement
   - async LP request/settlement
   - liquid staking continuity
   - family-equivalent custody
   - Euler loop rebalance
3. `AVCO-A0-03` Define a stable audit artifact format under
   `results/stats/{N}`.

Operational helpers prepared for this slice:

- `scripts/avco/reset-derived.sh`
  - resets downstream state to a fresh normalization/replay start
  - preserves raw evidence by default
  - pricing cache is preserved unless `--clear-pricing-cache` is passed
- `scripts/avco/capture-baseline.sh`
  - writes baseline artifacts into `results/stats/{N}`
  - captures Mongo counts, git SHA, Docker state, and backend tail log

Acceptance:

- baseline rerun artifacts exist
- no code-motion slice starts before the baseline passes

### Slice A1 — Replay State Extraction

Outcome:

- extract replay state types from `AvcoReplayService` without semantic change

Target extraction candidates:

- `AssetKey`
- `ContinuityKey`
- `PassThroughScopeKey`
- `CarryTransfer`
- `PositionSnapshot`
- `QuantityConsumption`
- `PositionState`
- `ContinuityBucket`
- simple replay context holder

Acceptance:

- no logic change
- `AvcoReplayServiceTest` stays green
- rerun output identical to baseline

### Slice A2 — Ledger Point Materialization Isolation

Outcome:

- isolate ledger-point recording from replay math

Target extraction candidates:

- `LedgerPointCollector`
- ledger-point persistence boundary
- replay sequence generation
- basis-effect mapping

Acceptance:

- `asset_ledger_points` output is identical to baseline
- no replay math is embedded in the collector

### Slice A3 — Generic Position Flow Engine

Outcome:

- extract the generic flow math into one bounded engine

Target responsibilities:

- `BUY`
- `SELL`
- `FEE`
- generic `TRANSFER`
- sponsored gas
- unresolved / shortfall marking
- wallet AVCO recompute

Current method candidates:

- `applyBuy`
- `applySell`
- `applyFee`
- `applyTransfer`
- `applyUnknownTransfer`
- `applySponsoredGasIn`
- `materializePendingInbound`
- `markUnresolved`
- `recordQuantityShortfall`
- `recomputePerWalletAvco`
- `resolveTemporaryUnresolved`

Acceptance:

- generic spot/transfer behaviour unchanged
- regression tests cover both covered and uncovered paths

### Slice A4 — Corridor and Continuity Services

Outcome:

- isolate carry planning from carry consumption

Target components:

- `PassThroughCorridorPlanner`
- `ContinuityBucketService`
- reserved carry service

Current method candidates:

- `buildPassThroughCorridorPlan`
- `selectPassThroughInboundCandidate`
- `selectPassThroughOutboundCandidate`
- `moveToContinuityBucket`
- `restoreFromContinuityBucket`
- `reservePassThroughCarryIfPlanned`
- `consumeReservedCarry`
- `restoreToPosition`

Acceptance:

- same corridor plan on the same ordered stream
- no carry duplication or stale reservation leakage

### Slice A5 — Protocol / Lifecycle Handlers

Outcome:

- move family-specific replay semantics out of the generic replay loop

Handler groups:

1. `LiquidStakingReplayHandler`
   - `selectLiquidStakingPrincipalFlows`
   - `applyLiquidStakingConversion`
2. `FamilyEquivalentCustodyReplayHandler`
   - `selectSimpleFamilyEquivalentCustodyFlows`
   - `applySimpleFamilyEquivalentCustodyFlows`
3. `AsyncLifecycleReplayHandler`
   - GMX LP request / settlement
   - async LP exit settlement
   - position-scoped LP exit
4. `AsyncSpotOrderReplayHandler`
   - request / settlement pairing
5. `EulerLoopReplayHandler`
   - loop rebalance and restoration paths

Acceptance:

- family handlers remain functionally identical
- new protocol support can be added by implementing a new handler instead of
  editing the central loop

### Slice A6 — Replay Transaction Router

Outcome:

- the main replay loop becomes a dispatcher, not a semantic monolith

Target responsibilities:

- route one transaction to one specialized handler or the generic flow engine
- centralize precedence rules between handlers
- keep deterministic fallback order

Acceptance:

- top-level `replayConfirmed(...)` becomes orchestration-focused
- routing precedence is explicit and testable

### Slice A7 — Query and Persistence Throughput

Outcome:

- improve replay runtime without changing semantics

Target changes:

1. Remove redundant in-memory `.stream().sorted(...)` if repository order is
   already deterministic and covered by tests.
2. Evaluate batched persistence for:
   - `normalized_transactions.saveAll(...)`
   - `asset_ledger_points.saveAll(...)`
3. Consider paged or streaming replay input only after corridor-planning needs
   are preserved.
4. Add telemetry:
   - replay input size
   - corridor count
   - ledger point count
   - persistence duration
   - total replay duration

Acceptance:

- runtime is neutral-to-better
- no semantic drift
- no full-collection anti-pattern beyond what is required for deterministic
  corridor planning

### Slice A8 — Protocol Coverage Expansion

Outcome:

- use the decomposed handler seams to close protocol-family gaps with official
  evidence

Priority coverage pool:

- `GMX`
- `AAVE`
- `EULER`
- `MORPHO`
- `PancakeSwap`
- `Sushiswap`
- `Balancer`
- `Fluid`
- `Lagoon`
- `Yearn`
- `Hyperliquid`
- `Paradex`
- `Benqi`
- `Gearbox`
- `Silo`
- other top-TVL DefiLlama families proven relevant by audit

Acceptance:

- each added replay-family rule is backed by:
  - official documentation
  - raw evidence
  - rerun audit

---

## 8. Executor-Ready Task Breakdown

1. `AVCO-A0` Build the golden replay baseline and rerun audit harness.
   Depends on: none.
2. `AVCO-A1` Extract replay state carriers into dedicated replay model classes.
   Depends on: `AVCO-A0`.
3. `AVCO-A2` Extract ledger-point materialization and replay persistence seam.
   Depends on: `AVCO-A1`.
4. `AVCO-A3` Extract generic position flow engine.
   Depends on: `AVCO-A1`, `AVCO-A2`.
5. `AVCO-A4` Extract corridor / continuity services.
   Depends on: `AVCO-A1`, `AVCO-A3`.
6. `AVCO-A5` Extract protocol-specific replay handlers.
   Depends on: `AVCO-A3`, `AVCO-A4`.
7. `AVCO-A6` Introduce the transaction router and shrink the coordinator.
   Depends on: `AVCO-A5`.
8. `AVCO-A7` Optimize query/persistence path and replay telemetry.
   Depends on: `AVCO-A6`.
9. `AVCO-A8` Expand protocol-family coverage through the new handler seams.
   Depends on: `AVCO-A6`.

---

## 9. Mandatory Review and Audit Loop

Every accepted slice must follow this cycle:

1. `backend-dev`
   - implement one bounded slice only
   - keep build and targeted tests green
2. Prepare rerun dataset
   - stop backend
   - clear derived normalization/replay outputs
   - keep raw evidence
   - prefer fresh canonical regeneration over repair
3. Run full downstream pipeline
   - normalization
   - clarification
   - linking
   - pricing
   - cost basis
4. `financial-logic-auditor`
   - produce a detailed report in `results/stats/{N}`
   - verify AVCO accuracy, movement coverage, and protocol-family correctness
   - use official protocol documentation when needed
5. `system-architect`
   - review the slice for boundary quality, performance impact, and future
     extensibility
6. Gate decision
   - accept slice
   - or reopen the cycle with explicit findings

No slice is considered complete before this loop is closed.

---

## 10. Audit Deliverables

For each slice, `financial-logic-auditor` should publish:

- `results/stats/{N}/summary.json`
- `results/stats/{N}/full-pipeline-audit.md`
- `results/stats/{N}/data/derived/avco-market-value-coverage.json`
- `results/stats/{N}/data/derived/phase-coverage.json`
- helper scripts under `results/stats/{N}/scripts/` or a reusable internal
  helper folder when the script should survive across reruns

The report must include:

1. movement coverage
2. price coverage
3. AVCO coverage by market value
4. protocol-family defect list
5. exact recommended fix path backed by official documentation where applicable

---

## 11. Performance Expectations

Hard requirement:

- no accepted slice may make replay materially slower on the audited dataset
  without an explicit architect-approved trade-off

Target:

- by the end of `AVCO-A7`, replay should be at least no worse than the current
  baseline and ideally measurably faster

Primary levers:

1. remove unnecessary in-memory work
2. isolate persistence so it can be batched or replaced safely
3. separate family dispatch from generic flow math
4. avoid writing unchanged rows when safe and provable

---

## 12. Risks and Mitigations

- Risk: “clean-code” extraction silently changes replay semantics.
  Mitigation: every slice is guarded by golden tests and a full rerun audit.
- Risk: performance gets worse because extra indirection is added.
  Mitigation: add telemetry and defer heavier abstractions until after the
  generic flow engine is isolated.
- Risk: protocol coverage stalls because the handler seams are too generic.
  Mitigation: make family handlers bounded by real audited semantics, not by
  abstract inheritance hierarchies.
- Risk: team falls back to patching existing derived data.
  Mitigation: reject repair-job-first fixes in this initiative.
- Risk: pricing cache masks replay regressions.
  Mitigation: use cold-cache reruns when replay outcome depends on changed
  upstream canonicalization.

---

## 13. Immediate Next Step

The first executable slice is `AVCO-A0`.

Do not start code motion in `AvcoReplayService` before:

1. golden replay baseline is refreshed on the current machine
2. rerun audit artifacts are written
3. the first extraction boundary is frozen from real evidence rather than from
   taste
