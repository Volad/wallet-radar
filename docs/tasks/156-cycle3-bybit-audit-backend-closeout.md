# Task 156 — Cycle/3 Bybit audit: documentation anchor and backend close-out backlog

## Goal (one sentence)

Align product documentation and backend work with the **cycle/3 independent audit** so Bybit ingestion, extraction, normalization, replay, and acceptance checks match `qa-clarifications.md` and related cycle artifacts.

## Authoritative artifacts (read order)

1. `cycle-autorun/cycle-data/cycle/3/results/qa-clarifications.md` — normative Q&A (overrides conflicting wording elsewhere).
2. `cycle-autorun/cycle-data/cycle/3/results/required-changes.md` — change-sets A–F and acceptance snippets.
3. `cycle-autorun/cycle-data/cycle/3/results/reconciliation.md` — three-ledger model, dedup matrix §F, validation §G.
4. `cycle-autorun/cycle-data/cycle/3/results/blockers.md` — defect catalog and cycle close-out gates.
5. `cycle-autorun/cycle-data/cycle/3/data/derived/bybit_simulation_v6_three_ledger.json` — quantity truth baseline.

## Acceptance criteria (DoD) — by surface

### A. Inventory (hard gate for cycle/3)

- Per `reconciliation.md` §6.1 / `qa-clarifications.md` §6.1: for each sub-account `walletAddress` matching `^BYBIT:<uid>:(FUND|UTA|EARN)$`, per supported asset, pipeline physical quantity matches auditor `v6` truth within stated tolerance (quantities are primary; USD envelope secondary).
- G11/G12/G13: mirrors have `basisRelevant=false` on non-authoritative streams per §F matrix.
- G9: API key-set parity for `TRANSACTION_LOG` and `FUNDING_HISTORY` after re-backfill (`qa-clarifications.md` §4.2).

### B. Exact-asset / family / UI surfaces (separate)

- **G6** (single venue line): display rollup — may ship after inventory gate (`qa-clarifications.md` §6.1 intermediate acceptance).
- **G7** (Aave receipt basis): does **not** block cycle/3 inventory gate; document known residual on unrealised % (`blockers.md`, `qa-clarifications.md` §7.2).

## Edge cases (scope)

| Topic | In scope for task 156 initial backend batch |
|-------|---------------------------------------------|
| Full classification rule table + FH dedup + signed `quantityRaw` everywhere | **Next subtasks** (large `BybitExtractionService` change); not required to land doc+script+executor hardening in one PR. |
| Earn `/v5/earn/position` snapshot + `MANUAL_COMPENSATING` drift | **Dedicated job** per `qa-clarifications.md` §2.1 — separate implementation task. |
| `POST /api/v1/admin/integrations/{id}/full-rebuild` | Spec in `required-changes.md` / `qa-clarifications.md` §7.1; **auth + idempotency** before prod exposure. |
| `isUnsafeLoanRow` blocking all `BYBIT:` BORROW/REPAY | Must be **narrowed or removed** when loan classification ships; otherwise normalized BORROW/REPAY mapping is unused for Bybit. |

## Task breakdown (ordered)

1. **Docs** (this file + `docs/02-architecture.md` + `docs/04-api.md`): link cycle/3 artifacts; document `scripts/mongo-prep-full-2yr-backfill.sh`.
2. **Mongo prep script**: delete integration raw + extracted + segments + on-chain raw + reset `sync_status` for cold 2y replay; then run `scripts/avco/reset-derived.sh` (see script header).
3. **BybitBackfillSegmentPlanner**: clamp `TRANSACTION_LOG` and `EXECUTION_*` segment windows to **7 days** (API-friendly), matching `qa-clarifications` defensive posture.
4. **BybitBackfillSegmentExecutor**: repartition oversized segments for **TRANSACTION_LOG** and **EXECUTION_*** (same pattern as funding/earn); improve `providerEventKey` for transaction-log rows (`transLogId`, etc.) to reduce silent overwrites.
5. **BybitCanonicalTransactionBuilder**: `BORROW` → `BUY` + positive leg; `REPAY` → `SELL` + disposal sign per `qa-clarifications.md` §1.1 / §8.5; unit tests.
6. **Extraction + dedup matrix + Earn snapshot + admin rebuild + narrow `isUnsafeLoanRow`**: change-set B **partially landed** (walletRef `:FUND` for all FH, G11/G12/G13/G14/G15 rules, TX_LOG classification, `FEE` normalized type, derivatives non-basis, `INTERNAL_TRANSFER` for ETH2/Earn/FLEX mirror, signed withdrawal qty). **Still follow-up**: `/v5/earn/position` snapshot job, `POST …/full-rebuild` with auth, optional key-set parity verifier, exhaustive Bybit type matrix vs live API drift.

## Risk notes

- **Destructive mongo script** wipes all sessions’ pipeline progress and all Bybit raw for **entire DB** — run only on intended environment.
- **BORROW/REPAY** builder change does not yet affect rows still routed through `isUnsafeLoanRow` — coordinated change required for user-visible loan correctness.

## References

- Cycle/3 results under `cycle-autorun/cycle-data/cycle/3/results/`.
- Prior related task: `docs/tasks/155-bybit-normalization-stream-sync.md`.
