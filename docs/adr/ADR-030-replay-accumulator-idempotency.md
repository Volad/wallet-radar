# ADR-030 — Replay Accumulator Idempotency (rebuild-from-empty)

**Date**: 2026-06-13
**Status**: Proposed
**Related**: ADR-012 (borrow liability tracking), ADR-015 (counterparty basis pools), ADR-029 (deterministic corridor basis continuity + conservation guard)
**Implements**: `docs/tasks/bridge-corridor-carry-replay-determinism-implementation-plan.md` (WS-A / RC-12)

> Distinct ADR, **not** an amendment to ADR-029. ADR-029 is about *linking determinism* (same
> correlation key under refresh). ADR-030 is about *replay-orchestration determinism* (the persisted
> accumulator books must not double on refresh).

---

## Context

A clean full rebuild conserved (`conservationDeltaUsd ≈ −$2`). A real incremental refresh of the
same universe regressed it to **+$4,134**. Root cause **RC-12**: the AVCO replay seeds three
persisted accumulator books from their prior persisted output at the start of each run —

- `borrow_liabilities` (`BorrowLiabilityTracker.loadAllForUniverse`),
- `counterparty_basis_pools` (`CounterpartyBasisPoolService.loadAllForUniverse`),
- `lp_receipt_basis_pools` (`LpReceiptBasisPoolService.loadAllForUniverse`),

— and then **reprocesses the FULL CONFIRMED set every run**, re-accumulating each event
(`recordBorrow` / `lookupOrCreate.safeAdd` / `deposit`). Seeding from prior output therefore
**double-counts every still-open position** on an incremental refresh: four open borrows came out at
exactly 2× (aggregate `openBorrowLiabilityUsd` doubled from the true value to **$8,754.55**). The
`asset_ledger_points` projection never had this defect because it already starts from an empty list
each run.

---

## Invariant

> Every persisted accumulator book is a **pure derived projection of the full CONFIRMED set**. The
> replay rebuilds each one from an **empty map** at the start of every run, reprocesses the entire
> CONFIRMED set (no window/`since` filter), and is the **sole writer** of each book via end-of-run
> **replace-only** persistence. Therefore:
>
> `books(rebuild) == books(rebuild→refresh) == books(rebuild→refresh×N)` — bit-identical
> `qtyBorrowed` / `qtyOpen` / `qtyHeld`. A rebuild-from-empty can only drop **stale** state, never
> legitimate cross-run state.

### Verified support (resolves BA gating questions Q1/Q2/E1)

1. **Full-CONFIRMED reprocess** — `ConfirmedReplayQueryService.loadOrderedConfirmed` (and its
   `memberRefs` overload) has **no since/window filter**: the replay always re-derives the books from
   the complete CONFIRMED set.
2. **Sole writer, replace-only** — all three books are persisted exclusively by
   `replaceUniverse*` = `deleteByUniverseId → saveAll` at the end of replay. `persistDirty(...)` is
   dead code (used only in narrow unit tests, never on the live path).
3. **Pure projections (E1)** — no borrow/pool/LP state is seeded *only* into an accumulator book
   (no accumulator-only or manual-override row exists); each book is fully reconstructible from the
   CONFIRMED set. So rebuild-from-empty drops nothing legitimate.

---

## Decisions

### D1 — Empty-seed each accumulator book (ORCHESTRATION)

- `AvcoReplayService.replayConfirmed` constructs `counterpartyPools`, `borrowLiabilities`, and
  `lpReceiptPools` as **empty `LinkedHashMap`s** — exactly as `asset_ledger_points` are already
  built empty. The three `loadAllForUniverse(...)` seed calls are removed.
- The fix lives in the **orchestrator (the seed)**, not in the trackers. The trackers' accumulate
  semantics (`recordBorrow` / `lookupOrCreate` + `safeAdd` / `deposit`) remain correct once the seed
  is empty.

### D2 — Compute-vs-persisted drift canary (OBSERVABILITY)

- **NEW** `ReplayAccumulatorDriftCanary` (`costbasis.application.replay.support`). After the
  replace-only persistence, it compares the freshly-computed in-memory book totals + entry counts
  against the just-persisted reload. Because the replay is the sole writer, the two must agree within
  a tiny epsilon; any divergence WARN-logs `REPLAY_ACCUMULATOR_DRIFT` per book.
- Severity is **WARN-only** (architect decision): the canary never blocks the replay. It replaces the
  rejected "second full replay" runtime guard idea (too expensive). HARD_FAIL promotion is a deferred,
  tracked follow-up.
- The **hard correctness check** is the unit idempotency test
  (`rebuild == refresh == refresh×N`), not the canary.

### D3 — Forward constraint on incremental-window optimization

- A future incremental-window optimization (replaying only events since a watermark) **MUST NOT**
  reintroduce seeding the accumulator books from prior persisted output unless it also makes the
  trackers idempotent under re-accumulation (e.g. set-not-add semantics). The drift canary and the
  `rebuild == refresh × N` test must stay green. This constraint is asserted here so the seed cannot
  be silently re-added.

---

## Consequences

- **No-op on the full-rebuild path** (it already started empty): the only behavioral change is on
  incremental refresh, where the doubling is removed. A **post-deploy full rebuild** is required to
  flush any previously-doubled persisted rows (note the historical `deleteAll` vs `deleteByUniverseId`
  asymmetry on the GLOBAL path).
- `openBorrowLiabilityUsd` drops from the doubled **$8,754.55** to its true value; per-loan
  `qtyBorrowed == on-chain` and `qtyOpen == on-chain borrowed − repaid` (MANTLE 2,496; bybit-pledge
  1,246 MNT; BASE 600; AVAX 390) after refresh×N. `counterparty_basis_pools` / `lp_receipt_basis_pools`
  `qtyHeld` neither double nor lose state vs a single clean rebuild.
- `conservationDeltaUsd` under incremental refresh returns to within `|≤ $50|`.

---

## Open questions

- **Q1 (canary severity):** WARN → HARD_FAIL staged rollout (deferred, tracked).
- **Q2 (incremental window):** if/when a since-watermark replay is introduced, re-evaluate D3.
