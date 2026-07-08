# ADR-029 — Deterministic on-chain↔CEX Corridor Basis Continuity

**Date**: 2026-06-13
**Status**: Proposed
**Related**: ADR-019 (corridor carry policy), ADR-020 (bridge late-carry pass-through + RC-2/RC-4 cross-network corridor), ADR-023 (Bybit corridor spot basis), ADR-027 (LiFi destination discovery / corridor linking)
**Implements**: `docs/tasks/rc9-cex-corridor-basis-continuity-implementation-plan.md` (WS-1..WS-6, RC-9 + RC-7)

---

## Context

A clean full rebuild conserved (`conservationDeltaUsd = −20.13`). A real incremental refresh
regressed it to **+11,070.87** for the same data. Root cause **RC-9**: in corridor
`ARBITRUM → Bybit (CEX) → MANTLE`, the on-chain `INTERNAL_TRANSFER` deposit leg released a
`CARRY_OUT` of **$9,272.11** (3.06 ETH @ avco ≈ $3,030), but the Bybit-side deposit `CARRY_IN`
credited only a stale FUND residual of **$212.99**. The $9,272.11 carry-out was **orphaned**; ETH
returns on MANTLE re-acquired at avco **$540**, fabricating ~**+$3,481** unrealized.

The corridor was correct in the prior full rebuild. The incremental refresh re-materialised the
Bybit corridor link docs, and the stateful, order-dependent linking passes (`size()==1` gates,
"already-paired"/`createdAt` sensitivity, `blockTimestamp`-ordered keeper selection) produced a
**different correlation** → a different replay queue key → a different (wrong) credit basis.

**RC-7** is the bridge variant: a destination bridge `CARRY_IN` landed at avco $0 because it did
not inherit the source bridge `CARRY_OUT` basis (LiFi `bridge:lifi:<outHash>` corridor).

---

## Invariant

> A corridor credit (`CARRY_IN`) **inherits its matched counterpart `CARRY_OUT`'s released basis**
> — never a residual/spot/$0 — and **no released covered basis may be orphaned**. The corridor
> correlation triple is a **pure, idempotent, order-stable function of `(networkId,
> canonicalTxHash, assetFamily)`**, so an incremental refresh of an affected corridor yields
> **bit-identical** `corr-family:` / `bridge:` queue keys and `CARRY_IN` AVCO as a full rebuild.

---

## Decisions

### D1 — Deterministic, idempotent corridor projection (LINKING)

- **NEW** `CorridorCorrelationKeyFactory` (pure utility, `ingestion.pipeline.clarification`):
  `corridorKey(networkId, txHash) → "BYBIT-CORRIDOR:<net>:<canonicalTxHash>"` (prefix retained for
  back-compat) plus `bybitSubAccountEndpoint(...)` for the canonical counterpart endpoint. No
  `Instant.now()`, no DB reads — single source of truth.
- `BybitTransferContinuityRepairService` drops the `compatibleBybitRows.size() == 1` hard gate and
  the "already-paired"/`createdAt` sensitivity. It selects the canonical Bybit leg
  deterministically (wallet-endpoint-matching FUND deposit anchor → lowest `_id`) and re-stamps the
  corridor triple (`correlationId`, `continuityCandidate=true`, `matchedCounterparty`) idempotently
  on every run.
- `BybitInternalTransferPairer`: `dedupSameSignMirrors()` keeper selection gains a lowest-`_id`
  tiebreaker; corridor anchors are never re-keyed by `pairCrossUidUniversalTransfers` (which is now
  sorted by stable `_id`).
- `OnChainInternalTransferPairRepairService` never overwrites a `BYBIT-CORRIDOR:` corrId with a
  generic `internal-tx:` key.
- `BybitStreamAuthorityCollapser` excludes corridor legs from every mirror/unify/demote pass. A
  Bybit row is a corridor leg when it carries a `BYBIT-CORRIDOR:` corrId **or** an on-chain
  `txHash` (internal UTA/FUND/EARN stream transfers never have a chain txID). On a full rebuild the
  corridor deposit is `EXTERNAL_TRANSFER_IN/OUT` at collapse time and never enters the collapser;
  on an incremental refresh the persisted deposit is already rewritten to `INTERNAL_TRANSFER`, so
  without this guard the collapser re-keys it onto a `bybit-collapsed-v1:` queue — orphaning the
  matched on-chain `CARRY_OUT` and collapsing the Bybit AVCO back to the spot-fallback basis. The
  intrinsic `txHash` signal is stable across re-materialization (ObjectIds differ between rebuild
  and refresh; the chain txHash does not), so the corridor credit joins the same
  `corr-family:BYBIT-CORRIDOR:…` queue bit-identically in both cycles.

**Addendum (RC-9): extension to the remaining stream-mirror/pairing passes.** D1's "deterministic,
idempotent, pure function of the candidate set" principle was previously enforced only on the
paths listed above. It is now also restored on the following paths, all of which select one
canonical document among 2+ tied/near-tied candidates and previously broke the tie on the leaked
Mongo scan order rather than on a stable key:

- `BybitStreamAuthorityCollapser`: the `collapseMirrors()` main query now carries an explicit
  ascending `_id` `Sort`; `unifyOpposingCorrelations`'s bucket sort and `demoteResidualMirrors`'s
  canonical-leg selection both end in a lowest-`_id` tiebreak (the latter via a local
  timestamp-then-`_id` comparator, not `comparePriorityThenId` — its bucket can legitimately span
  multiple sub-accounts with incomparable `canonicalPriority` scales); `demoteEventCountMirrors`'s
  `canonicalSource` selection gains a secondary `source` string-compare tiebreak.
- `BybitInternalTransferPairer`: `pairBroadEconomicFingerprint`, `pairDemotedEconOrphans`,
  `repairSingletonPairs`, `pairBundles`, and `pairSameWalletRoundTrips` all sort candidates with a
  trailing lowest-`_id` tiebreak, mirroring the pattern already shipped on `dedupSameSignMirrors`.
- `BybitEarnPrincipalTransferPairer`: `pairEarnPrincipalTransfers`'s corridor sort gains the same
  tiebreak; `pairCoEventSiblings`'s `isPreferredSibling` gains a lowest-`_id` fallback for the
  "both candidates still unclaimed" case (the corridor-sort fix alone only fixes iteration order,
  not this method's own tie among multiple free candidates).
- `BybitStakingConversionPairer`: `pairConversions`'s `group.sort(...)` gains the same tiebreak.

The shared `idTiebreak()` helper lives in `BybitStreamAuthorityCollapserSupport` and is reused by
every path above. Regression coverage lives in `BybitStreamAuthorityCollapserTest`,
`BybitInternalTransferPairerTest`, `BybitEarnPrincipalTransferPairerTest`,
`BybitStakingConversionPairerTest` (fix-scoped reordered-scan unit tests), and the
system-property-gated whole-chain `BybitRepairChainIdempotencyIntegrationTest`.

### D2 — Carry-continuity rule in replay

- `ReplayTransferClassifier` splits the corridor predicate into direction-aware predicates:
  `isCexWithdrawalCorridorInbound` (CEX → wallet; spot ACQUIRE legal because the released basis
  lives on the untracked CEX spot ledger) vs `isCexDepositCorridor` (wallet → CEX; spot fallback
  **forbidden** — a missing carry is a defect).
- `TransferReplayHandler.applyTransfer` gates the spot fallback behind the **withdrawal-direction**
  predicate only. Deposit-direction (and on-chain↔on-chain) corridor misses route to the
  pending-transfer/guard path instead of fabricating a spot basis that masks the orphan.

### D3 — Orphan-carry conservation guard (REPLAY)

- **NEW** `CorridorBasisConservationGuard` (`costbasis.application.replay.support`): an
  end-of-replay sweep over `PendingTransferStore` for residual **covered** carries in
  `corr-family:` / `bridge:` / `bridge-settlement:` queues above an epsilon.
- `PendingTransferStore` exposes a read-only `residualCoveredCarries(epsilon)` view (no new
  mutation path). The orchestrator (`AvcoReplayService`) invokes the guard after the last event.
- **Rollout (Q1):** `SEVERITY = WARN` first — structured breach log, no replay failure. Promotion
  to `HARD_FAIL` is the single-line change of `CorridorBasisConservationGuard.SEVERITY`, to be
  flipped only after a clean full-rebuild + incremental cycle confirms zero orphans.

### D4 — RC-7 bridge inheritance

- Linking deterministically stamps the bridge destination with the shared `bridge:lifi:<outHash>`
  correlation, `continuityCandidate=true`, and `counterpartyType=BRIDGE` (RC-4 / ADR-027), so the
  destination `CARRY_IN` inherits the source `CARRY_OUT` basis across networks. Any uncovered tail
  promotes at cross-network market-at-timestamp and **never** fabricates a covered $0 (else
  uncovered + PENDING). The D3 guard covers the bridge queues identically.

---

## Consequences

- An affected corridor produces bit-identical `corr-family:`/`bridge:` queue keys and `CARRY_IN`
  AVCO under incremental refresh and full rebuild (determinism / idempotency / order-stability).
- MANTLE ETH avco restored to corridor-correct (~$3,030), the $9,272.11 carry-out is inherited, not
  orphaned.
- Genuine orphan CEX→wallet withdrawal IN (no on-chain carry-out) still routes to a spot ACQUIRE;
  the guard stays silent.
- The guard is observability-first (WARN) so it cannot regress a passing snapshot during rollout.

---

## Open questions

- **Q1 (guard severity):** WARN → HARD_FAIL staged rollout. Flag: `CorridorBasisConservationGuard.SEVERITY`.
- **Q2 (asset-changing CEX corridors):** out of scope; fold into route-settlement later if observed.
- **Q3 (tolerance band):** residual epsilon currently `$1.00`
  (`CorridorBasisConservationGuard.RESIDUAL_BASIS_EPSILON_USD`); revisit vs corridor-specific band.
