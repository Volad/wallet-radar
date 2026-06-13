# Move Basis — AVCO continuity + UI overhaul

> **Status:** Approved for implementation  
> **Date:** 2026-06-07  
> **Scope:** LP-open AVCO crater (read model), Move Basis UI (card, event log, chart brush, matched-tx collapse)

## Root cause (Phase 1 audit)

When `LP_ENTRY` drains a family's spot bucket to qty 0, basis is correctly reallocated into `FAMILY:LP_RECEIPT` at replay write. The move-basis timeline read model (`TimelineAvcoAuthority`) could not surface continuous AVCO because LP receipt points are excluded from spot-family aggregation.

**Earliest failed stage:** `avco` read model (not classification, pricing, or replay write).

## Part A — Backend (read-model)

**Change:** In `TimelineAvcoAuthority.resolve`, when aggregated covered qty drops to 0 due to a basis-preserving `REALLOCATE_OUT` (LP/custody lock, `realisedPnlDelta = 0`), carry forward the last known spot AVCO (`KIND_CARRIED_FORWARD`). Mirrors LENDING_DEPOSIT (ETH→AWETH) continuity.

**Do not change:** `LpReceiptEntryReplayHandler`, replay write, classification, pricing.

**Acceptance:**
1. Anchor tx `0x3d41db62…532d` on `FAMILY:ETH`: `avcoAfter ≈ $1,828.95` (non-null).
2. Realised PnL on LP open = 0.
3. Spot ETH ledger point unchanged.
4. LENDING_DEPOSIT continuity preserved.
5. 42 systemic `LP_ENTRY REALLOCATE_OUT avcoAfter=null` points no longer blank on timeline.

**Verify:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (read-model only; no re-replay required).

## Part B — Frontend (Move Basis UI)

| Item | Change |
|------|--------|
| B1 Card | Header + asset/qty/usd/price/tx/from/to/gas; AVCO/Basis footer; remove path + canonical flows |
| B2 Event log | Drop Flows + AVCO columns; Unit price before From; expandable detail row on click |
| B3 Chart brush | Last marker fits; handle hit zones; scale not pan on edges |
| B4 Matched tx | Collapse correlated transfer/bridge legs into one marker/row; expand to show legs |

## Risks

- Option A carry-forward is display-only; family qty on chart still drops while AVCO stays flat (economically correct).
- Matched-tx collapse is frontend-only; backend `collapseDisplayEvents` already handles same-tx internal pairs.

## Related

- `results/blockers.md`, `results/accounting-failure-analysis.md`, `results/required-changes.md`
- ADR-017 (amended 2026-06-07 — LP-lock carry-forward)
