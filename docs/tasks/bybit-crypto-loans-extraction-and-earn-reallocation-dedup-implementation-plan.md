# BLOCKER-6 + 5D — Bybit Crypto Loans extraction authority + Earn-reallocation phantom dedup

**Status:** DRAFT (Phase 2). Stop gate before Phase 4 pending user approval.
**Slug:** `bybit-crypto-loans-extraction-and-earn-reallocation-dedup`
**Related:** BLOCKER-5 (5E → this), BLOCKER-4 (shipped), ADR-012/028/046 (loan accounting), ADR-054.
**Source classification:** `results/blockers.md` → BLOCKER-6, BLOCKER-5 §5D/§5E.
**Reviews folded:** auditor + BA + architect on BLOCKER-6 (all APPROVE-WITH-CHANGES).

These two blockers **co-land in one pipeline reset** because TON only reconciles to zero when both are
fixed: BLOCKER-6 supplies the borrowed inventory that funds the withdrawal (kills the shortfall), and
5D removes the phantom Earn-reallocation inflows that overstate TON inventory.

---

## 1. Scope

- **BLOCKER-6** — Bybit Funding-History `Crypto Loans` BORROW/REPAY dropped (`status=RAW`,
  `basisRelevant=false`) → borrow that funds a withdrawal is invisible → phantom `quantityShortfall`
  (TON seq 5472 = 15.17). Fix is **extraction-only**; downstream loan machinery already exists.
- **5D (TON Earn-reallocation phantom)** — Bybit `bybitType="Earn"` FUND↔EARN reallocation legs
  classified as plain `INTERNAL_TRANSFER` (`canonicalType=INTERNAL_TRANSFER`, no correlation,
  `continuityCandidate=false`) land as `UNKNOWN` **uncovered inflows**, double-counting inventory
  (TON seq 1122 +32.393, seq 1749 +32.4386; ~63 TON uncovered-basis points downstream).

Out of scope (separate follow-ups): 5D **reward-basis** sub-cluster (CUDIS 12 / PAWS 7 / AGLD 1 / WLKN
1 / METH 1 — `REWARD_CLAIM` with `basisBackedQuantity=0`); 5A/5B/5C/5F (BLOCKER-5 primary PR); USDT
"Pledge Assets" collateral-lock semantics beyond "do not treat as borrow inflow".

Constraints: backend only; `--skip-frontend` reset; `--clear-pricing-cache` only to refresh TON/MNT
borrow-time prices (optional).

---

## 2. Root cause

### BLOCKER-6 (extraction authority) — code + DB verified
`BybitExtractionService` (`fhLoanShadow`, ~lines 459-465) demotes **all** Funding-History loan events
(`bybitType` "Crypto Loans"/"Loans", canonical BORROW/REPAY/FEE) to `basisRelevant=false`, assuming
UTA `TRANSACTION_LOG` is authoritative. Normalization only intakes `basisRelevant=true`, so they stay
`status=RAW`. The in-scope loans (TON 2025-08, MNT 2025-09→2026-02) are **FH-only, no TX_LOG
counterpart** → dropped. Everything downstream already exists and is exercised by UTA TX_LOG loans and
on-chain Aave: `BorrowReplayHandler`/`RepayReplayHandler`, `BorrowLiabilityTracker`,
`borrow_liabilities`, `bybit-pledge:<uid>:<asset>` correlation, market-at-borrow basis (F-5(b)),
net-$0 dual lane.

Full dropped scope (19 RAW events): TON 1 BORROW + 2 REPAY; MNT 6 BORROW + 6 REPAY + 2 "Loans/Repay
Principal" + 1 FEE; USDT 1 "Loans/Pledge Assets" BORROW (−523, **negative = collateral lock, NOT a
borrow inflow**).

### 5D (Earn-reallocation phantom) — DB verified
TON seq 1122 tx `…9e5280fe…`: raw `2025-03-16 U:FUND Earn (INTERNAL_TRANSFER) +32.393` (a flexible-
savings **redemption** EARN→FUND) normalized as `INTERNAL_TRANSFER`, `correlationId=-`,
`continuityCandidate=false`, flow `TRANSFER +32.393 acctRef=:FUND cpty=:EARN`. With no pairing to the
EARN sub-pool it replays as `UNKNOWN` and adds `uncoveredQuantity +32.393`. Same at seq 1749
(+32.4386). These phantom inflows persist as the ledger's "64.83", masking the loan-funded withdrawal
(80 → 15.17). Some Earn legs DO pair (seq 1128/1131 `EARN_FLEXIBLE_SAVING REALLOCATE_*`), so the defect
is a **classification/pairing inconsistency** for the `bybitType="Earn"` FUND↔EARN reallocation subset.

### Combined reconciliation (auditor)
Real pre-borrow TON FUND spot ≈ 0 (raw `walletBalance=80` right after the +80 borrow). Modeling the
borrow zeroes the seq-5472 shortfall; 5D dedup removes the ~64.83 phantom so post-repay TON does not
leave ~80 phantom. Both together → TON reconciles with correct inventory and zero shortfall.

---

## 3. Ordered changes (upstream first)

1. **BLOCKER-6 — extraction authority rule** (`BybitExtractionService.fhLoanShadow` +
   `refreshBasisRelevantFromRaw`, single source of truth):
   - Promote `basisRelevant=true` **only** for FH `bybitType="Crypto Loans"` with
     `canonicalType ∈ {BORROW, REPAY}`.
   - Keep `bybitType="Loans"` BORROW/REPAY and **all FEE** demoted (they shadow authoritative UTA
     TX_LOG `LOANS_*`).
   - Handle USDT `"Loans/Pledge Assets"` negative as a **collateral lock**, not a BORROW inflow
     (exclude from quantity inflow).
   - Add a `(uid, canonicalAsset, loan-window)` **no-TX_LOG-coverage guard** before promoting, to
     prevent double-counting a loan represented on both streams (defense-in-depth; today disjoint).
   - Make `refreshBasisRelevantFromRaw` re-derive the same rule (idempotent on re-run).
   - No new normalizer type, replay handler, liability schema, AVCO path, or Mongo index — reuse
     existing (`BORROW→BUY` / `REPAY→SELL` mapping, `bybit-pledge` correlation, dual lane).

2. **5D — Earn-reallocation classification/pairing** (Bybit normalizer / classification support):
   - Investigate the exact rule that sends some `bybitType="Earn"` FUND↔EARN reallocations to plain
     `INTERNAL_TRANSFER` (unpaired UNKNOWN) while others become `EARN_FLEXIBLE_SAVING REALLOCATE_*`.
   - Route the reallocation subset (FUND↔EARN, `bybitType="Earn"`) into the earn sub-pool pairing
     (`bybit-earn-*` correlation / continuity) so the redeem-inbound is matched to the subscribe-
     outbound and carries basis instead of adding `uncoveredQuantity`.
   - Guard: must not regress the already-correct `EARN_FLEXIBLE_SAVING` legs or the earn-principal
     conservation (`BybitEarnSubPoolConservationGuard`).

3. **Docs / ADR:**
   - **New small ADR** (source-authority rule): FH `Crypto Loans` BORROW/REPAY are authoritative loan
     lifecycle events when no UTA TX_LOG `LOANS_*` covers the same `(uid, asset)` window; `Loans`
     product FH rows + loan FEE remain TX_LOG-shadowed; FUND→umbrella inbound-collapse invariant for
     loan credits; repay-residual = interest/loan-P&L. Cite ADR-012/028/054, BLOCKER-4. Add to
     `docs/adr/INDEX.md` + worked example in `docs/examples/avco-replay-examples.md`.
   - Cost-basis rules: document the Earn-reallocation pairing rule (5D).

---

## 4. Acceptance (re-audit after `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`)

**Baseline:** capture pre-reset per-asset (TON, MNT) flagged count, per-key Σ`quantityDelta`, AVCO,
realized P&L, and `borrow_liabilities` state; assert diffs.

- **BLOCKER-6 materialization:** `borrow_liabilities` non-empty for **TON** (`bybit-pledge:33625378:TON`,
  qtyBorrowed 80, CLOSED after repay) and MNT Crypto-Loans cycles; normalized `BORROW`/`REPAY` txs
  exist for the promoted FH events.
- **No double-count:** early-2025 MNT UTA TX_LOG loans NOT duplicated (FH `"Loans"` stays demoted);
  MNT combined liability correct.
- **USDT pledge:** the −523 "Pledge Assets" event does NOT create a spurious BORROW inflow.
- **TON reconciliation (the co-land proof):** seq 5472 `quantityShortfallAfter=0`; TON flagged points
  on `BYBIT:33625378` → 0 for the shortfall + Earn-phantom classes; per-key Σ`quantityDelta` reflects
  real inventory (no ~64.83 / ~80 phantom); final TON position economically correct.
- **5D:** seq 1122/1749 no longer `UNKNOWN` uncovered inflows; TON `uncoveredQuantityAfter` chain
  cleared; the ~63 TON uncovered-basis points resolved (CUDIS/PAWS/AGLD reward-basis remain, tracked
  separately).
- **AVCO/P&L:** borrowed units carry market-at-borrow basis (not $0); repay principal realizes $0,
  interest residual realizes market P&L; no unexpected AVCO shift on unrelated assets (|Δ| ≤ 1e-6).
- **Conservation:** BORROW/REPAY excluded from `BybitEarnSubPoolConservationGuard` custody net-zero;
  `ReplayAccumulatorDriftCanary` / `PortfolioConservationGate` reconcile liabilities; assert every
  promoted loan lands in `borrow_liabilities`.
- **No regression:** BLOCKER-4 anchors intact; full `:backend:core:test` green.

---

## 5. Risks

- **Double-counting loans on both streams** — mitigated by the coverage guard + keeping `"Loans"`/FEE
  demoted; assert MNT not duplicated.
- **USDT pledge mis-modeled as inflow** — explicit negative-event handling + test.
- **5D over-pairing** — routing Earn reallocations into the earn pool must not swallow genuine
  transfers or regress `EARN_FLEXIBLE_SAVING`; guard with conservation + targeted tests.
- **Scope creep** — reward-basis (CUDIS/PAWS) and 5A/5B/5C/5F stay out; this PR = BLOCKER-6 + TON
  Earn-phantom only.
- **Pricing** — market-at-borrow for TON/MNT from the free resolver chain; pass `--clear-pricing-cache`
  only if borrow-time prices need refresh.

---

## 6. Test plan

- **Extraction unit tests:** FH `Crypto Loans` BORROW/REPAY → `basisRelevant=true`; FH `Loans` +
  FEE stay `false`; USDT `Pledge Assets` negative not treated as inflow; coverage guard suppresses a
  synthetic FH loan that overlaps a UTA TX_LOG loan; `refreshBasisRelevantFromRaw` idempotent.
- **Replay/integration:** TON Crypto Loan borrow→withdraw→deposit→repay → `borrow_liabilities`
  opens/closes, seq-5472 shortfall 0, interest residual realizes P&L; borrow inflow keys to umbrella
  (never `:FUND`).
- **5D:** TON `bybitType="Earn"` FUND↔EARN reallocation pairs to the earn sub-pool (no UNKNOWN, no
  uncovered add); `EARN_FLEXIBLE_SAVING` legs unchanged; earn-principal conservation holds.
- **Negative:** an unrelated FUND↔UTA `INTERNAL_TRANSFER` (not Earn) is not swept into earn pairing.
- **AvcoReplayServiceTest:** TON end-to-end reconciles to correct inventory + zero shortfall with
  BLOCKER-6 + 5D combined.
- Full `:backend:core:test`.

---

## 7. Reporting / DoD

- `results/blockers.md` — BLOCKER-6 + 5D (TON Earn-phantom) → RESOLVED with post-reset counts;
  `borrow_liabilities` evidence; note reward-basis (CUDIS/PAWS) + 5A/5B/5C/5F still open.
- `results/reconciliation.md` — TON borrow-cycle reconciliation table (borrow/withdraw/deposit/repay,
  liability open/close, shortfall before/after).
- New ADR + INDEX + worked example.
