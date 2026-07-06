# ADR-005 — Cycle 4 Bybit pipeline correctness and rebuild contract

**Status:** Accepted (partially amended 2026-05-11 — see below)  
**Date:** 2026-05-10  
**Context:** Cycle 4 audit (`cycle-autorun/cycle-data/cycle/4/results/blockers.md`, `required-changes.md`) identified stage-level defects (H1–H12): transfer continuity without `correlationId`, stream duplication (TX_LOG vs execution, FH vs INTERNAL_TRANSFER, on-chain vs FH deposit/withdraw), spot `feePaid` not reaching ledger, and Earn classification gaps.

**Amendment 2026-05-11 (cycle/5):** A follow-up regression audit (`cycle-autorun/cycle-data/cycle/5/results/`) showed that **stream authority** (which leg is `basisRelevant`) must follow the **cycle/5 matrix** — notably **FH/Deposit must not double-count** auto-route UTA credits (N1), and **sender-side** TX_LOG/FH transfer rows must not be suppressed (N5). **EARN** correctness for cycle/5 is defined as **sub-account + INTERNAL_TRANSFER** without a mandatory new snapshot collection; see **`docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md`**, which **supersedes §4** of this ADR for the EARN data-source decision on the audited path. Persisted `/v5/earn/position` in Mongo remains an **optional observability** follow-up, not a cycle/5 blocking requirement.

## Decision

1. **Fix upstream:** Prefer changes in **Bybit extraction** and **Bybit canonical builder** so that rerun rebuilds `normalized_transactions` and `asset_ledger_points` correctly. Do **not** rely on repair sweeps over historical normalized rows as the primary remediation.
2. **Rebuild contract:** Production correction uses `POST /api/v1/admin/integrations/{integrationId}/full-rebuild` (see `IntegrationPipelineAdminService#fullRebuildBybit`): deletes BYBIT normalized + ledger + raw + extracted for the integration, resets backfill windows, optionally repairs on-chain sync windows. Operators **must** take a Mongo snapshot before invoking.
3. **Acceptance basis:** Use the **canonical DoD and Mongo gates** in `blockers.md` / `required-changes.md` (portfolio, realised PnL band, Bybit allocation, per-asset list). Do not merge substitute metrics into a single “green” line.
4. **EARN / flexible saving (change-set I):** Production alignment with audit simulation requires **persisted** `/v5/earn/position` snapshots (Option A in `required-changes.md`). **Live API calls during replay are rejected** (non-deterministic, breaks reproducibility). Implementation is a **follow-up** tracked under task 157-T6 with explicit schema and replay read path.

## Consequences

- One-time **full re-backfill** cost after deploy; acceptable vs incorrect path-dependent cost basis.
- **Snapshot-first** dashboard constraint preserved: no RPC during GET; earn snapshot reads come from Mongo only once implemented.
- **Cost:** Additional Mongo collection (or tagged documents) for earn snapshots; bounded polling consistent with existing Bybit refresh cadence.

## Alternatives considered

- **Repair-only / CounterpartyRepairJob–centric fixes:** Rejected as primary path (violates earliest-stage fix; audit translation rules).
- **Live earn API inside replay:** Rejected (ADR-005 §4).
