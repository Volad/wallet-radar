# Financial audit follow-ups

Lightweight, durable tracking for items identified during financial-correctness audits that are
explicitly out of scope for a specific fix plan. `results/` audit artifacts are gitignored
(`.gitignore:34`) and are not a durable record on their own — this file exists so these items
survive independently of any single plan doc being re-read later. Entries here are stubs (a few
sentences), not full plan treatment; promote an entry to a real `docs/tasks/*.md` plan when it is
actually picked up.

## Open

### FU-1: `bybit-earn-carry:33625378:FAMILY:MNT` raw-evidence gap (~$122)

- **Source:** Surfaced as a 4th `CorridorBasisConservationGuard` `HARD_FAIL` breach
  (`totalOrphanedBasisUsd` component ≈ $122.04) alongside the Bybit collapser determinism
  investigation (`docs/tasks/bybit-collapser-determinism-fix-implementation-plan.md`), but confirmed
  as a separate, pre-existing defect — not caused by, and not fixed by, that plan.
- **What's wrong:** Bybit Earn extraction for UID 33625378 has a raw-evidence gap in the window
  2025-09-26 to 2025-10-05 that leaves this `FAMILY:MNT` corridor-carry queue one-sided.
- **Status:** Not investigated beyond initial triage. Needs a dedicated raw-evidence audit
  (Bybit Earn extraction / `bybit_extracted_events` for this UID and window) before a fix plan can
  be written.

### FU-2: Admin repair endpoint's pass order diverged from production's `processNextBatch` order (endpoint since removed — downgraded to a design principle)

- **Source:** Identified during the Bybit collapser determinism investigation
  (`docs/tasks/bybit-collapser-determinism-fix-implementation-plan.md`, "Explicitly NOT in scope").
- **What was wrong:** `IntegrationPipelineAdminService.rerunBybitRepairAndReplay` (a temporary admin
  endpoint at `POST /api/v1/admin/integrations/{sessionId}/repair-bybit-and-replay`) ran
  `earnPrincipal → internalTransfer → collapser → principalDedupe`, while
  `BybitNormalizationService.processNextBatch` runs `internalTransfer → collapser → earnPrincipal →
  principalDedupe → stakingConversion → botTransferCostBasis`. Each order was internally consistent
  on its own, but they disagreed with each other — a clean admin-endpoint verification would not by
  itself have proven organic-pipeline (production) idempotency, since the pass order actually
  differed.
- **Status update:** The specific `repair-bybit-and-replay` admin endpoint has been **deleted** by
  explicit user decision ("I never asked for an admin API to be invented... a real admin API will be
  designed much later — this is premature now"), along with its controller method and unused
  wiring. There is therefore **no longer a live, currently-shipped divergent call sequence creating
  an active risk today.** Checked: the one remaining admin endpoint, `fullRebuildBybit`, does not
  invoke the repair chain at all (it only resets sync windows and deletes data for a full
  re-backfill from scratch), so it does not share this risk. The Bybit collapser determinism plan's
  verification (`BybitRepairChainIdempotencyIntegrationTest`) now calls the repair-chain beans
  directly in production order, with no second order to reconcile.
- **Recommended durable design principle for later (kept for when a real admin API is designed, per
  the user's stated future intent):** if/when an admin API for Bybit repair/replay operations is
  properly designed later, it should not hand-roll a second, independently-ordered copy of the
  repair chain. Either have it call `BybitNormalizationService.processNextBatch`'s passes directly,
  or extract a single shared orchestration method that both the scheduled production path and any
  future admin tooling invoke, so there is exactly one place that defines "the Bybit repair chain
  order." This is a design principle to apply prospectively, not a currently-active defect to fix.
- **Priority:** Downgraded from "real, separate defect" to "forward-looking design note." No action
  needed until a new admin API is actually proposed.

## Considered and rejected (do not re-litigate)

### CR-1: ArchUnit / structural guard for `_id`-tiebreak ordering discipline

- **Source:** Raised during system-architect review of the Bybit collapser determinism plan as a
  possible durable guardrail against this class of bug recurring.
- **Why rejected:** `mongoOperations.find(` appears at 148 call sites across 65 files in
  `backend/core/src/main/java` alone (verified count at time of writing). The overwhelming majority
  of these are legitimately order-insensitive (single-document lookups, count-only queries, queries
  whose results are re-sorted or aggregated downstream in an order-insensitive way). A structural
  rule requiring every `find()` call to carry an explicit deterministic sort would be overbroad and
  produce far more noise than signal. No such rule should be built; ordering discipline for
  selection/pairing logic remains a manual code-review concern, backed by the precedent already
  established in `docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md` §D1.
- **Status:** Closed. Recorded here so this is not re-proposed without new information (e.g. a
  narrower, more targeted static-analysis rule scoped only to selection/pairing call sites, if one
  is ever devised, would be a new proposal, not a revival of this one).
