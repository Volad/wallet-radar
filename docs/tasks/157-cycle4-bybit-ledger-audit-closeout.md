# Task 157 — Cycle 4 Bybit ledger audit closeout

> **2026-05-11:** Cycle/5 audit (`cycle-autorun/cycle-data/cycle/5/results/`) documents a **portfolio regression** and supersedes parts of this task’s **stream `basisRelevant` matrix** and **EARN** approach. Continue tracking acceptance under **`docs/tasks/158-cycle5-portfolio-phantom-closeout.md`** and **`docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md`**.

**Goal:** Close the cycle/4 financial audit by fixing Bybit extraction/normalization/replay at the **earliest wrong stage**, then validating with a **full integration rebuild** (no canonical-row repair sweeps as primary path).

**Canonical audit inputs (do not paraphrase acceptance numbers elsewhere without these):**

- `cycle-autorun/cycle-data/cycle/4/results/blockers.md` — gates, DoD, H1–H12 mapping.
- `cycle-autorun/cycle-data/cycle/4/results/required-changes.md` — implementation guide change-sets **A–K**.

## Acceptance criteria (DoD)

1. **H1 / Change-set A:** Every Bybit `INTERNAL_TRANSFER` normalized doc built from sub-account / universal transfer raw ids carries deterministic `correlationId` + `continuityCandidate=true` where id matches `selfTransfer_<UUID>` or `uni_trans_<UUID>`.
2. **Change-set B:** On-chain `DEPOSIT_ONCHAIN` custody defaults to **UTA** when no TRANSACTION_LOG match; when a matching TX_LOG row exists (asset, |qty|, ±60s, `TRANSFER_IN`/`DEPOSIT`), `walletRef` follows that row.
3. **Change-set C:** TRANSACTION_LOG `TRADE` remains non-basis; `TRANSFER_IN` / `TRANSFER_OUT` non-basis; FH **Crypto Loans** / **Loans** `BORROW` / `REPAY` / `FEE` non-basis (TX_LOG authoritative per C.3).
4. **Change-set D:** No `AssetLedgerPoint` written with null `blockTimestamp`; mapper falls back to `importedAt` when `timeUtc` missing (warn log).
5. **Change-set E:** Operator uses existing `POST /api/v1/admin/integrations/{integrationId}/full-rebuild` + `X-WalletRadar-Admin-Token` after `mongodump` (see `docs/adr/ADR-005-cycle4-bybit-pipeline.md`).
6. **Change-set F:** No remaining `FUNDING_HISTORY` rows with `canonicalType=UNKNOWN_CEX` and `bybitType=Earn` where funding field is empty — reclassified to `INTERNAL_TRANSFER`, basis-relevant.
7. **Change-set G:** FH loan flows on **FUND** walletRef remain classifiable; **C.3** overrides: basis-relevant `BORROW`/`REPAY` only on **TRANSACTION_LOG** (FH duplicates suppressed).
8. **Change-set H:** `DEPOSIT_ONCHAIN` **non-basis** (FH `Deposit` **basis-relevant**); `WITHDRAWAL` stream remains non-basis (FH `Withdraw` gross authoritative).
9. **Change-set I (cycle/4 wording):** Superseded for **minimum EARN correctness** by cycle/5 **N4** (sub-account + INTERNAL_TRANSFER + routing); see ADR-006. Optional persisted snapshot / weekly probe remains a **separate** observability item, not the cycle/5 blocking ledger source.
10. **Change-set J (H12):** Spot swap normalized flows net `feePaid` into the execution leg quantity (no double fee row for folded fees).
11. **Change-set K:** FH `INTERNAL_TRANSFER` with `bybitType` **Transfer in** / **Transfer out** is **non-basis** (INTERNAL_TRANSFER stream is authoritative).

**Scorecard surfaces (keep separate):** exact-asset reconciliation, per–sub-account ledger, dashboard rollup, proof-clean vs final-clean — do not substitute one metric for another.

**Superseded by cycle/5 (do not implement from this list alone):** DoD **§3** (TX_LOG `TRANSFER_OUT` must become basis-relevant for N5), **§8** (FH `Deposit` must be non-basis alongside auto-route UTA leg — N1), **§11** (FH `Transfer out` must be basis-relevant — N5). Use task **158** + **ADR-006**.

## Task breakdown (implementation order)

| ID | Change-set | Work |
|----|------------|------|
| T1 | A, J | `BybitCanonicalTransactionBuilder`: correlation + spot fee net |
| T2 | C, F, G, H, K | `BybitExtractionService`: basis flags + earn + deposit/withdraw policy |
| T3 | B | TX_LOG lookup for on-chain deposit wallet ref + Mongo query |
| T4 | D | `BybitExtractedEventMapper` + `LedgerPointCollector` |
| T5 | E | Ops only: dump, rebuild, backfill, Mongo gates from `blockers.md` |
| T6 | I | Follow-up: optional earn **probe** or persisted snapshot (observability); cycle/5 N4 path per ADR-006 |

## Edge cases (scope)

- **In scope:** Bybit API–sourced rows, multi-sub-account UIDs, transfer pairing via `corr-family`, spot fees in fee currency matching the leg.
- **Out of scope (explicit):** Tax reporting; non-Bybit CEX; hash-keyed production rules; using `CounterpartyRepairJob` as default recovery for these flows.

## Risk notes

- **C vs G:** C.3 explicitly requires only TRANSACTION_LOG for basis-relevant `BORROW`/`REPAY`; FH duplicates are suppressed to avoid double-counting.
- **K unconditional:** FH “Transfer in/out” marked non-basis; if INTERNAL_TRANSFER stream were missing for an edge UID, operator would see a gap — escalate rather than re-enable duplicate FH legs.
- **I:** Without snapshot, EARN displayed quantity may drift; ADR documents Option A before implementation.

## Operator playbook — prepare data for re-normalization

1. **Snapshot Mongo** (mandatory before destructive rebuild):
   ```bash
   mongodump --uri="$MONGODB_URI" --out="backup/pre-cycle4-rebuild-$(date +%Y%m%d)"
   ```
2. **Deploy** the new backend image (example prod profile):
   ```bash
   docker compose -f docker-compose.prod.yml --profile prod build backend-prod
   docker compose -f docker-compose.prod.yml --profile prod up -d backend-prod
   ```
3. **Invoke full rebuild** for the Bybit integration (replace id and token):
   ```bash
   curl -sS -X POST \
     "http://127.0.0.1:${WR_PROD_BACKEND_PORT:-18086}/api/v1/admin/integrations/BYBIT-33625378/full-rebuild?repairOnChainWindows=true" \
     -H "X-WalletRadar-Admin-Token: ${WR_ADMIN_INTEGRATION_REBUILD_TOKEN}"
   ```
4. Wait until integration status leaves **BACKFILLING** and jobs refill `integration_raw_events` → `bybit_extracted_events` → normalization → replay.
5. Run Mongo acceptance queries from `blockers.md` / `required-changes.md` (sections A.3, C.3, F.3, H.3, J.3, K.3, etc.).

## References

- ADR: `docs/adr/ADR-005-cycle4-bybit-pipeline.md`
- Admin rebuild: `AdminIntegrationPipelineController`, `IntegrationPipelineAdminService`
