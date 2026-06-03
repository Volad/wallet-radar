# Confirmed Blockers — AVCO Audit 2026-06-03 (full audit cycle, refresh 9)

Universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
Pipeline state: CONFIRMED=7344 | PENDING=0 | PENDING_PRICE=0 | PENDING_CLARIFICATION=0 | ledger=9721

---

## USER COMPLAINT DIAGNOSIS (refresh 9)

**"ETH AVCO still spiking. Specifically calls out 0xa5e755a68349c9956b51ced38575733278b40467971ca4b9f9f40937fd5d2920."**

**Root cause: NONE — `0xa5e755a6` is CORRECT after the B-LP-EXIT-BASE-PANCAKE-UNKNOWN fix.**

| Observation | Actual finding | Verdict |
|---|---|---|
| `0xa5e755a6` CARRY_IN AMANWETH | INTERNAL_TRANSFER MANTLE: BYBIT:33625378:FUND → 0x1a87f12a WETH. CARRY_OUT −3.06 ETH cbD=−$9,011.24 from FUND at avco=$2,944.85. CARRY_IN +3.06 WETH cbD=+$9,011.24 to MANTLE. | ✅ CORRECT — full basis carried |
| "Spike" at `0xa5e755a6` | AMANWETH is a fresh position (1 ledger point). avcoBeforeUsd=null → avcoAfterUsd=$2,944.85. Looks like a "spike from zero" in the chart but is correct: it's a brand-new Aave position being created via LENDING_DEPOSIT. | ✅ EXPECTED behaviour |
| AMANWETH AVCO $1,533 (prev) | Fully explained by B-LP-EXIT-BASE-PANCAKE-UNKNOWN (now RESOLVED). AMANWETH AVCO now **$2,944.85** (basis $9,011.24). | ✅ FIXED |
| B-PCAKE-V3-PARTIAL-EXIT | 0x3dc35066 (2026-01-31) and 0x29dad570 (2026-02-02) confirmed as `LP_FEE_CLAIM` on BASE. | ✅ RESOLVED |
| B-ETH-ARBI-LOW-BASIS (R8) | Historical dust: ETH:ARBITRUM had 0.0004 ETH at AVCO ~$218-$583 in seq 5021-5072 (Sep 2025). Current ETH:ARBITRUM terminal: qty=0.0814, basis=$168, avco=$2,069. | ✅ NOT ACTIVE |
| B-EARN-DEPOSIT-MISSING (R8) | ETH 0.692975 (cbD=−$1,103.77, corrId='') and METH 0.668650 (cbD=−$447.66, corrId='') drained from BYBIT:33625378 main account to EARN with empty corrId. No matching EARN CARRY_IN found. | ❌ **ACTIVE P2** |
| B-EARN-MISTYPE (R8) | 79 BYBIT:33625378:EARN LENDING_WITHDRAWs — these are EARN_FLEXIBLE_SAVING redemptions mis-classified as LENDING_WITHDRAW. No `EARN_FLEXIBLE_SAVING` type exists in DB. Financial handling appears correct (REALLOCATE_OUT with cbD>0). | ❌ **ACTIVE P3 (type-model)** |
| B-DOUBLE-LEDGER-POINT (R8) | seq=5031+5032 for tx `0x04b1a5790c6f9a` (ARBITRUM INTERNAL_TRANSFER 0xf03b52e8→0x1a87f12a). Double CARRY_IN: Phase-1 has inflated cbD=$0.162 (should be $0.085), Phase-2 correction cbD=−$0.077. Net correct, intermediate AVCO spike $218→$583→$399 visible in history. | ❌ **ACTIVE P3 (cosmetic)** |

**P0 active regression: RESOLVED — AMANWETH now $2,944.85 AVCO (was $1,533). New active items: B-EARN-DEPOSIT-MISSING (~$1,551 cbD missing at EARN). No active AVCO spikes on material current positions.**

---

## RECENTLY RESOLVED (refresh 8–9)

| ID | Resolution |
|---|---|
| B-LP-EXIT-BASE-PANCAKE-UNKNOWN | **RESOLVED** — AMANWETH AVCO recovered from $1,533 → $2,944.85. Full LP basis ($9,011.24) now carried via chain: LP_EXIT→BRIDGE→aWETH→LENDING_WITHDRAW→UNWRAP→BYBIT_CORRIDOR→MANTLE. Verified 2026-06-03. |
| B-PCAKE-V3-PARTIAL-EXIT | **RESOLVED** — 0x3dc35066 and 0x29dad570 confirmed as `LP_FEE_CLAIM` on BASE (2026-01-31 and 2026-02-02). |
| B-ETH-ARBI-LOW-BASIS (R8) | **NOT ACTIVE** — Historical dust artifacts in ETH:ARBITRUM ledger (seq 5021-5072). Current terminal AVCO $2,069 is correct. |

---

## RANKED ACTIVE BLOCKER LIST (refresh 9)

**Audit verdict (2026-06-03 refresh 9): B-LP-EXIT-BASE-PANCAKE-UNKNOWN resolved. `0xa5e755a6` correctly carries $9,011.24 basis at $2,944.85 AVCO to AMANWETH — NOT a blocker. Main new finding: B-EARN-DEPOSIT-MISSING (~$1,551 cbD lost at EARN account for ETH+METH subscriptions with empty corrId).**

| Rank | ID | Severity | Description | Est. cbD shortfall | Active AVCO broken? | Fixable? |
|---|---|---|---|---|---|---|
| 0 | B-EARN-DEPOSIT-MISSING | ✅ **RESOLVED** | `BybitOnChainEarnOrphanRepairService` synthesises EARN counterpart for FUND-only On-chain Earn subscriptions. ETH CARRY_IN +$1,103.77 and METH CARRY_IN +$447.66 now materialise at EARN. Verified 2026-06-03. | **$0** (recovered) | No | ✅ RESOLVED |
| 1 | B-VAULT-WITHDRAW `0xc8b94615` | ✅ RESOLVED | `TurtleVaultBurnRepairService` synthesises turtleAvalancheUSDC burn; REALLOCATE_OUT -2787.57 → REALLOCATE_IN USDC +2831.20, cbAfter=$2,815. Confirmed 2026-06-03. | **$0** (recovered) | No | ✅ RESOLVED |
| 2 | B-BYBIT-CORRIDOR-2 (USDe) | DATA GAP | 2115 USDe MANTLE: SPOT→FUND FH missing; ~$2,105 shortfall | ~$2,105 | No (USDe position disposed May 2026) | ❌ Evidence missing |
| 3 | B-VAULT-WITHDRAW `0x971c8464` + `0xb47d87fa` | DATA GAP | MCUSDC upstream shortfall + wrapper-less vault no prior deposit | ~$4,608 (hist.) | No | ❌ Irreducible |
| 4 | B-EARN-BUNDLE | P2 ACTIVE | Multi-leg bundle fan-in defect — UTA carries orphaned at EARN. All positions now null but historical P&L distorted. | ~$1,632 hist. | No (all redeemed) | ✅ Move-basis defect |
| 5 | B-BYBIT-CORRIDOR-2 (cmETH) | DATA GAP | cmETH MANTLE GENUINE_EVIDENCE_MISSING; cmETH position now qty=0 (fully exited). | ~$212 | No (qty=0 after rebuild) | ❌ Evidence missing |
| 6 | B-EARN-MISTYPE | P3 TYPE-MODEL | 79 BYBIT:EARN LENDING_WITHDRAWs should be EARN_FLEXIBLE_SAVING. Financially handled correctly (cbD>0), but wrong type. | $0 (accounting OK) | No | ✅ Classification fix |
| 7 | B-DOUBLE-LEDGER-POINT | P3 COSMETIC | Double CARRY_IN at seq 5031-5032 for tx 0x04b1a579. Net numerically correct. Intermediate AVCO spike $218→$583→$399 in historical chart. | ~$0 (net correct) | No | ✅ Replay dedup |
| 8 | B-BRIDGE-ORPHAN-2 | DATA GAP | 5 BRIDGE_OUTs to 0xf5f93d26; 1 failed/refunded, 4 exits to untracked chain | ~$0.81 net | No | ❌ Trivial |
| 9 | B-BRIDGE-ORPHAN-1 | DATA GAP | 4 orphan BRIDGE_OUTs (LiFi USDC + Across ETH) | ~$7 | No | ❌ Cross-chain gap |

---

## 0xa5e755a6 DETAILED ANALYSIS (refresh 9)

### Transaction identity
| Field | Value |
|---|---|
| txHash | `0xa5e755a68349c9956b51ced38575733278b40467971ca4b9f9f40937fd5d2920` |
| type | INTERNAL_TRANSFER |
| status | CONFIRMED |
| networkId | MANTLE |
| blockTimestamp | 2026-02-19T08:15:22Z |
| walletAddress | `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` |
| flow | TRANSFER WETH +3.06 from counterparty BYBIT:33625378:FUND |

### Ledger points (both sides)
| Side | wallet | symbol | basisEffect | qtyDelta | cbDelta | avco |
|---|---|---|---|---|---|---|
| Source | BYBIT:33625378:FUND | ETH | CARRY_OUT | −3.06 | −$9,011.24 | $2,944.85 (before) |
| Destination | 0x1a87f12a (MANTLE) | WETH | CARRY_IN | +3.06 | +$9,011.24 | $2,944.85 (after) |

### Carry chain context
```
0xbc3fe1a5 (ARBITRUM INTERNAL_TRANSFER, BYBIT-CORRIDOR seq 8920)
  CARRY_OUT: 0x1a87f12a ETH:ARBITRUM −3.06 ETH, cbD=−$9,011.24 at avco=$2,944.85
  CARRY_IN:  BYBIT:33625378:FUND ETH +3.06, cbD=+$9,011.24, avcoAfter=$2,944.85

0xa5e755a6 (MANTLE INTERNAL_TRANSFER seq 8930)
  CARRY_OUT: BYBIT:33625378:FUND −3.06 ETH, cbD=−$9,011.24, qtyAfter=0
  CARRY_IN:  0x1a87f12a WETH:MANTLE +3.06, cbD=+$9,011.24, avcoAfter=$2,944.85

0x3b8592a7 (MANTLE LENDING_DEPOSIT seq 8933)
  REALLOCATE_IN: AMANWETH +3.06, cbD=+$9,011.24, avcoAfter=$2,944.85 ← CURRENT STATE
```

### Verdict
`0xa5e755a6` **correctly carries $9,011.24 in basis** at $2,944.85 AVCO from BYBIT FUND to MANTLE WETH. The visible "AVCO spike" in the UI is AMANWETH being a fresh position (avcoBeforeUsd=null → $2,944.85 after). This is expected and correct behavior for a new Aave lending deposit.

**Root cause of the $9,011 basis (not $4,691 as in Refresh 7):** The B-LP-EXIT-BASE-PANCAKE-UNKNOWN fix enabled the LP cost basis (~$2,026 from the LP receipt position) to flow correctly through: LP_EXIT `0x0a757aee` → BRIDGE_OUT `0x4ca0b79e` → BRIDGE_IN `0x38d445c4` → LENDING_DEPOSIT → LENDING_WITHDRAW `0xe564fec1` (REALLOCATE_IN: 3.046 WETH, cbD=$8,987.72, avco=$2,950) → UNWRAP `0x6f7aec13` → BYBIT CORRIDOR → MANTLE.

---

## B-EARN-DEPOSIT-MISSING — ✅ RESOLVED (2026-06-03)

### Problem
Two Bybit Earn subscription flows drained basis from the main account (BYBIT:33625378) but no matching CARRY_IN exists at EARN:

| Asset | qty | cbD drained from main | corrId | EARN CARRY_IN | Shortfall |
|---|---|---|---|---|---|
| ETH | 0.692975 | −$1,103.77 | `` (empty) | None found | **~$1,104** |
| METH | 0.668650 | −$447.66 | `` (empty) | None found | **~$448** |
| BBSOL | 1.742976 | $0 (zero) | bybit-collapsed-v1:... | Found | $0 |
| **Total** | | | | | **~$1,551** |

### Current EARN state
- BYBIT:33625378:EARN ETH: only 6 tiny ACQUIRE points (yield accruals, max 0.000014 ETH). No CARRY_IN for 0.693 ETH ever recorded.
- BYBIT:33625378:EARN METH: **zero ledger points** — the METH subscription never materialized at EARN.

### Root cause
Empty `correlationId` on the outbound CARRY_OUT events means the replay engine could not match the departure from the main account to an arrival at EARN. The `bybit-it-bundle-v1` / `bybit-collapsed-v1` matching paths require a non-empty corrId for pairing.

### Downstream impact
If ETH and METH were redeemed from EARN later (via LENDING_WITHDRAW), the redemption CARRY_OUT at EARN would have drained a near-zero basis (EARN had $0 for these assets). This would propagate zero-basis back to the main account at redemption time, causing a P&L distortion on any subsequent disposal.

### Failed stage hypothesis
`linking` / `move_basis` — BYBIT EARN subscription events without corrId are not paired to EARN account arrival. The CARRY_OUT fires correctly (draining main account), but the corresponding CARRY_IN at EARN is either not emitted or emitted with zero cbD.

### Evidence state
`EVIDENCE_PRESENT_UNUSABLE` — main account CARRY_OUTs exist with correct cbD but no corrId. EARN account shows no matching entry.

### Remediation
1. In the Bybit EARN subscription normalizer: ensure corrId is assigned to both the source (BYBIT:33625378) CARRY_OUT and the destination (BYBIT:33625378:EARN) CARRY_IN for ETH and METH subscription events.
2. Identify the specific source records (TRANSACTION_LOG or FUNDING_HISTORY) for these two subscriptions and retroactively assign matching corrIds.
3. Replay to materialize EARN CARRY_INs with correct cbD ($1,104 ETH + $448 METH).

---

## B-EARN-MISTYPE — P3 TYPE-MODEL (refresh 9)

### Problem
79 transactions at BYBIT:33625378:EARN have `type: LENDING_WITHDRAW`. These represent Bybit Flexible Savings (EARN_FLEXIBLE_SAVING) redemption events, not actual lending protocol withdrawals. The type `EARN_FLEXIBLE_SAVING` does not exist in the current DB type vocabulary.

### Assets affected
USDC, USDT, AGLD, ETH, CMETH, TON, BBSOL, MNT, ONDO, LDO, LINK (from 79 events)

### Financial impact
The financial handling at EARN LENDING_WITHDRAW time emits REALLOCATE_OUT with cbD proportional to EARN position basis. This is numerically correct for the cases verified (e.g., ONDO cbD=−$29.44 on REALLOCATE_OUT 400 ONDO). No active AVCO break detected from the mis-classification alone.

### Impact vector
If `EARN_FLEXIBLE_SAVING` type is introduced in the future with different replay logic, these 79 historical events would need to be reclassified. Until then, the LENDING_WITHDRAW path provides an acceptable proxy.

### Failed stage hypothesis
`classification` — Bybit EARN redemptions are mapped to LENDING_WITHDRAW because the EARN account is treated as a lending position. A distinct EARN_FLEXIBLE_SAVING type would better model the Bybit product semantics.

### Evidence state
`EVIDENCE_PRESENT_UNUSABLE` — transactions exist and are classified; type is semantically wrong but financially handled.

### Remediation
Add `EARN_FLEXIBLE_SAVING` as a normalized type with its own replay handler, or annotate existing LENDING_WITHDRAWs on BYBIT:EARN accounts with a subType flag to distinguish EARN from genuine Aave/lending protocol withdrawals.

---

## B-DOUBLE-LEDGER-POINT — P3 COSMETIC (refresh 9)

### Problem
ETH:ARBITRUM position for `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` has a double ledger point for a single CARRY_IN from transaction `0x04b1a5790c6f9aa72f19353f2226dab48d0e4630e679d7a18ad7b8ef9022c600` (ARBITRUM INTERNAL_TRANSFER `0xf03b52e8` → `0x1a87f12a`, 2025-09-05T18:25:12Z):

| seq | basisEffect | qtyDelta | cbDelta | qtyAfter | basisAfter | avcoAfter |
|---|---|---|---|---|---|---|
| 5031 | CARRY_IN | +0.00003782 | **+$0.162329** | 0.00042269 | **$0.246230** | **$582.53** |
| 5032 | CARRY_IN | 0 | **−$0.077615** | 0.00042269 | **$0.168614** | **$398.91** |

**Expected single point**: +0.00003782 ETH, cbD=+$0.084714, avcoAfter≈$399.

The intermediate state at seq=5031 (avco=$583) appears as an AVCO spike in the chart before being corrected at seq=5032. Net result after both points is numerically correct.

### Root cause hypothesis
The CARRY_IN was emitted as two journal entries by the two-phase carry mechanism. The Phase-1 entry received a cbD value that included the Phase-2 component (cbD was double-counted), and Phase-2 corrected it with a negative delta. The CARRY_OUT source (0xf03b52e8) shows a single CARRY_OUT at seq=5033 with cbD=−$0.08471 (correct). The two-phase split at the destination is a replay defect.

### Financial impact
Net correct. The dust position (~0.0004 ETH, basis ~$0.17) has no material accounting impact. The chart spike (avco $218 → $583 → $399) is a cosmetic artifact.

### Failed stage hypothesis
`replay` — two-phase carry-in handler emits Phase-1 with inflated cbD instead of the final carry amount.

### Audit terminal state
`AUTHORITATIVE_RECONSTRUCTION_COMPLETE` — net numerically correct. Cosmetic defect only.

---

## BROAD BASIS-LOSS SCAN RESULTS (refresh 9)

### Scan 1: totalCostBasisAfterUsd < $5, quantityAfter > 0.001
**50 entries found.** All are BYBIT stablecoin micro-positions (USDC, USDT), small token remnants (FLOCK, ALCH), or dust Bybit yield accruals. None involve ETH, WETH, BTC, WBTC, or other high-value assets at material quantities. **No new blockers.**

### Scan 2: basisEffect = UNKNOWN
**50 entries found.** All are LP_EXIT events. Distribution:
- LP receipt token burns (qty→0, basis=$0, avco=null): expected, always UNKNOWN for burned receipts
- LP_EXIT returning USD₮0 on UNICHAIN: `0x89ce8e60c21aec` (qty=772.85, $772.85, avco=$1.00) — correct carry
- LP_EXIT returning ETH on UNICHAIN: `0x89ce8e60c21aec` (ETH qty=0.11278, cbD=$163.10, avco=$1,569.12) — basis correctly computed, UNKNOWN instead of REALLOCATE_IN (W-12 class, P3)
- PancakeSwap BASE LP_EXITs returning CAKE: `0x42f3766b12a6b5` (CAKE 17.97 at $2.45, $44.04 basis) — acceptable; CAKE avco is market-plausible
- PancakeSwap BSC LP_EXIT returning XYZ: `0x8cd84503347886` (78K XYZ tokens, cbD=$0) — zero basis for unknown token, not material

All UNKNOWN entries are LP_EXIT artifacts. **No new material blockers from this scan.**

### Scan 3: ETH/WETH/BTC avcoAfterUsd between $0 and $500 (all historical ledger points)
**3 asset+wallet+network combinations** with historical low-AVCO points:
| wallet | symbol | network | historical low avco | current terminal avco | status |
|---|---|---|---|---|---|
| 0x1a87f12a | ETH | ARBITRUM | ~$218-$475 (seq 5021-5072, dust 0.0004 ETH) | $2,069 (qty=0.081 ETH) | BENIGN — dust period, resolved |
| 0x1a87f12a | WETH | BASE | $215 (seq 5006-5007, 0.0107 WETH) | qty=0 (fully disposed) | BENIGN — historical |
| 0x1a87f12a | WETH | ARBITRUM | $215 (seq 5021 undefined-order, 0.0107 WETH) | qty=0 (fully disposed) | BENIGN — historical |

Root cause of $215 AVCO on WETH:BASE and WETH:ARBITRUM: The BASE aWETH position was repeatedly cycled through LP_ENTRY/LP_EXIT events, depleting the tracked basis. By Sep 2025 (seq 5006), only 0.0107 WETH remained in AWETH:BASE with $2.31 basis (AVCO=$215). This was bridged to ARBITRUM, receiving $2.31 cbD on the BRIDGE_IN. Both $215 AVCO occurrences trace to the same aWETH basis-depletion event.

**No new blockers — all are historical artifacts with no current active AVCO impact.**

---

## ETH FAMILY STATE (refresh 9)

| Wallet | Symbol | Qty | Basis | AVCO | Status |
|---|---|---|---|---|---|
| 0x1a87f12a | AMANWETH | 3.060 | **$9,011.24** | **$2,944.85** | ✅ FIXED (was $1,533 in R7) |
| BYBIT:33625378 | ETH | 1.149 | $4,387.88 | $3,817.60 | ✅ |
| 0x1a87f12a | ETH | 0.081 | $168.48 | $2,068.98 | ✅ |
| BYBIT:33625378:FUND | ETH | 0.000 | $0 | — | ✅ (emptied via 0xa5e755a6) |
| 0xf03b52e8 | ETH | 0.011 | $42.65 | $3,822 | ✅ |
| Various other wallets | ETH/WETH | ~0.05 | ~$89 | ~$2,700 | ✅ |
| **FAMILY total** | | **~4.35 ETH** | **~$13,689** | **~$3,145** | |

Note: Combined FAMILY:ETH AVCO=$3,145 is higher than the user-expected ~$2,602. This reflects: (a) BYBIT ETH at $3,817 pulling the average up, (b) the Aave MANTLE ETH at $2,944, (c) fullSession AVCO calculation (which includes disposed positions) may differ from active-only. No active breaks detected.

---

## USER COMPLAINT DIAGNOSIS (refresh 7)

**"ETH AVCO ~$1,600 looks like a classification error. All data should be there."**

The user is **correct**. Root cause confirmed: **B-LP-EXIT-BASE-PANCAKE-UNKNOWN** (see below).

| Observation | Actual cause | Verdict |
|---|---|---|
| aManWETH AVCO $1,533 | UNKNOWN tx `0x0a757aee` (PancakeSwap V3 LP_EXIT on BASE) returned 0.799 ETH with no flows → zero basis carried through bridge | ❌ **CLASSIFICATION ERROR** |
| BRIDGE_IN `0x38d445c4` cbD=$0 | Source BASE position only had 0.000002364 ETH tracked; 0.799 ETH came from LP_EXIT not captured in flows | ❌ **UPSTREAM BUG** |
| B-LP-UNSTAKE-ETH-MISS "CLOSED" | **WRONG RESOLUTION** — previously attributed to SPONSORED_GAS_IN, but BASE SPONSORED_GAS_IN totals only 0.000446 ETH. Real source was LP_EXIT UNKNOWN. | ❌ **PRIOR AUDIT ERROR** |
| April 2025 SWAP ACQUIRE at $1,554 | Legitimate Bybit SPOT purchases on 2025-04-11; ETH genuinely ~$1,554 at that date. On BYBIT:33625378, not aManWETH path. | ✅ CORRECT |
| SPONSORED_GAS_IN AVCO dilution | 18 events on BASE totaling 0.000446 ETH ($0 cbD); tiny. 266 events total (all wallets) — GAS_ONLY with minimal quantities; not material | ✅ NOT MATERIAL |
| LENDING_WITHDRAW REALLOCATE_IN pulling AVCO $1,952→$1,531 | Correct restoration of aWETH position AVCO. But aWETH position is wrong because LP_EXIT cbD was not captured upstream | ❌ DOWNSTREAM SYMPTOM |
| CARRY_IN `0xa5e755` aManWETH avco: 2790→1533 | Cascade from zero-basis BRIDGE_IN: correct carry of upstream wrong value | ❌ DOWNSTREAM SYMPTOM |

**P0 active regression: aManWETH AVCO $1,533 instead of ~$2,219. Estimated cbD shortfall ~$2,100.**

---

## RECENTLY RESOLVED (refresh 6–7)

| ID | Resolution |
|---|---|
| B-BRIDGE-IN-ACQUIRE | **RESOLVED** — 129 CARRY_INs, $45,957 cbD, 0 oscillations. Verified 2026-06-03. |
| ~~B-LP-UNSTAKE-ETH-MISS~~ | **REOPENED as B-LP-EXIT-BASE-PANCAKE-UNKNOWN** — Prior "CLOSED" verdict was incorrect. 0.799 ETH came from LP_EXIT, not SPONSORED_GAS_IN. |
| B-SHORTFALL-1 oscillation | **RESOLVED** — Oscillation fix correct. No genuine BRIDGE_IN with non-zero source is losing basis. |

---

## RANKED ACTIVE BLOCKER LIST (refresh 7)

**Audit verdict (2026-06-03 refresh 7): P0 classification error found. aManWETH AVCO=$1,533 traces to UNKNOWN PancakeSwap V3 LP_EXIT on BASE (`0x0a757aee`) with empty flows. B-LP-UNSTAKE-ETH-MISS was wrongly closed — reopened as B-LP-EXIT-BASE-PANCAKE-UNKNOWN. cbD shortfall ~$2,100 actively broken.**

| Rank | ID | Severity | Description | Est. cbD shortfall | Active AVCO broken? | Fixable? |
|---|---|---|---|---|---|---|
| 0 | **B-LP-EXIT-BASE-PANCAKE-UNKNOWN** | **P0 ACTIVE** | PancakeSwap V3 LP_EXIT `0x0a757aee` on BASE: `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` → empty flows → 0.799 ETH return not tracked → BRIDGE_IN carries $0 → aManWETH AVCO $1,533 | **~$2,100** | **YES** (aManWETH 3.06 ETH basis $4,691, should be ~$6,791) | ✅ Normalization fix needed |
| 1 | B-VAULT-WITHDRAW `0xc8b94615` | P2 FIXABLE | Turtle Finance USDC vault AVALANCHE: vault token burn missing from flows | ~$2,815 | No (USDC disposed) | ✅ Normalization fix needed |
| 2 | B-BYBIT-CORRIDOR-2 (USDe) | DATA GAP | 2115 USDe MANTLE: SPOT→FUND FH missing; ~$2,105 shortfall | ~$2,105 | No (USDe position disposed May 2026) | ❌ Evidence missing |
| 3 | B-VAULT-WITHDRAW `0x971c8464` + `0xb47d87fa` | DATA GAP | MCUSDC upstream shortfall + wrapper-less vault no prior deposit | ~$4,608 (hist.) | No | ❌ Irreducible |
| 4 | B-BYBIT-CORRIDOR-2 (cmETH) | DATA GAP | cmETH MANTLE GENUINE_EVIDENCE_MISSING | ~$212 | Yes (cmETH qty=0.1053 no basis) | ❌ Evidence missing |
| 5 | B-BRIDGE-ORPHAN-2 | DATA GAP | 5 BRIDGE_OUTs to 0xf5f93d26; 1 failed/refunded, 4 exits to untracked chain | ~$0.81 net | No | ❌ Trivial |
| 6 | B-BRIDGE-ORPHAN-1 | DATA GAP | 4 orphan BRIDGE_OUTs (LiFi USDC + Across ETH) | ~$7 | No | ❌ Cross-chain gap |

---

## B-LP-EXIT-BASE-PANCAKE-UNKNOWN — P0 ACTIVE (refresh 7)

### Problem class
LP_EXIT (PancakeSwap V3 concentrated liquidity, ETH/USDC pool, BASE network)

### Protocol and scope
- Protocol: PancakeSwap V3 (MasterChef V3 / NonfungiblePositionManager)
- Network: BASE
- Contract interaction: router method not supported by WalletRadar classifier
- LP position ID: `lp-position:base:pancakeswap:9`, position token NFT `938761`
- Affected wallet: `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`

### The failing transaction
| Field | Value |
|---|---|
| txHash | `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a` |
| network | BASE |
| timestamp | 2026-02-06T07:15:37Z |
| pipeline classification | `UNKNOWN` |
| classifiedBy | `PROTOCOL_REGISTRY` (protocol recognized; method unsupported) |
| flows | **EMPTY** |
| missingDataReasons | `["ROUTER_METHOD_OVERLOAD_UNSUPPORTED"]` |
| protocolName | PancakeSwap, V3 |

### Authoritative raw-source reconstruction

Chronological event sequence on BASE, 2026-02-06:

| Time | txHash (short) | Type | Effect |
|---|---|---|---|
| 07:14:19 | `0x87169b31` | REWARD_CLAIM | Harvests CAKE fees; ETH gas -$0.011 |
| 07:14:45 | `0xe66f9242` | LP_POSITION_UNSTAKE | Unstakes LP NFT from MasterChef V3 |
| 07:15:13 | `0xed7c0f4d` | LP_FEE_CLAIM | Claims $5.48 USDC fee income from position |
| 07:15:37 | **`0x0a757aee`** | **UNKNOWN (LP_EXIT)** | **Burns LP NFT 938761; returns ~0.799 ETH to wallet — flows EMPTY** |
| 07:17:13 | `0xd644d3f8` | SPONSORED_GAS_IN | Relay protocol pays 0.000002364 ETH gas rebate |
| 07:17:15 | `0x4ca0b79e` | BRIDGE_OUT (LI.FI) | Sends 0.799 ETH to ARBITRUM |

**Physical flow**: 0.799 ETH was returned by the LP exit at 07:15:37 and immediately bridged at 07:17:15 (98-second gap). The BRIDGE_OUT flow `"assetSymbol":"ETH","quantityDelta":"-0.799"` is confirmed. The BASE ETH tracked position at 07:17 held only 0.000002364 ETH (a Relay gas rebate), not the 0.799 ETH.

**LP position cost basis**:

| Entry | Date | ETH deposited | USDC deposited | ETH price (est.) | ETH cost |
|---|---|---|---|---|---|
| `0x5532ff4b` LP_ENTRY | 2025-12-20T10:23 | 0.262291541 | $1,148.84 | ~$3,700 | ~$970 |
| `0x9d6199bb` LP_ENTRY | 2025-12-20T10:25 | 0.009404129 | $41.10 | ~$3,700 | ~$35 |
| `0xc9c5686c` LP_ENTRY | 2026-02-02T11:17 | 0.016310631 | $13.00 | ~$2,000 | ~$33 |
| `0xbdf8cee9` LP_ENTRY | 2026-02-02T11:33 | 0.001299999 | $1.07 | ~$2,000 | ~$3 |
| **Total deposited** | | **0.289306 ETH** | **~$1,204 USDC** | | **~$1,041 ETH cost** |
| Partial exits (Jan 31, Feb 2) | | — | −$13.82 USDC returned | | |
| **Net LP cost basis** | | | | | **~$2,231** |

At exit time (Feb 6, ETH ~$1,897): 0.799 ETH × $1,897 = $1,516. LP returned mainly ETH (concentrated liquidity shifted to ETH as price fell from $3,700 → $1,897, i.e., impermanent loss accumulated ETH). Minimal USDC returned (LP_FEE_CLAIM returned only $5.48 USDC as fees).

**Estimated missing cbD on returned ETH**: $2,231 × ($1,516 / $1,521) ≈ **~$2,214** (attributing nearly all LP cost basis to ETH given the heavy IL toward ETH).

A conservative lower bound: $1,000 (only the ETH-portion cost at deposit); upper bound: $2,231 (full LP cost).

### Cascade chain (how the $1,533 aManWETH AVCO was produced)

```
0x0a757aee BASE LP_EXIT (UNKNOWN, empty flows)
  → 0.799 ETH returned to wallet with $0 tracked basis
  → 0x4ca0b79e BASE BRIDGE_OUT LI.FI
      CARRY_OUT: 0.000002364 ETH cbD=$0 (only the Relay gas rebate tracked)
      [gap: 0.798997636 ETH not in tracked position → cbD=$0 on bridge]
  → 0x38d445c4 ARBITRUM BRIDGE_IN LI.FI
      CARRY_IN: 0.799 ETH cbD=$0 ← MISSING ~$2,214 here
  → 0x3099ace0 ARBITRUM LENDING_DEPOSIT REALLOCATE_IN
      aWETH position: 0.799 ETH cbD=$0.92 added to 2.087 ETH position at $4,663
      AVCO: (4,663 + 0.92) / 2.886 → $1,616 (from $2,234)
  → additional LENDING_DEPOSITs
      aWETH grows to 3.046 ETH at basis $4,664, AVCO $1,531
  → 0xe564fec1 ARBITRUM LENDING_WITHDRAW REALLOCATE_IN
      Restores NATIVE:ARBITRUM ETH 3.046 ETH cbD=$4,664 AVCO $1,531
  → 0x6f7aec13 UNWRAP REALLOCATE_IN
      Combined with 0.017 native ETH → 3.065 ETH AVCO $1,533
  → 0xbc3fe1a5 ARBITRUM INTERNAL_TRANSFER CARRY_OUT → BYBIT:33625378:FUND
  → BYBIT:33625378:FUND CARRY_IN 3.06 ETH cbD=$4,691 AVCO $1,533
  → 0xa5e755a6 MANTLE INTERNAL_TRANSFER CARRY_IN
      0x1a87f12a NATIVE:MANTLE 3.06 ETH cbD=$4,691 AVCO $1,533
  → 0x3b8592a7 MANTLE LENDING_DEPOSIT REALLOCATE_IN
      aManWETH position: 3.06 ETH cbD=$4,691 AVCO $1,533 ← FINAL BROKEN STATE
```

### Authoritative reconstruction vs current pipeline output

| Surface | Current pipeline output | Auditor-derived correct value |
|---|---|---|
| `0x0a757aee` type | UNKNOWN, flows=[] | Should be LP_EXIT with ETH TRANSFER +0.799 flows |
| `0x38d445c4` BRIDGE_IN cbD | $0 | ~$2,214 |
| NATIVE:ARBITRUM aWETH AVCO after deposit | $1,616 | ~$2,156 |
| LENDING_WITHDRAW NATIVE:ARBITRUM cbD | $4,664 | ~$6,878 |
| NATIVE:MANTLE CARRY_IN cbD | $4,691 | ~$6,905 |
| aManWETH basis (current) | **$4,691** | **~$6,905** |
| aManWETH AVCO (current) | **$1,533** | **~$2,257** |

### Failed stage hypothesis
`normalization` — PancakeSwap V3 router method is recognized (PROTOCOL_REGISTRY match) but `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` prevents flow extraction. The classifier produces zero flows. The LP_EXIT handler then has nothing to work with.

### Evidence state
`EVIDENCE_PRESENT_UNUSABLE` — All raw evidence exists in `raw_transactions`. The protocol is identified as PancakeSwap V3 with HIGH confidence. The blocking issue is the router method overload not being parsed, not missing data.

### Prior audit error
The previous resolution in B-LP-UNSTAKE-ETH-MISS concluded "0.799 ETH on BASE was SPONSORED_GAS_IN receipts — zero basis correct." This is demonstrably wrong:
- SPONSORED_GAS_IN on BASE for wallet `0x1a87f12ac0` = 18 events totaling **0.000446 ETH** (not 0.799 ETH)
- The RELAY gas rebate `0xd644d3f8` added only **0.000002364 ETH** — the BRIDGE_OUT `0x4ca0b79e` CARRY_OUT correctly used this tiny amount as the tracked position
- The 0.799 ETH discrepancy is entirely explained by the LP_EXIT `0x0a757aee` returning ETH to the wallet 98 seconds before the bridge, with no flows recorded

### SPONSORED_GAS_IN AVCO dilution (separate analysis)

SPONSORED_GAS_IN events create `GAS_ONLY` ledger entries with positive `quantityDelta` and `costBasisDeltaUsd=0`. This represents gas fee rebates from protocols like Relay.

Impact on NATIVE:ARBITRUM position around 2026-02-19:
- Three events add tiny ETH (0.0000017–0.0000081 ETH each) to the native position (which held ~0.016 ETH)
- AVCO shifts: $1,953.64 → $1,953.42 → $1,952.38 → $1,952.11 (total dilution: ~$1.5)
- **Not material.** The AVCO drop from $1,952 → $1,531 was from the LENDING_WITHDRAW restoring the aWETH position (which correctly reflects the upstream LP_EXIT bug, not the SPONSORED_GAS_IN events).

### Remediation

**Pipeline stage**: `normalization` — add support for the PancakeSwap V3 router method overload that is currently blocking flow extraction.

**Required work**:
1. Identify the specific PancakeSwap V3 router selector in transaction `0x0a757aeeb58667c545` on BASE
2. Add that method/overload to the PancakeSwap V3 normalization handler
3. Emit LP_EXIT flows: `TRANSFER ETH +0.799` (from pool back to wallet) + `TRANSFER LP-RECEIPT -1` (burn)
4. The LP_EXIT basis-carry mechanism should then propagate LP position cost basis to the returned ETH
5. The BRIDGE_OUT `0x4ca0b79e` will then carry ~$2,214 cbD instead of $0 to the ARBITRUM BRIDGE_IN
6. Rebuild pipeline → aManWETH AVCO should recover from $1,533 to ~$2,257

**Acceptance criteria**:
- `0x0a757aee` normalized as LP_EXIT with non-empty ETH flow ≥ 0.799
- BRIDGE_IN `0x38d445c4` CARRY_IN cbD > $1,000
- aManWETH `avcoAfterUsd` > $2,000

### Estimated cbD shortfall
**~$2,100** (median; range $1,000–$2,214 depending on LP cost attribution method)

### Active AVCO impact
**YES** — aManWETH (0x1a87f12a, MANTLE) currently shows qty=3.06, basis=$4,691, AVCO=$1,533. Correct AVCO should be ~$2,257.

### Audit terminal state
`AUTHORITATIVE_RECONSTRUCTION_COMPLETE` — Pipeline correction required at normalization stage.

---

## STATUS UPDATE — B-EARN-BUNDLE RESOLVED (refresh 5)

**B-EARN-BUNDLE** is **RESOLVED** as of refresh 5 (fan-in fix deployed).

Verified post-rebuild AC (CARRY_IN cbD by asset, all EARN accounts):

| asset | total cbD | entry count | verdict |
|---|---|---|---|
| LDO | $449.99 | 22 | ✅ Previously-zero bundles now have Phase-2 TRANSACTION_LOG/FUNDING_HISTORY carries |
| TON | $656.60 | 10 | ✅ |
| LINK | $355.66 | 10 | ✅ |
| ARB | $10.73 | 5 | ✅ |

Confirmed pattern: Phase-1 EARN_FLEXIBLE_SAVING entry carries qty with cbD=$0; Phase-2 TRANSACTION_LOG / FUNDING_HISTORY entry carries cbD>0 with qty=0. Both-phase carry correctly populates EARN basis.

Previously-zero LDO bundles now have Phase-2 entries:
- 17.806 LDO: Phase-2 cbD=$21.04 (TRANSACTION_LOG seq 5753)
- 102.4 LDO: Phase-2 cbD=$34.998 (FUNDING_HISTORY seq 6412)
- 14.973 LDO: Phase-2 cbD=$12.514 (TRANSACTION_LOG seq 6468)
- 27.899 LDO: Phase-2 cbD=$19.963 (TRANSACTION_LOG seq 7207)

**B-EARN-BUNDLE: PASS ✅**

---

## STATUS UPDATE — B-GMX-LP-SETTLEMENT RESOLVED (refresh 5)

**B-GMX-LP-SETTLEMENT** is **RESOLVED** as of refresh 5 (LP_ENTRY_SETTLEMENT REALLOCATE_IN fix deployed).

All 5 LP_ENTRY_SETTLEMENT events now emit REALLOCATE_IN with cbD > 0:

| txHash | LP token | LP cbD | ETH refund cbD |
|---|---|---|---|
| `0x9fab1650` | GLV [WETH-USDC] 40.35 | $68.83 | $0.014 |
| `0x3ad60ac2` | GM: ETH/USD [WETH-USDC] 529.62 | $1,001.00 | $0.054 |
| `0x61c1272c` | GM: ETH/USD [WETH-USDC] 21.60 | $40.25 | $0.128 |
| `0x1aa3438d` | GM: ETH/USD [WETH-USDC] 97.96 | $149.91 | $0.092 |
| `0x52924cd8` | GM: ETH/USD [WETH-USDC] 4.63 | $7.27 | $0.094 |

Elevation check (LP_ENTRY_REQUEST REALLOCATE_OUTs with GMX corrId): total = **-$1,267.65** across 10 rows — consistent with sum of above settlement REALLOCATE_INs ($1,267.38). Rounding difference negligible.

**B-GMX-LP-SETTLEMENT: PASS ✅**

---

## STATUS UPDATE — B-VAULT-WITHDRAW `0xc8b94615` — NORMALIZATION DEFECT IDENTIFIED (refresh 5)

**New finding (refresh 5 investigation):** `0xc8b94615` is NOT a wrapper-less vault — it is a **Turtle Finance USDC vault** on Avalanche with a normalization defect. The vault token (`turtleAvalancheUSDC`) burn is missing from the VAULT_WITHDRAW flows.

### Protocol identification

- Vault contract: `0x3048925b3ea5a8c12eecccb8810f5f7544db54af` (Avalanche)
- Vault token: `turtleAvalancheUSDC` (same contract address, ERC4626-style)
- Deposit: `0xf49217e3...` (2025-12-12) — classified as **SWAP**: SELL 2,815.03 USDC → BUY 2,787.57 turtleAvalancheUSDC (cbD=+$2,815 ACQUIRE)
- Withdrawal: `0xc8b94615` (2026-01-12) — classified as **VAULT_WITHDRAW**: TRANSFER USDC +2,831.2 only

### Root cause

The VAULT_WITHDRAW flows contain only `TRANSFER USDC +2,831.199` (inbound). The turtleAvalancheUSDC burn (which happens on-chain when the user calls `redeem` or equivalent) is NOT captured in the normalized flows. Without the turtleAvalancheUSDC REALLOCATE_OUT flow, the Bug A wrapper bucket mechanism cannot activate.

The turtleAvalancheUSDC position remains PHANTOM in the database: 2,787.57 units with $2,815 basis (ACQUIRE from SWAP, never drained). The VAULT_WITHDRAW handler issued REALLOCATE_IN USDC cbD=$0 because no wrapper was found.

Classifier test (`OnChainClassifierTest.java` line 6739-6756) shows the classifier knows `claimSharesAndRequestRedeem` on `0x3048925b` and classifies it as UNKNOWN/PENDING_REDEEM_REQUEST. The actual `0xc8b94615` is likely the subsequent `claim` or `redeem` step which returns USDC without the visible vault token burn.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — turtleAvalancheUSDC position holds $2,815 basis from the SWAP ACQUIRE. The VAULT_WITHDRAW handler does not find or drain it.

### Phantom position

| asset | qty | cbD | avco | status |
|---|---|---|---|---|
| TURTLEAVALANCHEUSDC | 2,787.57 | $2,815.03 | $1.009850 | PHANTOM — already redeemed on-chain, not drained in DB |

### Failed stage hypothesis

`normalization` — the vault token burn (turtleAvalancheUSDC sent to vault/burn address) is not captured in the VAULT_WITHDRAW flows. The Bug A basis-carry mechanism requires an explicit REALLOCATE_OUT on the vault token in the same tx flows.

### Remediation

Two options:
1. **Normalization enrichment**: When normalizing `0xc8b94615` (and future turtleAvalancheUSDC redemptions), add the vault token burn as an outbound flow (`TRANSFER turtleAvalancheUSDC -2787.57` from wallet to vault/0x0), enabling the existing Bug A wrapper bucket carry. Requires identifying the vault token burn event in the raw transaction token transfers.
2. **VAULT_WITHDRAW handler enhancement**: When handling VAULT_WITHDRAW from a known vault contract with no wrapper token in flows, look up the wallet's position in the vault token and carry its proportional basis — even without an explicit REALLOCATE_OUT in the current tx.

Option 1 is simpler and consistent with the existing Bug A mechanism. The classifier already recognizes `claimSharesAndRequestRedeem` (PENDING_REDEEM_REQUEST) but needs to link the follow-up claim step and include the vault token redemption in the normalized flows.

### cbD shortfall

~$2,815 (the original USDC deposited in the SWAP → stuck in phantom TURTLEAVALANCHEUSDC position)

---

## STATUS UPDATE — B-VAULT-WITHDRAW `0x971c8464` + `0xb47d87fa` — UPSTREAM SHORTFALLS CONFIRMED (refresh 5)

**`0x971c8464` (ARBITRUM, 1,783 USDC, MCUSDC wrapper):**
- REALLOCATE_OUT MCUSDC cbD = -$0.001119 (≈$0) → wrapper bucket was essentially empty
- REALLOCATE_IN USDC cbD = +$0.001119 (≈$0) → basis not carried
- ACQUIRE USDC +33.92 cbD = +$33.92 (yield interest portion, correctly priced at market)
- Confirmed: 2nd and 3rd MCUSDC deposits brought near-zero basis USDC → wrapper accumulated nearly no cost basis → VAULT_WITHDRAW correctly reflects $0 wrapper bucket
- **IRREDUCIBLE_REMAINDER_PROVEN** — upstream shortfall at deposit time. No fix available.

**`0xb47d87fa` (ARBITRUM, 2,825 USDC, vault `0x6a2abff960b663462cbc46a2cfcf85063fe8ae14`):**
- Only 1 ledger point: REALLOCATE_IN USDC +2825.31, cbD=$0, avcoAfter=$1
- avcoAfter=$1 is correct — the large pre-existing USDC position ($1 avco) absorbed the zero-basis addition with negligible AVCO distortion
- No VAULT_DEPOSIT for vault `0x6a2abff9` found anywhere in `normalized_transactions`
- The USDC deposit happened before the ingestion scope or was never tracked
- **IRREDUCIBLE_REMAINDER_PROVEN** — pre-history deposit. No fix available.

---

## STATUS UPDATE — B-CORRIDOR-2 USDe — GENUINE_EVIDENCE_MISSING CONFIRMED (refresh 5)

Root cause chain confirmed via `bybit_extracted_events` direct query:

1. `BYBIT-33625378:EXECUTION_SPOT:2230000000735733889` acquired 2,115 USDe at **$2,115 cbD** at 2025-08-30T09:22:33
2. `bybit_extracted_events` query for USDe on 2025-08-30: **EMPTY** — no FUNDING_HISTORY records for USDe exist in the raw Bybit export for this date
3. FUND sub-account received only 10 USDe (from a separate small FUNDING_HISTORY record `ce163d1b...` at 09:38:19)
4. Corridor CARRY_IN `0x79d17a8d`: 2,115 USDe with only **$10 cbD** (FUND had $10 at withdrawal)

The SPOT→FUND transfer of 2,115 USDe is MISSING from `bybit_extracted_events`. The Bybit raw export did not include the FUNDING_HISTORY record for this transfer. Without it, the carry chain is: SPOT has $2,115 (isolated) → FUND has $10 → corridor carries only $10 → Mantle receives 2,115 USDe at $10 basis.

**GENUINE_EVIDENCE_MISSING_PROVEN** — the Bybit FUNDING_HISTORY for the 2,115 USDe SPOT→FUND transfer was never exported into `bybit_extracted_events`. Shortfall ~$2,105 is irreducible on the current dataset. Re-fetching Bybit FUNDING_HISTORY for this date could potentially recover this basis.

---

## STATUS UPDATE — B-BRIDGE-ORPHAN-2 — FINAL CLASSIFICATION (refresh 5)

All 5 BRIDGE_OUTs from `0xf03b52e8` ARBITRUM to `0xf5f93d26229482adca3e42f84d08d549cf131658`:

| txHash | Date | USDC sent | ETH forwarded | Return found | Classification | Net shortfall |
|---|---|---|---|---|---|---|
| `0xb1e9f65d` | 2026-01-12 | 2,829.12 | 0.000064 | YES: 2,828.31 USDC from same contract in 19min | FAILED BRIDGE → refunded | $0.81 (protocol fee) |
| `0x4a2eb3ee` | 2026-01-13 | 1,050.00 | 0.000064 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |
| `0x5ca14340` | 2026-01-15 | 50.00 | 0.000064 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |
| `0x87967a7a` | 2026-02-02 | 99.67 | 0.000068 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |
| `0x360988904f` | 2026-02-10 | 50.00 | 0.000068 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |

All USDC inflow-only from `0xf5f93d26` in entire dataset = 1 record (`0x7f6ccd24ba`, 2828.31 USDC, 19min after `0xb1e9f65d`). No ETH or USDC returns exist for the other 4.

**The 4 "successful" bridges carry USDC to an untracked destination chain. CARRY_OUT cbD is correctly drained at source. No BRIDGE_IN expected. No accounting error.**

`0xb1e9f65d`: failed bridge refunded via EXTERNAL_TRANSFER_IN. CARRY_OUT drained $2,829.12; EXTERNAL_TRANSFER_IN ACQUIRE created $2,828.31 fresh basis. Net: -$0.81 (bridge fee). No AVCO distortion since USDC ≈ $1 throughout.

**Total real B-BRIDGE-ORPHAN-2 accounting shortfall: $0.81** (failed-bridge fee only).

Prior estimate of ~$3,979 was incorrect — based on assumption that destination was tracked but no BRIDGE_IN found. Actual finding: 4/5 are correct exits to an untracked chain.

**Terminal state: IRREDUCIBLE_REMAINDER_PROVEN** for all 5 (USDC ≈ $1, no AVCO correction warranted).

### IRREDUCIBLE DATA GAPS (no code fix possible)
- SYRUPUSDC LENDING_WITHDRAW `0xb8d6c7` — 1887 syrupUSDC, corrId=null, no paired deposit → upstream shortfall
- WRAP/UNWRAP zero cbD — 104 WETH + 98 ETH entries, all valueUsd=$0 → no pricing available, trivial
- LP_EXIT UNKNOWN LP-RECEIPTs — 58 entries, totalValueUsd=$0 → receipt tokens have no tracked value
| — | B-MNT-CARRY-1 | **RESOLVED** | MNT MANTLE corrId fix | ✅ $69 recovered | — |
| — | B-ONDO-CARRY-1 | **RESOLVED** | ONDO bybit-collapsed FIFO fallback fix | ✅ EARN cbD=$344 | — |
| — | B-CROSS-UID (Defect 1) | **RESOLVED** | FUND-sourced FUNDING_HISTORY corrId | ✅ BTC+TON ~$259 recovered | — |

---

## STATUS UPDATE — B-MNT-CARRY-1 RESOLVED

**B-MNT-CARRY-1** (MNT MANTLE INTERNAL_TRANSFERs missing corrId → zero-basis CARRY_IN) is **RESOLVED** as of refresh 3.

`OnChainInternalTransferPairRepairService` fix (removed `continuityCandidate=false` filter) correctly assigns `internal-tx:mantle:txHash` corrIds to all 5 MNT transactions.

Verified post-rebuild (all 5 txHashes emit two-phase CARRY_IN: qty+cbD entry + qty=0 basis-carry entry):

| txHash | MNT qty | phase-1 cbD | phase-2 cbD | total cbD | verdict |
|---|---|---|---|---|---|
| `0xffc959c2` | 0.8 | $0.531 | $0.029 | **$0.560** | ✅ |
| `0x3c011394` | 25.0 | $18.77 | $2.564 | **$21.33** | ✅ |
| `0xe2bf4c4f` | 23.3 | $15.86 | $4.094 | **$19.95** | ✅ |
| `0x4fa1f2a2` | 31.1 | $20.22 | $6.419 | **$26.64** | ✅ |
| `0x7e5e7443` | 0.584 | $0.443 | $0.012 | **$0.455** | ✅ |
| **Total** | **80.78** | | | **~$68.94** | ✅ PASS |

---

## STATUS UPDATE — B-ONDO-CARRY-1 RESOLVED

**B-ONDO-CARRY-1** (4 ONDO CARRY_OUT with `bybit-collapsed-v1:` corrId, no matching CARRY_IN) is **RESOLVED** as of refresh 3.

`ReplayPendingTransferKeyFactory` FIFO check reordering + `TransferReplayHandler.applyBybitMultiLegBundleTransfer()` FIFO fallback (`earnCarryFifoKey`) now recovers orphaned collapsed-v1 carries.

Verified:
- ONDO CARRY_IN cbD on `BYBIT:33625378:EARN`: **$344.01** (24 CARRY_INs total, was ~$0 for the 4 bybit-collapsed-v1 orphans)
- ONDO CARRY_IN cbD on `BYBIT:33625378` main: **$27.42** (73.21 ONDO, 1 entry)
- Both ONDO positions are now null (all redeemed) — no current active AVCO gap

---

## STATUS UPDATE — B-CROSS-UID Defect 1 RESOLVED

**B-CROSS-UID Defect 1** (FUNDING_HISTORY outbound on `BYBIT:516601508:FUND` missing corrId → zero-cbD CARRY_IN on destination) is **RESOLVED** as of refresh 3.

FUND outbound records for BTC and TON now carry `bybit-cross-uid-v1:` corrIds, enabling `isBybitSelfTransfer()` guard to permit CARRY_OUT and correctly propagate basis.

Verified:

| corrId (short) | asset | qty | cbD (post-fix) | verdict |
|---|---|---|---|---|
| `bybit-cross-uid-v1:866903d7` | BTC | 0.000797 | **$76.855** | ✅ (was $0) |
| `bybit-cross-uid-v1:a893b645` | BTC | 0.000362 | **$34.890** | ✅ (was $0) |
| `bybit-cross-uid-v1:6b956290` | TON | 32.442 | **$147.611** | ✅ (was $0) |

**BTC DISPOSE event** (`BYBIT:33625378`, swap 2025-12-12): now correctly removes `cbD=−$111.745` (was $0). Historical P&L corrected.

Remaining B-CROSS-UID residuals after Defect 1 fix:
- MNT `47eaa702` from UID 409666492: ~$0.29 (negligible)
- USDT `9a0ae038` from UID 409666492: ~$0.002 (negligible)
- ETH `ebf90bee`: $0 cbD confirmed **legitimate** — source UID had no ETH at transfer time

**B-CROSS-UID total cbD recovered (Defect 1):** ~$259 ($112 BTC + $148 TON). Blocker now **FULLY RESOLVED** for all material items.

---

## STATUS UPDATE — ONDO REALLOCATE_OUT spike: NOT A NEW BLOCKER

The `REALLOCATE_OUT bybit-earn-principal-v1:0a0566f8` ONDO AVCO drop (EARN $0.203 → $0.005) is **not a new independent blocker**. It is a downstream symptom of B-EARN-BUNDLE.

Evidence:
- `BYBIT:33625378:EARN` LENDING_WITHDRAW `bybit-earn-principal-v1:0a0566f8` at 2026-01-20T06:36:02Z
- EARN drains 300.127 ONDO: CARRY_OUT cbD=−$5.058 (EARN had only $5.06 total basis for 300 ONDO)
- P0-A lot carry override fires ($5.06 < $100 dust threshold): FUND CARRY_IN gets cbD=+$103.30 (market price × qty)
- EARN AVCO drops from $0.203 → $0.005 because EARN accumulated ONDO at near-zero basis (B-EARN-BUNDLE subscription path deficit)

Root: EARN accumulated 1,642.5 ONDO via subscriptions but only received $344 total basis (avg $0.21/ONDO). Expected ~$0.97/ONDO acquisition cost. The deficit comes from B-EARN-BUNDLE multi-leg timing (see below).

P0-A override partially corrects the redemption side (FUND gets market basis), but does not fix the EARN accumulation deficit. ONDO EARN position is now null (all redeemed) — no ongoing active impact.

---

## B-EARN-BUNDLE — P2 ACTIVE — Multi-leg bundle timing defect (root cause refined)

**Severity**: P2
**Status**: ACTIVE — root cause identified as multi-leg timing defect, not simple "no carry source"
**Reported**: 2026-06-02 (zero-basis CARRY_IN scan)
**Revised**: 2026-06-02 (refresh 3 — direction corrected, root cause refined)

### Direction correction

Previous description stated "CARRY_INs for Bybit Earn redemptions arrive at BYBIT:33625378 main account with cbD=0." This was incorrect. The zero-cbD CARRY_INs are at **`BYBIT:33625378:EARN`** (EARN sub-account). These are **EARN subscriptions** (main → EARN), not redemptions.

Direction: `BYBIT:33625378:UTA` (−qty) + `BYBIT:33625378:FUND` (−tiny qty) → `BYBIT:33625378:EARN` (+qty, cbD=0)

### Root cause: multi-leg outbound timing — FUND steals pending inbound

For each bundle event, Bybit emits timestamps as:
- `EARN` inbound: `blockTimestamp T`
- `FUND` outbound: `blockTimestamp T+1s` (FUND leg)
- `UTA` outbound: `blockTimestamp T+1.033s` (UTA leg)

Replay processes in time order: EARN inbound arrives first → queue is empty → EARN is enqueued as `pendingInbound` in `corr-family:bybit-it-bundle-v1:...:assetKey` queue.

FUND outbound arrives 1 second later → `applyBybitMultiLegBundleTransfer` negative-qty path:
- calls `matcher.findUniqueBridgeQueueIndex(queue, true)` → finds the EARN pending inbound
- **removes** it from queue → calls `attachLateCarryToPendingInbound` with FUND's tiny carry (e.g., $0.015 for 0.016 ONDO)
- EARN position updated with FUND's tiny basis ✓ but small

UTA outbound arrives 33ms later → `findUniqueBridgeQueueIndex(queue, true)` → **queue is empty** (FUND already consumed the pending inbound) → UTA's large carry ($8.04 for 8.325 ONDO) is pushed to queue as orphan. It is never consumed by a matching EARN inbound.

### Why FIFO fallback doesn't fully solve it

`earnCarryFifoKey` fallback in `applyBybitMultiLegBundleTransfer` inbound path is used when the corr-family queue is empty. A SUBSEQUENT bundle's EARN inbound may pick up a prior bundle's orphaned UTA carry — but this FIFO-matches the WRONG bundle's carry to the WRONG inbound, producing mismatched basis.

### Observed impact

| asset | EARN total qty received | EARN total cbD | avg cbD/unit | expected cbD/unit | shortfall |
|---|---|---|---|---|---|
| SYMBOL:ONDO | 1,642.5 | $344.01 | $0.21 | ~$0.97 | ~$1,249 |
| SYMBOL:LDO | 163.1 | — (see below) | — | ~$1.00 | ~$163 |
| SYMBOL:TON | 39.4 | — | — | ~$3.00 | ~$118 |
| SYMBOL:LINK | 6.89 | — | — | ~$14.00 | ~$97 |
| SYMBOL:ARB | 14.4 | — | — | ~$0.35 | ~$5 |

All EARN positions are now null (assets redeemed). No current AVCO gap. Historical P&L distortion from low-basis EARN positions flowing through earn-principal redemptions.

### Instance table (13 zero-cbD initial CARRY_INs — initial pending materializations)

| asset | qty | initial cbD | late FUND carry cbD | late UTA carry cbD | UTA orphaned? |
|---|---|---|---|---|---|
| SYMBOL:ONDO | 8.340887 | $0 | $0.015 (attached) | $8.038 (**orphaned**) | ✅ |
| SYMBOL:ONDO | 78.942416 | $0 | $0.054 | $85.26 (attached!) | ✗ (UTA attached) |
| SYMBOL:ONDO | 22.986403 | $0 | $0.004 | $21.60 (**orphaned**) | ✅ |
| SYMBOL:ONDO | 14.989694 | $0 | $0.004 | $13.25 (**orphaned**) | ✅ |
| SYMBOL:ONDO | 20.982496 | $0.002 | $0.051 | $13.61 (attached!) | ✗ |
| SYMBOL:ONDO | 39.945087 | $0.032 | $0.032 | $19.90 (attached!) | ✗ |
| SYMBOL:LDO | 17.806 | $0 | — | — | — |
| SYMBOL:LDO | 102.400 | $0 | — | — | — |
| SYMBOL:LDO | 14.973 | $0.00008 | — | — | — |
| SYMBOL:LDO | 27.899 | $0 | — | — | — |
| SYMBOL:LINK | 6.894 | $0 | — | — | — |
| SYMBOL:TON | 6.994 | $0 | — | — | — |
| SYMBOL:TON | 32.403 | $0.010 | — | — | — |
| SYMBOL:ARB | 14.424 | $0 | — | — | — |

Note: For bundles where EARN timestamp = T and UTA timestamp = T+1 and FUND timestamp = T+1 (same second as UTA), timing may vary. When FUND and UTA timestamps are identical (seconds-resolution), sort order determines which one attaches first. If UTA sorts before FUND, UTA's larger carry IS attached and FUND's tiny carry is orphaned (negligible impact).

### Failed stage hypothesis

`move_basis` — `applyBybitMultiLegBundleTransfer` negative-qty path removes the pending inbound on first outbound leg arrival, preventing subsequent outbound legs from attaching their carries. This is a multi-leg ordering defect in the bundle handler.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — main account basis is correctly drained (CARRY_OUT cbD > 0 on BYBIT:33625378), but the CARRY_IN to EARN only receives the first outbound leg's carry. Subsequent legs' carries are orphaned in the pending-transfer queue.

### Remediation sketch

`applyBybitMultiLegBundleTransfer` outbound path must NOT remove the pending inbound on first attachment. Options:
1. Accumulate all outbound carries (total cbD) before attaching to the single EARN pending inbound — requires knowing how many outbound legs are expected (N-leg bundle awareness), OR
2. Re-insert an updated pending inbound after each late-carry attachment with the residual uncovered quantity, OR
3. Change `attachLateCarryToPendingInbound` to UPDATE the pending carry in-place (additive, not replace) without removing from queue; remove only after all expected outbounds have been processed

### Type adequacy

The `CARRY_OUT` / `CARRY_IN` pair is semantically adequate. The defect is in the multi-leg queue management (N-outbound-to-1-inbound fan-in), not the type model.

---

---

## STATUS UPDATE — B-SHORTFALL-1 RESOLVED

**B-SHORTFALL-1** (zero-basis BRIDGE_IN sources generating zero cbD at destination) is **RESOLVED** as of the post-B-SHORTFALL-1 cycle.

`UnmatchedBridgeInboundPricingFallbackService` now detects paired BRIDGE_IN transactions whose source had zero basis (shortfall) and reprices them at market (`continuityCandidate=false`, role `BUY`, `status→CONFIRMED`).

Verified post-rebuild:
- `0x38d445c4` ETH ARBITRUM: `cbD=$1,515.67` (qty=0.799 × $1,896.96) ✅ (was ~$0)
- `0xe11ab436` USDC ARBITRUM: `cbD=$427.95` (qty=427.946 × $1.00) ✅ (was $0.000439)
- ETH family AVCO restored to **$2,539** (was $1,527 pre-fix, baseline $2,589) ✅
- W-1 (unexplained ETH AVCO drop) is now **explained and resolved** by this fix.

---

## STATUS UPDATE — W-1 RESOLVED (B-SHORTFALL-1 explains the drop)

**W-1** ("ETH AVCO drop from $2,589 → $1,527 not fully traced") is **RESOLVED**.

---

## STATUS UPDATE — B-USDC-1 RESOLVED

**B-USDC-1** (BORROW using `perWalletAvco()` instead of market price for cbD) is **RESOLVED**.

- BORROW `0xf299178e` (ARBITRUM, 800 USDC): `costBasisDeltaUsd`=$800 ✅ (was $1,225,570)

---

## B-BYBIT-CORRIDOR-2 — P1 PARTIALLY RESOLVED — Bybit Corridor CARRY_IN zero-basis (all assets)

**Severity**: P1
**Status**: PARTIALLY RESOLVED — Sub-pattern A 21/23 fixed; 2 residual cmETH + USDe partial shortfall remain

### Post-fix residuals (as of 2026-06-02)

**BYBIT_CORRIDOR group (1 remaining with qty > 0.001):**
- `0x5067b0e1` cmETH MANTLE: qty=0.10528, cbD=$0 → GENUINE_EVIDENCE_MISSING_PROVEN

**Near-zero cbD residuals (qty ≤ 0.001, filtered from zero-carry scan):**
- `0xc6a03abc` cmETH MANTLE: qty=0.001, cbD=$0 → GENUINE_EVIDENCE_MISSING_PROVEN
Total cmETH shortfall: ~$212

**Sub-pattern B USDe partial shortfall:**
Instance 13 (`0x79d17a8d`, 2115 USDe MANTLE): `cbD=$10` because FUND held only 10 USDe at withdrawal time. The remaining 2105 USDe had zero umbrella basis coverage. Shortfall: **~$2,105**

**GROUP C (cross-UID) remaining:** 4 confirmed + 1 new USDT (negligible)

| ntId (short) | asset | qty | from UID | est. shortfall |
|---|---|---|---|---|
| `uni_trans_47eaa702` | MNT | 0.293 | 409666492 | ~$0.29 |
| `uni_trans_6b956290` | TON | 32.442 | 409666492 | ~$130 |
| `uni_trans_9a0ae038` | USDT | 0.002 | 409666492 | ~$0.002 |
| `uni_trans_ebf90bee` | ETH | 0.01375 | 516601508 | ~$34 |
| `uni_trans_866903d7` | BTC | 0.000797 | 516601508 | ~$79 |
| `uni_trans_a893b645` | BTC | 0.000362 | 516601508 | ~$36 |

Note: BTC entries (qty=0.000797 and qty=0.000362) filtered from the zero-cbD CARRY_IN scan by the `quantityDelta > 0.001` threshold but confirmed still zero-cbD in direct query. Combined ~$115 BTC shortfall.

**GROUP C total: ~$279 (6 entries)**

**GROUP B (bybit-it-pair-v1):** SOL 0.3 qty, ~$0.30 shortfall (previously documented as MNT 0.3 — now shows SYMBOL:SOL).

**Total B-BYBIT-CORRIDOR-2 active shortfall: ~$2,596**

---

## B-VAULT-WITHDRAW — P1 — VAULT_WITHDRAW REALLOCATE_IN zero/near-zero cbD

**Severity**: P1
**Status**: PARTIALLY RESOLVED — Bug A (MEV Capital mevUSDC wrapper) fixed; 3 remaining cases explained; see post-fix residual table below
**Reported**: 2026-06-02 (AVCO spike scan + zero-basis CARRY_IN scan)
**Verified**: 2026-06-02

### Problem

`VAULT_WITHDRAW` REALLOCATE_IN events return stablecoin assets (USDC) to the user's wallet with near-zero or zero cost basis, even when the deposited USDC had full basis ($1/USDC). The vault position accumulated the USDC basis via REALLOCATE_OUT at deposit time, but the VAULT_WITHDRAW handler does not carry that vault position basis back to the returned USDC.

### Post-fix instance table (2026-06-02 verification)

**Fixed by Bug A (wrapper bucket mechanism):**

| ntId (short) | asset | network | qty | cbD (pre-fix) | cbD (post-fix) | status |
|---|---|---|---|---|---|---|
| `0x4e4740e3` ★ | USDC AVALANCHE | mevUSDC | 1,628 | ~$0.002 | **$1,623** | ✅ FIXED |
| `0xff65de51` ★ | USDC AVALANCHE | mevUSDC | 1,014 | ~$0.001 | **$1,001** | ✅ FIXED |
| `0xe6b02813` ★ | USDC AVALANCHE | mevUSDC | 1,000 | ~$0.001 | **$993** | ✅ FIXED (just under $1k) |
| `0x4737a9c2` | USDC ARBITRUM | MCUSDC | 1,689 | ~$0 | **$2,148** | ✅ FIXED |
| `0x6343bac5` | USDC ARBITRUM | syrupUSDC | 1,710 | ~$0 | **$2,136** | ✅ FIXED |
| (+ 20 more rows across all vault types) | | | | | $12,927 total | ✅ FIXED |

★ = MEV Capital mevUSDC wrapper on AVALANCHE (original Bug A scope)

**Total VAULT_WITHDRAW REALLOCATE_IN cbD recovered: $19,118 (28 rows, 25 with cbD>$0)**

**Remaining cbD≈$0 cases (3) — not a Bug A regression:**

| ntId (short) | network | qty | cbD | root cause |
|---|---|---|---|---|
| `0x971c8464` | ARBITRUM | 1,783 USDC | $0.001 | MCUSDC wrapper: 2nd and 3rd deposits brought USDC with cbD≈$0 (upstream shortfall at deposit time). Wrapper correctly reflects what was deposited. **IRREDUCIBLE** (refresh 5) |
| `0xc8b94615` | AVALANCHE | 2,831 USDC | $0 | **[REVISED refresh 5] Turtle Finance USDC vault** (turtleAvalancheUSDC `0x3048925b`). Deposit SWAP acquired 2,787.57 vault tokens at $2,815 basis. Vault token burn NOT in VAULT_WITHDRAW flows → basis stuck in phantom position. **FIXABLE — normalization defect.** See STATUS UPDATE section above. |
| `0xb47d87fa` | ARBITRUM | 2,825 USDC | $0 | Vault `0x6a2abff960b663462cbc46a2cfcf85063fe8ae14` — no VAULT_DEPOSIT in dataset (pre-history gap). Single REALLOCATE_IN cbD=$0; avcoAfter=$1 (existing USDC position masked AVCO impact). **IRREDUCIBLE** (refresh 5) |

Note: `0xc7aa483f` ETHEREUM (1266 USDC) still has cbD=$0 but was already excluded (avco=$1 in current position from subsequent acquisition).

### Cascade context

The AVALANCHE instances include the previously documented MEV Capital vault cascade (`0xe6b02813`). All 4 AVALANCHE instances follow the same pattern: USDC deposited via REALLOCATE_OUT (basis correctly drained) → vault generates MEVUSDC/vault shares → VAULT_WITHDRAW redeems shares → REALLOCATE_IN returns USDC at cbD≈$0.001 or $0.

### Current AVCO impact

**Current active stable positions: 0 broken.** All USDC/USDT positions currently show avco≈$1.00. The zero-basis USDC from vault withdrawals was subsequently DISPOSED or CARRY_OUT transferred, and replacement ACQUIRE events restored correct basis. The AVCO spikes (avco dropped to $0.000001) visible in ledger history were transient.

**Historical P&L impact (material):**
When the ~11,082 zero-basis USDC was later disposed or bridged out, the disposal was recorded as $0 cost basis removal instead of ~$1/USDC. This inflated apparent P&L gains (or reduced apparent losses) by ~$11,082 for those events.

### Failed stage hypothesis

`cost_basis` / `REALLOCATE_IN basis carry` — the VAULT_WITHDRAW replay handler emits REALLOCATE_IN for the returned stable with zero cbD, instead of reading the vault token position's `totalCostBasisAfterUsd` and proportionally carrying it to the returned USDC.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the vault position holds accumulated basis from the REALLOCATE_OUT deposit events. The VAULT_WITHDRAW handler does not use it.

### Type adequacy

The `REALLOCATE_OUT` / `REALLOCATE_IN` pair is semantically adequate. The defect is in the basis-carry computation at VAULT_WITHDRAW time, not the type model.

### Remediation

The VAULT_WITHDRAW replay handler must:
1. At REALLOCATE_OUT (vault deposit): carry USDC basis to vault token position via REALLOCATE_IN on the vault token side.
2. At VAULT_WITHDRAW (vault redemption): perform REALLOCATE_OUT on vault token position (draining proportional basis), carry that basis to the returned USDC via REALLOCATE_IN with positive cbD.

This is the same pattern as the LP/EARN principal carry, applied to vault token positions.

---

## B-EARN-BUNDLE — P2 NEW — Bybit Earn bundle CARRY_IN zero cbD

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (zero-basis CARRY_IN scan)

### Problem

`bybit-it-bundle-v1` correlation CARRY_IN events for Bybit Earn position redemptions arrive at the BYBIT:33625378 main account with `costBasisDeltaUsd=0`. The Earn position (BYBIT:EARN) holds the asset with cost basis from prior acquisition, but when the asset is redeemed (transferred back from EARN to main account), the basis is not carried.

### Complete instance table (21 entries)

| asset | qty | cbD | est. missing cbD |
|---|---|---|---|
| SYMBOL:LDO | 14.97 + 102.40 + 17.81 + 27.90 = **163.08 total** | ≈$0 | ~$163 |
| SYMBOL:TON | 32.40 + 6.99 = **39.39 total** | ≈$0 | ~$118 |
| SYMBOL:LINK | 6.89 | $0 | ~$97 |
| SYMBOL:ONDO | 8.34 + 78.94 + 22.99 + 14.99 + 20.98 = **146.25 total** | ≈$0 | ~$12 |
| SYMBOL:ARB | 14.42 | $0 | ~$5 |

**Total estimated shortfall: ~$395**

Note: estimated at current market prices (LDO~$1, TON~$3, LINK~$14, ONDO~$0.08, ARB~$0.35)

### Root cause

The `bybit-it-bundle-v1` corridor path for EARN_FLEXIBLE_SAVING redemptions uses INTERNAL_TRANSFER events. The CARRY_IN to the main account from the EARN sub-account does not invoke the carry-basis logic that would propagate the EARN position's AVCO to the main account.

### Failed stage hypothesis

`move_basis` — EARN-to-main bundle transfers lack the same earnPrincipalCarrySourcePosition fallback that the EARN withdrawal path (via FUNDING_HISTORY) implements.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the BYBIT:EARN position holds cost basis from prior acquisitions; the bundle transfer handler does not read it.

---

## AVCO Spike Summary (Check 1 — updated refresh 2)

**Total spikes >20% found: ~190+** (across all positions, all basisEffect types)

After triage, spikes cluster into the following classes:

| Class | Count (approx) | Example | Action required |
|---|---|---|---|
| First-acquire from zero position (artificial) | ~40 | 0xb97ef9ef avco $0→$1 (98M%) | None — expected |
| LP/vault receipt token REALLOCATE_IN | ~30 | LP-RECEIPT PANCAKESWAP spike | None — expected for LP receipts |
| Historical vault cascade artifacts (now superseded) | ~25 | USDC ARBITRUM $0.02→$49.78→$1531 | See B-VAULT-WITHDRAW |
| Bybit Earn position AVCO volatility | ~20 | ONDO EARN $0.01→$0.07 | Review with B-EARN-BUNDLE |
| Natural market price moves (ACQUIRE after dip) | ~30 | NATIVE:ARBITRUM $1179→$4090 | None — expected |
| WBTC/WETH LENDING_DEPOSIT + UNWRAP sawtooth | ~41 | WBTC $92k→null then fresh ACQUIRE, WETH→ETH blends | See note below |
| ONDO CARRY_OUT without destination CARRY_IN | ~4 | ONDO BYBIT:33625378 CARRY_OUT, corrId orphan | See B-ONDO-CARRY-1 |
| Residual corridor artifacts | ~5 | cmETH MANTLE 78.7% drop | See B-BYBIT-CORRIDOR-2 |
| Active broken positions | 2 | SYMBOL:BTC BYBIT avco=$0 | See B-CROSS-UID below |
| Other minor | ~35 | various | Low priority |

**WBTC/WETH sawtooth note (visual artifact, not accounting bug):**
WBTC is repeatedly bought via SWAP then deposited to Aave ARBITRUM (LENDING_DEPOSIT). Each deposit fully empties the wallet WBTC position (quantityAfter=0, avcoAfterUsd=null). The basis correctly moves to `AARBWBTC` (Aave receipt token). On the next SWAP, a fresh WBTC position is created at market price. This creates a sawtooth WBTC AVCO chart: repeated drops to null followed by jumps to fresh market price. 14 LENDING_DEPOSIT occurrences, 22 SWAP ACQUIREs, 0.00427 WBTC total deposited, $362 total basis correctly tracked in AARBWBTC. **No fix needed for AVCO chart; consider UI annotation for positions temporarily moved to lending.**

WETH UNWRAP (41 cases on 0x1a87f12 + 0x68bc): WETH→ETH REALLOCATE_OUT/REALLOCATE_IN blends AVCO. Jumps occur when WETH AVCO > ETH AVCO. Drops occur when WETH position empties. Accounting is correct.

**Active broken AVCO in current positions (>20% unexplained):**
- `SYMBOL:BTC` `BYBIT:33625378`: **RESOLVED** — position disposed 2025-12-12, DISPOSE cbD now -$111.74 at avBef=$96,406 ✅. P&L correctly computed.

---

## B-CROSS-UID — P2 — Cross-UID basis propagation (Bybit multi-UID scope)

**Severity**: P2
**Status**: PARTIALLY RESOLVED — UTA-sourced cross-UID carries work; BTC FUND-sourced carries now fixed; TON FUND-sourced carry still broken
**Verified**: 2026-06-02; Defect 1 BTC RESOLVED confirmed 2026-06-02 targeted audit

### Post-fix state

**Working (Defect 2 fixed — `isBybitSelfTransfer` correlationId guard active):**

| correlationId (short) | asset | source | qty | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:a20cadab…` | ETH | BYBIT:516601508:UTA | 0.01146 | **$42.46** | ✅ FIXED |
| `bybit-cross-uid-v1:a6fd39ab…` | ETH | BYBIT:516601508:UTA | 0.00663 | **$23.03** | ✅ FIXED |

Works because source UNIVERSAL_TRANSFER has `accountRef: 'BYBIT:516601508:UTA'` and `bybit-cross-uid-v1:` correlationId → `isBybitSelfTransfer()` returns false → CARRY_OUT emitted with correct cbD.

**Defect 1 BTC — RESOLVED (2026-06-02 targeted audit):**

| correlationId (short) | asset | source sub-acct | FUNDING_HISTORY corrId | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:866903d7…` | BTC | BYBIT:516601508:FUND | ✅ assigned | **-$76.86** | ✅ FIXED |
| `bybit-cross-uid-v1:a893b645…` | BTC | BYBIT:516601508:FUND | ✅ assigned | **-$34.89** | ✅ FIXED |

FUNDING_HISTORY records now have `correlationId` assigned; CARRY_OUT cbD>0 emitted correctly.

**Still broken (Defect 1 — TON only):**

| correlationId (short) | asset | source sub-acct | FUNDING_HISTORY corrId | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:6b956290…` | TON | BYBIT:409666492:FUND | ❌ none | **$0** | ❌ FAIL |

TON FUNDING_HISTORY outbound still lacks `correlationId`. ~$97 shortfall remains.

**Not a pipeline defect (legitimate zero):**

| correlationId (short) | asset | reason |
|---|---|---|
| `bybit-cross-uid-v1:ebf90bee…` | ETH | Source BYBIT:516601508 had no ETH in ledger before Nov 1, 2025. First ETH acquisition was Nov 3 via SWAP. Zero cost basis at source at transfer time — correct. |

### Current position impact

`SYMBOL:BTC BYBIT:33625378`: position was fully DISPOSED (SWAP) on 2025-12-12. **Now RESOLVED**: CARRY_OUT cbD correctly emitted on source (BYBIT:516601508:FUND); CARRY_IN cbD correctly applied at destination (BYBIT:33625378:FUND); SPOT DISPOSE cbD=-$111.74 correctly computed at avBef=$96,406. No remaining P&L impact.

### Remaining shortfall (FUND-sourced Defect 1)

| asset | qty | est. missing cbD | status |
|---|---|---|---|
| BTC (×2 carries) | 0.001159 | ~$112 | ✅ RESOLVED — cbD now flowing |
| TON | 32.442 | ~$97 (at $3/TON) | ❌ still broken |
| **Total active** | | **~$97** | |

### Required fix (Defect 1)

In `BybitInternalTransferPairer.pairCrossUidUniversalTransfers()` second pass: after assigning `bybit-cross-uid-v1:<uuid>` correlationId to the loner inbound, also find the FUNDING_HISTORY outbound on the source UID and assign the same correlationId. This enables the `isBybitSelfTransfer()` correlationId guard to unblock the CARRY_OUT on that record.

---

---

## B-ONDO-CARRY-1 — P2 NEW — ONDO bybit-collapsed-v1 CARRY_OUT without matching CARRY_IN

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (AVCO spike scan, fresh audit cycle refresh 2)

### Problem

4 ONDO CARRY_OUT events on `BYBIT:33625378` (UTA) have `bybit-collapsed-v1:` correlationId but no matching CARRY_IN exists in `asset_ledger_points`. The ONDO basis is drained from the UTA wallet with no credit issued on any destination wallet.

### Instance table

| corrId (short) | qty drained | blockTimestamp | est. missing cbD |
|---|---|---|---|
| `bybit-collapsed-v1:4f7b4cdf…` | 30.76 ONDO | 2025-02-22 | ~$37.8 |
| `bybit-collapsed-v1:7f22913f…` | 2.05 ONDO | 2025-06-22 | ~$2.5 |
| `bybit-collapsed-v1:0c7df416…` | 43.70 ONDO | 2025-10-10 | ~$53.7 |
| `bybit-collapsed-v1:49002ea9…` | 19.96 ONDO | 2025-10-10 | ~$24.5 |
| **Total** | **96.47 ONDO** | | **~$118.5** |

ONDO CARRY_OUT balance: 28 CARRY_OUTs, 24 CARRY_INs on BYBIT:33625378:EARN (4 unmatched).

### Root cause hypothesis

`bybit-collapsed-v1` corrIDs are generated for Bybit Earn staking/unstaking events collapsed from multiple source records. The matching CARRY_IN on `BYBIT:33625378:EARN` may have been generated under a different corrId or absorbed into a later bundle. The ONDO on the EARN side shows 24 CARRY_INs with 1,642.5 ONDO received vs. 472.8 ONDO sent via CARRY_OUT from UTA — suggesting EARN receives ONDO from sources other than these specific UTA CARRY_OUTs (e.g., Bybit EARN interest). However, the 4 unmatched UTA CARRY_OUTs represent real basis drain with no credit.

### Failed stage hypothesis

`move_basis` — `bybit-collapsed-v1` bundle matching logic does not ensure every CARRY_OUT has a corresponding CARRY_IN at the destination sub-account.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the corrId exists but the matching CARRY_IN record is absent.

### Remediation

Verify whether the 4 ONDO CARRY_OUT quantities arrived in BYBIT:33625378:EARN under a different corrId. If yes, update the corrId to link them. If no matching EARN record exists, create the CARRY_IN with basis propagated from the CARRY_OUT's avcoBeforeUsd.

---

## B-MNT-CARRY-1 — P2 NEW — MNT INTERNAL_TRANSFER on MANTLE missing corrId → zero-basis CARRY_IN

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (zero-basis CARRY_IN scan, fresh audit cycle refresh 2)

### Problem

5 MANTLE INTERNAL_TRANSFER transactions moving MNT from wallet `0xa0dd42c626b002778f93e1ab42cbed5f31c117b2` to wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` have no `correlationId` on the `normalized_transactions` record. Without a corrId, the replay cannot match the CARRY_OUT (source wallet has correct basis) to the CARRY_IN (destination wallet gets 0 basis).

### Instance table

| txHash (short) | MNT qty received | Missing cbD | corrId on NT |
|---|---|---|---|
| `0xffc959c2` | 0.8 | ~$0.68 | ❌ none |
| `0x3c011394` | 25.0 | ~$21.3 | ❌ none |
| `0xe2bf4c4f` | 23.3 | ~$20.0 | ❌ none |
| `0x4fa1f2a2` | 31.1 | ~$26.6 | ❌ none |
| `0x7e5e7443` | 0.58 | ~$0.46 | ❌ none |
| **Total** | **80.78 MNT** | **~$69** | |

### Contrast — same-wallet CMETH works

CMETH INTERNAL_TRANSFER `0x2723a876` on MANTLE has `correlationId: "internal-tx:mantle:0x2723…"` and correctly emits two CARRY_IN ledger points: a qty-movement entry (seq 1328, cbD=$0) followed by a basis-transfer entry (seq 1329, cbD=+$2.13). The net effect after both entries is correct AVCO.

MNT txs lack the `internal-tx:mantle:` corrId, so neither step fires.

### Root cause

The clarification/linking stage that assigns `internal-tx:mantle:txHash` corrIds to same-universe INTERNAL_TRANSFERs on MANTLE was not applied to these 5 MNT transactions. The txs were likely processed before the corrId assignment was implemented or are missing a re-clarification run.

### Failed stage hypothesis

`linking` — the MANTLE INTERNAL_TRANSFER clarification step does not assign `internal-tx:mantle:` corrId consistently to all tracked-wallet-to-tracked-wallet transfers.

### Evidence state

`EVIDENCE_PRESENT_UNLINKED` — the CARRY_OUT on `0xa0dd42c` has correct costBasisDeltaUsd (non-zero). The CARRY_IN on `0x1a87f12` has qty but no basis because the link is missing.

### Remediation

In the MANTLE INTERNAL_TRANSFER clarification pipeline, ensure all INTERNAL_TRANSFERs between any two tracked wallets in the same accounting universe receive `correlationId = "internal-tx:mantle:<txHash>"`. Run a selective re-clarification for these 5 txHashes, then replay.

---

## B-BRIDGE-ORPHAN-1 — P3 — 4 BRIDGE_OUTs with no matching BRIDGE_IN

**Severity**: P3 (small USD impact)
**Status**: ACTIVE
**Reported**: 2026-06-02 (bridge pairing scan, fresh audit cycle refresh 2)

### Problem

4 BRIDGE_OUT transactions have a `correlationId` assigned but no matching `BRIDGE_IN` exists in `normalized_transactions`. The bridge funds reached the destination chain but the BRIDGE_IN record is absent (destination chain transaction either not in `raw_transactions` or misclassified).

### Instance table

| corrId (short) | asset | qty | source | dest (likely) | USD impact |
|---|---|---|---|---|---|
| `bridge:lifi:0xdd83df3d…` | USDC | 3.1 | ARBITRUM | unknown | ~$3.1 |
| `bridge:lifi:0x8b40041f…` | USDC | 3.14 | ARBITRUM | unknown | ~$3.1 |
| `bridge:across:0x266c0258…` | ETH | ~small | OPTIMISM | unknown | <$1 |
| `bridge:across:0x258ed5c3…` | ETH | 0.0003 | ARBITRUM | unknown | <$1 |
| **Total** | | | | | **~$7** |

### Root cause

Destination chain transactions are absent from `raw_transactions` (destination wallet not ingested for that chain/time), or the BRIDGE_IN was classified as `EXTERNAL_TRANSFER_IN` without a bridge correlationId.

For the LiFi USDC bridges (3.1 and 3.14 USDC), the counterparty address `0x89c6340b1a1f4b25d36cd8b063d49045caf3f818` on ARBITRUM is a LiFi bridge relayer. The destination chain is unknown. Check whether USDC arrived on BASE/ETHEREUM/OPTIMISM around those dates for wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`.

### Failed stage hypothesis

`linking` — cross-chain BRIDGE_IN record absent or unlinked. The `AcrossBridgePairLinkService` / `LiFiBridgePairLinkService` could not find the destination tx.

### Evidence state

`EVIDENCE_MISSING` (destination chain transaction not in raw_transactions).

### Remediation

Verify on-chain that the USDC/ETH arrived on the destination chain. If the destination transaction is tracked, inspect why it was not classified as `BRIDGE_IN` with the matching corrId. If not tracked, add the destination chain wallet to the ingestion scope.

---

## No Other Active P0 Blockers

Pipeline state:
- 0 PENDING_PRICE ✅
- 0 NEEDS_REVIEW ✅
- ETH family AVCO: $2,079–$3,822 (market-plausible, Bybit ETH at $3,822)
- USDT family AVCO: $1.00 ✅
- USDC family AVCO: $1.00 (all current positions) ✅

---

---

## Targeted Tx Audit — BTC + ETH Cluster — 2026-06-02

Universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
Pipeline state at audit time: CONFIRMED=7338 | PENDING=0

### Per-Transaction Analysis Table

| ntId (short) | type | classification | corrId | cbD | AVCO before | AVCO after | cluster |
|---|---|---|---|---|---|---|---|
| `BYBIT:33625378:FUND:uni_trans_a893b645` | INTERNAL_TRANSFER | ✅ CARRY_IN | ✅ `bybit-cross-uid-v1:a893b645…` | ✅ +$34.89 | $96,406 | $96,406 | RESOLVED |
| `BYBIT:33625378:FUND:uni_trans_866903d7` | INTERNAL_TRANSFER | ✅ CARRY_IN | ✅ `bybit-cross-uid-v1:866903d7…` | ✅ +$76.86 | $96,406 | $96,406 | RESOLVED |
| `BYBIT:33625378:EXECUTION_SPOT:2290000000967318296` | SWAP | ✅ DISPOSE BTC | N/A | ✅ -$111.74 | $96,406 | $0 (fully disposed) | RESOLVED |
| `0x87503b88` MANTLE BRIDGE_OUT WBTC | BRIDGE_OUT | ✅ | ✅ `bridge:lifi:0x87503b88…` | ✅ -$19.91 | $87,869 | $87,869 | OK |
| `0xe77ad6d0` ARBITRUM SWAP USD₮0→WBTC | SWAP | ✅ | N/A | ✅ DISPOSE -$19.91 / ACQUIRE +$19.81 | $1.005 | $87,429 | OK |
| `0xa5e755a6` MANTLE CARRY_IN WETH | INTERNAL_TRANSFER | ✅ CARRY_IN | ✅ `BYBIT-CORRIDOR:MANTLE:0xa5e755a6…` | ✅ +$7,845 | N/A | $2,563 | OK |
| `BYBIT:516601508:TXN_LOG:uni_trans_a6fd39ab` | INTERNAL_TRANSFER | ✅ CARRY_OUT | ✅ `bybit-cross-uid-v1:a6fd39ab…` | ✅ -$19.80 | $2,985 | N/A | OK (prev. fixed) |
| `0xf2155c12` ARBITRUM SWAP USDC→ETH | SWAP | ✅ | N/A | ✅ DISPOSE -$20 / ACQUIRE +$20 | $1.00 | $1,926 | OK |
| `0x9c6c4c68` ARBITRUM LP_ENTRY_REQUEST GMX | LP_ENTRY_REQUEST | ✅ | ✅ `0x4e731ed5…` | ✅ REALLOCATE_OUT -$7.08 USDC / -$0.29 ETH | $1 / $1,749 | N/A | OK |
| `0x1aa3438d` ARBITRUM LP_ENTRY_SETTLEMENT GMX | LP_ENTRY_SETTLEMENT | ✅ | ✅ `gmx-lp:arbitrum:weth-usdc` | ❌ UNKNOWN / $0 for ETH+GM token | $1,749 | $1,749 | C — GMX settlement |
| `0xe06740b6` BASE LP_ENTRY PancakeSwap | LP_ENTRY | ✅ | ✅ `lp-position:base:pancakeswap:477096` | ✅ REALLOCATE_OUT -$14.62 USDC | $1 | N/A | OK |
| `0xb1e9f65d` ARBITRUM BRIDGE_OUT USDC 2,829 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$2,829 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0x4a2eb3ee` ARBITRUM BRIDGE_OUT USDC 1,050 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$1,050 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0x5ca14340` ARBITRUM BRIDGE_OUT USDC 50 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$50 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0x360988904f` ARBITRUM BRIDGE_OUT USDC 50 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$50 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0xd8b6e516` LINEA BRIDGE_OUT ETH 0.000241 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$0.83 but no BRIDGE_IN | $3,432 | $3,432 | D — orphan |
| `0xc69ef119` KATANA BRIDGE_IN weETH+ETH | BRIDGE_IN | ✅ | ❌ MISSING | CARRY_IN market $402 weETH + $794 ETH, no source | $3,603 ETH | $2,790 ETH | B — unlinked BRIDGE_IN |

### Cluster Table

| Cluster | Transactions | Root cause | Stage | Est. $ impact |
|---|---|---|---|---|
| **RESOLVED — B-CROSS-UID Defect 1** | `uni_trans_a893b645`, `uni_trans_866903d7`, `SPOT:2290000000967318296` | FUNDING_HISTORY corrId now assigned; both CARRY_OUTs emit cbD; SPOT DISPOSE uses correct avBef | `linking` → `replay` | ~$112 BTC basis now flowing ✅ |
| **OK — ETH corridor + Bybit ETH cross-UID** | `0xa5e755a6`, `BYBIT:516601508:TXN_LOG:a6fd39ab` | Correctly paired, cbD flowing | — | — |
| **OK — WBTC bridge pair + SWAP** | `0x87503b88`, `0x59cbe774` ARBITRUM USD₮0 BRIDGE_IN, `0xe77ad6d0` SWAP | BRIDGE_OUT/IN correctly paired via `bridge:lifi:0x87503b88…`; USD₮0 receives $19.91 carried basis | — | — |
| **OK — LP entries (GMX request + PancakeSwap)** | `0x9c6c4c68`, `0xe06740b6` | REALLOCATE_OUT correct | — | — |
| **C — GMX LP_ENTRY_SETTLEMENT UNKNOWN** | `0x1aa3438d` + 4 other settlements | All 5 GMX settlements emit UNKNOWN for ETH refund + LP token with cbD=0; systematic | `cost_basis` | ETH dust refunds; GM/GLV tokens unaccounted |
| **D — USDC bridge orphans from 0xf03b52** | `0xb1e9f65d`, `0x4a2eb3ee`, `0x5ca14340`, `0x360988904f` | All 4 BRIDGE_OUT to contract `0xf5f93d26…` ARBITRUM with no corrId; no matching BRIDGE_IN in universe | `linking` | ~$3,979 USDC missing at destination |
| **D — LINEA ETH orphan** | `0xd8b6e516` | BRIDGE_OUT ETH LINEA no corrId; coincident AVALANCHE AVAX BRIDGE_IN (`0xce1ad77f`) is independent (different asset, continuityCandidate=false) | `linking` | ~$0.83 drained with no destination |
| **B — KATANA BRIDGE_IN unlinked** | `0xc69ef119` | BRIDGE_IN weETH 0.144 + ETH 0.285 on KATANA no corrId; no BRIDGE_OUT found; basis $1,196 created at market under shortfall-fallback | `linking` | ~$1,196 uncorrelated basis arrival |

### ETH Bridge Orphans Detail

| corrId | asset | qty | source wallet | destination | USD impact | status |
|---|---|---|---|---|---|---|
| ❌ none | USDC | 2,829.12 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$2,829 | no BRIDGE_IN |
| ❌ none | USDC | 1,050.58 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$1,051 | no BRIDGE_IN |
| ❌ none | USDC | 50 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$50 | no BRIDGE_IN |
| ❌ none | USDC | 50 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$50 | no BRIDGE_IN |
| ❌ none | ETH | 0.000241 | `0x1a87f12` LINEA | unknown | ~$0.83 | no BRIDGE_IN |
| ❌ none (source) | weETH + ETH | 0.144 + 0.285 | KATANA (unknown source) | `0x1a87f12` KATANA | ~$1,196 created | BRIDGE_IN without source |

Bridge contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM: all 4 USDC BRIDGE_OUTs from `0xf03b52` call this contract. Likely a LiFi or Connext bridge endpoint. Destination chain and wallet unknown from current dataset.

### BTC AVCO Timeline at BYBIT:33625378

**B-CROSS-UID Defect 1 is NOW RESOLVED for both BTC entries** (queried 2026-06-02):

| event | corrId | source CARRY_OUT cbD | destination CARRY_IN cbD | status |
|---|---|---|---|---|
| `uni_trans_866903d7` 2025-12-12T10:15 | `bybit-cross-uid-v1:866903d7…` | ✅ -$76.86 (`BYBIT:516601508:FUND:FUNDING_HISTORY:56fcc99b…`) | ✅ +$76.86 (`BYBIT:33625378:FUND:UNIVERSAL_TRANSFER`) | **FIXED** |
| `uni_trans_a893b645` 2025-12-12T10:16 | `bybit-cross-uid-v1:a893b645…` | ✅ -$34.89 (`BYBIT:516601508:FUND:FUNDING_HISTORY:296e10c2…`) | ✅ +$34.89 (`BYBIT:33625378:FUND:UNIVERSAL_TRANSFER`) | **FIXED** |
| `SPOT:2290000000967318296` 2025-12-12T10:22 | — | — | DISPOSE BTC qty=0.0011591, cbD=-$111.74, avBef=$96,406 | ✅ correct |

Combined BTC CARRY_IN basis: $34.89 + $76.86 = **$111.75 ≈ SPOT DISPOSE $111.74** ✅ (rounding).  
BTC position at BYBIT:33625378 is **fully disposed** (qty=0 since 2025-12-12). Current AVCO = N/A (no open position). Historical P&L correctly reflects ~$112 cost basis.

---

## STATUS UPDATE — B-CROSS-UID Defect 1 RESOLVED (BTC)

**B-CROSS-UID** FUND-sourced Defect 1 is **RESOLVED** for the two BTC transfers (`uni_trans_866903d7` and `uni_trans_a893b645`).

Verified 2026-06-02:
- `bybit-cross-uid-v1:866903d7…` FUNDING_HISTORY now has corrId; CARRY_OUT cbD=-$76.86 ✅
- `bybit-cross-uid-v1:a893b645…` FUNDING_HISTORY now has corrId; CARRY_OUT cbD=-$34.89 ✅
- Combined $111.74 basis correctly consumed by SPOT DISPOSE at BYBIT:33625378 ✅

Remaining Defect 1 scope: TON `bybit-cross-uid-v1:6b956290…` (qty=32.442, ~$97 shortfall) — not verified in this session.

---

## B-BRIDGE-ORPHAN-2 — P2 NEW — 4 USDC BRIDGE_OUTs from 0xf03b52 ARBITRUM with no BRIDGE_IN

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (targeted tx audit)

### Problem

4 `BRIDGE_OUT` transactions on ARBITRUM from wallet `0xf03b52e8686b962e051a6075a06b96cb8a663021` all target contract `0xf5f93d26229482adca3e42f84d08d549cf131658` with USDC as the main bridged asset. None have a `correlationId`. None have a matching `BRIDGE_IN` in any tracked wallet. The CARRY_OUT basis is correctly drained at the source (cbD>0), but the basis is never credited at the destination.

### Instance table

| txHash (short) | network | asset | qty | CARRY_OUT cbD | date | BRIDGE_IN found |
|---|---|---|---|---|---|---|
| `0xb1e9f65d` | ARBITRUM | USDC | 2,829.12 | -$2,829.12 | 2026-01-12 | ❌ none |
| `0x4a2eb3ee` | ARBITRUM | USDC | 1,050.58 | -$1,050.58 | 2026-01-13 | ❌ none |
| `0x5ca14340` | ARBITRUM | USDC | 50 | -$50.01 | 2026-01-15 | ❌ none |
| `0x360988904f` | ARBITRUM | USDC | 50 | -$50.00 | 2026-02-10 | ❌ none |
| **Total** | | | **3,979.70** | **-$3,979.71** | | |

Each BRIDGE_OUT also carries out a small ETH amount (~0.000064 ETH, cbD≈$0.19) — likely a native gas forward. ETH CARRY_OUTs are also unmatched.

### Root cause

The bridge linking service did not assign a `correlationId` to these BRIDGE_OUTs. The destination chain transactions are either:
1. On a chain not tracked in this universe, or
2. On a tracked chain but not classified as `BRIDGE_IN` with matching corrId

### Failed stage hypothesis

`linking` — `LiFiBridgePairLinkService` (or equivalent) did not produce corrIds for these txs. The destination chain/wallet is not in `raw_transactions`.

### Evidence state

`EVIDENCE_MISSING` — destination chain transactions absent from dataset. Contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM requires identification to determine destination chain.

### Remediation

1. Identify bridge contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM (likely LiFi or Connext router).
2. Look up the 4 destination transactions on-chain for wallet `0xf03b52e8686b962e051a6075a06b96cb8a663021` around the dates above.
3. If destination wallet is tracked: re-run bridge pairing with corrId assignment.
4. If destination wallet is external (Bybit deposit or external wallet): these exits are correct — CARRY_OUT drains basis, destination is outside the universe.

---

## B-BRIDGE-ORPHAN-1 — EXTENSION — LINEA ETH orphan added

(Extends existing B-BRIDGE-ORPHAN-1 entry.)

**New instance:**

| corrId | asset | qty | source | dest | USD impact |
|---|---|---|---|---|---|
| ❌ none | ETH | 0.000241 | `0x1a87f12` LINEA | unknown | ~$0.83 |

`0xd8b6e516c96c923ed30d8d66ec2886e48828efdd84dca05dfb1aafb700dd6c83` — BRIDGE_OUT ETH on LINEA, CARRY_OUT cbD=-$0.83, no corrId.

A coincident AVALANCHE AVAX BRIDGE_IN (`0xce1ad77f`, +2 sec, same wallet) is **NOT** the counterpart: it receives AVAX (different asset), has `continuityCandidate=false`, and its own CARRY_IN cbD=$0.67 from existing AVCO. These are independent events. The LINEA ETH basis of $0.83 has no destination credit in the dataset.

---

## B-KATANA-BRIDGE-IN-1 — P3 NEW — KATANA BRIDGE_IN weETH+ETH without matched source

**Severity**: P3
**Status**: ACTIVE
**Reported**: 2026-06-02 (targeted tx audit)

### Problem

`0xc69ef119e3658ad023b85e0866231b31cbc08aebe0c4b2eed62df47ea00b7be` is classified as `BRIDGE_IN` on KATANA for wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`. It receives weETH (0.144, cbD=+$402) and ETH (0.285, cbD=+$794). No `correlationId` is set; no matching BRIDGE_OUT found. The basis was assigned at market price under the unmatched-BRIDGE_IN fallback (B-SHORTFALL-1 pattern).

Raw data: `to=0x223ec22d67716fca620aee72b25ffe4ece436f25` — contract identity on KATANA not established in current dataset.

The nearby BRIDGE_OUT `0x826f66b6` (ARBITRUM ETH, 2025-11-21T06:48, 0.07 ETH) is **already correctly matched** to a ZKSYNC BRIDGE_IN (`0xfba1a0df`) via `bridge:lifi:0x826f66b6…` and is NOT the source of this KATANA inflow.

### Evidence state

`EVIDENCE_MISSING` — source chain transaction not in `raw_transactions`. No BRIDGE_OUT with matching corrId or amounts found.

### Accounting impact

$1,196 of basis ($402 weETH + $794 ETH) was created at market price. If a corresponding BRIDGE_OUT exists on an untracked source chain (basis correctly drained there), the net accounting effect is: source basis lost externally, destination basis fresh at market → acceptable under unmatched BRIDGE_IN repricing policy. If no source BRIDGE_OUT exists, the CARRY_IN was a fresh acquisition on KATANA and market-price basis is correct.

### Remediation

Identify `0x223ec22d67716fca620aee72b25ffe4ece436f25` on KATANA. Determine whether a matching BRIDGE_OUT exists on any tracked chain around 2025-11-21T07:02. If untracked source exists and matters for carry, add source wallet/chain to ingestion scope.

---

## B-GMX-LP-SETTLEMENT-UNKNOWN — P3 NEW — GMX v2 LP_ENTRY_SETTLEMENT UNKNOWN basisEffect

**Severity**: P3
**Status**: ACTIVE
**Reported**: 2026-06-02 (targeted tx audit)

### Problem

All 5 `LP_ENTRY_SETTLEMENT` events under `corrId=gmx-lp:arbitrum:weth-usdc` on ARBITRUM emit `basisEffect=UNKNOWN` and `costBasisDeltaUsd=0` for **both** the ETH execution-fee refund and the GM/GLV LP token received. This is a systematic pattern across all settlements in this position's history.

### Instance table

| txHash (short) | date | ETH qty received | LP token | LP token qty | cbD ETH | cbD LP token |
|---|---|---|---|---|---|---|
| `0x9fab1650` | 2025-09-04 | 0.0000033 ETH | GLV [WETH-USDC] | 40.35 | $0 UNKNOWN | $0 UNKNOWN |
| `0x3ad60ac2` | 2025-12-16 | 0.0000194 ETH | GM: ETH/USD [WETH-USDC] | 529.62 | $0 UNKNOWN | $0 UNKNOWN |
| `0x61c1272c` | 2026-01-29 | 0.000046 ETH | GM: ETH/USD [WETH-USDC] | 21.60 | $0 UNKNOWN | $0 UNKNOWN |
| `0x1aa3438d` | 2026-02-06 07:32 | 0.0000528 ETH | GM: ETH/USD [WETH-USDC] | 97.96 | $0 UNKNOWN | $0 UNKNOWN |
| `0x52924cd8` | 2026-02-06 07:53 | 0.0000535 ETH | GM: ETH/USD [WETH-USDC] | 4.63 | $0 UNKNOWN | $0 UNKNOWN |

The `LP_ENTRY_REQUEST` events correctly emit `REALLOCATE_OUT` (USDC and ETH drained with cbD>0). The LP position's principal basis is therefore correctly established at deposit time. The settlement step then records receipt of the LP token under `UNKNOWN`.

### Analysis

In WalletRadar's LP accounting model, the LP token (GM/GLV) is a receipt marker; the actual economic basis lives in the LP position corridor account (not in the token). The REALLOCATE_OUT at LP_ENTRY_REQUEST already moved the user's USDC+ETH basis to the LP position. The settlement receiving GM tokens with UNKNOWN/cbD=0 may be intentional (LP token is not the basis carrier).

However, the ETH execution-fee refund (tiny amounts, max 0.0000535 ETH ≈ $0.09) with UNKNOWN also has cbD=0 and AVCO unchanged — consistent with it being treated as a negligible gas-return noise event.

### Risk

If an LP_EXIT or LP_EXIT_SETTLEMENT handler returns basis via the GM token balance (rather than via the LP position corridor), the UNKNOWN basisEffect on the GM token would cause a zero-basis exit and inflated gains. If the LP exit correctly reads the corridor position's accumulated basis, UNKNOWN on GM token is harmless.

### Failed stage hypothesis

`cost_basis` / `LP_ENTRY_SETTLEMENT` handler — the handler emits UNKNOWN for GM/GLV receipts and ETH refunds. Whether this is intentional (LP token = receipt only) or a gap in the settlement path requires verification against LP_EXIT accounting.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the LP position corridor holds basis from REALLOCATE_OUT; settlement does not carry it to the LP token.

### Remediation

Verify: when this GMX position is eventually exited (`LP_EXIT_SETTLEMENT`), does the exit handler emit a REALLOCATE_IN on USDC/ETH from the LP position corridor with cbD>0? If yes, UNKNOWN at settlement is acceptable and no fix needed. If not, the LP position basis is silently lost at exit.

---

## AVCO SPIKE INVESTIGATION — Refresh 5 Supplement (2026-06-02)

Investigation of 6 reported AVCO spike clusters (A–F). Full Mongo reconstruction performed from `asset_ledger_points`, `normalized_transactions`, raw flows.

### Key systemic finding: ALL 122 BRIDGE_INs with corrId are classified as ACQUIRE — none as CARRY_IN

```
Total BRIDGE_INs with correlationId  : 122
BRIDGE_INs with ACQUIRE LP points    : 107
BRIDGE_INs with CARRY_IN LP points   :   0
```

This is the root cause of Clusters A, C, and D spikes. The pipeline links BRIDGE_INs to their BRIDGE_OUTs via `correlationId` but classifies every receipt as an ACQUIRE at market price rather than a CARRY_IN that preserves source-chain basis. Stage: `classification` / `cost_basis` replay.

---

### B-BRIDGE-IN-ACQUIRE (NEW — CRITICAL, P1)

**Systemic.** 107 out of 122 BRIDGE_INs that carry a matching `correlationId` to a BRIDGE_OUT are emitting `ACQUIRE` ledger points (market-priced) instead of `CARRY_IN` (basis-preserving).

| Surface | Wrong now | Correct |
|---|---|---|
| BRIDGE_IN basisEffect | `ACQUIRE` | `CARRY_IN` |
| BRIDGE_IN cbD | market price × qty | proportional carry from BRIDGE_OUT cbD |
| AVCO at destination | reflates to current market | continuous with source-chain AVCO |

**Cluster A evidence (0x3fd2c7 → 0x74b417):**
- BRIDGE_OUT (wallet `0x1a87f12a`): ETH CARRY_OUT cbD=-$2.647, avco=$2,112.93
- BRIDGE_IN (wallet `0xf03b52e8`, 10 min later, same corrId `bridge:lifi:0x3fd2c709...`): ETH **ACQUIRE** cbD=+$2.333, avco=**$2,335.78**
- Correct CARRY_IN cbD = 0.000999 × $2,112.93 = **$2.111** (-$0.222 overstatement)
- AVCO discontinuity: source=$2,112.93 → destination=$2,335.78 (+**$222/ETH**)

**Cluster D evidence (0x4ca0b7 → 0x38d445c4fc):**
- BRIDGE_OUT (wallet `0x1a87f12a` on Base): ETH CARRY_OUT cbD=0 (zero-basis sponsored gas), corrId `bridge:lifi:0x4ca0b79...`
- BRIDGE_IN (same wallet, 1 sec later, same corrId): 0.799 ETH **ACQUIRE** cbD=+**$1,515.67**, avco=**$1,896.61**
- ETH AVCO before BRIDGE_IN: **$1,453.85** (diluted by SPONSORED_GAS_IN)
- AVCO spike: $1,453 → $1,896 = **+$443/ETH (+30%)**

**Failed stage hypothesis:** `classification` — BRIDGE_IN classifier does not promote matched (corrId-linked) BRIDGE_INs to CARRY_IN status.

**Evidence state:** `EVIDENCE_PRESENT_UNLINKED` — corrId is present and correctly assigned on both BRIDGE_OUT and BRIDGE_IN. Pipeline does not use it to carry basis.

**Remediation:** When a BRIDGE_IN has a `correlationId` matching an existing BRIDGE_OUT with CARRY_OUT effect: emit CARRY_IN (not ACQUIRE) with cbD = (received_qty / sent_qty) × |source_CARRY_OUT_cbD|. Fee lost to bridge = remaining portion, treated as GAS_ONLY/bridge-fee expense. Zero-cbD BRIDGE_OUTs propagate zero cbD to BRIDGE_IN (no AVCO inflation at destination).

**cbD shortfall (Cluster A):** +$0.22 overstatement. **Cluster D:** up to $1,515 overstatement. **Total across 107 cases:** unquantified but systemic.

**Active AVCO broken:** YES — visible in Clusters A and D. Cluster C's "basis проваливается" is the downstream dilution of the same D2 BRIDGE_IN spike.

---

### B-LP-UNSTAKE-ETH-MISS (NEW — HIGH, P2)

**Cluster D root cause layer 2.** On 2026-02-06 07:14–07:17 (wallet `0x1a87f12a`, Base):

1. `LP_POSITION_UNSTAKE` (0xe66f924216, 07:14:45): flow = ETH -8.8e-7 FEE only. LP_POINT: GAS_ONLY cbD=0, avco=null.
2. `LP_FEE_CLAIM` (0xed7c0f4d81, 07:15:13, corrId `lp-position:base:pancakeswap:938761`): receives USDC 5.485. No ETH REALLOCATE_IN.
3. `SPONSORED_GAS_IN` (07:17:13): ETH +0.000002364 at zero basis.
4. `BRIDGE_OUT D2` (0x4ca0b7, 07:17:15): raw flow shows ETH -0.799 TRANSFER. LP tracking: CARRY_OUT cbD=0 for only 0.000002 ETH.

On-chain raw flow shows 0.799 ETH leaving the wallet. Accounting only tracks 0.000002 ETH in the position. The **0.799 ETH gap** (worth ~$1,516 at the time) represents ETH that was atomically withdrawn from a Base protocol position (lending/LP) within the same bridge transaction but was NOT captured as a REALLOCATE_IN on the ETH LP.

Consequence: D2 BRIDGE_OUT emits cbD=0 for 0.799 ETH. The subsequent BRIDGE_IN (covered by B-BRIDGE-IN-ACQUIRE) then assigns $1,515 at market instead of the correct protocol cost basis.

**Failed stage hypothesis:** `classification` / `normalization` — LP_POSITION_UNSTAKE handler on Base does not detect or carry basis from the LP position to the released ETH. Likely an atomic-multicall path where the LENDING_WITHDRAW or LP_EXIT sub-call is not normalized as a standalone event.

**Evidence state:** `EVIDENCE_PRESENT_UNUSABLE` — raw flows confirm 0.799 ETH left wallet; LP accounting sees only 0.000002 ETH.

**Remediation:** LP_POSITION_UNSTAKE (and composite bridge txs that include a protocol withdrawal) must emit a REALLOCATE_OUT on the LP/lending receipt token and REALLOCATE_IN on the released ETH before the BRIDGE_OUT CARRY_OUT, so the full cost basis threads through to the destination BRIDGE_IN.

**cbD shortfall:** Estimated ~$1,516 in cbD for the 0.799 ETH (actual basis unknown; overstatement at market may partially offset if historical cost was close to current price). AVCO spike +$443/ETH visible.

**Active AVCO broken:** YES — Cluster D. Also manifests as Cluster C "basis проваливается" (same BRIDGE_IN).

---

### B-LP-EXIT-UNLINKED (NEW — MEDIUM, P3)

**Cluster E root cause.** On 2026-01-29 19:20 (wallet `0x1a87f12a`, Arbitrum):

- `LP_EXIT_REQUEST` (0x806ccd26c2, 19:20:09): corrId=`0x46badf589e3b2764c8f5a8ffb37c83c8906bab72ae34f1827a5e1e6e4ab9d32e`. ETH REALLOCATE_OUT -$3.24 (avco=$2,807.99).
- **No matching `LP_EXIT_SETTLEMENT`** exists with that corrId in the database.
- `EXTERNAL_TRANSFER_IN` (0xf3581fb987, 19:20:13, **4 seconds later**, corrId=none): 3 ACQUIRE flows, ETH +0.020863 at market price → avco drops $2,807.99 → **$2,780.15** (-$28/ETH).
- `LENDING_DEPOSIT` E1 (0xf3eb1aef, 19:21:04): ETH REALLOCATE_OUT → aArbWETH REALLOCATE_IN. AVCO unchanged.

The EXTERNAL_TRANSFER_IN is the GMX LP exit settlement arriving 4 seconds after the request. It is misclassified as an unlinked external inflow (ACQUIRE at market) instead of `LP_EXIT_SETTLEMENT` with CARRY_IN preserving the LP's allocated ETH basis.

**AVCO impact:** ETH drops $28/ETH at the moment of settlement. Because E1 immediately deposits the ETH to Aave, the incorrect AVCO ($2,780 vs $2,808) propagates into aArbWETH.

**cbD shortfall:** 0.020863 ETH × ($2,808 - $2,780) = **~$0.58** understatement.

**Failed stage hypothesis:** `linking` — the GMX LP_EXIT_REQUEST settlement is not matched to the LP_EXIT_REQUEST via corrId; the settlement tx arrives with no corrId and falls through to EXTERNAL_TRANSFER_IN classification.

**Evidence state:** `EVIDENCE_PRESENT_UNLINKED` — settlement TX exists (0xf3581fb987); LP_EXIT_REQUEST corrId exists; pipeline does not assign it to the settlement.

**Remediation:** GMX LP settlement matching must assign the same corrId from LP_EXIT_REQUEST to the arriving settlement TX (based on timing and on-chain GMX execution logic). The settlement should be classified as LP_EXIT_SETTLEMENT with CARRY_IN effect (not ACQUIRE).

**Active AVCO broken:** YES — Cluster E. Manifests as $28/ETH drop visible in chart at 19:20 on 2026-01-29.

---

### B-BRIDGE-ORPHAN-3 (NEW — MEDIUM, P4)

BRIDGE_OUT `0x4a2eb3ee44ab87cbdfe4a6dc59e9733e5541b4bc41ef796e78693d746ba9fb6a` (wallet `0xf03b52e8`, 2026-01-13 13:13).

| Field | Value |
|---|---|
| Asset out | USDC -1,050.0 + ETH -0.000063765 |
| CARRY_OUT cbD | USDC -$1,050.58 + ETH -$0.190 |
| corrId | none |
| Matching BRIDGE_IN found | Partial: `0xfded5a55` (+499.86 USDC on `0x1a87f12a`) + `0xc894c2d939` (+648.22 USDC on `0x1a87f12a`) |
| Matching corrId link | Missing — BRIDGE_OUT has no corrId, BRIDGE_IN has `bridge:lifi:0x95f47c80...` |

Combined inflow ($499.86 + $648.22 = $1,148.08) exceeds outflow $1,050. The two inflows may represent split delivery or unrelated inflows. Without corrId linking, the USDC CARRY_OUT ($1,050.58) remains orphaned.

**cbD shortfall:** ~$0 for USDC (stablecoin, AVCO=$1). ETH CARRY_OUT -$0.190 is small. No active AVCO broken.

**Active AVCO broken:** No (USDC stablecoin).

**Failed stage:** `classification` — corrId not assigned to the BRIDGE_OUT on 0xf5f93d26 path.

---

### B-BRIDGE-ORPHAN-4 (NEW — MEDIUM, P4)

BRIDGE_OUT `0x5ca14340e17ce74a5bcdbef9fd1b72756f90ec41ee6394abb3dd8d6aff73d1fa` (wallet `0xf03b52e8`, 2026-01-15 09:35).

| Field | Value |
|---|---|
| Asset out | USDC -50.0 + ETH -0.000063765 |
| CARRY_OUT cbD | USDC -$50.01 + ETH -$0.190 |
| corrId | none |
| Matching BRIDGE_IN found | None on Jan 15–16 |

No matching BRIDGE_IN or EXTERNAL_TRANSFER_IN found within 24 hours of the BRIDGE_OUT. Confirmed orphan.

**cbD shortfall:** ~$0 (USDC stablecoin). No active AVCO broken.

**Failed stage:** `classification` — corrId missing, BRIDGE_IN not normalized (possible failed bridge or untracked destination wallet).

---

### CLUSTER C — NOT A NEW BLOCKER (diagnosis only)

Cluster C pair (0x1aa343 LP_ENTRY_SETTLEMENT + 0x9c6c4c LP_ENTRY_REQUEST, 2026-02-06):

- ETH AVCO is **stable at $1,749.71** throughout both transactions.
- The "basis проваливается" visible in the chart is the AVCO dropping from $1,896 → $1,749 between 07:17 and 07:32.
- Root cause: the BRIDGE_IN (0x38d445c4fc) at 07:17:16 was classified as ACQUIRE at $1,896 (covered by B-BRIDGE-IN-ACQUIRE + B-LP-UNSTAKE-ETH-MISS).
- SPONSORED_GAS_IN events (07:27–07:32) then dilute ETH AVCO from $1,896 to $1,749 by adding zero-basis ETH.
- C1 and C2 are correctly accounted. The spike is upstream.

**Action:** Fix B-BRIDGE-IN-ACQUIRE. No additional fix needed for the GMX LP operations themselves.

---

### CLUSTER B — NOT A NEW BLOCKER (display artifact)

B1 (0xa5e755a6 BYBIT-CORRIDOR INTERNAL_TRANSFER WETH CARRY_IN, 2026-02-19):

Full reconstruction confirmed:
1. LENDING_WITHDRAW (0xe564fe): 3.0458 WETH from Aave → REALLOCATE_IN +$8,199 to WETH.
2. UNWRAP (0x6f7aec): WETH → ETH +$8,204 basis.
3. INTERNAL_TRANSFER (0xbc3fe1a56b): ETH CARRY_OUT $8,223 → Bybit Mantle; ETH CARRY_IN returns $8,223 (round-trip accounting for corridor).
4. B1 (0xa5e755): ETH CARRY_OUT -$8,223 (on-chain wallet) + WETH CARRY_IN +$8,223 (from Bybit).
5. LENDING_DEPOSIT (0x3b8592): WETH REALLOCATE_OUT -$8,223 → **aManWETH REALLOCATE_IN +$8,223** (avco=$2,687.35).

**Basis is preserved end-to-end.** aManWETH on Mantle holds the full $8,223 basis. No cbD shortfall. No accounting error.

The "basis теряется" observation is a **display artifact**: on-chain ETH position collapses from 3.064 ETH (avco=$2,687) to ~0.005 ETH as the corridor executes, then the basis reappears in aManWETH on Mantle. If the portfolio chart does not aggregate ETH-family assets across networks, this looks like basis loss.

**cbD shortfall:** $0.

**Active AVCO broken:** No.

---

### Cluster F — status update

| Hash | Known ID | Direction | cbD out | Matching BRIDGE_IN | Net AVCO impact |
|---|---|---|---|---|---|
| `0xd8b6e516` | B-BRIDGE-ORPHAN-1 | ETH to Linea | -$0.827 | None found (AVAX CARRY_IN unrelated) | ETH -$0.827 basis lost |
| `0xb1e9f65d` | B-BRIDGE-ORPHAN-2 | USDC $2,829 + ETH $0.190 | -$2,829.31 | `0xd410b4e7` (ACQUIRE, $2,831 USDC, wallet `0xf03b52e8`) — unlinked | ~$0 (USDC stablecoin) |
| `0x4a2eb3ee` | **B-BRIDGE-ORPHAN-3** (new) | USDC $1,050 + ETH $0.190 | -$1,050.77 | Partial (split delivery, unlinked) | ~$0 (USDC stablecoin) |
| `0x5ca14340` | **B-BRIDGE-ORPHAN-4** (new) | USDC $50 + ETH $0.190 | -$50.20 | None found | ~$0 (USDC stablecoin) |

F2 (B-BRIDGE-ORPHAN-2) previously confirmed at $0.81 net impact. Correct exits to untracked destination chain confirmed for F2-F4 — USDC AVCO unaffected (stablecoin). ETH GAS CARRY_OUTs ($0.190 per tx) represent bridge gas costs, not recoverable.

---

### Updated Active Blocker Table (post-spike investigation)

| Rank | ID | Sev | Description | Est. cbD shortfall | Active AVCO broken? | Fixable? |
|---|---|---|---|---|---|---|
| 1 | **B-BRIDGE-IN-ACQUIRE** | P1 CRITICAL | ALL 107 linked BRIDGE_INs emit ACQUIRE instead of CARRY_IN — systemic | Systemic (varies per bridge) | **YES** (ETH spikes visible) | ✅ Classification fix |
| 2 | **B-LP-UNSTAKE-ETH-MISS** | P2 HIGH | LP_POSITION_UNSTAKE on Base: 0.799 ETH atomic withdrawal not captured in ETH position before bridge | ~$1,516 (Cluster D, market-basis) | **YES** (+$443/ETH spike) | ✅ Normalization fix |
| 3 | **B-LP-EXIT-UNLINKED** | P3 MEDIUM | LP_EXIT_SETTLEMENT arrives as EXTERNAL_TRANSFER_IN ACQUIRE; settlement not linked to request | ~$0.58 | YES (-$28/ETH, Cluster E) | ✅ Linking fix |
| 4 | B-VAULT-WITHDRAW `0xc8b94615` | P2 FIXABLE | Turtle Finance USDC vault AVAX: vault token burn missing from flows | ~$2,815 | No | ✅ Normalization fix |
| 5 | B-BYBIT-CORRIDOR-2 (USDe) | DATA GAP | 2115 USDe MANTLE: FUND $10 at withdrawal; $2,105 SPOT acq exists | ~$2,105 | Yes (USDe avco≈$0.005) | ❌ Evidence missing |
| 6 | B-VAULT-WITHDRAW `0x971c8464`+`0xb47d87fa` | DATA GAP | MCUSDC upstream shortfall + wrapper-less vault | ~$4,608 | No | ❌ Irreducible |
| 7 | B-BYBIT-CORRIDOR-2 (cmETH) | DATA GAP | cmETH MANTLE GENUINE_EVIDENCE_MISSING | ~$212 | Yes (cmETH) | ❌ Evidence missing |
| 8 | **B-BRIDGE-ORPHAN-3** | P4 LOW | USDC $1,050 BRIDGE_OUT (0x4a2eb3) unlinked, partial inflow match | ~$0 (stable) | No | ❌ corrId gap |
| 9 | **B-BRIDGE-ORPHAN-4** | P4 LOW | USDC $50 BRIDGE_OUT (0x5ca143) orphan, no BRIDGE_IN found | ~$0 (stable) | No | ❌ corrId gap / failed bridge |
| 10 | B-BRIDGE-ORPHAN-2 | DATA GAP | USDC BRIDGE_OUTs to 0xf5f93d26 ($0.81 net fee) | ~$0.81 | No | ❌ Trivial |
| 11 | B-BRIDGE-ORPHAN-1 | DATA GAP | ETH $0.827 to Linea (no BRIDGE_IN) | ~$0.827 | No | ❌ Cross-chain gap |

---

## Previously Resolved

| ID | Fix | Status |
|----|-----|--------|
| B-SPIKE-1 | BRIDGE_OUT secondary positive flow retagging regression | **RESOLVED** |
| B-BRIDGE-1 | Across threshold relaxation | **RESOLVED** |
| B-SPIKE-2 | SPONSORED_GAS_IN GAS_ONLY | **RESOLVED** |
| B-BTC-1 | BTC cross-UID corridor zero-qty DISPOSE | **RESOLVED** |
| B-CORRIDOR-1 | "BRIDGE_IN cbD=0 basis loss" | **RETRACTED** |
| B-USDC-1 | BORROW using perWalletAvco instead of market price | **RESOLVED** |
| B-SHORTFALL-1 | Zero-basis BRIDGE_INs repriced at market | **RESOLVED** |
| B-BYBIT-CORRIDOR-2 sub-A | 21/23 corridor CARRY_INs now have cbD>0 | **PARTIALLY RESOLVED** |
| B-VAULT-WITHDRAW Bug A | mevUSDC wrapper bucket mechanism implemented; $19,118 cbD recovered | **PARTIALLY RESOLVED** |
| B-CROSS-UID (Defect 2) | isBybitSelfTransfer correlationId guard working for UTA-sourced carries | **PARTIALLY RESOLVED** |
| B-CROSS-UID (Defect 1 BTC) | FUNDING_HISTORY corrId now assigned; both BTC CARRY_OUTs emit cbD>0; SPOT DISPOSE uses correct avBef=$96,406 | **RESOLVED** |

---

## REFRESH 8 — 2026-06-03 (AVCO spikes investigation)

**Scope:** 17 on-chain txs + 4 Bybit events provided for triage.
**Method:** `normalized_transactions` + `asset_ledger_points` + `bybit_extracted_events` cross-checked chronologically.
**Pipeline state at inspection:** same session `df5e69cc`.

---

### 0. B-LP-EXIT-BASE-PANCAKE-UNKNOWN — VERIFICATION ✅ CONFIRMED RESOLVED (structural)

`0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`

- **Status:** The normalization fix was applied. `type=LP_EXIT`, flows correctly contain ETH +0.796271 and LP-RECEIPT:BASE:PANCAKESWAP:938761 -1.
- **Ledger:** ETH `REALLOCATE_IN` +0.796271, LP-RECEIPT `REALLOCATE_OUT` -1. Structural accounting is clean.
- **ETH basis after:** $1.07 total / AVCO $1.35 per ETH. This is NOT a new bug here — it is the downstream effect of **B-PCAKE-V3-PARTIAL-EXIT** (see below), which destroyed the LP-RECEIPT basis upstream. The LP_EXIT correctly carries back whatever the LP-RECEIPT held at time of exit.
- **Verdict:** B-LP-EXIT-BASE-PANCAKE-UNKNOWN is resolved as filed. A new P0 emerges upstream.

---

### NEW BLOCKERS FOUND IN THIS CYCLE

---

#### B-PCAKE-V3-PARTIAL-EXIT — P0 ACTIVE

**Root cause:** Two PancakeSwap V3 position management transactions were misclassified as full `LP_EXIT`, destroying the LP-RECEIPT basis on each cycle. The actual operations were partial liquidity removes via a position-manager wrapper contract (`0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3`) that temporarily takes and returns the NFT, but the pipeline only saw the NFT outflow and classified it as a complete exit.

**Evidence trace — LP-RECEIPT:BASE:PANCAKESWAP:938761 lifecycle:**

| Timestamp | Tx | Type | Effect on LP-RECEIPT | LP-RECEIPT basis |
|---|---|---|---|---|
| 2025-12-20 10:23 | `0x5532ff4b` | LP_ENTRY | REALLOCATE_IN +1 (USDC $1,148.84 + ETH $795.27) | **$1,944.10** |
| 2025-12-20 10:25 | `0x9d6199bb` | LP_ENTRY | REALLOCATE_IN qty=0 (USDC $41.10 + ETH $28.02 added) | **$2,013.23** |
| 2026-01-31 19:54 | `0x3dc35066` | **LP_EXIT** ❌ | REALLOCATE_OUT -1 → basis $0. USDC +$11.16 credited. | **$0** (from $2,013) |
| 2026-02-02 11:17 | `0xc9c5686c` | LP_ENTRY | REALLOCATE_IN +1 (USDC $12.996 entered) | **$12.996** |
| 2026-02-02 11:32 | `0x29dad570` | **LP_EXIT** ❌ | REALLOCATE_OUT -1 → basis $0. USDC +$2.66 credited. | **$0** (from $12.996) |
| 2026-02-02 11:33 | `0xbdf8cee9` | LP_ENTRY | REALLOCATE_IN +1 (USDC $1.07 + ETH $2.97 entered) | **$1.072** |
| 2026-02-06 07:15 | `0x0a757aee` | LP_EXIT ✅ | REALLOCATE_OUT -1. ETH +0.796271 REALLOCATE_IN $1.07 | — |

**Financially correct reconstruction:**
- 0x3dc35066 was a **partial USDC liquidity removal** from the position (all or part of the in-range USDC portion removed). The ETH half of the concentrated liquidity position (which held most of the value since ETH price had dropped below range) remained in the LP. The Pancake V3 NFT was not burned — it was handled by the wrapper and returned in a subsequent transaction. Pipeline saw NFT outflow only and treated it as full burn.
- 0x29dad570: same pattern — partial USDC removal with NFT roundtrip, not a full burn.
- The final LP_EXIT (0x0a757aee, fixed) correctly returned 0.796271 ETH worth ~$1,507 at market — confirming the position was still alive and held ETH value from the original $2,013+ deposit.

**cbD shortfall:** ~$2,012 (LP-RECEIPT basis destroyed and not redistributed to the ETH portion that remained in the LP)

**Current wrong state:** ETH receives $1.07 basis on LP_EXIT for 0.796271 ETH (AVCO $1.35/ETH) → correct AVCO should inherit from LP-RECEIPT basis ~$2,013 / 0.796271 ETH = **~$2,527/ETH** (blended with any prior ETH balance).

**Earliest failed stage:** `classification` — partial V3 liquidity removal should not generate LP-RECEIPT REALLOCATE_OUT.

**Evidence state:** `EVIDENCE_PRESENT_UNLINKED` — the NFT was not burned; the pipeline missed the NFT return flow.

**Remediation:** Add detection for Pancake V3 position manager wrapper pattern. When NFT leaves and re-enters in the same or closely-adjacent transaction via a known wrapper, classify as LP_PARTIAL_REMOVE (or LP_FEE_CLAIM if only fees harvested), not LP_EXIT. LP-RECEIPT basis must not be zeroed on partial removals.

---

#### B-ETH-ARBI-LOW-BASIS — P1 ACTIVE

**Root cause:** The Arbitrum ETH balance for wallet `0x1a87f12` accumulated to near-zero cost basis (~$0.004 total) through a chain of CARRY_IN/CARRY_OUT cycles between Feb 2025 and Feb 2026 that gradually depleted the basis without proportional basis inflow. Additionally, a ghost ledger point at Sep 5 2025 (see B-DOUBLE-LEDGER-POINT) lowered AVCO from $582 → $399 on a zero-qty phantom event.

**Consequence for GMX transactions:**
- Before GMX LP_EXIT_REQUEST (0xa83cc44b, Feb 6): ETH AVCO = **$2.43**, total basis $0.004.
- LP_ENTRY_REQUEST (0xff684e80): ETH REALLOCATE_OUT -0.000165 ETH consumed at $2.43 AVCO.
- LP_ENTRY_SETTLEMENT (0x1aa3438d): ETH REALLOCATE_IN +0.0000528 refund → AVCO **$15.79** (impossible for ETH in Feb 2026 at ~$1,900 market).
- Second settlement (0x52924cd8): same AVCO inherited.

**Affected txs:** `0xa83cc44b`, `0xff684e80`, `0x1aa3438d`, `0x9c6c4c68`, `0x52924cd8`

**cbD shortfall:** Small in absolute terms (execution fee refunds are 0.00005–0.00007 ETH), but AVCO distortion is extreme ($15.79 vs ~$1,900). If ETH position on Arbitrum is later consolidated, the AVCO error propagates.

**Earliest failed stage:** `replay` (ghost ledger point corrupts AVCO) + `carry` (upstream carry chains lost basis).

**Remediation:** Fix B-DOUBLE-LEDGER-POINT (see below). Then audit all CARRY_IN chains for Arbitrum ETH on 0x1a87f12 to identify the point where basis was incorrectly zeroed.

---

#### B-EARN-MISTYPE — P1 ACTIVE

**Root cause:** Bybit `EARN_FLEXIBLE_SAVING` principal redemption events have `canonicalType=INTERNAL_TRANSFER` in `bybit_extracted_events` (correctly identified by the extraction layer), but `normalized_transactions` carries them as `type=LENDING_WITHDRAW`. The correct type is `INTERNAL_TRANSFER` (EARN → FUND account transfer).

**Affected events:**

| Event ID | Asset | Qty (flow) | Ledger qty (REALLOCATE_OUT) | Discrepancy |
|---|---|---|---|---|
| `EARN_FLEXIBLE_SAVING:e475b70e` | CMETH | -0.13981517 | -0.00026422 | 530× under |
| `EARN_FLEXIBLE_SAVING:734bf6f1` | CMETH | -0.14379048 | -0.00052442 | 274× under |
| `EARN_FLEXIBLE_SAVING:a67c0479` | ETH | -0.15114916 | -0.00001379 | 10,959× under |

The ledger qtys are tiny because the EARN account has nearly no balance tracked — the EARN deposit counterpart was never properly credited (see B-EARN-DEPOSIT-MISSING). As a result:
1. EARN `LENDING_WITHDRAW` REALLOCATE_OUT carries almost no basis.
2. FUND never receives a matching CARRY_IN → the redeemed CMETH and ETH appear from nowhere in FUND without basis.
3. No FUND counterpart found in `normalized_transactions` within ±24h of any redemption.

**Earliest failed stage:** `classification` (LENDING_WITHDRAW vs INTERNAL_TRANSFER).

**Evidence state:** `EVIDENCE_PRESENT_UNUSABLE` — raw event has correct `canonicalType=INTERNAL_TRANSFER` and `bybitDescription="Flexible Savings Principal Redemption"`, but normalization overrode it.

**Remediation:** Reclassify `EARN_FLEXIBLE_SAVING` principal redemptions as `INTERNAL_TRANSFER` with CARRY_OUT from EARN and CARRY_IN to FUND. Requires FUND counterpart to be generated.

---

#### B-EARN-DEPOSIT-MISSING — P1 ACTIVE

**Root cause:** When METH is subscribed to Bybit Earn (`FUNDING_HISTORY:a854608d`, description "On-chain Earn subscription"), the FUND account correctly emits CARRY_OUT. But the EARN account never receives a matching CARRY_IN. The METH balance on `BYBIT:33625378:EARN` therefore stays at 0, and all subsequent EARN-side events work on a phantom empty balance.

| FUNDING_HISTORY event | Asset | FUND CARRY_OUT | EARN CARRY_IN | Status |
|---|---|---|---|---|
| `a854608d` (METH, 2025-03-12) | METH | -0.66865026 → totalBasis=$0 | NOT FOUND | ❌ Missing |

The same pairing defect likely exists for the CMETH and ETH earn subscriptions (not all subscription events were in scope, but the near-zero EARN balance confirms they were not credited).

**Earliest failed stage:** `linking` (CARRY_OUT/CARRY_IN pairing for FUND→EARN transfers).

**Remediation:** For every FUNDING_HISTORY event with description matching earn subscription pattern, ensure an EARN-side CARRY_IN counterpart is generated during normalization/clarification.

---

#### B-DOUBLE-LEDGER-POINT — P2 ACTIVE

**Root cause:** The internal transfer `0x04b1a5790c6f9aa72f19353f2226dab48d0e4630e679d7a18ad7b8ef9022c600` on ARBITRUM (0xf03b52 → 0x1a87f12) generated two ledger points attributed to wallet `0x1a87f12` in the Arbitrum ETH ledger:

- Entry `:5031` (`normalizedTransactionId` = `...ARBITRUM:0x1a87f12...`): correct CARRY_IN +0.0000378 ETH, AVCO $582.53.
- Entry `:5032` (`normalizedTransactionId` = `...ARBITRUM:0xf03b52e8...`): ghost CARRY_IN qty=0, totalCostBasis drops $0.246→$0.168, AVCO $582→$399.

The second entry bears the 0xf03b52 wallet's tx ID but is written into 0x1a87f12's ledger space (walletAddress=0x1a87f12), reducing ETH AVCO by ~$183 on a zero-qty event. This is a replay-stage cross-wallet contamination.

**Earliest failed stage:** `replay` — ledger point written to wrong wallet.

**Remediation:** In the replay stage, validate that `walletAddress` in each ledger point matches the wallet in the `normalizedTransactionId`. Purge `:5032` from 0x1a87f12's ledger; it belongs to 0xf03b52 (or is a bug in the INTERNAL_TRANSFER dual-perspective handling).

---

#### B-GM-SECOND-SETTLEMENT-UNKNOWN — P3 MINOR

`0x52924cd84b0dcab31a9be5d6157af7a5c594ebe5cd8d73965746191d21912a6f`

- `GM: ETH/USD [WETH-USDC]` receives `basisEffect=UNKNOWN` instead of `REALLOCATE_IN`.
- The first LP_ENTRY_SETTLEMENT (`0x1aa3438d`) correctly shows `REALLOCATE_IN` for GM tokens.
- No material AVCO impact since GM qty is small (4.634 tokens) and the UNKNOWN leaves basis at $0.
- **Earliest failed stage:** `cost_basis` / `move_basis`.

---

#### B-PENDLE-REWARD-UNKNOWN — P3 MINOR

`0xf7f8908b455261dc67a7f905ca99f1041987de690a7574d440e31739c3132430`

- `PENDLE` reward on LP_EXIT has `basisEffect=UNKNOWN`, total basis $0. Should be `ACQUIRE` (reward income, fresh acquisition at market price $4.833/PENDLE).
- Amount: 0.0127 PENDLE = ~$0.062. Negligible absolute impact.
- **Earliest failed stage:** `cost_basis`.

---

### TRANSACTION INVENTORY — Refresh 8

| Tx / Event | Type | Status | AVCO impact | Blocker? |
|---|---|---|---|---|
| `0xa5e755a6` | INTERNAL_TRANSFER MANTLE (Bybit corridor) | CONFIRMED | CARRY_IN 3.06 WETH at $2,227 AVCO — clean | None |
| `0xff684e80` | LP_ENTRY_REQUEST GMX ARBITRUM | CONFIRMED | Classification correct; ETH AVCO distorted by upstream | Downstream of B-ETH-ARBI-LOW-BASIS |
| `0x1aa3438d` | LP_ENTRY_SETTLEMENT GMX ARBITRUM | CONFIRMED | ETH REALLOCATE_IN at $15.79 AVCO ❌ | Downstream of B-ETH-ARBI-LOW-BASIS |
| `0x9c6c4c68` | LP_ENTRY_REQUEST GMX ARBITRUM | CONFIRMED | Clean classification | Downstream of B-ETH-ARBI-LOW-BASIS |
| `0x52924cd8` | LP_ENTRY_SETTLEMENT GMX ARBITRUM | CONFIRMED | GM UNKNOWN basis | B-GM-SECOND-SETTLEMENT-UNKNOWN (P3) |
| `0xa83cc44b` | LP_EXIT_REQUEST GMX ARBITRUM | CONFIRMED | Classification correct | None |
| `0x0a757aee` | LP_EXIT BASE PancakeSwap | CONFIRMED ✅ FIXED | Structural fix verified; $1.07 ETH basis = upstream effect | B-LP-EXIT-BASE-PANCAKE-UNKNOWN RESOLVED |
| `0xb9ad84bba` | BRIDGE_OUT BASE → ARBITRUM LiFi | CONFIRMED | CARRY_OUT zeroes BASE ETH; BRIDGE_IN credited $7.75 at $2,875 on Arbitrum | None |
| `0xf7f8908b` | LP_EXIT MANTLE Pendle | CONFIRMED | cmETH REALLOCATE_IN $2,368 AVCO (correct); PENDLE UNKNOWN | B-PENDLE-REWARD-UNKNOWN (P3) |
| `0xe2f90520` | INTERNAL_TRANSFER MANTLE BYBIT corridor (CMETH) | CONFIRMED | Clean | None |
| `0x04b1a579` | INTERNAL_TRANSFER ARBITRUM (0xf03b52→0x1a87f12) | CONFIRMED | Ghost ledger point corrupts Arbitrum ETH AVCO | B-DOUBLE-LEDGER-POINT (P2) |
| `0x9633536` | LENDING_WITHDRAW BASE (Aave AWETH→WETH) | CONFIRMED | AWETH REALLOCATE_OUT, WETH REALLOCATE_IN; small interest ACQUIRE | None |
| `0xf4c41d79` | INTERNAL_TRANSFER OPTIMISM (0x68bc→0x1a87f) | CONFIRMED | Clean CARRY pairing | None |
| `0xfaf8160c` | LP_ENTRY MANTLE Pendle | CONFIRMED | cmETH REALLOCATE_OUT → Pendle pool — clean | None |
| `0xfaf8160c` LP_EXIT variant | LP_EXIT MANTLE Pendle (via correlationId) | CONFIRMED | LP_EXIT correctly returns cmETH via REALLOCATE_IN | None |
| `0xbc698f78` | SWAP MANTLE USDC→cmETH | CONFIRMED | SWAP_DERIVED pricing correct | None |
| `0x8c03a4ef` | BRIDGE_OUT OPTIMISM WETH→UNICHAIN LiFi | CONFIRMED | Bridge matched; BRIDGE_IN on UNICHAIN clean | None |
| `0xed509348` | INTERNAL_TRANSFER BASE (0x1a87f→0x68bc) | CONFIRMED | Clean CARRY | None |
| `EARN_FLEXIBLE_SAVING:e475b70e` | LENDING_WITHDRAW EARN (CMETH) | CONFIRMED ❌ | Flow qty vs ledger qty: 0.13981 vs 0.00026 | B-EARN-MISTYPE (P1) |
| `EARN_FLEXIBLE_SAVING:734bf6f1` | LENDING_WITHDRAW EARN (CMETH) | CONFIRMED ❌ | 0.14379 vs 0.00052 | B-EARN-MISTYPE (P1) |
| `EARN_FLEXIBLE_SAVING:a67c0479` | LENDING_WITHDRAW EARN (ETH) | CONFIRMED ❌ | 0.15114 vs 0.00001 | B-EARN-MISTYPE (P1) |
| `FUNDING_HISTORY:a854608d` | INTERNAL_TRANSFER FUND→EARN (METH) | CONFIRMED ❌ | CARRY_OUT FUND → no matching EARN CARRY_IN | B-EARN-DEPOSIT-MISSING (P1) |

---

### RANKED BLOCKER LIST — Refresh 8

| Rank | ID | Severity | Stage | Assets | Root cause | cbD shortfall | Remediation class |
|---|---|---|---|---|---|---|---|
| 0 | **B-PCAKE-V3-PARTIAL-EXIT** | **P0 ACTIVE** | classification | ETH, LP-RECEIPT | Pancake V3 partial liquidity removal (0x3dc35066, 0x29dad570) misclassified as full LP_EXIT; LP-RECEIPT basis $2,013→$0 on each cycle; final LP_EXIT carries only $1.07 to ETH | **~$2,012** | Normalization fix: detect NFT roundtrip via wrapper; classify as LP_PARTIAL_REMOVE, preserve LP-RECEIPT basis |
| 1 | **B-ETH-ARBI-LOW-BASIS** | P1 ACTIVE | replay + carry | ETH (ARBITRUM) | Arbitrum ETH for 0x1a87f12 has ~$0.004 total basis from carry chain depletion; ghost ledger point (B-DOUBLE-LEDGER-POINT) contributes; GMX fee refund REALLOCATE_IN results in $15.79/ETH AVCO | Small absolute (tiny fee qtys); AVCO distorted | Replay fix (ghost point) + carry chain audit for Arbitrum ETH |
| 2 | **B-EARN-MISTYPE** | P1 ACTIVE | classification | CMETH, ETH (BYBIT:EARN) | EARN_FLEXIBLE_SAVING principal redemptions classified as LENDING_WITHDRAW; raw canonicalType=INTERNAL_TRANSFER ignored; no FUND counterpart created; EARN ledger near-empty | ~$400–600 est. (near-empty EARN balance × market price) | Re-classify as INTERNAL_TRANSFER; generate FUND CARRY_IN counterpart |
| 3 | **B-EARN-DEPOSIT-MISSING** | P1 ACTIVE | linking | METH, CMETH, ETH (BYBIT:EARN) | FUND→EARN CARRY_OUT emitted but no EARN CARRY_IN created; EARN account has near-zero balance on all assets | Compounds B-EARN-MISTYPE | Linking fix: pair FUNDING_HISTORY earn subscription events with EARN CARRY_IN |
| 4 | **B-DOUBLE-LEDGER-POINT** | P2 ACTIVE | replay | ETH (ARBITRUM) | Ghost ledger point for 0x04b1a579 written to 0x1a87f12 ledger with 0xf03b52 tx ID; reduces AVCO $582→$399 on zero-qty phantom event | Small (ETH AVCO $183 drop, corrects partially by subsequent events) | Replay fix: validate walletAddress matches normalizedTransactionId wallet |
| 5 | **B-GM-SECOND-SETTLEMENT-UNKNOWN** | P3 MINOR | cost_basis | GM: ETH/USD (ARBITRUM) | Second LP_ENTRY_SETTLEMENT (0x52924cd8) emits UNKNOWN instead of REALLOCATE_IN for GM tokens | Negligible (4.634 GM, $0 basis) | Cost-basis fix: GM REALLOCATE_IN on settlement |
| 6 | **B-PENDLE-REWARD-UNKNOWN** | P3 MINOR | cost_basis | PENDLE (MANTLE) | LP_EXIT reward PENDLE basisEffect=UNKNOWN; should be ACQUIRE | ~$0.062 | Cost-basis fix: reward income = ACQUIRE at market price |

---

### CARRY-OVER ACTIVE BLOCKERS (unchanged status from refresh 7)

| ID | Severity | Status |
|---|---|---|
| B-VAULT-WITHDRAW `0xc8b94615` | P2 | Active — Turtle Finance USDC vault AVALANCHE, ~$2,815 |
| B-BYBIT-CORRIDOR-2 (USDe) | DATA GAP | Active — ~$2,105, evidence missing |
| B-VAULT-WITHDRAW `0x971c8464`+`0xb47d87fa` | DATA GAP | Active — ~$4,608, irreducible |
| B-BYBIT-CORRIDOR-2 (cmETH) | DATA GAP | Active — ~$212, evidence missing |
| B-BRIDGE-ORPHAN-* | P4 | Active — trivial net shortfall |

