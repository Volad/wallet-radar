# Implementation plan: portfolio AVCO across all operations (financial audit)

**Slug:** `avco-eth-2300-band`  
**Status:** Phase 3 complete (2026-05-29) — **awaiting user approval for Phase 4–6**  
**Session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`

**Audit artifacts:**
- [`results/avco-eth-timeline-audit/avco-coverage-report.md`](../results/avco-eth-timeline-audit/avco-coverage-report.md)
- [`results/avco-eth-timeline-audit/lp-pool-avco-table.md`](../results/avco-eth-timeline-audit/lp-pool-avco-table.md) — per-pool AVCO + data quality issues
- [`results/avco-eth-timeline-audit/eth-family-holdings-breakdown.md`](../results/avco-eth-timeline-audit/eth-family-holdings-breakdown.md)
- [`results/avco-eth-timeline-audit/blockers.md`](../results/avco-eth-timeline-audit/blockers.md)
- [`results/avco-eth-timeline-audit/eth-c9b-cmeth-fund-trace.md`](../results/avco-eth-timeline-audit/eth-c9b-cmeth-fund-trace.md)
- [`results/avco-eth-timeline-audit/eth-c8-lending-lot-trace.md`](../results/avco-eth-timeline-audit/eth-c8-lending-lot-trace.md) — **CLOSED**

---

## User requirements (binding)

1. **~3.114 ETH** from CEX / DeFi / LP / lending / swap / bridge / rewards — **one portfolio AVCO** after all operations.
2. Expected band **~$2,300–3,000** — audit must prove or disprove from raw.
3. **No UI diagnostic labels** — correct pipeline only.
4. **CMETH / WSTETH / RSETH / METH** are staked ETH — **must not be excluded** from family cost basis.
5. Logic **generalizes to all assets**.

---

## Scope

| Dimension | Value |
|-----------|-------|
| Session | `df5e69cc-a0c0-4910-8b7d-74488fa266e2` |
| Wallets | `0x1a87…` (primary), `0xf03b…`, `0x68bc…`, `0xa0dd…`, `BYBIT:33625378` |
| Networks | ARBITRUM, BASE, ETHEREUM, MANTLE, OPTIMISM, KATANA, BSC, ZKSYNC |
| Assets | ETH, WETH, AMANWETH, CMETH, METH, WEETH, WSTETH + LP families |

---

## Phase 1 — Cluster registry (all diagnosed, 2026-05-29)

| ID | First failed stage | Symptom | AVCO impact | Status |
|----|-------------------|---------|-------------|--------|
| **ETH-C1** | `move_basis` | Corridor `0xa5e755…`: Bybit `$1,714` → WETH `$1,639` on 3.06 lot | **+$229 basis** on corridor fix → header **$1,648→$1,721** | OPEN P0 |
| **ETH-C4** | `replay` (write path) | 9 `FAMILY:ETH` + `LP-RECEIPT:*` tags | Pollutes family rollup | OPEN P0 |
| **ETH-C6** | `linking` → `move_basis` | CMETH FUND 0.952 @ **$4.31** (earn-principal dust) | CMETH FUND **$4 → ~$2,170** | OPEN P0 |
| **ETH-C9** | `classification` | CMETH/METH/WEETH/RSETH excluded from `FAMILY:ETH` timeline | Missing qty in family | OPEN P0 |
| **ETH-C9b** | `move_basis` / `replay` | Earn-principal lot not fully carried: CMETH `:EARN→:FUND` | CMETH FUND **$4 → ~$2,170** | OPEN P0 |
| **ETH-NEW-WRAP** | `cost_basis` / `replay` | `WRAP` (ETH→WETH) resets AVCO to 0 — seq 7230 BASE WETH | WETH BASE avco=0 on 448475/450450 entries; chain-wide WRAP bug | OPEN P1 |
| **ETH-C7** | `replay` | 938761 receipt qty=1 after close — `LpReceiptEntryReplayHandler` re-writes receipt for re-add without NFT flow | Receipt basis $1.07 stuck | OPEN P2 |
| **ETH-C10** | `classification` | Katana SushiSwap LP + Pendle CMETH LP have `correlationId=null` | Fee attribution broken; pool missing from stats | OPEN P2 |
| **ETH-C2** | `verification` (header scope) | Header on-chain only (~3.14); misses Bybit ~0.42 ETH + CMETH | Full-session blend needed | OPEN P1 |
| **ETH-C8** | — | 3.06 AMANWETH @ $1,637 — deposit-lot math, not mispricing | **CLOSED** — cannot reach $2,300 on this lot | **CLOSED** |

### Authoritative AVCO summary (prod vs projected post-all-P0)

| Scope | Qty | Today | After all P0 | Notes |
|-------|-----|-------|--------------|-------|
| On-chain (API header) | 3.14 | **$1,648** | **~$1,721** | Corridor only; AMANWETH-heavy |
| Bybit CMETH FUND | 0.95 | $4 | **~$2,170** | ETH-C9b |
| Bybit ETH-only blend | 0.42 | $2,274 | $2,274 | Unchanged |
| Full session deduped (P1) | ~5.18 | n/a | **~$1,840–1,870** | Honest economics (range, not point estimate) |
| Median history (reference) | — | $2,388 | $2,388 | Historical only |

**User band $2,300–3,000:** disproved for **current 3.14 on-chain blend** (AMANWETH @ $1,637 is deposit-lot correct). Band is valid for **median history, CMETH earn lots, and LP entry AVCO** ($2,700–4,200). Post-P0 honest maximum: **~$1,841** full-session.

---

## Implementation order (upstream-first)

### P0-A — Earn-principal lot carry: CMETH FUND (ETH-C9b / ETH-C6) — **highest impact**

**Root cause:** `TransferReplayHandler` carries only dust (~$1–2) instead of `lotQty × avcoBefore` when `:EARN → :FUND` principal transfer fires.

**Changes:**
1. `TransferReplayHandler` — earn-principal case: compute `carryBasis = lotQty × avcoBeforeUsd` and pass to `ContinuityCarryService`
2. `ContinuityCarryService` — accept explicit `carryBasis` override; do not recompute from post-move state
3. Unit tests: CMETH `:EARN→:FUND` scenario, AGLD earn analogue

**Files:** `backend/**/costbasis/application/replay/handler/TransferReplayHandler.java`, `ContinuityCarryService.java`

**Acceptance:** CMETH FUND AVCO ≥ $2,000 (target ~$2,170); `uncov=0`

---

### P0-B — Remove staked ETH timeline exclusion (ETH-C9)

**Root cause:** `AccountingAssetFamilySupport.SPOT_ETH_TIMELINE_EXCLUDED_SYMBOLS` contains `CMETH`, `METH`, `WEETH`, `STETH`. `RSETH` (R4-named asset) is also excluded or absent — must be confirmed and fixed.

**Changes:**
1. `AccountingAssetFamilySupport` — remove CMETH/METH/WEETH/STETH/**RSETH** from exclusion set; keep `BBSOL` excluded from `FAMILY:ETH`
2. Amend ADR-017 to document staked ETH inclusion policy (enumerate the full included set: ETH, WETH, CMETH, METH, WEETH, WSTETH, STETH, RSETH, AMANWETH)
3. `docs/03-accounting.md` — update family scope table

**Files:** `backend/**/accounting/support/AccountingAssetFamilySupport.java`, `docs/adr/ADR-017-*.md`

**Acceptance:** CMETH/METH/WEETH/RSETH appear in family timeline aggregation; no BBSOL in `FAMILY:ETH`

---

### P0-C — Corridor carry basis fix (ETH-C1) + ADR-019

**Root cause:** `ContinuityCarryService` / `PassThroughCorridorPlan` uses wrong per-unit rate for Bybit→on-chain corridor; inbound `WETH.avcoAfterUsd = $1,639` instead of `$1,714+`.

**Changes:**
1. `ContinuityCarryService` / `ReplayTransferClassifier` / `BybitVenueInternalReplayHandler` — corridor `CARRY_IN` must use outbound slice per-unit AVCO, not total-pool rate
2. Draft **ADR-019** (before code): corridor carry policy (outbound-AVCO preservation rule)
3. Update `docs/03-accounting.md` §corridor

**Files:** `ContinuityCarryService.java`, `ReplayTransferClassifier.java`, `BybitVenueInternalReplayHandler.java`, `docs/adr/ADR-019-corridor-carry-policy.md`

**Acceptance:** Corridor tx `0xa5e755…` inbound WETH `avcoAfterUsd ≥ $1,714`; AMANWETH AVCO ≥ $1,714; no residual $3,918 spike on Bybit umbrella

---

### P0-D — WRAP/UNWRAP AVCO carry (ETH-NEW-WRAP) — **new finding**

**Root cause:** `WRAP` (ETH→WETH) replay handler writes `WETH REALLOCATE_IN` with `avco=0` / `costBasisDelta=0`. Evidence: seq 7230 BASE WETH `WRAP REALLOCATE_IN qty=0.546 avco=0`. Downstream LP entries (448475, 450450) see zero WETH AVCO at entry.

**Changes:**
1. `GenericFlowReplayEngine` / `ReplayFlowSupport` — WRAP flow: carry source ETH AVCO to WETH bucket (same qty, same basis)
2. UNWRAP flow: carry WETH AVCO to ETH bucket
3. Tests: WRAP on BASE chain with populated ETH AVCO; assert WETH bucket inherits rate

**Files:** `backend/**/costbasis/application/replay/support/GenericFlowReplayEngine.java`, `ReplayFlowSupport.java`

**Acceptance (A8 / A8b):**
- A8 WRAP: `WETH BASE WRAP REALLOCATE_IN avcoAfterUsd` equals source ETH bucket AVCO at carry time (±0.1%); business rule: "WRAP/UNWRAP preserves AVCO from source to dest bucket"
- A8b UNWRAP: after UNWRAP (WETH→ETH), ETH bucket inherits WETH AVCO at the same per-unit rate (±0.1%)
- Pools 448475/450450 show non-zero wallet AVCO at LP entry after rebuild
- Source ETH bucket with AVCO=0 → WETH inherits 0 (not treated as an error, but documented)

---

### P0-E — LP family rebuild + `FAMILY:LP_RECEIPT` at write (ETH-C4)

**Root cause:** 9 legacy ledger points `FAMILY:ETH` + `LP-RECEIPT:*` from pre-ADR-017 replay.

**Changes:**
1. Full rebuild (`prod-reset-rebuild-backend.sh --skip-frontend`)
2. Verify `FAMILY:ETH` + `LP-RECEIPT` count = 0

**Acceptance:** `count({accountingFamilyIdentity:"FAMILY:LP_RECEIPT"})` increases; legacy 9 gone

---

### P1 — WRAP/UNWRAP + full-session header (ETH-C2) — blocking for epic sign-off

**Root cause:** `AssetLedgerQueryService.currentStateView` uses on-chain only; Bybit ETH + CMETH excluded.

**Changes:**
1. Extract `PortfolioAvcoAggregationService` — shared deduped formula
2. Union: on-chain `FAMILY:ETH` + Bybit ETH-family; deduplicate corridor units (no WETH+AMANWETH double-count)
3. API returns full-session AVCO; document dedup policy in `docs/03-accounting.md`

**Projected result:** full-session AVCO **~$1,841** (honest economics)

---

### P2 — `LpReceiptEntryReplayHandler` guard for re-add after close (ETH-C7 / Issue B)

**Root cause:** Handler writes `REALLOCATE_IN qty=1.0` for any `LP_ENTRY` tx in the pool's correlationId, even after the NFT receipt was closed (qty=0). Seq 8608: 4th LP_ENTRY for 938761 after 2× LP_EXIT with receipt-1.

**Changes:**
1. `LpReceiptEntryReplayHandler` — before calling `synthesizeReceiptFromOutbound`, check `replayState.lpReceiptLifecycleClosed(corrId)`; skip synthesis if true. (`lpReceiptLifecycleClosed` is already implemented in `ReplayExecutionState`: returns true when exits ≥ entries AND exits > 0.)
2. Tests:
   - **Negative path (orphan re-add):** 4-entry/2-exit lifecycle: entry→add(no NFT)→exit×1(receipt-1)→exit×1(receipt-1)→orphan-add → assert `quantityAfter=0`
   - **Positive regression (new NFT re-open):** full close followed by LP_ENTRY with new LP-RECEIPT NFT flow → assert new receipt written (`quantityAfter=1`)

**Acceptance:** 938761 receipt `quantityAfter = 0` after rebuild; genuine re-open with new NFT still creates receipt

---

### P2 — Katana + Pendle correlationId (ETH-C10)

**Root cause:**
- Katana SushiSwap/Angle vault contract not in protocol registry → `correlationId=null`, `protocolName=null`
- Pendle CMETH market on Mantle not registered in `PendleLpCorrelationSupport`

**Changes:**
1. Add Katana SushiSwap LP contract to protocol registry; emit `lp-position:katana:sushiswap:{poolId}`
2. Register Mantle CMETH Pendle market in `PendleLpCorrelationSupport` (symbol `PENDLE-LPT` → marketId map)
3. Full rebuild; verify fees linked

**Files:** `backend/**/ingestion/pipeline/classification/lp/PendleLpCorrelationSupport.java`, protocol registry JSON, `GmxMarketCorrelationSupport` pattern for Katana

**Acceptance:** Katana LP shows `correlationId=lp-position:katana:*`; Pendle shows `pendle-lp:mantle:*`; fee claims linked

---

## Required documentation (Phase 4, before code)

| Doc | Change |
|-----|--------|
| **ADR-019** (new) | Corridor carry policy: outbound-AVCO preservation, dedup guard |
| ADR-017 (amend) | Staked ETH (CMETH/METH/WEETH) now included in family timeline |
| ADR-018 (amend) | ETH-C10: Katana LP → `lp-position:katana:*`, Pendle CMETH market registration |
| `docs/03-accounting.md` | Corridor policy §, staked ETH §, WRAP/UNWRAP carry §, full-session AVCO § |

---

## Acceptance criteria (all must pass before Phase 6 sign-off)

| ID | Criterion | Target |
|----|-----------|--------|
| A1 | CMETH FUND AVCO | **≥ $2,000** (target ~$2,170); earn-principal lot carry |
| A2 | Full-session deduped ETH AVCO from API | `POST /api/session/{id}/asset-ledger FAMILY:ETH` returns AVCO within **0.1%** of auditor-computed baseline (recomputed from ledger post-P0, projected **~$1,840–1,870**) |
| A3 | On-chain ~3.14 AVCO | **$1,700–1,750** (post-corridor); not forced to $2,300 |
| A4 | Staked ETH in timeline + portfolio | CMETH/METH/WEETH/**RSETH** included; BBSOL excluded from `FAMILY:ETH` |
| A5 | WETH/AMANWETH no double-count | Same wallet — one bucket only |
| A6 | Corridor `0xa5e755…` inbound AVCO | **≥ $1,714**; ADR-019 drafted before P0-C code |
| A7 | No UI labels / receipt split | Pipeline formula only |
| A8 | WRAP AVCO carry | `WETH BASE WRAP REALLOCATE_IN avcoAfterUsd` equals source ETH AVCO ±0.1%; rule: "WRAP/UNWRAP preserves AVCO from source to dest bucket" |
| A8b | UNWRAP AVCO carry | After UNWRAP (WETH→ETH), ETH bucket inherits WETH AVCO ±0.1% |
| A9 | 938761 receipt | `quantityAfter = 0` after rebuild; genuine re-open with new NFT still creates receipt |
| A10 | `FAMILY:ETH` + LP-RECEIPT | **0** legacy points |
| A11 | Katana LP correlationId + fee attribution | `lp-position:katana:sushiswap:*` assigned; ≥1 fee-claim tx has matching correlationId |
| A12 | Pendle CMETH correlationId + fee attribution | `pendle-lp:mantle:*` assigned; ≥1 fee-claim tx has matching correlationId |
| A13 | Timeline tail | `PRIMARY_FLOW`; native ETH tail **≥ $1,900**; AMANWETH tail **≥ $1,714** |
| A14 | AGLD earn-principal carry | Unit test: AGLD `:EARN→:FUND` preserves lot basis (analogous to CMETH, passes in CI) |

---

## Risks

| Risk | Mitigation |
|------|------------|
| WRAP fix changes basis on all WETH positions globally | Regression test suite on WRAP/UNWRAP across all networks before deploy |
| Corridor fix raises AMANWETH basis but $1,639 genesis trail is deposit-lot correct (ETH-C8) | ETH-C8 CLOSED — corridor fix only bumps $1,639→$1,714; does not re-price genesis; test separately |
| P1 full-session dedup: Bybit corridor 3.06 still counted on-chain (double-count guard) | `PortfolioAvcoAggregationService` must exclude Bybit 3.06 lot after corridor |
| Pendle market registration: wrong market address breaks CMETH LP correlation | Validate market address from `PendleLpCorrelationSupport` against on-chain tx |
| LpReceiptEntryReplayHandler guard: too-aggressive close check could suppress valid re-opens | Guard only when: no LP-RECEIPT flow in tx AND prior receipt qty was 0 |

---

## Implementation order summary

```
Phase 4 (docs):
  ADR-019 → ADR-017 amend → ADR-018 amend → docs/03-accounting.md

Phase 5 (code, upstream first):
  P0-A  earn-principal carry (CMETH FUND)
  P0-B  remove staked ETH exclusion
  P0-C  corridor carry fix (requires ADR-019 first)
  P0-D  WRAP/UNWRAP AVCO carry  ← NEW
  P0-E  LP family rebuild
  P1    PortfolioAvcoAggregationService + full-session header
  P2    LpReceiptEntryReplayHandler guard (938761)
  P2    Katana/Pendle correlationId registration

Phase 6 (verify):
  prod-reset-rebuild-backend.sh --skip-frontend
  Re-run financial-logic-auditor on A1–A13
```

---

## Review log

| Role | Pass | Verdict | Notes |
|------|------|---------|-------|
| financial-logic-auditor | 1 | APPROVE | Sync stale §5/§8 |
| business-analyst | 1 | REVISE | A1–A9 incorporated |
| system-architect | 1 | REVISE | ADR-019 before P0-C; PortfolioAvcoAggregationService |
| financial-logic-auditor | 2 | **APPROVE** | 4 minor notes applied: C7 lifecycle "2-exit", C9 stage=classification, full-session range ~$1,840–1,870, A13 split by asset |
| business-analyst | 2 | **APPROVE** | 7 gaps applied: RSETH in P0-B/A4, A8 precision rule, A8b UNWRAP AC, A14 AGLD, A2 baseline, A11/A12 fee attribution, positive regression test in A9 |
| system-architect | 2 | **APPROVE** | Guard uses `replayState.lpReceiptLifecycleClosed(corrId)`; ADR-019/017/018 mandatory before Phase 5 |
| User | — | **Pending approval for Phase 4–6** | |
