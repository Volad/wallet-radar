# RC-9 / RC-7 — Deterministic on-chain↔CEX Corridor Basis Continuity — Implementation Plan

> Status: **Proposed — awaiting approval before coding.**
> Audit ref: financial-logic-auditor `agent_id 87b8695c`. Spec: business-analyst `agent_id 0e18064a`. Design + ADR: system-architect `agent_id d6f4fd22`.
> Companion ADR (to be created on implementation): `docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md`.

## 1. Problem (why)

A **clean full rebuild** conserved (`conservationDeltaUsd = -20.13`). A real **incremental refresh** regressed it to **+11,070.87** (unrealized flipped −$4,674 → +$2,670).

Root cause **RC-9**: in corridor `ARBITRUM → Bybit (CEX) → MANTLE`, the on-chain `INTERNAL_TRANSFER` deposit leg released a `CARRY_OUT` of **$9,272.11** (3.06 ETH @ avco ≈ $3,030), but the Bybit-side deposit `CARRY_IN` credited only a stale FUND residual of **$212.99**. The $9,272.11 carry-out is **orphaned**; ETH returns on MANTLE at avco **$540**, fabricating **+$3,481** unrealized. The same corridor was **correct in the prior full rebuild** — the incremental refresh re-materialized the Bybit corridor link docs and the stateful, order-dependent linking passes produced a different correlation.

Two defect layers:
1. **Linking is not a pure function of the raw transactions** — corridor pairing is done by stateful repair passes gated on `compatibleBybitRows.size() == 1`, "already-paired" predicates, and `blockTimestamp`-ordered keeper selection; refresh presents different DB state → different corrId → different replay queue key.
2. **Replay has no continuity invariant** — a credit is not required to inherit the matched counterpart's released basis, and an orphaned `CARRY_OUT` goes undetected until the dashboard-time conservation gate.

**RC-7** (regressed, ~$30) is the bridge variant: a LINEA bridge `CARRY_IN` (0.0116 ETH) lands at avco $0 because it did not inherit the bridge `CARRY_OUT` basis.

## 2. Invariant (the one rule)

> A corridor credit (`CARRY_IN`) **inherits its matched counterpart `CARRY_OUT`'s released basis** — never a residual/spot/$0 — and **no released covered basis may be orphaned**. The corridor correlation triple is a **pure function of `(networkId, canonicalTxHash, assetFamily)`**, so an incremental refresh of an affected corridor yields **bit-identical** `corr-family:`/`bridge:` queue keys and CARRY_IN AVCO as a full rebuild.

## 3. Scope

**In:** on-chain↔CEX (`on-chain → BYBIT:<uid> → on-chain`) corridor pairing + basis inheritance; determinism/idempotency/order-stability under incremental refresh; end-of-replay orphan-carry guard; RC-7 bridge `CARRY_IN` inheritance.

**Out:** genuine CEX orphan IN with no on-chain `CARRY_OUT` (legit spot ACQUIRE, CEX→wallet); OOS families (SOL/TON/HYPEREVM, RC-8); asset-changing CEX corridors (open question Q2); startup repair sweep of historical canonical rows (fix is upstream + rerun); new pricing sources.

## 4. Workstreams (design decisions D1–D4 → code)

### WS-1 — Deterministic, idempotent corridor projection (LINKING) — D1
- **NEW** `CorridorCorrelationKeyFactory` (pure utility, `ingestion.pipeline.clarification`): `corridorKey(networkId, txHash) → "BYBIT-CORRIDOR:<net>:<canonicalTxHash>"` + canonical counterpart endpoint. No `Instant.now()`, no DB reads. Single source of truth.
- `BybitTransferContinuityRepairService`: replace `size() == 1` hard gate and "already-paired"/`createdAt` sensitivity with a **deterministic canonical-leg selector** (stable tiebreaker: FUND deposit anchor → lowest `_id`); re-stamp the corridor triple (`correlationId`, `continuityCandidate=true`, `matchedCounterparty`) **idempotently on every run**.
- `BybitInternalTransferPairer`: `dedupSameSignMirrors()` keeper selection deterministic by stable `_id`; corridor anchors **immune** to demotion/re-key; `pairCrossUidUniversalTransfers` second pass made order-independent.
- `OnChainInternalTransferPairRepairService`: never overwrite a `BYBIT-CORRIDOR:` corrId with a generic `internal-tx:` key.

### WS-2 — Carry-continuity rule in replay — D2
- `ReplayTransferClassifier.isBybitCexCorridor`: split into **direction-aware** predicates — `isCexWithdrawalCorridorInbound` (CEX→wallet; spot ACQUIRE legal) vs `isCexDepositCorridor` (wallet→CEX; spot fallback **forbidden**, missing carry is an error).
- `TransferReplayHandler.applyTransfer`: gate the spot fallback behind the withdrawal-direction predicate only; deposit-direction misses route to the conservation guard.

### WS-3 — Orphan-carry conservation guard (REPLAY) — D3
- **NEW** `CorridorBasisConservationGuard` (replay support): end-of-replay sweep over `PendingTransferStore` for residual **covered** carries in `corr-family:` / `bridge:` / `bridge-settlement:` queues above ε.
- `PendingTransferStore`: read-only `residualCoveredCarries()` view (no new mutation path).
- Orchestrator (`AvcoReplayService`/dispatcher): invoke guard after last event; surface a structured replay blocker to `results/blockers.md`.
- **Rollout:** emit WARN + blocker first; promote to **hard-fail** after one clean full-rebuild + incremental cycle confirms zero orphans.

### WS-4 — RC-7 bridge inheritance — D4
- Linking deterministically stamps the bridge destination with the shared `bridge:lifi:<outHash>` correlation, `continuityCandidate=true`, `counterpartyType=BRIDGE` (RC-4 / ADR-027). Bridge `CARRY_IN` inherits the bridge `CARRY_OUT` basis; uncovered tail promotes at cross-network market-at-timestamp, **never covered $0** (else uncovered + PENDING). D3 guard covers bridge queues identically.

### WS-5 — Mongo indexes (supporting determinism)
- Add/confirm compound index `normalized_transactions { networkId: 1, txHash: 1, source: 1 }` (drives deterministic corridor lookup; makes input set order-independent).
- Confirm `{ source: 1, correlationId: 1 }` for corridor-anchor recognition / idempotent re-stamp. No schema change, no new collection.

### WS-6 — Docs + ADR
- Create `docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md` (Proposed → Accepted on merge).
- Update `docs/pipeline/cost-basis/03-basis-pools-and-carry.md` and `docs/adr/INDEX.md`.

## 5. Acceptance criteria (from BA spec)

- **AC-1** every on-chain↔CEX carry-out's released basis is received by exactly one paired credit (no orphan).
- **AC-2** when a matched carry-out exists, the credit inherits its basis — no residual/$0 fallback.
- **AC-3 (determinism)** incremental refresh == full rebuild on affected corridors: bit-identical AVCO/basis/PnL; re-materialized link docs do not change the outcome.
- **AC-4 (idempotency/order-stability)** N repeated refreshes yield identical pairing/basis; outcome independent of leg arrival order.
- **AC-5 (guard)** fails loudly when a `CARRY_OUT` basis is not received by a paired credit.
- **AC-6 (RC-7)** bridge `CARRY_IN` inherits `BRIDGE_OUT` basis; never covered $0 (else uncovered + PENDING).
- **AC-7** genuine orphan IN (no on-chain carry-out) still routes to generic `ACQUIRE`; guard does not fire.
- **AC-8** post-fix `conservationDeltaUsd` after incremental refresh within **|≤ $71|** (consistent with −20.13 baseline).
- **AC-9** MANTLE ETH avco restored to corridor-correct (~$3,030, not $540).
- **AC-10** `FAMILY:ETH` CARRY net not collapsed.
- **AC-11** fix lands upstream (linking + replay), validated by rerun — no sweep of canonical rows.
- **AC-12** unit + replay tests (incl. determinism + guard) green.
- **AC-13** no regression on Bybit/LiFi corridors (RC-2/RC-4), RC-3 PENDING, RC-8 OOS.

## 6. Tests (from BA spec)

Unit: T-1 credit inherits carry-out basis; T-2 no orphan; T-3 MANTLE inherited basis; T-4 CEX→on-chain symmetry; T-5 genuine orphan IN → ACQUIRE; T-6 RC-7 inherit; T-7 RC-7 empty source → PENDING (no $0); T-8 partial/batched Σ-conservation.
Replay/integration: T-9 full-rebuild vs incremental-refresh determinism (bit-identical); T-10 idempotent N-refresh; T-11 guard fires on orphan; T-12 guard no false positive; T-13 FAMILY:ETH net conserved.

## 7. Edge cases (in scope)

Partial deposit (pro-rata basis); batched deposits (Σ received == released, no double-credit); inbound-first ordering; CEX→on-chain direction; fee/dust legs (don't fragment principal basis); missing counterpart → guard; genuine orphan IN → ACQUIRE; repeated refreshes; FAMILY rollup across networks; OOS assets excluded symmetrically; re-materialized link docs.

## 8. Affected files (design targets)

| File | Change |
|------|--------|
| `ingestion/pipeline/clarification/CorridorCorrelationKeyFactory.java` (new) | Pure corridor key derivation |
| `ingestion/pipeline/clarification/BybitTransferContinuityRepairService.java` | Deterministic canonical-leg selection; idempotent re-stamp |
| `ingestion/pipeline/bybit/BybitInternalTransferPairer.java` | Stable-`_id` keeper; protect corridor anchors from dedup/re-key |
| `ingestion/pipeline/clarification/OnChainInternalTransferPairRepairService.java` | Never overwrite `BYBIT-CORRIDOR:` corrId |
| `costbasis/application/replay/support/ReplayTransferClassifier.java` | Direction-aware corridor predicate |
| `costbasis/application/replay/handler/TransferReplayHandler.java` | Spot fallback only CEX→wallet; deposit miss → guard |
| `costbasis/application/replay/.../CorridorBasisConservationGuard.java` (new) + `state/PendingTransferStore.java` | End-of-replay orphan detection |
| `ingestion/pipeline/clarification/BridgePairLinkSupport.java` (+ RC-4/ADR-027 linking) | Deterministic `bridge:lifi:<outHash>` + `BRIDGE` stamping (RC-7) |
| `docs/adr/ADR-029-...md`, `docs/pipeline/cost-basis/03-basis-pools-and-carry.md`, `docs/adr/INDEX.md` | Docs |

## 9. Risks & mitigations

- Determinism fix perturbs other passing corridors → AC-13 / T-9..T-13 regression coverage.
- Guard false positives on legit orphan IN / OOS → T-12 / AC-7 / RC-8.
- Deterministic keeper picks wrong row when genuinely ambiguous → stable tiebreaker (FUND anchor → lowest `_id`); residual ambiguity logged as corridor blocker, not silently mis-paired.
- Idempotent re-stamp fights another re-keying pass → corridor-anchor protection in pairer + on-chain repair.
- "Bit-identical" is metric-sensitive → assert exact-asset AVCO equality on affected corridors (`asset_ledger_points` CARRY_IN avco/basisDelta), keep exact-asset and family/proof-clean metrics separate.

## 10. Open questions (decide at/after handoff)

- Q1: guard severity — hard-fail (block snapshot) vs loud WARN/breach. Plan: WARN→hard-fail staged rollout (WS-3).
- Q2: asset-changing CEX corridors — confirm out of scope vs fold into route-settlement.
- Q3: CEX-pairing tolerance band (qty/time) — reuse existing carry tolerance vs corridor-specific.

## 11. Rebuild & verification (per prod-rebuild-workflow)

1. `./gradlew :backend:test` green (incl. new T-1..T-13).
2. Full prod rebuild+replay: `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (no pricing-cache clear; no pricing source changed).
3. **Incremental refresh** of the affected corridor; assert AC-3 (bit-identical) + AC-8 (`conservationDeltaUsd` within |≤ $71|) + AC-9 (MANTLE ETH avco ~$3,030) + zero corridor/bridge blockers.
4. Re-run financial-logic-auditor sign-off (RC-9 closed, RC-7 closed, no RC-1/2/4/5/6/8 + spoof regression).

## 12. Suggested execution order

WS-1 → WS-2 → WS-3 (WARN mode) → WS-4 → WS-5 → tests (WS-6 docs alongside) → rebuild + incremental-refresh determinism verify → promote guard to hard-fail → auditor sign-off.
