# AVCO Corridor Basis-Conservation — Implementation Plan

> **Status:** Phase 2 plan, **revised after Phase 3 review** (financial-logic-auditor + business-analyst + system-architect). **Awaiting user approval before any code (hard gate).**
> **Trigger:** User validation against Bybit "Avg. Cost" screenshot (replay #10). LINK/LDO/ONDO AVCO 5–9× low, MNT qty ~15×, LTC qty short, DOGE avco ~40% high.
> **Phase 1 source:** `results/blockers.md`, `results/required-changes.md`, `results/accounting-failure-analysis.md`.
> **Universe:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`. Bybit umbrella `33625378` + subs `409666492`, `421325298`, `516601508`.

## 0. Finding

Independent single-pool AVCO reconstruction from raw `bybit_extracted_events` reproduces the Bybit
screenshot **to the coin**; it differs from the DB **only** by ignoring internal-transfer/earn-corridor
plumbing ⇒ the divergence is a **basis/quantity conservation defect in corridor carry (replay)**, enabled
by an upstream **corridor-pairing window defect (linking)**. `NET ≈ TAX` for all in-scope assets (reward
qty < 0.5 %); ADR-040 reward treatment is correct — the **NET dashboard headline is corrupted by the
corridor leak**, not by rewards.

### Authoritative targets (both lanes, within tolerance)

| Asset | qty target | TAX | NET | Bybit anchor | DB now (TAX/NET) |
|---|---:|---:|---:|---:|---:|
| LINK | 17.1440 | $14.71 | $14.67 | $15.40 | $2.92 / $2.90 |
| LDO | 338.042 | $0.767 | $0.766 | $0.76 | $0.116 / $0.115 |
| ONDO | 401.216 | $0.643 | $0.642 | $0.61 *(anchor itself ~5% off recon; pass window ~$0.61–0.675)* | $0.068 / $0.067 |
| DOGE | 661.170 | ~$0.19 | ~$0.19 | $0.18 | $0.253 / $0.253 |
| LTC | 1.26107 | $49.29 | $49.19 | $49.28 | $55.38 (qty short 0.511) |
| MNT | 109.87 (Bybit) | ≈$0.80 | ≈$0.80 | $0.80 | $0.64 on 1706.85 (phantom +1597) — **deferred, see RC-C** |

## 1. Scope

- **In scope:** Bybit intra-account corridor carry conservation (FUND ↔ UTA ↔ EARN), the corridor pairing
  window, LTC unpaired subscribe, DOGE pre-coverage bot-lot pricing.
- **Primary assets:** LINK, LDO, ONDO (RC-A), LTC (RC-B), DOGE (RC-D). Secondary/soft: USDT, MNT (RC-C, deferred).
- **Explicitly DEFERRED / `UNSUPPORTED_SCOPE` (requires user sign-off, §7):** full MNT/USDT reconstruction
  to live — cross-venue Mantle↔Bybit de-dup + crypto-loan (BORROW/REPAY) liability modeling + grid-bot
  `DISPOSE`-at-$0-basis. This plan only stops the **corridor source (i)** from growing the phantom; the
  **disposal-at-$0-basis source (ii)** continues until RC-C. **MNT/USDT will still be wrong after this cycle.**
- **Out of scope:** frontend layout (headline = Net, secondary = Tax, chart plots both — already correct).
  A **UI confidence-flag** for deferred/soft-reconciled assets is a separate decision (§7, optional).

## 2. Root causes (revised — 5 items)

| RC | Cluster | Assets | Earliest stage | Mechanism |
|---|---|---|---|---|
| **RC-0** | **corridor pairing window too tight** *(NEW, upstream of RC-A)* | LINK, LDO, ONDO, LTC, MNT, USDT | linking | `BybitEarnPrincipalTransferPairer.MAX_PAIR_DRIFT = 30 min`; real Flexible-Savings cycles span 18 h → weeks → a month (see `results/blockers.md` L523–536). `withinDrift()` breaks the FIFO match ⇒ most cycles never pair ⇒ redeem left asymmetric, feeding RC-A/RC-B. |
| **RC-A** | corridor carry basis-non-conserving | LINK, LDO, ONDO, USDT, MNT(partial) | replay/cost_basis | The AVCO-re-derivation fallback injects **$0-cost quantity** on the IN leg when `avco==null` (`ContinuityCarryService.syntheticBybitEarnProductCarry` L406–408): IN gains qty at $0 → AVCO diluted 5–9×; net lane leaks equally. |
| **RC-B** | unpaired / one-sided-excluded corridor leg | LTC (twin of B-REG-02) | linking (→replay) | LTC inbound EARN leg was **one-sidedly excluded** (`bybit-collapsed-v1` subscribe-excluded/redeem-booked). Pairer only re-correlates `excludedFromAccounting != true` legs, so it **cannot** materialize the missing leg. Basis lands on `:EARN` with qty=0 ($41.54 ghost); quantity on umbrella. |
| **RC-C** | gross-accumulation phantom *(DEFERRED)* | MNT, USDT | classification/linking + replay | (i) corridor asymmetry (stopped by RC-0/A/B) **+** (ii) grid-bot `SWAP DISPOSE` at `costBasisDeltaUsd=0` + deposits/loans as fresh acquisitions (NOT stopped here). |
| **RC-D** | pre-coverage bot-lot misprice | DOGE | pricing | 150.591 DOGE "Bot" inflow (2025-01-31, sub `421325298`) priced $0.5766 (no pre-2025-09-22 DOGE bucket; out-of-range fallback). |

## 3. Ordered changes (upstream stages first)

### Task 1 — RC-D (pricing): bound the pre-coverage bot-lot price *(isolated; ship first)*
- Extend DOGE historical price coverage back to the event date, or bounded nearest-valid-bucket fallback
  instead of an out-of-range value. Correction point: pricing coverage/fallback bounds; secondary — `Bot`
  bybit-type inflow basis carry. Likely docs-only pricing-rule tweak (short ADR optional, not ADR-043).
- **Acceptance:** DOGE bot lot basis ≈ 150.591 × market(2025-01-31); blended DOGE AVCO ≈ $0.18–0.19.

### Task 2 — RC-0 (linking): fix the corridor pairing window + exclusion symmetry *(true first mover for corridor)*
- Drop/widen `MAX_PAIR_DRIFT` to the earn-holding horizon; rely on FIFO **equal-principal** matching keyed
  `{uid, family, |qty|, redeem-follows-subscribe-in-time}` per the Phase-1 pairing spec (`results/blockers.md` L560–567).
- Fix **one-sided exclusion**: `suppressCorridorDepositStakeCycles` / `bybit-collapsed-v1` must exclude
  **both** legs of a cycle or **neither** (paired-exclusion symmetry), so the inbound EARN leg exists as a
  non-excluded normalized tx for the pairer to correlate (this is what unblocks RC-B/LTC).
- Correction point: `BybitEarnPrincipalTransferPairer` (drift), `BybitStreamAuthorityCollapser` /
  `BybitInternalTransferExternalCpReclassifier` (exclusion symmetry).
- **Acceptance:** every closed cycle pairs (redeem_qty = subscribe_qty ±tol); no one-sided-excluded leg.

### Task 3 — RC-B (linking→replay): materialize the paired inbound leg for LTC
- With Task 2's exclusion symmetry, LTC's inbound EARN leg exists; the pairer correlates it. Where a leg is
  genuinely unpaired at a boundary, raise a conservation flag — never silently drop inventory.
- **Acceptance:** LTC total qty = 1.26107 (`:EARN` 0.75 + umbrella 0.511); blended AVCO ≈ $49.3 (fixes the
  $103.8 B-REG-02 ghost). Runs **before** RC-A (basis conservation is meaningless without the leg).

### Task 4 — RC-A (replay/cost_basis): paired carry is the **sole authoritative basis source**
- **Seam (single point):** the paired-carry consumption in `TransferReplayHandler`. The queued paired carry
  (`drainCarrySlice` OUT basis → pending queue → IN restore) is authoritative; **demote** the
  AVCO-re-derivation fallbacks (`syntheticBybitEarnProductCarry`, `resolveEarnPrincipalFallbackAvco`,
  `applyEarnPrincipalLotCarryOverride`) to fire **only** when the queue proves NO paired carry exists
  (open/unredeemed position or genuine unpaired boundary). Never inject $0-cost quantity on a redeem.
- **Net lane:** route the matched-carry path through the **8-arg `CarryTransfer`** so
  `Σ(netCostBasisDelta)=0` is enforced independently, not inferred from tax.
- **Interest:** `redeem_qty − subscribe_qty > tol` ⇒ book the excess as a **priced `REWARD_CLAIM` (ACQUIRE)**,
  not a $0-basis qty bump on the IN leg.
- **FUND drain symmetry:** make the `:FUND` outbound drain symmetric for **all** FUND↔UTA legs, not only
  `isEarnPrincipalFundDrainContext` (prevents the B-REG-01 umbrella-phantom class from reappearing on
  non-earn legs). *(Verify against ETH — already 0 post-ADR-042; must not regress.)*
- **Scope guard:** restrict to the **earn/internal** carry constructors; **DO NOT touch**
  `bridgeInboundCarry` / `bridgeSettlementInboundCarry` / pass-through reserves (those carry the RC-9 ETH
  corridor, currently correct at AMANWETH 3.06 @ ~$2936).

### Task 5 — Invariant + conservation guard (reuse existing guards)
- **Invariant (ADR-043), two precise statements — NOT "single position key":**
  - **(a) per-transfer:** `Σ costBasisDelta(OUT+IN) = 0` **and** `Σ netCostBasisDelta(OUT+IN) = 0` across
    source+dest keys of one subscribe/redeem transfer.
  - **(b) per-family (umbrella + all subs):** `Σ` over all internal `INTERNAL_TRANSFER + EARN_*` legs `= 0`.
  - *Open-position note:* both legs of an open subscribe are internal and offset within the family sum, so
    `Σ=0` holds even for unredeemed positions **provided every OUT has a materialized IN** (RC-B).
- **Guards (extend, don't create):**
  - `CorridorBasisConservationGuard`: add Bybit earn-principal / venue-internal queue prefixes to
    `GUARDED_QUEUE_PREFIXES` → leftover OUT carry with no matched IN ⇒ `CORRIDOR_BASIS_IMBALANCE`
    (end-of-replay sweep + $1 dust epsilon + OOS carve-out; **no in-replay throw** — open positions park
    basis legitimately mid-walk). WARN-first, one-line flip to HARD_FAIL after clean rebuild.
  - `BybitEarnSubPoolConservationGuard`: keeps combined-total (umbrella+:FUND+:EARN+:UTA) vs
    `bybit_live_balances` reconciliation + `qtyΔ=0 / costBasisΔ>0` ghost check; **soft** for deferred
    MNT/USDT. Wire LINK/LDO/ONDO/LTC controls into its test set.

## 4. Documentation (Phase 4, before code)
- **New ADR-043** "Corridor carry basis conservation (continuation of ADR-041)", cites **ADR-042** in
  `Related` with the clause: *"Basis symmetry is keyed on the paired carry value, not the drained
  sub-position; the ADR-042 target redirect is basis-neutral by construction."* One-line `Extended-by:
  ADR-043` status pointer atop ADR-041 (no rewrite).
- `docs/pipeline/cost-basis/02-avco-rules.md`; `docs/pipeline/normalization/rules/protocols/bybit-earn.md`
  (pairing window + subscribe↔redeem symmetric basis); `docs/reference/ledger-points-and-basis-effects.md`.
- RC-C and RC-D get their **own** ADRs/workstreams (not folded into ADR-043).

## 5. Acceptance (Phase 6 rerun + auditor sign-off)
Full replay (`--skip-frontend`; add `--clear-pricing-cache` for RC-D). Re-run `data/derived/leak.js` +
`recon.js`. Pass bar (all measurable):
1. **Target assets both lanes:** LINK/LDO/ONDO/LTC/DOGE blended AVCO within 5 % of §0 in **Net AND Tax**;
   LTC qty = 1.26107. Measured on the **user-visible surface** (dashboard coverage-weighted family value +
   move-basis `TimelineAvcoAuthority` output), not only the terminal aggregate.
2. **Quantity conservation (ADR-041 half stays green):** `Σ internal Δqty = 0` for LINK/LDO/ONDO; `= open
   principal` for LTC (0.75 + 0.511) and MNT (103.78).
3. **Basis conservation both lanes:** `Σ costBasisDelta = 0` AND `Σ netCostBasisDelta = 0` per family (±dust).
4. **Sub-pool reconciliation:** `|ledger_netQty(pool) − live| ≤ max(0.5 %·live, floor)` per FUND/EARN/umbrella.
5. **Regression-control pass/fail table (merge gate):**
   | Control | Expected |
   |---|---|
   | FAMILY:ETH combined | ≈ $2.23k; BYBIT ETH-family umbrella/EARN/METH/CMETH ≈ 0 vs live |
   | RC-9 AMANWETH | 3.06 held @ ~$2936, unchanged |
   | RC-2 XRP `:FUND` / LDO `:FUND` | phantom stays eliminated (XRP umbrella 4.05, `:FUND` 0) |
   | BTC | ≈ $79k (points $59k–96k) |
   | AVAX | ≈ $14.23 combined (max $20) |
   | SUSHI | ≈ $0.55 |
6. **MNT/USDT deferred bar:** non-increasing vs replay #10 in **quantity AND phantom cost-basis** (catches
   RC-A amplification on still-mis-paired legs); AVCO stays ≈$1 (error is quantity, not AVCO).

## 6. Risks
- RC-A edits carry code shared by bridge/family-custody/earn → constrained to earn/internal constructors
  (Task 4) + control-table merge gate (§5.5). Highest risk = re-introducing B-REG-01 ETH umbrella phantom;
  mitigated by symmetric FUND drain + ETH first-class control.
- RC-B inbound materialization idempotency (interacts with linking guards + suppression pass paired-exclusion).
- RC-A/RC-2/ADR-042 compose on orthogonal axes (target vs value) — architect-confirmed no regression, locked
  by two blast-radius tests: RC-9 single-leg-OUT must NOT raise `CORRIDOR_BASIS_IMBALANCE`; suppression pass
  must preserve paired-exclusion symmetry.
- Ordering: Task 2 (pairing) → Task 3 (RC-B leg) → Task 4 (RC-A basis) → Task 5 (guard).

## 7. Decisions needed from the user (Phase 3 gate)
1. **MNT/USDT deferral sign-off:** accept that MNT/USDT remain quantity-wrong after this cycle (only
   non-increasing); full reconstruction is a separate workstream.
2. **UI confidence-flag (optional):** flag deferred/soft-reconciled assets in the UI, or leave silent this cycle?
3. **Final table scope:** user asked for a per-asset NET & TAX AVCO table after fixes — cover **all held
   assets** (recommended) or just the six validated here?
