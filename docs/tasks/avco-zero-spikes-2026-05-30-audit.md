# AVCO Zero-Spike Audit — Phase 1
**Date:** 2026-05-30  
**Universe:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
**Method:** Independent ledger reconstruction from `asset_ledger_points` + `normalized_transactions`, API timeline cross-check (`/api/v1/sessions/.../asset-ledger?familyIdentity=FAMILY:ETH`), prod Mongo on `localhost:27019`

---

## Executive Summary

| Question | Verdict |
|---|---|
| Does dashboard AVCO match coverage math? | **PASS** — $2,589 AVCO, 3.123 covered / 0.019 uncovered / 3.142 net qty |
| Does raw ledger show qty>0 with AVCO=0 on material balances? | **NO** — 0 points with `basisBackedQuantityAfter > 0.001 ETH` and `avcoAfterUsd ≤ 1` |
| Do user-visible graph zero-cliffs reflect replay basis loss? | **NO** — cliffs are **read-model UNAVAILABLE gaps** (484/1992 timeline rows) rendered at chart bottom |
| Are suspicious txs root cause of zero AVCO? | **Partially** — they correlate with UNAVAILABLE timeline rows; only **EXECUTION_SPOT** and **0x6ac6 corridor** are material replay/linking defects |

**Primary root cause of vertical $0 graph drops:** `TimelineAvcoAuthority` returns `avcoKind=UNAVAILABLE` (`avcoAfterUsd=null`) for 24% of timeline events because the selected authoritative ledger leg has `avcoAfterUsd` null/0 (dust bucket, empty EARN sub-wallet, GAS_ONLY leg). The chart line connects PRIMARY_FLOW points through these null points (plotted at Y-min), producing vertical cliffs. **Family covered quantity and total cost basis continue correctly** — this is not a basis-wipe in replay storage.

---

## A. Zero-AVCO Spike Scan

### Scan results (3,780 `FAMILY:ETH` ledger points)

| Category | Count | Material? |
|---|---|---|
| `avcoAfterUsd=0` with `bbAfter > 0` (any size) | 42 | **No** — all dust sponsored-gas / GAS_ONLY on BASE/OPTIMISM (bb ≤ 0.011 ETH, mostly < 1e-4) |
| `bbAfter > 0.001 ETH` AND `avcoAfterUsd ≤ 1` | **0** | — |
| Family running AVCO drop >90% while covered qty > 0 | **0** | — |
| API timeline PRIMARY→UNAVAILABLE→PRIMARY cliffs (prev AVCO > $500) | **479** | **Yes (display)** — causes graph zero-cliffs |

### Classification

| Pattern | Example | Verdict |
|---|---|---|
| True zero position | Wallet ETH bb→0 after full dispose | OK — AVCO undefined |
| Dust zero-basis gas | `SPONSORED_GAS_IN` seq 4222, bb=9.3e-6, avco=0 | OK — zero-basis gas sponsorship |
| Bug: qty>0, avco=0 on material balance | — | **Not found** |
| Bug: transient ordering double-count | — | **Not found** in family AVCO series |
| Display artifact | EARN `LENDING_WITHDRAW` → UNAVAILABLE | **Read-model gap**, not basis loss |

### Zero-drop → triggering transaction map (graph cliffs)

All graph cliffs follow the same mechanism: event at time T has `avcoKind=UNAVAILABLE`. Representative triggers from user suspect list:

| Timestamp | Tx / event | Timeline `avcoKind` | Why UNAVAILABLE | Family AVCO before→after (next PRIMARY) |
|---|---|---|---|---|
| 2025-01-12 | `EARN_FLEXIBLE_SAVING:a67c0479…` | UNAVAILABLE | EARN `REALLOCATE_OUT` leg: bb=0, avco undefined | $3,252 → $3,289 |
| 2025-01-22 | convert `…1018593482554086883672064…` | UNAVAILABLE | ETH dust `DISPOSE`: bb=0, avco=0 | $3,295 → $3,273 |
| 2025-02-07 | `EARN_FLEXIBLE_SAVING:734bf6f1…` | UNAVAILABLE | CMETH interest `REALLOCATE_OUT` on EARN | $3,136 → $2,720 |
| 2025-02-07 | `EXECUTION_SPOT:2200000000707964104` | UNAVAILABLE | CMETH `DISPOSE` recorded qty=0 (replay defect) | $2,720 → $2,754 |
| 2025-02-07 | `0x6ac6fc6010af5b…` | UNAVAILABLE | On-chain `ACQUIRE` cb=0; FUND leg excluded | $2,754 → $2,746 |
| 2025-05-10 | `0x258ed5c30165032d…` | **PRIMARY_FLOW** $1,803 | Bridge OUT — AVCO preserved on wallet | — |
| 2025-05-10 | `0x6c5bd905efe5f9c4b…` | **PRIMARY_FLOW** $3,289 | Bridge OUT — AVCO preserved | — |

**Note:** The two bridge suspects do **not** produce graph zero-cliffs; they emit valid PRIMARY_FLOW AVCO. They remain linking coverage concerns (unpaired OUT).

---

## B. Coverage vs AVCO Consistency

### Dashboard `current` (live-cap, matches UI)

Source: `GET /api/v1/sessions/.../asset-ledger?familyIdentity=FAMILY:ETH` → `current`

| Metric | API | UI (user) | Match |
|---|---|---|---|
| Net quantity | 3.142015 ETH | 3.142 ETH | ✓ |
| Covered quantity | 3.122524 ETH | 3.123 ETH | ✓ (rounding) |
| Uncovered quantity | 0.019490 ETH | 0.019 ETH | ✓ |
| Total cost basis | $8,084.59 | — | — |
| AVCO (`totalBasis / covered`) | **$2,589.12** | **$2,588** | ✓ |

**Verdict: PASS** — uncovered qty is excluded from AVCO denominator via live-cap `min(ledger.bb, on_chain_qty)` per bucket.

### Full-session ledger tail (not UI headline)

| Metric | Value |
|---|---|
| Ledger tail quantity | 5.284 ETH |
| Ledger tail covered | 5.284 ETH |
| Ledger tail AVCO | $2,829.72 |

Difference vs UI: dashboard caps to **live on-chain + Bybit visible balances** (~3.14 ETH); ledger tail sums all replay buckets including off-exchange Bybit FUND/UTA holdings (~1.19 ETH on `BYBIT:33625378` alone).

---

## C. Suspicious Transaction Deep Dives

### 1. Convert pair `convert:BYBIT-33625378:TRANSACTION_LOG:1018593482554086883672064…`

| Field | Value |
|---|---|
| Type | `SWAP` CONFIRMED |
| Flows | SELL ETH −4.3e-7 @ $3,318; BUY MNT +0.001234 |
| Ledger | ETH `DISPOSE` qtyD=−4e-9, cbD=−$0.000013, bb→0, avco→0 |
| Failed stage | **read-model** (timeline UNAVAILABLE on dust leg) |
| Basis lost? | **No** — economically negligible dust conversion |
| Shortfall note | Session shortfall list shows 1.57 ETH at convert timestamp — aggregate phantom from umbrella, not this tx's delta |

### 2. `EARN_FLEXIBLE_SAVING:a67c0479…` (ETH, 2025-01-12)

| Field | Value |
|---|---|
| Type | `LENDING_WITHDRAW` CONFIRMED |
| Ledger | EARN `REALLOCATE_OUT` qty=−0.00001379 ETH, cbD=−$0.045 |
| Same-day FUND principal | `REALLOCATE_IN` +0.151149 ETH, cbD=+$494.56, avco=$3,289 |
| Failed stage | **read-model** — timeline picks EARN leg (bb=0) |
| Basis lost? | **No** — interest-only slice; principal carry on FUND is intact |

### 3. `EARN_FLEXIBLE_SAVING:734bf6f1…` (CMETH, 2025-02-07)

| Field | Value |
|---|---|
| Ledger | EARN `REALLOCATE_OUT` qty=−0.000524 CMETH, cbD=−$1.64 |
| Failed stage | **read-model** |
| Basis lost? | **No** |

### 4. `EXECUTION_SPOT:2200000000707964104:base|quote`

| Field | Value |
|---|---|
| Type | `SWAP` CONFIRMED — SELL CMETH −0.0038 @ $2,873; BUY USDT |
| Ledger CMETH leg | `DISPOSE` **qtyD=0, cbD=0** (bb=0) |
| Ledger USDT leg | `ACQUIRE` +10.91 USDT — OK |
| Failed stage | **replay / cost_basis** — CMETH disposal not applied |
| Basis lost? | **Yes** — 0.0038 CMETH basis should dispose ~$10.92; family CMETH AVCO overstated after spot sell |
| Pattern count | **38** CMETH `DISPOSE` rows with qtyD=0 company-wide |

### 5. `0x6ac6fc6010af5b146194439f261c97fd1bffd1fc185ca4a5b0073bca848f8029`

| Field | Value |
|---|---|
| Type | `INTERNAL_TRANSFER` — Bybit FUND→on-chain ARB corridor |
| On-chain leg | `ACQUIRE` +0.0039528 ETH, **cbD=0, uncovD=+0.0039528** |
| FUND leg | `BYBIT-33625378:FUNDING_HISTORY:0ca2c683…` — **`excludedFromAccounting: true`**, **0 ledger points** |
| Failed stage | **linking / move_basis** — FUND CARRY_OUT never replayed |
| Basis lost? | **Partial** — 0.00395 ETH on `0x68bc…` remains uncovered |

### 6. `0x258ed5c30165032d02467ca36c3f94e716bc16765d0f20ef92c71b32c353569a`

| Field | Value |
|---|---|
| Type | `BRIDGE_OUT` Across V2, ARBITRUM |
| Flows | TRANSFER ETH −0.0003 |
| Ledger | `CARRY_OUT` cbD=−$0.54 — basis preserved on wallet |
| correlationId | **missing** |
| BRIDGE_IN pair | **Not found** in universe |
| Failed stage | **linking** |
| Graph spike? | **No** — PRIMARY_FLOW AVCO $1,803 |

### 7. `0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784`

| Field | Value |
|---|---|
| Type | `BRIDGE_OUT` LiFi, ARBITRUM |
| correlationId | `bridge:lifi:0x6c5bd905…` |
| Ledger | `CARRY_OUT` −0.000221 ETH, cbD=−$0.73; spurious `CARRY_IN` +2.5e-7 uncov |
| BRIDGE_IN | **Not found** with same correlationId |
| Failed stage | **linking** |
| Graph spike? | **No** |

---

## D. EARN_FLEXIBLE_SAVING Pattern (Re-investigation)

User correlation with spikes is **real for the graph** but **misattributed for basis economics**:

1. Earn withdraw rows create EARN-sub-wallet `REALLOCATE_OUT` legs with **bb→0** → timeline UNAVAILABLE → graph cliff.
2. **Principal** routes through FUND `REALLOCATE_IN` / corridor rows with full cbD match (verified on seq 152, 183, 494, etc.).
3. Six earn-adjacent rows flagged with non-zero cbD/uncov — all are **correct principal carry**, not zero-basis holes.

**Verdict:** EARN events are **timeline correlation**, not AVCO/basis corruption. Fix belongs in timeline authority selection, not earn pairing logic.

---

## E. BYBIT Convert / EXECUTION_SPOT

| Flow | Classification | Basis treatment | Issue |
|---|---|---|---|
| Convert ETH→MNT | Correct `SWAP` | Dust ETH dispose — negligible | Timeline UNAVAILABLE only |
| EXECUTION_SPOT CMETH→USDT | Correct `SWAP` | **CMETH dispose dropped** | **Replay defect** — 38 instances |

---

## F. Confirmed Blockers (Clustered by Earliest Failed Stage)

### Cluster 1 — Read-model / timeline (`replay` verification surface)

**B-ZERO-1: Timeline UNAVAILABLE cliff storm**
- **Count:** 484 UNAVAILABLE / 1,992 timeline rows (24%)
- **Wrong surface:** Graph shows AVCO→$0 vertical cliffs
- **Correct surface:** Family covered qty + basis monotonic; next PRIMARY AVCO ≈ prior
- **Failed stage:** `replay` read-model (`TimelineAvcoAuthority`)
- **Evidence:** `EVIDENCE_PRESENT_UNUSABLE` — legs exist but authoritative selection picks zero-avco bucket
- **Remediation:** Forward-fill series AVCO on UNAVAILABLE when aggregated covered qty unchanged; or select FUND/umbrella leg over empty EARN leg

### Cluster 2 — Replay / cost basis

**B-ZERO-2: EXECUTION_SPOT CMETH zero dispose**
- **Tx:** `BYBIT-33625378:EXECUTION_SPOT:2200000000707964104:base|quote`, seq 497
- **Wrong surface:** CMETH `DISPOSE` qtyD=0, cbD=0 despite norm SELL −0.0038
- **Correct surface:** DISPOSE −0.0038 CMETH, cbD≈−$10.92
- **Failed stage:** `replay` / `cost_basis`
- **Evidence:** `EVIDENCE_PRESENT_UNUSABLE`
- **Remediation:** Fix swap replay for Bybit EXECUTION_SPOT CMETH SELL leg (38-row pattern)

### Cluster 3 — Linking / move basis

**B-ZERO-3: Bybit corridor 0x6ac6 FUND leg excluded**
- **Wrong surface:** On-chain +0.00395 ETH uncovered; FUND −0.00399 not in ledger
- **Correct surface:** CARRY_OUT FUND + CARRY_IN on-chain with matched basis
- **Failed stage:** `linking` (FUND `excludedFromAccounting:true`) → `move_basis`
- **Evidence:** `EVIDENCE_PRESENT_UNLINKED`

**B-ZERO-4: Unpaired Across bridge 0x258…**
- **Wrong surface:** BRIDGE_OUT without IN; no correlationId
- **Failed stage:** `linking`
- **Evidence:** `EVIDENCE_PRESENT_UNLINKED` — destination may be off-universe wallet/chain

**B-ZERO-5: Unpaired LiFi bridge 0x6c5…**
- **Wrong surface:** BRIDGE_OUT with correlationId but no IN
- **Failed stage:** `linking`
- **Evidence:** `EVIDENCE_PRESENT_UNLINKED`

### Cluster 4 — Known shortfall (not zero-graph, but coverage)

**B-ZERO-6: BASE LiFi bridge shortfall 0.799 ETH** (shortfallSources seq `0x4ca0b79e…`)
- **Failed stage:** `move_basis` — CARRY_OUT from empty BASE bucket
- Still inflates family basis on ARB CARRY_IN at spot — material AVCO spike (+30%), not zero-drop

---

## Recommended Fix Direction (Phase 2 — no code in Phase 1)

| Priority | Blocker | Fix direction |
|---|---|---|
| P0 | B-ZERO-1 | Extend `TimelineAvcoAuthority.resolve()` to carry forward `lastAvcoByAssetIdentity` when member points lack avco but event does not reduce family covered basis; prefer umbrella/FUND leg over empty EARN leg in scoring |
| P0 | B-ZERO-2 | `SwapReplayHandler` / Bybit spot: apply CMETH SELL quantity to DISPOSE (regression on EXECUTION_SPOT 2200000000707964104) |
| P1 | B-ZERO-3 | Stop excluding correlated FUNDING_HISTORY corridor OUT from replay when on-chain IN exists with same `correlationId` |
| P1 | B-ZERO-4/5 | Bridge linking: assign correlationId on Across OUT; locate or classify orphan OUT/IN pairs |
| P2 | B-ZERO-6 | Corridor CARRY_IN basis from CARRY_OUT evidence, not spot fallback when shortfall > ε |

---

## Artifacts

- Machine output: `data/derived/avco-zero-spikes-2026-05-30.json`
- Audit script: `scripts/audit/avco-zero-spikes-2026-05-30.mongosh.js`
- Results bundle: `results/*.md`

---

## Audit Terminal States

| Blocker | Terminal state |
|---|---|
| B-ZERO-1 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| B-ZERO-2 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| B-ZERO-3 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| B-ZERO-4 | EVIDENCE_PRESENT_UNLINKED — destination not in universe |
| B-ZERO-5 | EVIDENCE_PRESENT_UNLINKED |
| B-ZERO-6 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE (from prior cycle) |
| Material qty>0 avco=0 | IRREDUCIBLE_REMAINDER_PROVEN — none material |
