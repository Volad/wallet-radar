# Bybit collapser determinism fix — implementation plan

> **Revision note:** this plan was revised after Phase 3 parallel review (financial-logic-auditor,
> business-analyst, system-architect all returned PASS WITH CHANGES). All 13 required changes from
> that review are incorporated below. See the `## Revision changelog` section at the end for a
> summary of what changed and why.

## Origin

> **Note on the admin endpoint referenced below (added in this revision):** the symptom that
> originally surfaced this bug was observed via a temporary admin endpoint
> (`POST /api/v1/admin/integrations/{sessionId}/repair-bybit-and-replay`) that has since been
> **deleted** by explicit user decision ("I never asked for an admin API to be invented... a real
> admin API will be designed much later — this is premature now"). The endpoint, its controller
> method, and its now-unused `BybitRepairAndReplayResult` record have been removed from
> `IntegrationPipelineAdminService`/`AdminIntegrationPipelineController`; the pre-existing,
> legitimate `fullRebuildBybit` admin endpoint is untouched and unaffected. This plan's Acceptance
> Criteria have been redesigned around a JUnit integration test invoking the same underlying
> `@Service` beans directly (no HTTP layer) — see "Acceptance criteria" below. The description
> immediately below is kept as historical context for how the bug was originally discovered; it no
> longer describes a currently-invokable code path.

Two consecutive forced replays via what was at the time a non-destructive admin endpoint
(`POST /api/v1/admin/integrations/{sessionId}/repair-bybit-and-replay`, since deleted — see note
above; it reran earn-principal pairer → internal-transfer pairer → stream authority collapser →
principal dedupe → forced `CostBasisReplayJob`) against the same underlying data produced
**different** `bybit-collapsed-v1:<hash>` correlation-id assignments for the same underlying legs,
and on at least one run tripped `CorridorBasisConservationGuard` (`HARD_FAIL`, no override) with real
breaches: `severity=HARD_FAIL breaches=4 totalOrphanedBasisUsd=924.9274570598425426405403658348555`
across `FAMILY:ETH` ($499.82), `FAMILY:MNT` ($200.79, collapser-attributable), `FAMILY:USDT`
($102.28). A fourth breach, `bybit-earn-carry:33625378:FAMILY:MNT` ($122.04), is a pre-existing,
separately-triaged raw-evidence gap in Bybit Earn extraction for UID 33625378 (2025-09-26 to
2025-10-05) — confirmed out of scope here, tracked durably in `docs/tasks/financial-audit-followups.md`
(see Scope and Acceptance Criteria below for why this must not be folded into this plan).

`financial-logic-auditor` Phase 1 classification (`results/bybit-collapser-determinism-blockers.md`,
`results/bybit-collapser-determinism-accounting-failure-analysis.md`,
`results/bybit-collapser-determinism-required-changes.md`) independently verified a prior
investigation's root-cause hypothesis against current source, **confirmed and refined** it (the
`demoteResidualMirrors` defect is worse than hypothesized — zero ordering discipline, not merely
"no tiebreak on close deltas"), and proved the defect pattern is **systemic** across multiple Bybit
normalization passes in the same package, two of which (`BybitInternalTransferPairer.dedupSameSignMirrors`,
`BybitCrossUidUniversalTransferPairer`) already contain a **shipped, working fix** for this exact
defect class (tagged `RC-9 D1` / documented in `docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md`).

**Correction from Phase 1:** `results/bybit-collapser-determinism-blockers.md` missed a sibling with
the identical defect shape — `BybitStakingConversionPairer.pairConversions()` (see Root cause and
Scope below). This is folded into Tier 2 scope in this revision.

## Scope

Backend only. Two tiers:

**Tier 1 (must-fix, this plan's primary target):**

1. `backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitStreamAuthorityCollapser.java`
2. `backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitStreamAuthorityCollapserSupport.java`

**Tier 2 (folded into this plan's scope, not deferred — see justification below):**

3. `backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitInternalTransferPairer.java`
   (5 of its 6 public methods; `dedupSameSignMirrors` is already fixed)
4. `backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitEarnPrincipalTransferPairer.java`
5. `backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitStakingConversionPairer.java`
   (**new in this revision** — see below)

### Why Tier 2 is folded in rather than deferred as a separate follow-up

**Primary argument (load-bearing correctness, not just review overhead):** `BybitInternalTransferPairer`
runs **before** `BybitStreamAuthorityCollapser` in production order
(`BybitNormalizationService.processNextBatch`, lines 78-104: `internalTransfer.repairAll() →
collapser.collapseMirrors() → earnPrincipal.pairEarnPrincipalTransfers() →
principalExclusivity.demoteDuplicatePrincipalEvents() → stakingConversion.pairConversions() →
botTransferCostBasis.computeBotCostBasis()` — note `computeBotCostBasis` is untouched by this plan
and listed only for completeness of the observed call sequence). If `BybitInternalTransferPairer`'s
own non-determinism is left
unfixed, the exact candidate **set** fed into `collapseMirrors()` can differ run-to-run even after
Tier 1 lands on its own — for example, if `pairBroadEconomicFingerprint` non-deterministically
excludes-from-accounting a different sibling leg on run 2 than run 1, that leg's `continuityCandidate`/
`excludedFromAccounting` state changes what `collapseMirrors()`'s own `mongoOperations.find()` query
returns, before `collapseMirrors()`'s internal logic ever runs. This means **Tier 1's own idempotency
acceptance criteria (see Acceptance Criteria below) could fail for reasons entirely outside Tier 1's
files**, making `BybitInternalTransferPairer` a genuine dependency of Tier 1's correctness claim, not
an optional adjacent cleanup. `BybitStakingConversionPairer.pairConversions()` is invoked directly
from `BybitNormalizationService.processNextBatch` alongside the other passes (same production-path
justification) and its `bybit-staking-conv-v1:`-tagged, `continuityCandidate=true` output routes to
the identical `corr-family:` guarded queue via `ReplayPendingTransferKeyFactory.transferKey()`'s
generic continuity-candidate path (lines 117-121) — so the same argument applies to it directly for
the `corr-family:` queue-sharing reason (see secondary argument below), though it does not sit
upstream of the collapser in production order the way `BybitInternalTransferPairer` does.

**Secondary argument (mechanical similarity — weaker, and only fully applies to
`BybitEarnPrincipalTransferPairer` and `BybitStakingConversionPairer`, not to
`BybitInternalTransferPairer`):** every Tier 2 method writes into the exact same `corr-family:`/replay
surface guarded by `CorridorBasisConservationGuard`, shares the identical root cause (unsorted
`find()` feeding a non-total-order sort/selection), and the fix pattern is a **mechanical, low-risk,
already-proven one-liner-per-site** (`.thenComparing(idTiebreak())` or an equivalent explicit
tiebreak) — not a new algorithm. Splitting 7 near-identical one-line fixes across the same
file/package into separate plans would cost more in review/rebuild overhead than it saves in
isolation, and leaving them unfixed while fixing only `BybitStreamAuthorityCollapser` would leave the
*same* class of live-pipeline non-determinism unresolved for no principled reason.

**Explicitly NOT in scope** (tracked durably in `docs/tasks/financial-audit-followups.md`, created as
part of this plan's action items — see below):

- **(Superseded by the admin endpoint's removal — kept here for history, resolved in
  `docs/tasks/financial-audit-followups.md` FU-2.)** The now-deleted admin `repair-bybit-and-replay`
  endpoint's pass order (`earnPrincipal → internalTransfer → collapser → principalDedupe`) did not
  match production's order (`BybitNormalizationService.processNextBatch`, lines 78-104:
  `internalTransfer → collapser → earnPrincipal → principalDedupe → stakingConversion → ...`). This
  was a real, separate defect while the endpoint existed, but since the endpoint has been deleted
  (see Origin note above), there is no longer a live, currently-shipped divergent call sequence to
  reconcile. This plan's Acceptance Criteria now verify idempotency exclusively under the actual
  **production** order via a direct-service-call JUnit integration test (see Acceptance Criteria
  below) — there is no separate "admin-endpoint order" left to verify against. `financial-audit-followups.md`
  FU-2 retains the underlying architectural observation (two independently-hand-maintained copies of
  "the same" repair chain can drift) as a forward-looking design principle for if/when a real admin
  API is designed later.
- `ReplayPendingTransferMatcher`/`findUniqueCompatibleQueueIndex`, ADR-020/ADR-043 corridor-carry
  policy, and the `bybit-earn-carry:` FIFO queue semantics — untouched by this plan; none of the
  Tier 1/2 files reference the shared matcher.
- The `bybit-earn-carry:33625378:FAMILY:MNT` raw-evidence gap — separate, pre-existing, not a
  determinism defect.

## Root cause (line-cited)

### RC-a — `unifyOpposingCorrelations`: greedy nearest-neighbor match over a non-total-order sort

```480:513:backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitStreamAuthorityCollapser.java
            bucket.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = 0; i < bucket.size(); i++) {
                NormalizedTransaction left = bucket.get(i);
                if (reKeyed.contains(left.getId())) {
                    continue;
                }
                int leftSign = principalSign(left);
                if (leftSign == 0) {
                    continue;
                }
                NormalizedTransaction bestRight = null;
                Duration bestDelta = BUCKET_DRIFT_WINDOW.plusSeconds(1);
                for (int j = i + 1; j < bucket.size(); j++) {
                    NormalizedTransaction right = bucket.get(j);
                    if (reKeyed.contains(right.getId())) {
                        continue;
                    }
                    int rightSign = principalSign(right);
                    if (rightSign == 0 || rightSign == leftSign) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (delta.compareTo(BUCKET_DRIFT_WINDOW) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestRight = right;
                    }
                }
```

`Comparator.comparing(getBlockTimestamp, ...)` is not a total order when two candidates share a
`blockTimestamp` (routine for co-emitted/near-simultaneous legs). `List.sort` is stable, so tied
elements retain their pre-sort order — the unsorted Mongo scan order from `collapseMirrors()` line
93. The inner loop's `delta.compareTo(bestDelta) < 0` (strict) then resolves any delta tie by
"whichever tied candidate appears earlier in this leaked order," which differs run-to-run whenever
the underlying `mongoOperations.find()` scan order differs (expected after any in-place `saveAll`
between runs, even against unchanged logical data).

### RC-b — `demoteResidualMirrors`: canonical-leg selection with zero ordering discipline

```392:420:backend/core/src/main/java/com/walletradar/application/cex/normalization/venue/bybit/BybitStreamAuthorityCollapser.java
        Map<String, List<NormalizedTransaction>> broadGroups = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            String key = broadSignature(tx);
            if (key == null) {
                continue;
            }
            broadGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int residual = 0;
        for (List<NormalizedTransaction> bucket : broadGroups.values()) {
            if (bucket.size() < 3) {
                continue;
            }
            NormalizedTransaction canonicalDebit = null;
            NormalizedTransaction canonicalCredit = null;
            for (NormalizedTransaction tx : bucket) {
                if (tx.getCorrelationId() != null
                        && tx.getCorrelationId().startsWith(COLLAPSED_CORR_PREFIX)) {
                    int sign = principalSign(tx);
                    if (sign < 0 && canonicalDebit == null) {
                        canonicalDebit = tx;
                    } else if (sign > 0 && canonicalCredit == null) {
                        canonicalCredit = tx;
                    }
                }
            }
```

`bucket` is populated by iterating `allDocs` (raw, unsorted Mongo scan order) into a `HashMap`; no
sort of any kind precedes the `canonicalDebit`/`canonicalCredit` pick. When a `broadSignature`
group (`uid+family+|qty|`, 60s-bucketed, ≥3 members) contains 2+ already-`bybit-collapsed-v1:`-tagged
candidates of the same sign (realistic once RC-a has non-deterministically tagged different subsets
across runs), the pick is a pure, unweighted function of Mongo scan order. This is strictly worse
than RC-a: there isn't even a flawed tiebreak — there's no ordering criterion at all.

**Important nuance resolved in this revision (see Changes §3 below):** `broadSignature`'s grouping
key (`uid|family|absQty|bucket`) has **no subAccount component** — by design, since this pass exists
specifically to catch cross-subAccount UTA↔FUND↔EARN mirrors. This means 2+ same-sign
`bybit-collapsed-v1:`-tagged candidates in one `broadGroups` bucket can legitimately come from
*different* subAccounts (EARN/UTA/FUND each have their own independent `canonicalPriority` ranking
scale — see `BybitStreamAuthorityCollapserSupport.canonicalPriority`). Comparing `canonicalPriority`
ints across different subAccount scales is not economically meaningful here, unlike at the
`pickCanonical`/`demoteEventCountMirrors` call sites where the grouping key already pins an exact
subAccount/wallet. The fix below therefore uses a **local timestamp-then-`_id` comparator**, not
`comparePriorityThenId`.

### Why this produces genuine orphans (not cosmetic relabeling), traced through the consumer

`ReplayPendingTransferKeyFactory` routes every `bybit-collapsed-v1:`-tagged leg to a `corr-family:`
queue whose key embeds the literal `correlationId` string (`ReplayPendingTransferKeyFactory.java:57-61,
151-158, 209-216`), and `CorridorBasisConservationGuard` sweeps exactly these queues for one-sided
residue. `demoteResidualMirrors`/`demoteEventCountMirrors` never invalidate a previously-assigned
`bybit-collapsed-v1:` id on a document that isn't itself re-selected this run — so if run 1 pairs
legs A+B (`hash(A,B)` persisted) and run 2's non-deterministic reselection instead pairs A+C
(`hash(A,C)`), leg B is left holding the now-orphaned, one-sided `corr-family:hash(A,B):...` queue.
A pure hash-relabel of an *unchanged* pairing could never produce a nonzero `orphanedQty` — the
reported nonzero breach totals are themselves proof that set membership, not just the label,
changed between runs. Full reasoning, including the boundary conditions under which
`demoteResidualMirrors`'s ±30s window / `broadSignature` 60s-bucket net or
`collapseOrphanMirrorsAdjacentToCollapsedPairs`'s 48h drift-neighbor check can (or cannot) rescue
the stranded leg, is in `results/bybit-collapser-determinism-blockers.md`.

**Important, and separate from the orphan question above: `CorridorBasisConservationGuard` runs
BEFORE `asset_ledger_points` persistence, not after.** In `AvcoReplayService`
(`corridorBasisConservationGuard.evaluate(replayState)` at line 148, `assetLedgerPointRepository.deleteAll()`
at line 158+), a `HARD_FAIL` throws and aborts the replay *before* the new ledger points are
persisted — the previously-persisted `asset_ledger_points` (and therefore every dollar figure
`capture-financial-snapshot.sh` reads) are left untouched by a failed run. The bug's `saveAll(dirty)`
writes inside `collapseMirrors()` itself (which mutate `normalized_transactions.correlationId`/
`excludedFromAccounting`) are **not** guarded and persist unconditionally regardless of what happens
later in replay. This distinction matters directly for Acceptance Criteria §6 (dollar-level
verification) below.

### Systemic siblings (Tier 2) — same shape, different files

| Method | Lines | Defect |
|---|---|---|
| `BybitInternalTransferPairer.pairBroadEconomicFingerprint` | 223-224, 258 | timestamp-only sort, strict-`<` best-delta pick |
| `BybitInternalTransferPairer.pairDemotedEconOrphans` | 134-135, 169 | same |
| `BybitInternalTransferPairer.repairSingletonPairs` | 380-381, 409 | same |
| `BybitInternalTransferPairer.pairBundles` | 465-466 | same (narrower blast radius) |
| `BybitInternalTransferPairer.pairSameWalletRoundTrips` | 557-558, 590 | same |
| `BybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers` (corridor sort) | 106-109 | same, feeds `pairCoEventSiblings`/`pairHoldFifoEqualPrincipal` |
| `BybitEarnPrincipalTransferPairer.pairCoEventSiblings` (`isPreferredSibling`) | 240, 270-272 | tiebreak only covers "one claimed, one free"; 2+ free ties still leak scan order |
| `BybitStakingConversionPairer.pairConversions` (**new in this revision**) | 118, 145 | identical shape: `group.sort(Comparator.comparing(c -> c.timestamp))` with no `_id` tiebreak, then strict-`<` best-delta pick with no `nullsLast` |

**Correction from Phase 1:** `results/bybit-collapser-determinism-blockers.md` did not identify
`BybitStakingConversionPairer.pairConversions()`. Confirmed during Phase 3 review: identical defect
shape at lines ~118-148, stamps `bybit-staking-conv-v1:<hash>` correlation ids with
`continuityCandidate=true` (line 190), which routes to the same guarded `corr-family:` queue via
`ReplayPendingTransferKeyFactory.transferKey()`'s generic continuity-candidate branch (lines
117-121), and is invoked directly from `BybitNormalizationService.processNextBatch`. Added to Tier 2
scope (see Scope, Root cause, and Changes).

**Already fixed** (precedent, cite in code review): `BybitInternalTransferPairer.dedupSameSignMirrors`
(317-323, `RC-9 D1`), `BybitCrossUidUniversalTransferPairer.pairCrossUidUniversalTransfers` (56-65)
and `.findFundingHistoryOutbound` (274-278), `BybitStreamAuthorityCollapserSupport.pickCanonical`
(261-269), `enforceCollapsedUtFundPairSymmetry.restoreCanonicalExcludedLeg` (219-250, via
`comparePriorityThenId`, 306-314).

## Changes (upstream-first)

### 0. Shared helper: `idTiebreak()` in `BybitStreamAuthorityCollapserSupport`

To avoid the same `.thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(Comparator.naturalOrder()))`
lambda being inlined verbatim at 8+ call sites across 4 files, extract one package-visible static
comparator helper. All Tier 1 and Tier 2 files are in the same
`com.walletradar.application.cex.normalization.venue.bybit` package, so no new class or visibility
change is needed:

```java
// BybitStreamAuthorityCollapserSupport.java
static Comparator<NormalizedTransaction> idTiebreak() {
    return Comparator.comparing(NormalizedTransaction::getId,
            Comparator.nullsLast(Comparator.naturalOrder()));
}
```

Every code change below that adds an `_id` tiebreak uses `.thenComparing(idTiebreak())` instead of
the inline lambda.

### 1. `BybitStreamAuthorityCollapser.collapseMirrors()` (line 93)

Add an explicit ascending `_id` `Sort` to the initial `mongoOperations.find(query, ...)` call:

```java
List<NormalizedTransaction> docs = mongoOperations
        .find(query.with(Sort.by(Sort.Direction.ASC, "_id")), NormalizedTransaction.class)
        .stream()
        .filter(doc -> !isCorridorLeg(doc))
        .toList();
```

Defense-in-depth: most downstream selection becomes deterministic once steps 2-3 land, but a
stable base scan order removes ambiguity for any future pass added to this method without a
full re-audit.

### 2. `unifyOpposingCorrelations` sort (lines 480-481)

Add an `_id` tiebreak via the shared helper:

```java
bucket.sort(Comparator
        .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(idTiebreak()));
```

This alone makes the existing "first tied candidate wins" inner-loop logic (line 509) a pure,
reproducible function of the candidate set — no other line in the greedy-match loop needs to
change, matching the exact pattern already shipped in `dedupSameSignMirrors`.

### 3. `demoteResidualMirrors` canonical selection (lines 405-417)

**Revised in this Phase 3 pass** (see the "Important nuance" callout under Root cause): reuse of
`comparePriorityThenId` is **not** used here, because `broadSignature`'s grouping key has no
subAccount component, so `canonicalPriority` comparisons across candidates from different
subAccounts are not economically meaningful. Use a **local timestamp-then-`_id` comparator**
instead — this sidesteps the cross-subAccount incommensurability question entirely and better
reflects chronological pairing intent:

```java
Comparator<NormalizedTransaction> residualCanonicalOrder = Comparator
        .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(idTiebreak());

NormalizedTransaction canonicalDebit = null;
NormalizedTransaction canonicalCredit = null;
for (NormalizedTransaction tx : bucket) {
    if (tx.getCorrelationId() == null || !tx.getCorrelationId().startsWith(COLLAPSED_CORR_PREFIX)) {
        continue;
    }
    int sign = principalSign(tx);
    if (sign < 0 && (canonicalDebit == null || residualCanonicalOrder.compare(tx, canonicalDebit) < 0)) {
        canonicalDebit = tx;
    } else if (sign > 0 && (canonicalCredit == null || residualCanonicalOrder.compare(tx, canonicalCredit) < 0)) {
        canonicalCredit = tx;
    }
}
```

This is a stability fix, not necessarily an economic-correctness upgrade for which SPECIFIC legs
end up paired when 3+ genuinely valid candidates exist in one broad-signature bucket — see Risks.

### 4. `demoteEventCountMirrors` `canonicalSource` selection (lines 349-355)

Add a secondary string-compare tiebreak on `source` for completeness (currently unreachable in
practice since all 5 known stream tags have distinct priorities, but cheap and closes the gap for
any future 6th stream tag). This call site compares `canonicalPriority`/`source` primitives, not
`NormalizedTransaction` instances directly, so `idTiebreak()` does not apply here:

```java
if (priority < canonicalPriority
        || (priority == canonicalPriority && (canonicalSource == null || source.compareTo(canonicalSource) < 0))) {
    canonicalPriority = priority;
    canonicalSource = source;
}
```

### 5. `BybitInternalTransferPairer` (5 methods)

Apply `.thenComparing(idTiebreak())` to the `docs.sort(...)`/`window`/corridor-building calls in
`pairBroadEconomicFingerprint` (223-224), `pairDemotedEconOrphans` (134-135), `repairSingletonPairs`
(380-381), `pairBundles` (465-466), `pairSameWalletRoundTrips` (557-558) — mirroring the exact
change already applied to `dedupSameSignMirrors` in the same file. No change to the "strict `<`
best-delta" inner-loop logic is needed once the sort has a total order. Each of these 5 methods
loads its candidate set via a single `mongoOperations.find()` call (`pairBroadEconomicFingerprint`,
`repairSingletonPairs`, `pairBundles`, and `pairSameWalletRoundTrips` all route through the shared
private `loadSingletons()` helper at line 633-639; `pairDemotedEconOrphans` has its own query at
line 112) — this single-query-per-invocation shape is simpler than `collapseMirrors()`'s two-query
shape (see Regression test plan §4 below for why that distinction matters for test design).

### 6. `BybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers`

Add the `idTiebreak()` tiebreak to the `corridor.sort(...)` at lines 106-109. Separately,
`pairCoEventSiblings`'s `isPreferredSibling` (lines 240, 270-272) should gain a fallback tiebreak
for the "both candidates unclaimed" case (prefer the lowest `_id` when neither `isAlreadyClaimed`,
using `idTiebreak()`), since the corridor-sort fix alone only fixes iteration order, not this
method's separate ties-among-multiple-free-candidates gap.

### 7. `BybitStakingConversionPairer.pairConversions()` (**new in this revision**)

Add an `_id` tiebreak to the `group.sort(...)` at line 118. The candidate list here is a `List<Candidate>`
(a local record wrapping `tx`, not `NormalizedTransaction` directly), so `idTiebreak()` is reused via
`Comparator.thenComparing(keyExtractor, keyComparator)`:

```java
group.sort(Comparator
        .comparing((Candidate c) -> c.timestamp())
        .thenComparing(Candidate::tx, idTiebreak()));
```

No change to the inner best-delta loop (line 145, strict `<`) is needed once the sort has a total
order — same reasoning as Change #2. `idTiebreak()` is package-visible in
`BybitStreamAuthorityCollapserSupport` and `BybitStakingConversionPairer` is in the same package, so
no new import beyond the existing package access is required.

## Regression test plan

**The critical design constraint for `collapseMirrors()` specifically: a naive "call it twice with a
two-value `.thenReturn()` stub chain" test does NOT reproduce this bug, and worse, can silently test
the wrong thing.** `collapseMirrors()` makes **two separate** `mongoOperations.find()` calls per
single invocation: the main docs query (line ~93) and a second, separate correlationId-regex query
inside `enforceCollapsedUtFundPairSymmetry` (line ~185, `Criteria.where("correlationId").regex(...)`).
The existing test suite's established convention (see `BybitStreamAuthorityCollapserTest`,
e.g. `restoresExcludedFundOutboundWhenUtaInboundActiveInCollapsedPair` at lines 405-407,
`restoresExcludedUtaInboundCreditWhenFundOutboundActiveInCollapsedPair` at lines 447-449, and
`suppressesCorridorDepositAndStakeCycleCollapseGroupsOnFund` at lines 636-639) already stacks
`.thenReturn(...)` values in strict call order to match each **query site within one invocation**,
not across invocations. A naive `.thenReturn(orderA).thenReturn(orderB)` two-value chain feeds
`orderA`/`orderB` to run 1's TWO queries (main query, then symmetry query), not to run-1-vs-run-2 —
it would not test what this plan claims. **The new determinism regression test must therefore stub
FOUR values in strict sequence**: `[run1MainQuery, run1SymmetryQuery, run2MainQuery, run2SymmetryQuery]`,
where the run-2 pair reorders/reverses the run-1 pair's *same object instances* (so run 2 reflects
run 1's field mutations — id, `correlationId`, `excludedFromAccounting` — exactly as a real second
Mongo scan against the same now-mutated documents would).

This two-query-per-invocation shape is **unique to `collapseMirrors()`** among the methods this plan
touches — every Tier 2 method (and `BybitStakingConversionPairer.pairConversions()`) loads its
candidate set via exactly one `mongoOperations.find()` call per invocation (see Changes §5, §7), so
their reordered-scan tests only need the simpler two-value `.thenReturn(orderA).thenReturn(orderB)`
shape already used by the existing `dedupSameSignMirrors` test.

**Shared test utility (system-architect finding, incorporated here):** to avoid rewriting the same
"stub N orderings, run N times, assert field-level equality" logic ~9 times across this plan's test
additions, extract a small package-visible test helper (e.g.
`BybitDeterminismTestSupport.assertReorderInvariant(...)` in
`backend/core/src/test/java/com/walletradar/application/cex/normalization/venue/bybit/`), used by
both Tier 1 and Tier 2 fix-scoped tests. Its responsibility: given two (or more) differently-ordered
but object-identical candidate lists and a `Supplier<Integer>`/`Runnable` invoking the method under
test, assert that every mutable field of interest (`correlationId`, `excludedFromAccounting`,
`accountingExclusionReason`, `continuityCandidate`) is identical after each invocation. This does
**not** remove the need for `collapseMirrors()`'s test to correctly sequence its 4 stub values per
run (that part is method-specific and must stay explicit in that test) — the shared utility only
extracts the "run + assert field-level equality" boilerplate, not the stubbing itself.

1. **New test in `BybitStreamAuthorityCollapserTest`**: `collapseMirrorsIsIdempotentAcrossRepeatedInvocationsWithReorderedScan`
   (or similar name).
   - Fixture: 3+ candidates sharing `(uid, family, |qty|)` within the relevant drift windows —
     e.g. one FUND-out debit `D` and two UTA-in credits `C1`, `C2` at slightly different but
     equally-plausible deltas from `D` (or identical `blockTimestamp` to force an exact tie), all
     eligible for `unifyOpposingCorrelations`. Add a 4th document reachable only via
     `demoteResidualMirrors`'s wider `broadSignature` bucket to also exercise RC-b.
   - Stub `mongoOperations.find(...)` with the 4-value sequence described above:
     `.thenReturn(run1Main).thenReturn(run1Symmetry).thenReturn(run2Main).thenReturn(run2Symmetry)`,
     where `run2Main`/`run2Symmetry` are `run1Main`/`run1Symmetry` with candidates
     reordered/reversed (same object instances).
   - Call `collapseMirrors()` twice (capturing each `saveAll` call separately).
   - Assert **field-level equality** (via the shared test utility above): every document's final
     `correlationId`, `excludedFromAccounting`, `accountingExclusionReason`, `continuityCandidate`
     is IDENTICAL after run 2 compared to after run 1.
   - Additionally assert the "no genuine orphan" invariant directly: group the final active
     (`excludedFromAccounting != true`) documents by `correlationId` and assert every
     `bybit-collapsed-v1:`-prefixed group has both a debit and a credit member (mirrors what
     `CorridorBasisConservationGuard` would flag).
2. **Fix-scoped unit tests** for each Tier 1 change:
   - `unifyOpposingCorrelations`: given 3 candidates with two of them exactly tied in `|Δt|` to the
     third, assert the SAME `bestRight` is chosen regardless of the input list's initial order
     (parameterize the mock return order across 2-3 permutations of the same 3 documents).
   - `demoteResidualMirrors`: given a ≥3-member broad-signature bucket with 2 candidates already
     tagged `bybit-collapsed-v1:` on the same sign (including at least one variant where the two
     candidates belong to different subAccounts, to prove the fix does not depend on
     `canonicalPriority`), assert the SAME `canonicalDebit`/`canonicalCredit` is chosen regardless
     of scan order, and assert it is the timestamp-then-lowest-`_id` candidate.
3. **Fix-scoped unit tests** for Tier 2 (`BybitInternalTransferPairer`'s 5 methods,
   `BybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers`, and
   `BybitStakingConversionPairer.pairConversions`), mirroring the pattern of the existing
   (already-fixed) `dedupSameSignMirrors` / `BybitCrossUidUniversalTransferPairer` tests — add one
   reordered-scan idempotency test per method touched, using the two-value stub shape (single
   query per invocation) and the shared test utility above.
4. **No-regression checks**: re-run the full existing `BybitStreamAuthorityCollapserTest` suite
   (27 existing `@Test` methods spanning mirror demotion, event-count mirrors, orphan-drift,
   corridor/earn-principal protection, symmetry restoration) to confirm none of the ordering
   changes alter any single-run outcome for the already-covered fixtures — the fix must only remove
   ambiguity, not change behavior when there is no ambiguity to begin with.
5. **New whole-chain integration test (replaces the deleted admin endpoint as the verification
   mechanism — see Origin note and Acceptance Criteria below): `BybitRepairChainIdempotencyIntegrationTest`.**

   Items 1-4 above are Mockito-based unit tests scoped to individual methods — they do not, on
   their own, prove end-to-end idempotency of the *whole* repair chain against *real* Mongo-backed
   data the way the (now-deleted) admin endpoint's manual mongodump-and-repeat verification did.
   This new test closes that gap by calling the same underlying `@Service`/`@Component` beans the
   deleted admin method used to wire together — directly, with no HTTP layer, per the user's
   explicit rejection of introducing any admin API for this purpose.

   - **Location**: `backend/core/src/test/java/com/walletradar/application/cex/normalization/venue/bybit/BybitRepairChainIdempotencyIntegrationTest.java`
     (same package as the classes under test, matching this suite's existing convention).
   - **Why a full `@SpringBootTest` context, not hand-instantiation (checked against existing
     convention first):** every other test in this package (`BybitStreamAuthorityCollapserTest`
     and siblings) directly `new`s up the class under test with Mockito `@Mock`s for
     `MongoOperations`/`NormalizedTransactionRepository` — there is no existing Spring-context test
     precedent in this codebase to follow for the narrower services. That pattern does not scale to
     this test's scope, though: `CostBasisReplayJob` (needed to exercise the actual
     `CorridorBasisConservationGuard` sweep, not just the normalization passes) has an 11-parameter
     constructor pulling in `AccountingUniverseService`, `PricingDataGateService`,
     `AvcoReplayService`, `PipelineTelemetrySnapshotService`, and more — hand-wiring this graph
     would mean re-implementing Spring's DI container by hand and would be more fragile than using
     Spring itself. **This is therefore the first full-application-context Spring Boot test in this
     codebase** (the one existing `@SpringBootTest` in `ExecutorConfigTest` loads only 2 narrow
     `@Configuration` classes, not the full app) — call this out explicitly as new-but-necessary
     test infrastructure, not a silent precedent change.
   - **Mongo backing**: `org.testcontainers:mongodb` + `org.testcontainers:junit-jupiter` are
     **already declared test dependencies** in `backend/core/build.gradle.kts` (lines 33-35) but are
     currently **unused anywhere in the codebase** — this test would be their first actual use. Use
     a `@Testcontainers`-managed `MongoDBContainer`, with `spring.data.mongodb.uri` overridden via
     `@DynamicPropertySource` to point the Spring context at the container. This guarantees the test
     never touches a real/shared Mongo instance by construction (no manual scratch-URI bookkeeping
     needed), which is a stronger safety property than the deleted admin endpoint ever had.
   - **Seeding the container from the reported session's real data**: restore a `mongodump` backup
     of the affected session (the collections needed: `normalized_transactions`, `raw_transactions`,
     `bybit_extracted_events`, `historical_prices`, `asset_ledger_points`, `user_sessions`, and any
     other collections `AccountingUniverseService`/`PricingDataGateService` read) into the
     testcontainers Mongo instance in a `@BeforeAll` step, via `mongorestore` against the
     container's dynamically-assigned host port (either shelled out via `ProcessBuilder` if
     `mongorestore` is available on the CI/dev machine's `PATH`, or — more portably — via
     testcontainers' `execInContainer` using a `mongo`-image sidecar, or the same container image if
     it bundles `mongorestore`). The dump file path is supplied via a system property/environment
     variable (e.g. `-Dwr.test.bybit.dumpPath=...`), not hardcoded, since it is real session data
     that should not be committed to the repo.
   - **Known risk, stated explicitly rather than glossed over**: `AvcoReplayService`'s replay path
     may consult `PricingDataGateService` for price resolution. If the restored `historical_prices`
     collection already covers every price point this session's replay needs (expected, since this
     session was already fully backfilled and replayed for real at some point), the test should run
     with **zero live external network calls**. If a genuine coverage gap exists, the test will
     surface it as a pending-price/`NEEDS_REVIEW` symptom rather than a clean pass — that is an
     acceptable, informative failure mode for this test (it would indicate a real, separate pricing
     gap, not a false failure of the determinism fix), but it does mean this test's reliability
     depends on the completeness of the seeded dump, which must be validated once during
     implementation.
   - **Self-skip gate, since this is a manual/local verification aid, not a default CI-automated
     test** (no existing JUnit `@Tag`/Gradle `excludeTags` convention exists in this repo to build
     on, and inventing one is out of scope for this plan): gate the test with
     `@EnabledIfSystemProperty(named = "wr.test.bybit.dumpPath", matches = ".+")` (or an equivalent
     `Assumptions.assumeTrue(...)` guard in `@BeforeAll`), so it is skipped by default under
     `./gradlew :backend:core:test` / CI, and only runs when a developer explicitly supplies a dump
     path to verify this fix locally (or in a dedicated, manually-triggered CI job later, if the
     team decides to invest in that separately — not part of this plan).
   - **Test body**: autowire `BybitInternalTransferPairer`, `BybitStreamAuthorityCollapser`,
     `BybitEarnPrincipalTransferPairer`, `BybitPrincipalEventExclusivityService`,
     `BybitStakingConversionPairer`, and `CostBasisReplayJob` — the same beans the deleted admin
     method wired together, plus `BybitStakingConversionPairer` (added to scope in this plan's first
     revision). For `N` in `1..2` (or more):
     1. Call, in **production order** (`BybitNormalizationService.processNextBatch`, lines 78-104):
        `bybitInternalTransferPairer.repairAll()` →
        `bybitStreamAuthorityCollapser.collapseMirrors()` →
        `bybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers()` →
        `bybitPrincipalEventExclusivityService.demoteDuplicatePrincipalEvents()` →
        `bybitStakingConversionPairer.pairConversions()`.
     2. Call `costBasisReplayJob.runReplay(sessionId)` (the same forced single-session replay entry
        point the deleted admin method used) — `runReplayForSession` re-throws any
        `RuntimeException` (including `CorridorBasisConservationException`) rather than swallowing
        it, so a `HARD_FAIL` breach surfaces directly as a thrown exception
        (`assertThatNoException().isThrownBy(() -> costBasisReplayJob.runReplay(sessionId))` per
        run, mirroring `CorridorBasisConservationGuardTest`'s existing
        `assertThrows(CorridorBasisConservationException.class, ...)` pattern for the failure case).
     3. After each run, snapshot every affected session document's `correlationId`,
        `excludedFromAccounting`, `accountingExclusionReason`, `continuityCandidate` (direct Mongo
        read via the autowired `MongoOperations`/`MongoTemplate`).
   - **Assertions**: (a) run 2's snapshot is field-for-field identical to run 1's snapshot (zero
     churn); (b) `runReplay` throws no exception on any run (zero `HARD_FAIL` breaches); (c) no
     other test infrastructure needs to separately verify "production order," since this test
     *is* the production order — see the "Production-path verification" note under Acceptance
     Criteria below for why this removes a layer of indirection the previous (admin-endpoint-based)
     plan needed.
6. `./gradlew :backend:core:test` green (this excludes the new integration test above by design,
   per its self-skip gate — it is verified separately, manually, per Acceptance Criteria below).

## Docs impact

- **`docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md` §D1** — this ADR already
  states the exact governing principle ("Deterministic, idempotent corridor projection") and
  already documents the `dedupSameSignMirrors` lowest-`_id` fix and `BybitStreamAuthorityCollapser`'s
  corridor-leg exclusion. Add a short addendum noting that the D1 principle is extended to
  `BybitStreamAuthorityCollapser.unifyOpposingCorrelations`/`demoteResidualMirrors`/`demoteEventCountMirrors`,
  to the five newly-fixed `BybitInternalTransferPairer` methods, to
  `BybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers`, and to
  `BybitStakingConversionPairer.pairConversions` — i.e. "every Bybit normalization pass that selects
  one specific document among 2+ tied/near-tied candidates must end its ordering criterion in an
  explicit `_id` tiebreak." No new ADR needed — this is a bugfix restoring the already-decided D1
  policy to code paths it was not yet applied to, not a new decision.
- No `docs/pipeline/normalization/classification-spec.md` change needed — no classification rule
  changes, no new canonical type or lifecycle state.
- **Action item: create `docs/tasks/financial-audit-followups.md`** (new file, written alongside
  this plan revision) with durable stub entries for the items excluded from this plan's scope — see
  "Explicitly NOT in scope" above and the dedicated subsection below. `results/` artifacts are
  gitignored (`.gitignore:34`) and are not a durable record on their own.

## Acceptance criteria

These are the REQUIRED merge/session-level verification gate for this plan — cheap, non-destructive,
and sufficient to prove the fix works. (See the "Explicit deviation from standing rebuild policy"
subsection below for what is deliberately deferred and why.)

1. All new and existing unit tests green, `./gradlew :backend:core:test`.
2. **Idempotency, precisely defined**: for a fixed underlying `normalized_transactions` state (no
   new ingestion between runs), N ≥ 2 consecutive invocations of the repair chain — via the
   `BybitRepairChainIdempotencyIntegrationTest` direct-service-call test described in Regression
   test plan §5 — against the same session produce:
   - **Zero `bybit-collapsed-v1:`/`bybit-staking-conv-v1:` id churn** — every document's
     `correlationId` after run k+1 equals its value after run k, for every k in 1..N-1 (not merely
     "the same total count of ids changed" — literal per-document value equality).
   - **Zero `CorridorBasisConservationGuard` breaches** across all N runs (not just the final one —
     a fix that is deterministic-but-wrong on run 1 and then "stably wrong" on runs 2..N does not
     satisfy this).
3. **Verification mechanism**: run `BybitRepairChainIdempotencyIntegrationTest` (Regression test plan
   §5) against a `mongodump` backup of the reported session (or an equivalent fixture reproducing
   the same shape) restored into a disposable testcontainers Mongo instance — never against
   production or a shared database. Confirm the `bybit-collapsed-v1:`/`bybit-staking-conv-v1:` ids
   and `excludedFromAccounting`/`accountingExclusionReason` fields are byte-identical across N ≥ 2
   runs, and that `costBasisReplayJob.runReplay(sessionId)` throws no
   `CorridorBasisConservationException` on any run for the collapser-attributable families
   (`FAMILY:ETH`/`FAMILY:MNT`/`FAMILY:USDT`) — the `bybit-earn-carry:33625378:FAMILY:MNT` breach is
   expected to persist (separate, out of scope, tracked in `docs/tasks/financial-audit-followups.md`)
   and must not be miscounted as a regression. (No admin HTTP endpoint is used or reintroduced for
   this verification — see Origin note above.)
4. **Production-path verification (promoted from Risk-section prose in the original draft — required,
   non-optional gate; simplified in this revision).** The original draft needed a separate criterion
   here because the (now-deleted) admin endpoint's pass order diverged from
   `BybitNormalizationService.processNextBatch`'s production order, so an admin-endpoint-only
   verification would not have proven production-path idempotency. That indirection is now
   unnecessary: `BybitRepairChainIdempotencyIntegrationTest` calls the repair-chain beans directly
   and is written to exercise the **actual production order** (Regression test plan §5, step 1) —
   there is no separate divergent order left to reconcile. This criterion is retained, narrowed to:
   confirm `BybitRepairChainIdempotencyIntegrationTest`'s call sequence is kept in sync with
   `BybitNormalizationService.processNextBatch`'s current order at implementation time (a one-line
   diff check, not a second test run), so the test does not silently drift from production if the
   orchestration order ever changes.
5. **Full pairing-set diff sign-off gate (promoted from Risk-section prose — required, non-optional,
   named sign-off, not skippable).** Changing tie-break order can change WHICH legs get paired, not
   just which hash they get, for cases that were previously "accidentally" landing on a
   correct-looking pairing by luck of scan order. Before merge, a reviewer must explicitly compare
   the FULL set of `(correlationId → member legs)` groupings before vs. after the fix on a realistic
   multi-UID fixture (or the live session's snapshot) — not only the final hash strings — and record
   explicit sign-off that no grouping's economic membership changed in an unexpected way. This
   review is a named, required step of this plan, not an optional risk note.
6. **Dollar/AVCO-level financial snapshot criterion.** Using the existing
   `scripts/avco/capture-financial-snapshot.sh` (per-`(wallet, network, asset)` snapshot: `quantityAfter`,
   `avcoAfterUsd`, `totalCostBasisAfterUsd`, `cumulativeRealisedPnlUsd`) and
   `scripts/avco/compare-financial-snapshot.sh` tools — the same tools used as the acceptance gate
   in the sibling `corridor-basis-conservation-orphan-fix-implementation-plan.md` — capture a
   before-snapshot immediately before running `BybitRepairChainIdempotencyIntegrationTest`
   (Criterion 3) against the restored testcontainers Mongo instance, and an after-snapshot
   immediately after the test's final run, for the affected session. (Since the snapshot scripts
   read via `mongosh` against a Mongo URI, they work identically whether pointed at the disposable
   testcontainers instance or a real database — no admin endpoint involvement either way.)

   **Expected direction, stated explicitly (not left ambiguous):** `CorridorBasisConservationGuard`
   runs *before* `asset_ledger_points` persistence in `AvcoReplayService` (see Root cause above) — a
   `HARD_FAIL` blocks that replay attempt from ever persisting, so the currently-visible
   `asset_ledger_points`-derived dollar totals for this session already reflect whichever prior
   repair attempt *did* pass the guard, not a silently-corrupted "missing $802.89" state. This fix
   is therefore expected to be **dollar-neutral in aggregate** for a session already sitting on a
   guard-passing replay: no value is created or destroyed, only which specific pairing wins is
   pinned down. A **zero or near-zero** diff across all four fields for the affected session is the
   expected, asserted outcome. Any nonzero diff observed during verification must be individually
   explained by a specific pairing-membership change already surfaced in Criterion 5's diff review
   (i.e., the deterministic algorithm settling on a different — but still valid — pairing than
   whatever the last successful, guard-passing run happened to land on) — an unexplained nonzero
   diff is a blocking finding, not an accepted result.
7. **Installation-wide regression sweep.** Tier 1 and Tier 2 methods run for every Bybit session in
   the installation via `BybitNormalizationService.processNextBatch`, not just the one reported
   session. **This sweep does not use the deleted admin endpoint and never did per-session
   spot-checking through it** — it is, and remains, a live-system check performed via the normal
   `processNextBatch` path over time: after deployment, confirm **zero NEW
   `CorridorBasisConservationGuard` breaches across the whole installation** by comparing a
   pre-deploy vs. post-deploy count/list of `CORRIDOR_BASIS_CONSERVATION_SUMMARY` `HARD_FAIL` log
   entries (or, if a queryable breach record exists, a direct query) across all Bybit sessions —
   sourced from application logs / `AvcoReplayService`'s guard evaluation output as
   `processNextBatch` and its downstream replay naturally run for every session, not by manually
   invoking anything per-session.

### Explicit deviation from standing rebuild policy

Per `.cursor/rules/prod-rebuild-workflow.mdc`, a normalization-path change under
`backend/**/normalization/**` would ordinarily require a full `--skip-frontend` install-wide reset
and re-normalization immediately as part of this plan's verification. **This plan explicitly defers
that mandatory full rebuild** — this is called out here as a visible, one-time deviation from the
standing policy for explicit user sign-off, not a silent decision:

- The user has separately stated a preference for ONE consolidated full transaction reset to the
  initial normalization level, done once ALL currently-known follow-up items are implemented: this
  plan, the `bybit-earn-carry:33625378:FAMILY:MNT` ~$122 raw-evidence gap investigation, and the
  low-priority `KnownBridgeRouterRegistry` mislabel fix — not necessarily immediately after just
  this one plan.
- Criteria 2 through 7 above (the `BybitRepairChainIdempotencyIntegrationTest` direct-service-call
  verification against a disposable testcontainers-restored mongodump, run N≥2 times in production
  order + pairing-set diff sign-off + financial-snapshot diff + installation-wide log sweep) are
  cheap, non-destructive, and sufficient to prove this specific fix works in isolation, without
  requiring a full install-wide reset. None of them touch production or a shared database — the
  integration test runs entirely inside a disposable testcontainers Mongo instance.
- The mandatory full `--skip-frontend` installation-wide rebuild is **deferred, not skipped** — it
  will be bundled with the user's already-planned final consolidated reset once the other follow-up
  items are also implemented.
- **This is a one-time, explicitly-labeled deviation for this plan only.** It does not change the
  standing `prod-rebuild-workflow.mdc` policy for any other future normalization-path change, which
  should continue to require an immediate `--skip-frontend` rebuild unless a similarly explicit
  deviation is called out and signed off.

## Follow-up tracking (action item)

Create `docs/tasks/financial-audit-followups.md` (new file, lightweight stub entries, not full plan
treatment) with durable tracking for items excluded from this plan's scope, so they survive
independently of this plan doc being re-read later (`results/` artifacts are gitignored and not a
durable record — see `.gitignore:34`). Entries:

1. The `bybit-earn-carry:33625378:FAMILY:MNT` ~$122 raw-evidence gap (pre-existing, separate from
   this plan's determinism defect).
2. **(Reframed after the admin endpoint's removal.)** The specific `repair-bybit-and-replay` admin
   endpoint that used to hand-roll a divergent copy of the repair chain order has been deleted (see
   Origin note above) — there is no longer a live, currently-shipped divergent call sequence
   creating an active risk today. `fullRebuildBybit` (the one remaining admin endpoint) does not
   invoke the repair chain at all (it only resets sync windows and deletes data for a full
   re-backfill), so it does not share this risk. The underlying architectural observation is still
   worth keeping as a forward-looking design principle: two independently-hand-maintained call
   sequences of "the same" repair chain can drift, as this one did. Recommendation for if/when a
   real admin API is designed later (per the user's stated intent): ensure any future admin tooling
   that needs to invoke the Bybit repair chain calls `BybitNormalizationService.processNextBatch`'s
   passes directly (or a shared orchestration helper extracted from it), rather than hand-rolling a
   second, independently-ordered copy of the same sequence.
3. The ArchUnit/structural-guard feasibility question for enforcing `_id`-tiebreak ordering
   discipline across `mongoOperations.find()` call sites — considered and rejected during this
   plan's review (confirmed infeasible/overbroad: 148 call sites across 65 files in
   `backend/core/src/main/java` alone, most legitimately order-insensitive) — recorded so it is not
   re-litigated in a future cycle.

## Risks

- **`demoteResidualMirrors`'s timestamp-then-`_id` tiebreak** (Changes §3) is a stability fix, not
  necessarily an economic-correctness upgrade for which SPECIFIC legs end up paired when 3+
  genuinely valid candidates exist in one broad-signature bucket. If reviewers want a more
  principled choice (e.g. prefer the candidate closest in time to the OTHER canonical leg), that is
  a larger change than this plan's "make it a pure function" goal and should be flagged back to the
  auditor/business-analyst rather than silently substituted.
- **Tier 2 blast radius**: five methods in `BybitInternalTransferPairer`, one in
  `BybitEarnPrincipalTransferPairer`, and one in `BybitStakingConversionPairer` are touched. Land as
  separate, small, independently-revertable commits per method (mirroring the
  `corridor-basis-conservation-orphan-fix-implementation-plan.md` convention of isolating fixes
  even when verified together), so a rebuild regression can be attributed to a single method.
- **Verification loop is heavier than a pure unit-test change would suggest** — mongodump snapshot,
  standing up a disposable testcontainers Mongo instance, running the new integration test N≥2
  times, a manual pairing-set diff review, and a financial-snapshot diff. Budget for it. (The full
  install-wide `--skip-frontend` rebuild is explicitly deferred — see subsection above — which
  reduces but does not eliminate this loop's cost.)
- **`BybitRepairChainIdempotencyIntegrationTest` is new, first-of-its-kind test infrastructure for
  this codebase** (first full-context `@SpringBootTest`, first use of the already-declared-but-unused
  testcontainers-mongodb dependency). It is a manual/local verification aid gated to skip by default
  in CI (see Regression test plan §5), not a fully-productionized always-on CI test. If the team
  later wants this automated in CI, that is a separate, follow-up infrastructure investment (e.g.
  provisioning a curated, committable multi-UID fixture dump instead of a real session's mongodump,
  and deciding on a `@Tag`/Gradle test-source-set convention this repo does not yet have) — not
  bundled into this plan.
- **Pricing-coverage dependency for the new integration test**: if the restored `historical_prices`
  collection does not fully cover this session's replay needs, the test may fail on a pricing-gap
  symptom unrelated to the determinism fix itself (see Regression test plan §5's "Known risk" note).
  This is an acceptable, informative failure mode, but should be validated once during
  implementation so it isn't mistaken for a fix regression.

## Revision changelog

This plan was revised after Phase 3 parallel review (financial-logic-auditor, business-analyst,
system-architect — all PASS WITH CHANGES). Summary of changes from the original Phase 2 draft:

- Added `BybitStakingConversionPairer.pairConversions()` to Tier 2 scope (Scope, Root cause,
  Changes §7, Regression test plan) — a sibling defect missed in Phase 1.
- Rewrote the regression test design to correctly account for `collapseMirrors()`'s two-separate-
  `mongoOperations.find()`-calls-per-invocation shape (4 stub values, not 2), and clarified that
  Tier 2 methods (single query per invocation) use the simpler existing 2-value pattern.
- Changed `demoteResidualMirrors`'s proposed fix (Changes §3) from reusing `comparePriorityThenId`
  to a local timestamp-then-`_id` comparator, to avoid a cross-subAccount `canonicalPriority`
  incommensurability problem identified during review.
- Extracted a shared `idTiebreak()` comparator helper (Changes §0) and a shared reorder-invariance
  test utility (Regression test plan), replacing ~9 planned instances of duplicated inline
  lambdas/test boilerplate.
- Promoted two previously-informal Risk-section items to numbered, required Acceptance Criteria: a
  production-path `processNextBatch` verification pass (Criterion 4) and a full pairing-set diff
  sign-off gate (Criterion 5).
- Added a dollar/AVCO-level financial snapshot Acceptance Criterion (Criterion 6), with an explicit,
  reasoned prediction of the expected (near-zero) dollar delta rather than leaving it open.
- Added an installation-wide regression-sweep Acceptance Criterion (Criterion 7).
- Strengthened the Tier 2 fold-in justification with a primary, load-bearing correctness argument
  (upstream candidate-set instability feeding into Tier 1) distinct from the secondary mechanical-
  similarity argument.
- Added an explicit, separately-labeled "deviation from standing rebuild policy" subsection
  resolving the full-reset sequencing question, deferring the mandatory `--skip-frontend`
  install-wide rebuild to the user's planned consolidated reset.
- Added `docs/tasks/financial-audit-followups.md` as a new action item, with durable stub entries
  for the MNT gap, the admin-endpoint pass-order mismatch (with a "unify orchestration" durable-fix
  recommendation), and a considered-and-rejected ArchUnit feasibility note.
- Removed the "Open questions for review" section — all three of its items were resolved by this
  revision (comparator reuse resolved by Changes §3; shared-utility preference resolved by Changes
  §0; admin-endpoint pass-order and "lowest `_id` wins" interim-standard questions resolved by being
  moved into the durable follow-up tracking doc and Risks section respectively).

No new blocking issues were identified while incorporating these 13 points — the plan was ready for
final approval at that point.

### Second revision round: admin API surface rejected, verification redesigned around a direct-service-call test

After the above revision, the user explicitly rejected the admin HTTP endpoint
(`POST /api/v1/admin/integrations/{sessionId}/repair-bybit-and-replay`) this plan's Acceptance
Criteria depended on for verification ("I never asked for an admin API to be invented... a real
admin API will be designed much later — this is premature now") and deleted it, its controller
method, its `BybitRepairAndReplayResult` record, and the now-unused constructor wiring from
`IntegrationPipelineAdminService`/`AdminIntegrationPipelineController`. `fullRebuildBybit` (the
pre-existing, legitimate admin endpoint, which does not invoke the repair chain) is untouched.

This revision replaces every verification mechanism that depended on that endpoint with a new JUnit
integration test, `BybitRepairChainIdempotencyIntegrationTest` (Regression test plan §5), that
autowires the same repair-chain `@Service`/`@Component` beans directly (no HTTP layer) inside a
Spring context backed by a disposable testcontainers Mongo instance seeded from a mongodump restore
— never production or a shared database. No new admin endpoint or controller was introduced.
Changes:

- Replaced Acceptance Criteria 2 and 3's "N≥2 admin-endpoint-repair-chain calls" mechanism with the
  new integration test, run against a testcontainers-restored mongodump.
- **Simplified** Criterion 4 (production-path verification): since the deleted admin endpoint's
  pass order was the reason a *separate* production-order verification was needed, and the new test
  calls the repair-chain beans directly in whatever order the test itself specifies, the test is
  simply written to use the actual production order (`BybitNormalizationService.processNextBatch`'s
  current sequence) — there is no longer a second, divergent order to reconcile. Criterion 4 is
  narrowed to a lightweight "keep the test's call sequence in sync with production" check rather
  than a second full verification pass. This is a genuine simplification, not just a
  find-and-replace, as flagged in the user's request.
- Updated Criterion 6 (financial snapshot) and Criterion 7 (installation-wide sweep) to reference
  the new test / the normal `processNextBatch` live-system path instead of the admin endpoint.
- Updated the "Explicit deviation from standing rebuild policy" subsection's mechanism description;
  the underlying reasoning (defer the full `--skip-frontend` rebuild to the user's later
  consolidated reset) is unchanged.
- Corrected the production-order line citation throughout (`BybitNormalizationService.processNextBatch`,
  lines 78-104) to include `BybitStakingConversionPairer.pairConversions()`'s actual position in the
  chain (after `principalExclusivity`, before the untouched `bybitBotTransferCostBasisService`),
  which a re-read of the current source surfaced was missing from the prior revision's citation.
- Reframed `docs/tasks/financial-audit-followups.md` FU-2: the specific divergent endpoint no longer
  exists, so there is no active, currently-shipped risk today; confirmed `fullRebuildBybit` does not
  share this risk (it does not invoke the repair chain); kept the underlying "don't hand-roll a
  second copy of the same call sequence" principle as forward-looking guidance for any future admin
  API design.
- Added explicit, non-glossed-over risk notes: this is the first full-context `@SpringBootTest` and
  first actual use of the already-declared `testcontainers-mongodb` test dependency in this
  codebase, and the test is a manual/local verification aid gated to skip by default in CI (no
  existing `@Tag`/test-source-set convention exists in this repo to build on), not a new
  always-on CI test.

**Assessment: this is a mechanical-but-substantive substitution, not a change requiring another full
review round.** It does not alter the plan's root-cause analysis, the Tier 1/2 code changes, or the
per-method regression test plan (items 1-4 in Regression test plan, none of which ever depended on
the admin endpoint — they were always Mockito-based unit tests). It only changes *how* whole-chain,
real-data idempotency is proven end-to-end, and — per the user's own observation — actually
strengthens Criterion 4 by removing an indirection layer rather than weakening it. The one genuinely
new element introduced (a first-of-its-kind Spring-context integration test with testcontainers) is
called out explicitly as new infrastructure with stated risks (pricing-coverage dependency, CI-skip
gating) rather than presented as a drop-in replacement with no cost — a reviewer may want to weigh in
specifically on whether that infrastructure choice (full `@SpringBootTest` + testcontainers, gated to
skip by default) is acceptable, but that is a narrower, implementation-detail question rather than a
plan-substance question, and does not block proceeding to implementation.
