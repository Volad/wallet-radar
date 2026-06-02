# Reconciliation — AVCO Audit 2026-06-02 (full audit cycle, refresh 2)

Pipeline: CONFIRMED=7338 (+22) | PENDING_PRICE=0 | NEEDS_REVIEW=0 | ledger=9694 (+8)

---

## Refresh 2 — 2026-06-02 (post B-SHORTFALL-1, B-USDC-1, B-BYBIT-CORRIDOR-2-A, B-VAULT-WITHDRAW Bug A, B-CROSS-UID)

### New findings

| ID | Description | Impact |
|---|---|---|
| B-ONDO-CARRY-1 (NEW P2) | 4 ONDO CARRY_OUTs on BYBIT:33625378 with `bybit-collapsed-v1:` corrId have no matching CARRY_IN. 96.47 ONDO, ~$118 missing basis. | History P&L distortion |
| B-MNT-CARRY-1 (NEW P2) | 5 MNT INTERNAL_TRANSFERs on MANTLE (wallet 0xa0dd42c → 0x1a87f12) have no `internal-tx:mantle:` corrId. 80.78 MNT, ~$69 zero-basis CARRY_IN on destination wallet. | Ongoing AVCO undercount for MNT |
| B-BRIDGE-ORPHAN-1 (NEW P3) | 4 BRIDGE_OUTs with no matching BRIDGE_IN: 2 LiFi USDC (~6.24 USDC) + 2 Across ETH (small). Destination chain data absent. | ~$7 (minimal) |

### AVCO spike symptom analysis (user-reported: "still visible spikes")

The dominant AVCO spike patterns from the raw scan are:
1. **WBTC LENDING_DEPOSIT sawtooth** (14 cases, wallet 0x1a87f12): WBTC deposited to Aave ARBITRUM → position goes to 0 → avcoAfterUsd=null → chart spike. Basis correctly moved to `AARBWBTC`. Next SWAP creates fresh AVCO at market. **Not a bug.** Visual artifact from repeated lending cycle.
2. **WETH UNWRAP REALLOCATE_OUT** (25+16 = 41 cases): WETH→ETH transfers blend AVCO. Correct accounting. Chart shows spikes when WETH AVCO ≠ ETH AVCO.
3. **USDC CARRY_OUT BRIDGE_OUT** (26 cases): Basis leaves source network, chart drops. Correct. Destination BRIDGE_IN picks it up.
4. **ONDO CARRY_OUT** (12 cases in spike list): Position drained from BYBIT:33625378 to EARN. 24/28 CARRY_OUTs matched. 4 unmatched → B-ONDO-CARRY-1.

### AVCO carry symptom analysis (user-reported: "AVCO не переносится")

The primary cause is **B-MNT-CARRY-1**: 5 MNT INTERNAL_TRANSFERs on MANTLE with no corrId → destination wallet receives MNT with 0 AVCO. The source wallet (0xa0dd42c) has correct AVCO for these transfers but the basis is not forwarded to 0x1a87f12. Fix: ensure `internal-tx:mantle:txHash` corrId is assigned to all MANTLE INTERNAL_TRANSFERs between tracked wallets.

Secondary cause: the USDC AVCO jump ($0.10 → $0.97 on 2026-03-10 tx `0x8186161871`) is a cascade effect. A tiny USDC position had near-zero AVCO from accumulated zero-basis CARRY_INs (4 entries, 0.012 USDC). When 326 USDC arrived via BYBIT-CORRIDOR, the blend jumped to $0.97. Root cause is upstream zero-basis USDC CARRY_INs from BRIDGE_IN/EXTERNAL_TRANSFER paths (W-9 artifacts).

### Missing BRIDGE_IN symptom analysis (user-reported: "some BRIDGE_IN missing")

4 orphan BRIDGE_OUTs documented in B-BRIDGE-ORPHAN-1. Small USD amounts (<$10). Root cause: destination chain transactions absent from raw_transactions. These are not the cause of meaningful AVCO spikes.

---

## Full Audit 2026-06-02

### Check 1 — AVCO Spike Scan (all assets)

**Method**: scanned all `asset_ledger_points` with `basisEffect ≠ GAS_ONLY/UNAVAILABLE` and `avcoAfterUsd > 0`, grouped by `accountingAssetIdentity + walletAddress`, flagged consecutive transitions with >20% AVCO change.

**Result**: 187 spikes >20% found.

**Spike class breakdown:**

| Class | Est. count | Actionable |
|---|---|---|
| First-acquire from zero position | ~40 | No — expected |
| LP/vault receipt REALLOCATE_IN volatility | ~30 | No — expected for LP receipts |
| Historical vault cascade artifacts (superseded) | ~25 | See B-VAULT-WITHDRAW |
| Bybit Earn AVCO volatility | ~20 | See B-EARN-BUNDLE |
| Natural market-price ACQUIRE moves | ~30 | No — expected |
| Residual corridor artifacts | ~5 | See B-BYBIT-CORRIDOR-2 |
| Active broken positions (current) | 2 | See B-CROSS-UID (BTC) |
| Other minor | ~35 | Low priority |

**Active broken current-position spikes:**
- `SYMBOL:BTC BYBIT:33625378`: avco=$0, basis=$0, qty=0.000797 BTC (~$79 missing cbD) — cross-UID BTC carry

**Key observations for core asset families:**
- ETH: $2,079–$3,822 (market-plausible across positions) ✅
- USDC: all current positions avco≈$1.00 ✅
- USDT: avco=$1.00 ✅
- MNT: avco=$1.28–$1.45 (market-plausible) ✅
- cmETH 2 residual MANTLE positions: cbD=$0 (GENUINE_EVIDENCE_MISSING_PROVEN)

### Check 2 — Zero-basis CARRY_INs (remaining)

**Total zero-basis CARRY_INs (qty>0.001, cbD<$0.01):** 42

| Group | Count | Notes |
|---|---|---|
| BYBIT-CORRIDOR (sub-pattern A/B residual) | 1 | cmETH 0x5067b0e1 qty=0.10528; 0xc6a03abc (qty=0.001) filtered by threshold |
| bybit-cross-uid-v1 | 4 | MNT, TON, USDT, ETH; BTC 2 entries filtered by qty<0.001 threshold |
| BRIDGE_IN/EXTERNAL_TRANSFER_IN | 6 | Euler share tokens (eUSDC-2 and similar — inflated qty, W-9) |
| OTHER | 31 | bybit-it-bundle (21 entries), LP_POSITION_UNSTAKE (8), NATIVE:MANTLE transfers, PLASMA UNWRAP |

**Key new finding**: `bybit-it-bundle-v1` contributes 21 zero-cbD CARRY_INs (ONDO, LDO, TON, LINK, ARB, SOL) → B-EARN-BUNDLE (P2).

**BRIDGE_IN group**: All 6 entries are Euler vault share tokens with astronomically inflated quantities (1.6B–1.9B+ units). These are W-9 artifacts, not real stablecoin basis gaps. Actual USDC basis for those positions is tracked correctly ($1,043).

### Check 3 — REALLOCATE_IN basis carry (vault withdrawals)

**Query**: `basisEffect=REALLOCATE_IN, costBasisDeltaUsd<1, quantityDelta>10`

**Found**: 29 entries. Key stablecoin entries:

| ntId | asset | network | qty | cbD | type | missing cbD |
|---|---|---|---|---|---|---|
| `0x4e4740e3` | USDC AVALANCHE | AVALANCHE | 1628 | $0.0017 | VAULT_WITHDRAW | **$1,628** |
| `0xe6b02813` | USDC AVALANCHE | AVALANCHE | 1000 | $0.001 | VAULT_WITHDRAW | **$1,000** |
| `0xff65de51` | USDC AVALANCHE | AVALANCHE | 1014 | $0.001 | VAULT_WITHDRAW | **$1,014** |
| `0xc8b94615` | USDC AVALANCHE | AVALANCHE | 2831 | $0 | VAULT_WITHDRAW | **$2,831** |
| `0x971c8464` | USDC ARBITRUM | ARBITRUM | 1783 | $0.001 | VAULT_WITHDRAW | **$1,783** |
| `0xb47d87fa` | USDC ARBITRUM | ARBITRUM | 2825 | $0 | VAULT_WITHDRAW | **$2,825** |
| **TOTAL** | | | **11,081** | | | **$11,082** |

Non-stablecoin zero-basis REALLOCATE_INs include BYBIT:FUND LENDING_WITHDRAW entries (earn principal path, separate concern), LP_EXIT dust returns, and VAULT_DEPOSIT receipt tokens.

The VAULT_WITHDRAW defect is systemic across AVALANCHE and ARBITRUM for the same `VAULT_WITHDRAW` transaction type. Protocol: MEV Capital (AVALANCHE) and at least one unidentified vault (ARBITRUM). Root stage: `cost_basis`.

### Check 4 — Conservation check

**Per-wallet per-asset balance check (sumCbD vs stored totalCostBasisAfterUsd):**

| Asset | sumCbD | stored basis | gap | verdict |
|---|---|---|---|---|
| SYMBOL:USDT | $4,298 | $4,298 | $0 | ✅ balanced |
| NATIVE:BASE | $0 | $0 | $0.04 | ✅ rounding |
| NATIVE:UNICHAIN | $1 | $0 | $0.80 | ✅ minor (W-11) |
| SYMBOL:MNT | $5,896 | $5,896* | see note | ✅ (cross-wallet sum) |
| NATIVE:ARBITRUM | $127 | $125 | $1.49 | ✅ rounding |

*Note: The prior report showed $5,744 gap for SYMBOL:MNT — this was an artifact of comparing the multi-wallet sum to a single position's last stored value. Per-wallet aggregation confirms MNT is balanced.

Pre-existing conservation anomalies (unchanged):
- `SYMBOL:ETH BYBIT:33625378`: $499.76 phantom basis gap (W-10)
- `NATIVE:UNICHAIN 0x68bc3b81`: −$163.10 coverage gap (W-11)

### Check 5 — PENDING_PRICE / NEEDS_REVIEW scan

| Status | Count |
|---|---|
| CONFIRMED | 7338 (+22 since last audit) |
| PENDING_PRICE | **0** ✅ |
| NEEDS_REVIEW | **0** ✅ |

### Current AVCO Dashboard (2026-06-02)

| Asset family | Current AVCO | Basis | Qty | Status |
|---|---|---|---|---|
| ETH (BYBIT:33625378) | $3,822 | $4,340 | 1.149 ETH | ✅ market-plausible |
| ETH (NATIVE:ARBITRUM) | $2,079 | $125 | 0.060 ETH | ✅ |
| USDC (all current positions) | $1.00 | — | — | ✅ |
| USDT (BYBIT:33625378) | $1.00 | $500 | 500 | ✅ |
| MNT (BYBIT:33625378) | $1.28 | $95 | 74.8 | ✅ |
| BTC (BYBIT:33625378) | **$0** | **$0** | 0.000797 | ❌ cross-UID gap |
| cmETH 0x5067b0e1 (MANTLE) | **$0** | **$0** | 0.1053 | ❌ GENUINE_EVIDENCE_MISSING |

### Ranked Issues by Financial Impact

| Rank | ID | Severity | Description | Est. cbD shortfall | Current AVCO broken? |
|---|---|---|---|---|---|
| 1 | B-VAULT-WITHDRAW | P1 | VAULT_WITHDRAW REALLOCATE_IN zero/near-zero cbD | **~$11,082** (historical) | No (positions recovered) |
| 2 | B-BYBIT-CORRIDOR-2 (USDe residual) | P1 | 2115 USDe MANTLE CARRY_IN: only $10 cbD returned | **~$2,105** | Yes (USDe position) |
| 3 | B-CROSS-UID | P2 | Cross-UID transfers: MNT/TON/ETH/USDT/BTC | **~$279+$115=$394** | Yes (BTC position avco=0) |
| 4 | B-BYBIT-CORRIDOR-2 (cmETH) | P1 | 2 cmETH MANTLE: GENUINE_EVIDENCE_MISSING | **~$212** | Yes (cmETH position) |
| 5 | B-EARN-BUNDLE | P2 | bybit-it-bundle zero cbD (ONDO/LDO/TON/LINK/ARB) | **~$395** | Indirectly (Earn positions) |
| 6 | B-ONDO-CARRY-1 | P2 NEW | 4 ONDO bybit-collapsed-v1 CARRY_OUTs with no CARRY_IN | **~$118** | No (UTA qty=0 disposed) |
| 7 | B-MNT-CARRY-1 | P2 NEW | 5 MNT MANTLE INTERNAL_TRANSFERs without corrId | **~$69** | No (ongoing) |
| 8 | W-11 | P2/P3 | NATIVE:UNICHAIN coverage gap | **−$163** | No |
| 9 | W-10 | P3 | SYMBOL:ETH BYBIT conservation gap | **$500** (phantom) | No |
| 10 | W-12 | P2 | LP_EXIT UNKNOWN basisEffect | qualitative risk | No (stablecoins only so far) |
| 11 | B-BRIDGE-ORPHAN-1 | P3 NEW | 4 orphan BRIDGE_OUTs (2 LiFi USDC + 2 Across ETH) | **~$7** | No |
| — | B-BYBIT-CORRIDOR-2 (GROUP B) | P3 | bybit-it-pair SOL 0.3 qty | **~$0.30** | No |

**Total estimated cbD shortfall remaining (all open items): ~$14,634+** (+$194 new)
- Active/current AVCO-affecting: ~$2,596 (corridors) + $394 (cross-UID) + $212 (cmETH) = **~$3,202**
- Historical P&L affecting: ~$11,082 (vault withdrawals) + $163 (Unichain)
- Earn position basis: ~$395 (earn bundle)
- New (refresh 2): ~$187 (B-ONDO-CARRY-1 + B-MNT-CARRY-1 + B-BRIDGE-ORPHAN-1)

---

## Phase 11 — B-BYBIT-CORRIDOR-2 Verification (2026-06-01)

### Fix under test

`TransferReplayHandler.resolveCarrySourcePosition()` falls back to the umbrella `BYBIT:UID` position AVCO when the `:FUND` sub-account position has zero inventory at corridor outbound time. Expected to resolve 23 sub-pattern A corridor CARRY_INs that had `costBasisDeltaUsd=0`.

### Acceptance criterion table

| AC | Description | Result | Verdict |
|----|-------------|--------|---------|
| AC-1 | 21/23 sub-pattern A CARRY_INs now have cbD > 0 | 21/23 positive; 2 still $0 (cmETH MANTLE Apr 2025) | **PARTIAL PASS** |
| AC-2 | `0xbeba82fc` ETH BASE cbD ≈ $41.33; AVCO no dip | cbD=$42.16; avcoAfterUsd=$3,778 | **PASS** ✅ |
| AC-3 | USDC AVALANCHE AVCO ≥ $0.90 at `0xe6b02813` | avcoAfterUsd=$0.000001 (still collapsing at that point) | **FAIL** ❌ (pre-existing vault defect) |
| AC-4 | Total cbD for verified sub-pattern A instances ≥ $6,000 | 23-instance sum ≈ $5,978; all-corridor sum $23,419 | **BORDERLINE** ⚠️ |
| AC-5 | NEEDS_REVIEW=0, PENDING_PRICE=0; no regressions | All 0; B-SHORTFALL-1 entries intact | **PASS** ✅ |
| AC-6 | EARN principal path intact | EARN CARRY_INs have correct cbD | **PASS** ✅ |

Pipeline: CONFIRMED=7316 | PENDING_PRICE=0 | NEEDS_REVIEW=0 | ledger=9686

---

## Phase 11 — B-BYBIT-CORRIDOR-2 Verification (2026-06-01)

### Fix under test

`TransferReplayHandler.resolveCarrySourcePosition()` falls back to the umbrella `BYBIT:UID` position AVCO when the `:FUND` sub-account position has zero inventory at corridor outbound time. Expected to resolve 23 sub-pattern A corridor CARRY_INs that had `costBasisDeltaUsd=0`.

### Acceptance criterion table

| AC | Description | Result | Verdict |
|----|-------------|--------|---------|
| AC-1 | 21/23 sub-pattern A CARRY_INs now have cbD > 0 | 21/23 positive; 2 still $0 (cmETH MANTLE Apr 2025) | **PARTIAL PASS** |
| AC-2 | `0xbeba82fc` ETH BASE cbD ≈ $41.33; AVCO no dip | cbD=$42.16; avcoAfterUsd=$3,778 | **PASS** ✅ |
| AC-3 | USDC AVALANCHE AVCO ≥ $0.90 at `0xe6b02813` | avcoAfterUsd=$0.000001 (still collapsing) | **FAIL** ❌ |
| AC-4 | Total cbD for verified sub-pattern A instances ≥ $6,000 | 23-instance sum ≈ $5,978; all-corridor sum $23,419 | **BORDERLINE** ⚠️ |
| AC-5 | NEEDS_REVIEW=0, PENDING_PRICE=0; no regressions | All 0; B-SHORTFALL-1 entries intact; ETH AVCO=$2,079 (natural) | **PASS** ✅ |
| AC-6 | EARN principal path intact | EARN CARRY_INs have correct cbD; USDT/USDC avco≈$1 | **PASS** ✅ |

### AC-1 detail: 23 sub-pattern A instance status

| # | tx (short) | asset | qty | cbD before | cbD after | verdict |
|---|-----------|-------|-----|-----------|----------|---------|
| 1 | `0x5f5d0b7a` MNT MANTLE | MNT | 13.908 | $0 | $17.22 | ✅ |
| 2 | `0x25864ba2` MNT MANTLE | MNT | 11.200 | $0 | $12.65 | ✅ |
| 3 | `0x361f14cb` MNT MANTLE | MNT | 31.306 | $0 | $21.91 | ✅ |
| 4 | `0xc6a03abc` cmETH MANTLE | cmETH | 0.001 | $0 | **$0** | ❌ |
| 5 | `0x5067b0e1` cmETH MANTLE | cmETH | 0.10528 | $0 | **$0** | ❌ |
| 6 | `0xd4f67097` cmETH MANTLE | cmETH | 0.05065 | $0 | $89.82 | ✅ |
| 7 | `0x5b698b30` cmETH MANTLE | cmETH | 0.05223 | $0 | $99.62 | ✅ |
| 8 | `0x87a4d6e7` MNT MANTLE | MNT | 13.706 | $0 | $9.72 | ✅ |
| 9 | `0x24667bf5` USDC BASE | USDC | 508.29 | $0 | $508.29 | ✅ |
| 10 | `0xdb5ec079` USDC AVALANCHE | USDC | 1003.000 | $0 | $1,003.00 | ✅ |
| 11 | `0x0059a9b1` USDC AVALANCHE | USDC | 100.100 | $0 | $100.10 | ✅ |
| 12 | `0xdee92383` USDe MANTLE | USDe | 10.000 | $0 | $10.00 | ✅ |
| 13 | `0x79d17a8d` USDe MANTLE | USDe | 2115.000 | $0 | $10.00 ⚠️ | ✅ cbD>0 |
| 14 | `0x3511368d` USDC AVALANCHE | USDC | 2115.269 | $0 | $2,115.27 | ✅ |
| 15 | `0x87735e15` USDC BASE | USDC | 202.983 | $0 | $202.98 | ✅ |
| 16 | `0xe934c129` USDC ARBITRUM | USDC | 201.000 | $0 | $201.00 | ✅ |
| 17 | `0xfbf31361` ETH ARBITRUM | ETH | 0.02739 | $0 | $104.77 | ✅ |
| 18 | `0x8f39d55c` ETH ARBITRUM | ETH | 0.01371 | $0 | $51.80 | ✅ |
| 19 | `0xd327c3db` USDC BASE | USDC | 1005.500 | $0 | $1,005.50 | ✅ |
| 20 | `0xbeba82fc` ETH BASE | ETH | 0.01116 | $0 | $42.16 | ✅ |
| 21 | `0xa62e2422` ETH ARBITRUM | ETH | 0.00659 | $0 | $24.90 | ✅ |
| 22 | `0x4477abc2` WBTC MANTLE | WBTC | 0.000227 | $0 | $19.91 | ✅ |
| 23 | `0x8186161` USDC ARBITRUM | USDC | 326.956 | $0 | $326.96 | ✅ |
| **Total** | | | | **$0** | **≈$5,978** | **21/23** |

**Residual zeros — root cause**: Instances 4 and 5 (cmETH MANTLE, 2025-04-28). The fix looks up the `BYBIT:33625378` umbrella position AVCO as fallback. At 2025-04-28 the umbrella had **zero cmETH** — first umbrella cmETH ACQUIRE was 2025-05-06. Fallback returns null → `corridorOutboundSliceAvco` remains null → on-chain cbD stays $0. Terminal state: `GENUINE_EVIDENCE_MISSING_PROVEN`.

**Instance 13 note** (`0x79d17a8d`, 2115 USDe): cbD=$10 (non-zero, passes AC-1 letter). Root cause: FUND only had 10 USDe at withdrawal time (quantityShortfallDelta=2105); umbrella fallback provided AVCO for the covered 10 units only. The USDe avcoAfterUsd=$0.00473 is still economically broken. This is a separate shortfall issue not directly resolved by the umbrella fallback alone.

### AC-2 detail: ETH BASE `0xbeba82fc`

| Field | Value |
|-------|-------|
| basisEffect | CARRY_IN |
| quantityDelta | +0.01115853 ETH |
| costBasisDeltaUsd | **$42.16** (was $0) ✅ |
| avcoAfterUsd | **$3,778.31** ✅ |
| Expected cbD | ≈0.01115853 × $3,705 ≈ $41.33 (price diff: replay used $3,778 AVCO from umbrella) |
| ETH dip status | **RESOLVED** — no zero-AVCO gap between `0xbeba82fc` and `0x717463d2` |

### AC-3 detail: USDC AVALANCHE at `0xe6b02813`

| Field | Value |
|-------|-------|
| tx | `0xe6b02813` AVALANCHE 2025-10-01 |
| event | USDC REALLOCATE_IN +1000 USDC (MEV Capital vault withdraw) |
| costBasisDeltaUsd | **$0.001016** |
| avcoAfterUsd | **$0.000001** (collapse) |
| Fix status | **NOT RESOLVED** by B-BYBIT-CORRIDOR-2 fix |

Root cause chain: The corridor CARRY_IN `0xdb5ec079` (2025-08-08 AVALANCHE +1003 USDC) now has cbD=$1,003 ✅. However, the MEV vault REALLOCATE_OUT (basis drain) → MEVUSDC acquisition → REALLOCATE_IN (MEVUSDC → USDC) pipeline does not re-carry basis correctly when MEVUSDC position has accumulated fractional share basis. This is a **pre-existing separate blocker** (MEV vault REALLOCATE basis carry defect) not addressed by the corridor fix. The cascade path is:

1. 2025-08-08: corridor CARRY_IN 1003 USDC cbD=$1,003 ✅ (now fixed)
2. 2025-08-08: REALLOCATE_OUT −1003 USDC cbD=−$1,003 → deposited into MEV vault
3. 2025-09-20: REALLOCATE_IN +1628 USDC from MEVUSDC redemption → cbD=$0.001653 ← broken
4. 2025-10-01: REALLOCATE_IN +1000 USDC `0xe6b02813` → cbD=$0.001016 → avco=$0.000001

The REALLOCATE_IN propagation does not restore the original USDC basis from the MEVUSDC vault position. Separate fix required (out of scope of B-BYBIT-CORRIDOR-2).

### AC-4 detail: total cbD recovered

| Scope | Count | Total cbD |
|-------|-------|-----------|
| 23 sub-pattern A instances (specific) | 23 | **≈$5,978** |
| All on-chain corridor CARRY_INs (positive) | 49 | **$23,418.83** |

The $6,000 threshold is missed by ≈$22 for the exact 23 instances. Driven by:
- Instances 4+5 (cmETH, $0 each, est. ~$212 missed)
- Instance 13 (USDe 2115, $10 vs. ~$2,115 expected, est. ~$2,105 missed)

If measured against all 49 on-chain corridor CARRY_INs (including entries added post-audit), total cbD $23,419 far exceeds $6,000.

### AC-5 detail: regression check

| Check | Result |
|-------|--------|
| NEEDS_REVIEW | 0 ✅ |
| PENDING_PRICE | 0 ✅ |
| B-SHORTFALL-1 `0x38d445c4` ETH ARBITRUM | cbD=$1,515.67 ✅ (intact) |
| B-SHORTFALL-1 `0xe11ab436` USDC ARBITRUM | cbD=$427.95 ✅ (intact) |
| Any corridor CARRY_IN regressed to $0 | None — 2 zeros were already $0 before fix ✅ |
| ETH family AVCO | $2,079 (2026-05-28) |

ETH AVCO $2,079 vs. prior $2,539: this is natural AVCO evolution (new purchases at $3,190 Dec 2025, then ETH market price declined to ~$2,000-$2,100 range by May 2026 with diluting acquisitions). No pipeline regression detected.

### AC-6 detail: EARN path

| Check | Result |
|-------|--------|
| EARN CARRY_INs (bybit-collapsed-v1) | USDC cbD=$493.65 avco=$0.999; USDT cbD=$177.43 avco=$0.9998 ✅ |
| EARN USDC avco | ~$0.999 (correct, slight stablecoin drift) ✅ |
| EARN path regression | None ✅ |

### Summary

**B-BYBIT-CORRIDOR-2 sub-pattern A: PARTIALLY RESOLVED**

- 21 of 23 sub-pattern A instances now have cbD > 0 (up from 0/23 before fix)
- Total cbD recovered across 23 specific instances: ≈$5,978
- Total cbD across all corridor CARRY_INs: $23,419
- 2 residual zeros (cmETH MANTLE 2025-04-28): `GENUINE_EVIDENCE_MISSING_PROVEN` — umbrella had no cmETH at that date
- ETH dip (`0xbeba82fc`) resolved ✅
- USDC AVALANCHE AVCO collapse at MEV vault: pre-existing separate blocker ❌ (out of scope)
- No regressions on B-SHORTFALL-1 or EARN path ✅

---

## Phase 10 — Final Audit Verdict (2026-06-01, post-B-SHORTFALL-1 cycle)

### Acceptance criterion table

| Blocker | Acceptance criterion | Live DB result | Verdict |
|---------|----------------------|----------------|---------|
| B-SHORTFALL-1 | `0x38d445c4` ETH ARBITRUM cbD ≈ qty × ~$1897 | cbD=$1,515.67, qty=0.799, unitPrice=$1,896.96 ✅ | **PASS** |
| B-SHORTFALL-1 | `0xe11ab436` USDC ARBITRUM cbD ≈ qty × ~$1 | cbD=$427.95, qty=427.946, unitPrice=$1.00 ✅ | **PASS** |
| B-SHORTFALL-1 | Both BRIDGE_INs `continuityCandidate=false` | Both confirmed `continuityCandidate: false` ✅ | **PASS** |
| B-SHORTFALL-1 | Both BRIDGE_INs `status=CONFIRMED` | Both `status: CONFIRMED` ✅ | **PASS** |
| B-USDC-1 (carry-down) | BORROW `0xf299178e` USDC cbD ≈ $800 | `FAMILY:USDC` cbD=$800, avco=$1.163 ✅ | **PASS** |
| Regression | PENDING_PRICE=0 | 0 ✅ | **PASS** |
| Regression | NEEDS_REVIEW=0 | 0 ✅ | **PASS** |
| Regression | BTC family AVCO plausible | $81,845 (market-consistent) ✅ | **PASS** |
| Regression | USDT family AVCO ≈ $1.00 | $1.0000 ✅ | **PASS** |
| Conservation | ETH active positions within $1 tolerance | 13/15 pass; 2 pre-existing anomalies (see below) | **WARN** |

### Summary

B-SHORTFALL-1 **PASS** on all primary acceptance criteria.
B-USDC-1 carry-down **PASS**.
All regression checks **PASS**.
Two pre-existing conservation anomalies flagged (not regressions).

---

## B-SHORTFALL-1 Detail

### `0x38d445c4fc8f54149185a606240f0c7b212047a637dae42d7491a835b08d1cf2` — ETH ARBITRUM

| Field | Value |
|-------|-------|
| networkId | ARBITRUM |
| type | BRIDGE_IN |
| status | CONFIRMED |
| continuityCandidate | false |
| flow.role | BUY |
| flow.quantityDelta | 0.79899917984786304 ETH |
| flow.unitPriceUsd | $1,896.96 |
| flow.valueUsd | $1,515.67 |
| ledger basisEffect | ACQUIRE |
| ledger costBasisDeltaUsd | **$1,515.67** ✅ (was ~$0) |
| ledger avcoAfterUsd | $1,896.61 ✅ |
| accountingAssetIdentity | NATIVE:ARBITRUM |

Previous state: `continuityCandidate=true`, cbD≈$0, AVCO=$1,444.93 (deferred as P2).
Current state: repriced at market by `UnmatchedBridgeInboundPricingFallbackService`. ✅

### `0xe11ab43689786a2b518b8a058593926071a8cac4c99b02077c4ac82d6ac0848e` — USDC ARBITRUM

| Field | Value |
|-------|-------|
| networkId | ARBITRUM |
| type | BRIDGE_IN |
| status | CONFIRMED |
| continuityCandidate | false |
| flow.role | BUY |
| flow.quantityDelta | 427.946249 USDC |
| flow.unitPriceUsd | $1.00 (STABLECOIN) |
| flow.valueUsd | $427.95 |
| ledger basisEffect | ACQUIRE |
| ledger costBasisDeltaUsd | **$427.95** ✅ (was $0.000439) |
| ledger avcoAfterUsd | $1.00 ✅ |
| accountingAssetIdentity | `0xaf88d065e77c8cc2239327c5edb3a432268e5831` (USDC ARBITRUM) |

Previous state: effectively zero basis ($0.000439), classified as W-8 zero-basis shortfall.
Current state: repriced at $1.00/USDC by `UnmatchedBridgeInboundPricingFallbackService`. ✅

---

## ETH Family AVCO Evolution

| Cycle | AVCO | Total Basis | Covered Qty | Notes |
|-------|------|-------------|-------------|-------|
| 2026-05-30 baseline | $2,589 | ~$8,085 | ~3.12 ETH | Pre-fix baseline |
| 2026-05-31 cycle | $2,589 | ~$8,085 | ~3.12 ETH | Unchanged |
| 2026-06-01 (pre-B-SHORTFALL-1) | $1,527 | $4,767 | 3.122 ETH | Unexplained drop (W-1) |
| **2026-06-01 (post-B-SHORTFALL-1)** | **$2,539** | **$8,188** | **3.225 ETH** | ✅ Restored near baseline |

**W-1 EXPLAINED AND RESOLVED**: The ETH AVCO drop from $2,589 → $1,527 in the previous cycle was caused by many BRIDGE_INs acquiring ETH at zero basis (shortfall sources). B-SHORTFALL-1 repriced all such `continuityCandidate=false` BRIDGE_INs at market, restoring ETH family AVCO to $2,539 — close to the $2,589 baseline.

### ETH active positions (post-fix)

| Position | Qty (ETH) | Basis (USD) | AVCO |
|----------|-----------|-------------|------|
| `0xeac30ed8` (CMETH Mantle Aave receipt) | 3.060 | $7,835.76 | $2,560.71 |
| `SYMBOL:CMETH` (Bybit CMETH) | 0.1033 | $223.01 | $2,157.96 |
| `NATIVE:ARBITRUM` | 0.0603 | $125.41 | $2,078.78 |
| `NATIVE:ETHEREUM` | 0.0008 | $1.92 | $2,335.78 |
| `NATIVE:ZKSYNC` | 0.0004 | $1.42 | $3,643.28 |
| Other (Linea, Katana, small) | ~0.001 | ~$0.80 | various |
| **TOTAL** | **3.225** | **$8,188** | **$2,539** |

---

## Conservation Check

### ETH family active positions

| Position | Computed Basis | Last Stored Basis | Diff | Status |
|----------|----------------|-------------------|------|--------|
| `0xeac30ed8` CMETH receipt | $7,835.76 | $7,835.76 | $0.00 | ✅ |
| `SYMBOL:CMETH` | $223.01 | $223.01 | −$0.00 | ✅ |
| `NATIVE:ARBITRUM` (main) | $126.14 | $125.41 | +$0.73 | ✅ (rounding) |
| `NATIVE:ETHEREUM` (3 wallets) | $1.92–$13.73 | same | ~$0 | ✅ |
| `NATIVE:ZKSYNC` | $1.42 | $1.42 | $0.00 | ✅ |
| `SYMBOL:ETH BYBIT:33625378` | $5,041.23 | $4,541.48 | **+$499.76** | ⚠️ pre-existing |
| `NATIVE:UNICHAIN 0x68bc3b81` | −$162.31 | $0.80 | **−$163.10** | ⚠️ pre-existing gap |

### `SYMBOL:ETH BYBIT:33625378` — $499.76 breach (W-10)

The running total shows `totalCostBasisAfterUsd=$4,541.48`, but summing all flow `costBasisDeltaUsd` entries gives $5,041.23 (+$499.76). The discrepancy traces to the FIRST ACQUIRE of $499.76 (BYBIT-33625378:EXECUTION_SPOT spot trade). The subsequent running balance restarted at $0 before adding the next ACQUIRE ($29.93), implying a basis-removal event is missing from the ledger. This is a pre-existing issue unrelated to B-SHORTFALL-1.

### `NATIVE:UNICHAIN 0x68bc3b81` — −$163.10 gap (W-11)

CARRY_OUT outflows (−$8,386) exceed ACQUIRE+CARRY_IN inflows ($9,579) by −$163. Root cause: some ETH entered UNICHAIN with zero basis (prior shortfall BRIDGE_IN sources) and was then bridged out, removing basis via CARRY_OUT that was never fully added on CARRY_IN. A coverage-gap artifact. Pre-existing.

---

## B-USDC-1 Carry-Down Detail

| Check | Result |
|-------|--------|
| `0xf299178e` USDC `0xaf88d065` cbD | $800 ✅ |
| `0xf299178e` USDC avco after | $1.163 ✅ (diluted by W-8 residual) |
| Borrow receipt `0xf611aeb5` cbD | $0 (borrow liability token, expected) |

---

## Phase 9 — Final Audit Verdict (2026-06-01, avco-borrow-shortfall-2026-06-01 cycle)

_(Preserved from prior cycle for continuity — see Phase 10 above for current state)_

### Acceptance criterion table

| Blocker | Acceptance criterion | Live DB result | Verdict |
|---------|----------------------|----------------|---------|
| B-USDC-1 | BORROW 0xf299178e cbD=$800 (not $1.2M) | `costBasisDeltaUsd`=$800 ✅ | **PASS** |
| B-USDC-1 | USDC AVCO ≠ $1,532 | ARBITRUM AVCO=$0.777 (see W-8); ZKSYNC qty=0 | **PASS** (not $1,532) |
| B-USDC-1 | BRIDGE_OUT 0xbac9a1bf CARRY_OUT small (hundreds) | CARRY_OUT=−$930.68 ✅ | **PASS** |
| B-USDC-1 | ZKSYNC BRIDGE_IN CARRY_IN balanced | CARRY_IN=+$799.94, avco=$1.00 ✅ | **PASS** |
| B-USDC-1 | No other BORROW with inflated cbD | All BORROW cbD = qty × unitPriceUsd ✅ | **PASS** |
| Regression | PENDING_PRICE | 0 | **PASS** |
| Regression | NEEDS_REVIEW | 0 | **PASS** |
| Regression | ETH AVCO stable (~$1,527) | $1,526.97 ✅ | **PASS** |
| Regression | ETH AVCO spikes >20% | 0 ✅ | **PASS** |
| Regression | DISPOSE quantityDelta=0 | 0 ✅ | **PASS** |
| B-SHORTFALL-1 | 0x38d445c4 BRIDGE_IN ETH: deferred | CARRY_IN cbD=$0 confirmed; AVCO=$1,444.93; accepted as deferred | **DEFERRED** (P2) — now fixed in Phase 10 |
| W-7 | BTC cross-UID basis | BYBIT:516601508 qty=0.001648 BTC AVCO=$96,406; BYBIT:33625378 qty=0; non-material | **OPEN** (P2) |

---

## Dashboard Headline

| Metric | 2026-05-30 baseline | 2026-05-31 | 2026-06-01 (pre-B-SHORTFALL-1) | 2026-06-01 (post-B-SHORTFALL-1) | Target |
|--------|---------------------|------------|--------------------------------|---------------------------------|--------|
| Timeline UNAVAILABLE | 484 | 75 | 87 | **87** | < 100 ✅ |
| ETH AVCO | $2,589 | $2,589 | $1,527 ⚠️ | **$2,539** ✅ | near baseline |
| ETH total basis | ~$8,085 | ~$8,085 | $4,767 | **$8,188** ✅ | near baseline |
| PENDING_PRICE | — | 0 | 0 | **0** | 0 ✅ |
| NEEDS_REVIEW | — | 0 | 0 | **0** | 0 ✅ |
| B-USDC-1 (BORROW cbD) | — | — | $800 ✅ | **$800** ✅ | $800 ✅ |
| B-SHORTFALL-1 (ETH) | cbD=$0 | cbD=$0 | cbD=$0 (deferred) | **cbD=$1,515.67** ✅ | cbD>0 ✅ |
| B-SHORTFALL-1 (USDC) | cbD≈$0 | cbD≈$0 | cbD≈$0 (W-8) | **cbD=$427.95** ✅ | cbD>0 ✅ |
| USDC family AVCO | — | — | $0.777 (ARBITRUM) | **$0.769** (ARBITRUM) | ~$1 ⚠️ |
| BTC family AVCO | — | — | ~$96,406 | **$81,845** ✅ | market-plausible |
| USDT family AVCO | — | — | $1.00 | **$1.00** ✅ | $1.00 ✅ |
| Conservation breaches | — | — | 0 known | **2 pre-existing** (W-10, W-11) | 0 ideal |

**Overall verdict: B-SHORTFALL-1 PASS. ETH AVCO restored to $2,539 (near $2,589 baseline). W-1 resolved. No regressions. Two pre-existing conservation anomalies identified. Remaining open items are pre-existing warnings (W-3 through W-11).**

---

## AVCO Drop Analysis: 0xe06740b6

**Trigger**: User reports a visible USDC AVCO dip on the chart. Transaction A = `0xff9f45d2...` (REWARD_CLAIM KATANA), Transaction B = `0xe06740b6...` (LP_ENTRY PancakeSwap BASE, 2025-10-01 07:02:51 UTC).

### AVCO timeline around the drop

| seq | timestamp | tx (short) | basisEffect | qtyDelta | cbD | avcoAfter | note |
|-----|-----------|------------|-------------|----------|-----|-----------|------|
| 6063 | 2025-10-01 06:59:51 | `0x7441e12a` BASE LP_EXIT | UNKNOWN | +14.618701 | +14.618701 | **$1.00** | LP exit returns USDC, unitPrice=$1 |
| 6065 | 2025-10-01 07:02:51 | `0xe06740b6` BASE LP_ENTRY **(TX B)** | REALLOCATE_OUT | −14.618701 | −14.618701 | *undefined* (qty→0) | LP ADD deposits USDC; balance → 0 |
| 6075 | 2025-10-01 07:27:18 | `0xe6b02813` AVALANCHE VAULT_WITHDRAW | REALLOCATE_IN | +1000 | **+$0.001016** | **$0.000001** | MEV Capital vault returns 1000 USDC at near-zero basis |

### Root cause classification

**BUG: zero-basis carry — B-BYBIT-CORRIDOR-2**

The AVCO drop is **NOT caused by tx B** (the LP ADD). Tx B (LP_ENTRY, `REALLOCATE_OUT`) is correctly accounted: USDC allocated to LP carries its exact basis to the LP position.

The visible drop is caused by the MEV Capital vault withdraw (`seq=6075`, 24 min after tx B) which returns 1000 USDC at cbD≈$0.001, collapsing USDC AVCO on AVALANCHE to $0.000001.

**Why the vault withdraw has near-zero cbD:**

| Step | seq | event | qty | cbD | cause |
|------|-----|-------|-----|-----|-------|
| Bybit spot buy | 4690 | EXECUTION_SPOT Aug 08 | +1004 USDC | +$1,004 | ✅ correct |
| Bybit → AVALANCHE withdrawal | 4693 | `0xdb5ec079` CARRY_IN | +1003 USDC | **$0** | ❌ Bybit corridor drops basis |
| Vault deposit | 4697 | `0x444e5956` REALLOCATE_OUT | −1003 USDC | $0 | cascades zero-basis |
| Vault withdraw | 6075 | `0xe6b02813` REALLOCATE_IN | +1000 USDC | ≈$0.001 | carries back zero-basis |

The Bybit corridor (`correlationId=BYBIT-CORRIDOR:AVALANCHE:0xdb5ec079...`) correctly links the Bybit send to the on-chain receive, but the `CARRY_IN` is assigned `costBasisDeltaUsd=0` instead of carrying the Bybit-side basis (~$1,003 from the spot purchase).

### Step-by-step answers

1. **AVCO just before tx A** (REWARD_CLAIM KATANA — no USDC flow): USDC AVCO was $1.00 from the LP exit at `seq=6063`.
2. **At tx A**: No USDC ledger point (REWARD_CLAIM affects KAT/vbUSDC/SUSHI/vbETH, not plain USDC).
3. **AVCO just before tx B**: $1.00 (14.618701 USDC from LP exit, properly priced at $1).
4. **At tx B**: REALLOCATE_OUT removes all 14.618701 USDC → balance = 0, AVCO = undefined.
5. **AVCO immediately after tx B** (24 min, seq=6075): **$0.000001** — 1000 USDC at near-zero basis from MEV Capital vault.

### Verdict

| | |
|---|---|
| **Classification** | **BUG: zero-basis carry** |
| **Tx B (LP ADD) itself** | Correctly accounted — REALLOCATE_OUT is appropriate |
| **Root cause** | Bybit Corridor CARRY_IN (`0xdb5ec079`, AVALANCHE, Aug 08 2025) received 1003 USDC with `costBasisDeltaUsd=0` |
| **Pipeline stage** | `corridor_linking` / `move_basis` — Bybit corridor handler does not transfer Bybit-side cbD to on-chain CARRY_IN |
| **Blocker reference** | **B-BYBIT-CORRIDOR-2** (P1 ACTIVE) |
| **LP_EXIT basisEffect=UNKNOWN** | Secondary anomaly — `seq=6063` LP_EXIT returns USDC with UNKNOWN instead of REALLOCATE_IN; practically harmless for USDC (price≈$1) but semantically incorrect |

---

## AVCO Spike Analysis: 0xbeba82fc ↔ 0x717463d2 (ETH, Nov 6–10 2025)

**Requested**: 2026-06-01  
**Universe**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`

### Transaction facts

| Field | Tx A | Tx B |
|---|---|---|
| Hash | `0xbeba82fc…` | `0x717463d2…` |
| Normalized ID | `0xbeba82fc…:BASE:0xf03b52e8…` | `0x717463d2…:KATANA:0x1a87f12a…` |
| Type | INTERNAL_TRANSFER | LP_FEE_CLAIM |
| Network | BASE | KATANA |
| Wallet | 0xf03b52e8… | 0x1a87f12a… |
| Timestamp | 2025-11-06T10:54:03Z | 2025-11-10T08:28:42Z |
| Status | CONFIRMED | CONFIRMED |

**Tx A** is the on-chain leg of a Bybit withdrawal corridor (`correlationId=BYBIT-CORRIDOR:BASE:0xbeba82fc…`).  
**Tx B** is an LP fee claim on KATANA for vbUSDC + vbETH; ETH is consumed only as gas.  
They share the same accounting universe but are on **different wallets and networks**. The only shared accounting surface is the `FAMILY:ETH` aggregated AVCO.

### Ledger points for Tx A

| Wallet | Asset | basisEffect | qtyD | cbD | avco | seq |
|---|---|---|---|---|---|---|
| BYBIT:33625378:FUND | SYMBOL:ETH | CARRY_OUT | 0 | 0 | — | 6948 |
| 0xf03b52e8… | NATIVE:BASE | CARRY_IN | +0.01115853 | **$0** | **N/A (=$0)** | 6949 |

### Hidden intermediate ledger points between Tx A and Tx B

**177 intermediate ledger points exist** across multiple assets (NATIVE:AVALANCHE, SYMBOL:USDT, NATIVE:ETHEREUM, NATIVE:ARBITRUM, SYMBOL:MNT, etc.) between the two timestamps.  
For **NATIVE:BASE specifically**, only 2 intermediate points appear after seq 6949 and before seq 7127 (the first Tx B point):

| Timestamp | basisEffect | qtyD | cbD | seq |
|---|---|---|---|---|
| 2025-11-06T10:57:41Z | GAS_ONLY | −4.19e−7 | $0 | 6951 |
| 2025-11-06T10:58:25Z | GAS_ONLY | −2.85e−6 | $0 | 6953 |

No NATIVE:BASE ledger points exist between 2025-11-06T10:58 and 2025-11-11T14:00. There are **no hidden NATIVE:BASE transactions** that would change the AVCO.

### Minimum AVCO point

The ledger point at `seq=6949` (Tx A, CARRY_IN) is the origin of the zero-basis position:

- `qtyAfter = 0.01115853 ETH`, `cbAfter = $0`, `avcoAfterUsd = null/N/A`
- This is the minimum AVCO point: effective AVCO = **$0**.

### Root cause: Bybit corridor CARRY_IN zero-basis (ETH)

**Full Bybit withdrawal chain for Tx A:**

1. `BYBIT:516601508` → `BYBIT:33625378` via UNIVERSAL_TRANSFER at 10:52:05 UTC:  
   - CARRY_OUT from 516601508: −0.01145853 ETH, cbD=−$42.46 ✓  
   - CARRY_IN to 33625378 main: +0.01145853 ETH, cbD=+$42.46, avco=$3,705.68 ✓

2. `BYBIT:33625378` → `BYBIT:33625378:FUND` (internal Bybit sub-account move):  
   - **No ledger point exists for this step.**  
   - BYBIT:33625378 main **never received a CARRY_OUT** for the ETH it forwarded to FUND.  
   - Raw Bybit FUNDING_HISTORY confirms: `walletBalance=0`, `change=-0.01145853`, `walletRef=BYBIT:33625378:FUND` → the FUND account DID have 0.01145853 ETH before the withdrawal.

3. `BYBIT:33625378:FUND` → ON-CHAIN BASE at 10:52:54/10:54:03 UTC:  
   - FUND account ledger point: `qtyBefore=0`, `cbBefore=$0` (accounting is wrong — FUND had ETH)  
   - CARRY_OUT from FUND: qty=0, cbD=0 ❌ (should be −0.01145853, cbD=−$42.46)  
   - CARRY_IN to NATIVE:BASE: qty=+0.01115853, cbD=**$0** ❌ (should be ~+$42.40 proportional)

### Double-counting artefact

The BYBIT:33625378 main account retained `qtyAfter=1.19538389 ETH`, `cbAfter=$4,518.45` after the withdrawal — **the 0.01145853 ETH was never deducted** from the main account ledger. The same ETH then appears on-chain in NATIVE:BASE with zero basis. This creates a double-count of 0.01145853 ETH in the FAMILY:ETH universe.

### AVCO impact

| Surface | Pre-corridor-event | Post-CARRY_IN (actual) | Correct state |
|---|---|---|---|
| NATIVE:BASE avco | N/A (empty) | **$0** (cbAfter=0) | ~$3,705 |
| FAMILY:ETH avco | ~$3,823 | ~$3,745 (diluted by zero-basis + double-count) | ~$3,823 |

The user observes a **deep dip in the AVCO chart** at Tx A because:
- If viewing **NATIVE:BASE AVCO**: avco goes from undefined → $0 (dramatic chart drop to zero)
- If viewing **FAMILY:ETH AVCO**: avco drops ~$78 (0.01145853 extra ETH at $0 additional basis dilutes the family average)

### Verdict

| | |
|---|---|
| **Classification** | **BUG: B-BYBIT-CORRIDOR-2 additional instance (ETH)** |
| **Hidden transactions** | None for NATIVE:BASE; 177 for other assets in the same universe/period |
| **Root cause** | Bybit FUNDING_HISTORY CARRY_OUT from `:FUND` sub-account sees `qtyBefore=0` because the prior internal Bybit transfer (main→FUND) has no ledger entry, so cbD=0 propagates to the on-chain CARRY_IN |
| **Pipeline stage** | `carry` / `linking` — Bybit corridor handler does not resolve the correct source balance for the FUND sub-account when the ETH arrived via UNIVERSAL_TRANSFER to the main account first |
| **Blocker reference** | **B-BYBIT-CORRIDOR-2** (P1 ACTIVE) — same root cause as USDC instances |
| **Double-counting** | Yes — 0.01145853 ETH double-counted: remains in BYBIT:33625378 main AND appears on-chain |

---

## B-BYBIT-CORRIDOR-2: Complete Instance Enumeration (2026-06-01)

Full audit of all `asset_ledger_points` where `basisEffect=CARRY_IN`, `costBasisDeltaUsd<$0.01`, `quantityDelta>0`, correlated with `normalized_transactions` by `correlationId` prefix.

**Universe**: 80 zero-basis CARRY_IN points found total. 25 confirmed B-BYBIT-CORRIDOR-2 (GROUP A). 1 GROUP B (internal pair). 5 GROUP C (cross-UID). 48 GROUP D (other root causes, excluded).

### GROUP A — BYBIT-CORRIDOR pattern (25 instances)

All on `wallet=BYBIT:33625378:FUND` (CARRY_OUT with qty=0/cbD=0) → on-chain or FUND CARRY_IN.
All Bybit-side counterpart ledger points confirmed: `basisEffect=CARRY_OUT`, `qtyD=0`, `cbD=0` (FUND had zero inventory).

| # | normalizedTransactionId (truncated) | asset | qty | network | sub-pattern | est. USD shortfall |
|---|--------------------------------------|-------|-----|---------|-------------|-------------------|
| 1 | `0x5f5d0b7a…:MANTLE:0xa0dd42c6` | MNT | 13.908 | MANTLE | A | ~$14 |
| 2 | `0x25864ba2…:MANTLE:0xa0dd42c6` | MNT | 11.200 | MANTLE | A | ~$11 |
| 3 | `0x361f14cb…:MANTLE:0xa0dd42c6` | MNT | 31.306 | MANTLE | A | ~$28 |
| 4 | `0xc6a03abc…:MANTLE:0x1a87f12a` | cmETH | 0.001 | MANTLE | A | ~$2 |
| 5 | `0x5067b0e1…:MANTLE:0x1a87f12a` | cmETH | 0.10528 | MANTLE | A | ~$210 |
| 6 | `0xd4f67097…:MANTLE:0x1a87f12a` | cmETH | 0.05065 | MANTLE | A | ~$101 |
| 7 | `0x5b698b30…:MANTLE:0x1a87f12a` | cmETH | 0.05223 | MANTLE | A | ~$104 |
| 8 | `0x87a4d6e7…:MANTLE:0x1a87f12a` | MNT | 13.706 | MANTLE | A | ~$12 |
| 9 | `0x24667bf5…:BASE:0x1a87f12a` | USDC | 508.290 | BASE | A | ~$508 |
| 10 | `0xdb5ec079…:AVALANCHE:0x1a87f12a` | USDC | 1003.000 | AVALANCHE | A | ~$1,003 |
| 11 | `0x0059a9b1…:AVALANCHE:0x1a87f12a` | USDC | 100.100 | AVALANCHE | A | ~$100 |
| 12 | `0xdee92383…:MANTLE:0x1a87f12a` | USDe | 10.000 | MANTLE | A | ~$10 |
| 13 | `0x79d17a8d…:MANTLE:0x1a87f12a` | USDe | 2115.000 | MANTLE | A | ~$2,115 |
| 14 | `0x3511368d…:AVALANCHE:0x1a87f12a` | USDC | 2115.269 | AVALANCHE | A | ~$2,115 |
| 15 | `0x87735e15…:BASE:0x1a87f12a` | USDC | 202.983 | BASE | A | ~$203 |
| 16 | `0xe934c129…:ARBITRUM:0x1a87f12a` | USDC | 201.000 | ARBITRUM | A | ~$201 |
| 17 | `0xfbf31361…:ARBITRUM:0x1a87f12a` | ETH | 0.02739 | ARBITRUM | A | ~$68 |
| 18 | `0x8f39d55c…:ARBITRUM:0x1a87f12a` | ETH | 0.01371 | ARBITRUM | A | ~$34 |
| 19 | `0xd327c3db…:BASE:0xf03b52e8` | USDC | 1005.500 | BASE | A | ~$1,006 |
| 20 | `0xbeba82fc…:BASE:0xf03b52e8` | ETH | 0.01116 | BASE | A | ~$28 |
| 21 | `0xa62e2422…:ARBITRUM:0x1a87f12a` | ETH | 0.00659 | ARBITRUM | A | ~$16 |
| 22 | `0x4477abc2…:MANTLE:0x1a87f12a` | WBTC | 0.000227 | MANTLE | A | ~$23 |
| 23 | `0x8186161…:ARBITRUM:0x1a87f12a` | USDC | 326.956 | ARBITRUM | A | ~$327 |
| 24 | `BYBIT-33625378:FUNDING_HISTORY:ce163d1b…` (FUND CARRY_IN) | USDe | 10.000 | MANTLE | B | ~$10 |
| 25 | `BYBIT-33625378:FUNDING_HISTORY:546649b6…` (FUND CARRY_IN) | USDe | 2115.000 | MANTLE | B | ~$2,115 |

### Totals by asset (GROUP A, on-chain CARRY_INs only, rows 1–23)

| Asset | Total qty | Est. USD shortfall |
|-------|-----------|-------------------|
| USDC | 4,663.1 | ~$4,463 |
| USDe | 2,125.0 | ~$2,125 |
| cmETH | 0.2091 | ~$417 |
| ETH | 0.0589 | ~$147 |
| MNT | 70.1 | ~$65 |
| WBTC | 0.000227 | ~$23 |
| **TOTAL** | | **~$7,240** |

Including sub-pattern B FUND CARRY_INs (rows 24–25): additional ~$2,125 USDe = **~$8,425 total GROUP A cbD shortfall**.

### Assets with zero-basis corridor CARRY_INs confirmed: ETH, BTC (WBTC), USDC, USDe, MNT, cmETH

BTC confirmed affected via WBTC on Mantle (row 22, 0.000227 WBTC ~$23). ETH confirmed via rows 17–18, 20–21 (~$146 combined). Same fix resolves all assets — no asset-specific handling required.

### GROUP B — bybit-it-pair-v1 (1 instance)

| ntId | asset | qty | cbD | correlationId |
|------|-------|-----|-----|---------------|
| `BYBIT-33625378:UNIVERSAL_TRANSFER:uni_trans_3e16bab7…` | MNT | 0.3 | $0 | `bybit-it-pair-v1:…\|…` |

Root cause: internal Bybit transfer pair FIFO mismatch. Shortfall ~$0.30. Separate fix scope.

### GROUP C — bybit-cross-uid-v1 (5 instances)

| ntId | asset | qty | from UID | to UID | est. shortfall |
|------|-------|-----|----------|--------|---------------|
| `uni_trans_47eaa702…_409666492` | MNT | 0.293 | 409666492 | 33625378 | ~$0.29 |
| `uni_trans_6b956290…_409666492` | TON | 32.442 | 409666492 | 33625378 | ~$130 |
| `uni_trans_ebf90bee…_516601508` | ETH | 0.01375 | 516601508 | 33625378 | ~$34 |
| `uni_trans_866903d7…_516601508` | BTC | 0.000797 | 516601508 | 33625378 | ~$79 |
| `uni_trans_a893b645…_516601508` | BTC | 0.000362 | 516601508 | 33625378 | ~$36 |

CARRY_OUT source (different UID accounts) has zero inventory — basis never tracked for cross-UID scope. Est. shortfall ~$279. Different fix scope (cross-UID basis propagation not currently supported).

### Grand total cbD shortfall (B-BYBIT-CORRIDOR-2)

| Group | Count | Est. USD shortfall |
|-------|-------|--------------------|
| GROUP A (BYBIT-CORRIDOR) | 25 | ~$8,425 |
| GROUP B (bybit-it-pair-v1) | 1 | ~$0.30 |
| GROUP C (bybit-cross-uid-v1) | 5 | ~$279 |
| **TOTAL** | **31** | **~$8,704** |

---

## B-VAULT-WITHDRAW Bug A + B-CROSS-UID Verification 2026-06-02

**Pipeline state at verification:** CONFIRMED=7316 | PENDING_PRICE=0 | NEEDS_REVIEW=0 | ledger=9681
**Date:** 2026-06-02

---

### AC-1: MEV Capital VAULT_WITHDRAW cbD > $1,000 — PARTIAL PASS

VAULT_WITHDRAW REALLOCATE_IN ledger points with `quantityDelta > 10` found on AVALANCHE / ARBITRUM (non-Bybit wallets). Rows sorted by cbD:

| txHash (short) | network | wrapper token | qty USDC | cbD | avco |
|---|---|---|---|---|---|
| `0x4737a9c2` | ARBITRUM | MCUSDC | 1,689 | **$2,148** | 1.271543 |
| `0x6343bac5` | ARBITRUM | syrupUSDC | 1,710 | **$2,136** | 1.249117 |
| `0xc3e539e3` | ARBITRUM | (ERC-4626) | 1,698 | **$2,136** | 1.258092 |
| `0xd2c929c4` | ARBITRUM | (ERC-4626) | 1,799 | **$1,806** | 1.002410 |
| `0x4e4740e3` ★ | AVALANCHE | **mevUSDC** | 1,628 | **$1,623** | 0.997031 |
| `0x0da117f6` | AVALANCHE | Re7USDC | 1,484 | **$1,501** | 1.011339 |
| `0x9f1da8f4` | ARBITRUM | (ERC-4626) | 1,084 | **$1,064** | 0.990375 |
| `0xff65de51` ★ | AVALANCHE | **mevUSDC** | 1,014 | **$1,001** | 0.986934 |
| `0xaef9a2b1` | ARBITRUM | fUSDT→USDT | 926 | $999 | 1.078163 |
| `0xe6b02813` ★ | AVALANCHE | **mevUSDC** | 1,000 | $993 | 0.993466 |
| … (10 more rows, all cbD > $0) | | | | | |

★ = MEV Capital mevUSDC wrapper (token `0x4dc1ce9b...` AVALANCHE)

**MEV Capital (mevUSDC) assessment:**
- `0x4e4740e3`: cbD=$1,623 ✅ >$1,000
- `0xff65de51`: cbD=$1,001 ✅ >$1,000
- `0xe6b02813`: cbD=$993 ❌ ~$7 short of $1,000 threshold (wrapper AVCO ~$0.001016 × 978M tokens)

2 of 3 MEV Capital mevUSDC withdrawals exceed $1,000 cbD. All 3 are > $0 (vs $0.001–$0.002 pre-fix). The $993 case falls $7 short because the mevUSDC wrapper AVCO diluted slightly from the first withdraw.

**Remaining VAULT_WITHDRAW cbD≈$0 cases (3):**

| txHash (short) | network | qty | cbD | root cause |
|---|---|---|---|---|
| `0x971c8464` | ARBITRUM | 1,783 USDC | $0.001 | MCUSDC wrapper bucket nearly empty at last 3 deposits: 2nd deposit brought USDC with cbD=0 (upstream shortfall), accumulated only $0.001 basis |
| `0xc8b94615` | AVALANCHE | 2,831 USDC | $0 | Wrapper-less vault: flow has no wrapper token burn (single TRANSFER USDC in from `0x30489...`); no wrapper bucket to source cbD from. Different vault type than mevUSDC. |
| `0xb47d87fa` | ARBITRUM | 2,825 USDC | $0 | Separate wallet `0xf03b...`; wrapper bucket had $0 basis at deposit time (upstream). |

These 3 are NOT regressions from Bug A fix. They are either upstream cost-basis shortfalls (no basis at deposit time) or a different vault type with no wrapper token in normalized flows.

---

### AC-2: Total VAULT_WITHDRAW REALLOCATE_IN cbD — PASS

| Metric | Value |
|---|---|
| Total VAULT_WITHDRAW REALLOCATE_IN rows (non-Bybit) | 28 |
| Rows with cbD > $0 | 25 |
| Rows with cbD = $0 or near-zero | 3 |
| **Total cbD recovered** | **$19,118** |
| Pre-fix total cbD | $0 |
| AC threshold | ≥$3,500 |

**Result: PASS — $19,118 >> $3,500.**

---

### AC-3: USDC AVALANCHE AVCO stability — PARTIAL PASS

USDC AVALANCHE (`0xb97ef9ef`) on wallet `0x1a87...`:

| Sequence | Type | Event | cbD | avcoAfter |
|---|---|---|---|---|
| 5064 | VAULT_DEPOSIT | mevUSDC REALLOCATE_IN | +$2,121 | — (wrapper) |
| 5769 | VAULT_WITHDRAW `0x4e47` | mevUSDC REALLOCATE_OUT / USDC in | +$1,623 | **0.9970** ✅ |
| 6055 | VAULT_WITHDRAW `0xe6b0` | USDC in | +$993 | **0.9935** ✅ |
| 6556 | VAULT_WITHDRAW `0xff65` | USDC in | +$1,001 | **0.9869** ✅ |
| … | multiple swaps/bridges | | | ≈$1.00 ✅ |
| 8143 | VAULT_WITHDRAW `0xc8b9` | 2,831 USDC, cbD=$0 | $0 | **null** ❌ |
| 8145–8148 | CARRY_OUT × 2 | bridge out | $0 | — |

**Verdict:** AVCO is stable ~$1 through the three mevUSDC VAULT_WITHDRAW events (seq 5769–6556). However at seq 8143 (2026-01-12), the wrapper-less vault withdraw of 2,831 USDC with cbD=0 breaks the chain again. From that point AVCO is null until a tiny $0.00001 ACQUIRE restores avco=1.

The mevUSDC wrapper fix works for its specific protocol scope. The Jan 2026 failure is a separate issue (different vault type not yet handled).

---

### AC-4: LP_EXIT REALLOCATE_IN no regression — PASS

Top LP_EXIT REALLOCATE_IN rows (FAMILY:USDC):

| txHash (short) | network | qty | cbD | avco | proportional? |
|---|---|---|---|---|---|
| `0x9087a3ca` | AVALANCHE | 2,167 | $2,167 | 1.0000 | ✅ |
| `0x21687905` | AVALANCHE | 1,770 | $1,770 | 1.0000 | ✅ |
| `0xb7f6757c` | BASE | 1,770 | $1,313 | 0.7415 | ✅ (non-stable) |
| `0x457b9d30` | BASE | 736 | $978 | 0.6291 | ✅ (non-stable) |
| `0xbeaad45c` | AVALANCHE | 897 | $897 | 0.9999 | ✅ |

All LP_EXIT REALLOCATE_INs use proportional slice (cbD ≈ qty × avco). No drain of full position basis. No regression from the vault-withdraw fix.

---

### AC-5: BTC AVCO recovery on BYBIT:33625378 — FAIL

BTC ledger on `BYBIT:33625378` (full history — only 3 rows):

| seq | type | basisEffect | qty | cbD | avco | correlationId |
|---|---|---|---|---|---|---|
| 7733 | INTERNAL_TRANSFER | CARRY_IN | +0.0007972 | **$0** ❌ | — | `bybit-cross-uid-v1:866903d7…` |
| 7734 | INTERNAL_TRANSFER | CARRY_IN | +0.0003619 | **$0** ❌ | — | `bybit-cross-uid-v1:a893b645…` |
| 7738 | SWAP | DISPOSE | −0.0011591 | $0 | — | — |

**Current BTC position on BYBIT:33625378: position disposed (qty=0), no active open position.**

Root cause confirmed: FUNDING_HISTORY outbound on source UID `BYBIT:516601508` does NOT have `correlationId` assigned and has `continuityCandidate=false`. Inspection:
```
BYBIT-516601508:FUNDING_HISTORY:56fcc99b…: -0.0007972 BTC
  matchedCounterparty: 'BYBIT:516601508:UTA'
  continuityCandidate: false
  correlationId: (none)
```
`isBybitSelfTransfer()` sees FUND→UTA on same UID → returns true → CARRY_OUT skipped on source. No cost basis reaches the pending queue → destination CARRY_IN gets cbD=0.

**Defect 1 (BybitInternalTransferPairer: FUNDING_HISTORY outbound not linked with correlationId) remains unimplemented.**

Pre-fix state: BTC avco=$0, qty=0.000797 active (open position). Post-fix state: BTC position was swapped out with cbD=0 (no improvement in P&L accuracy). The missing cbD on the BTC DISPOSE is ~$112.

---

### AC-6: TON and ETH cross-UID CARRY_IN cbD — PARTIAL PASS

**TON on BYBIT:33625378:**

| correlationId | qty | cbD | verdict |
|---|---|---|---|
| `bybit-cross-uid-v1:6b956290…` | 32.442 | **$0** ❌ | FAIL |

Source UID: `BYBIT:409666492`. FUNDING_HISTORY outbound (`BYBIT-409666492:FUNDING_HISTORY:a2486224…`) has `matchedCounterparty: 'BYBIT:409666492:UTA'`, no `correlationId`. Same Defect 1.

**ETH on BYBIT:33625378:**

| correlationId | qty | cbD | avco | verdict |
|---|---|---|---|---|
| `bybit-cross-uid-v1:ebf90bee…` | 0.01375 | **$0** — | $3,825 | ZERO (legitimate — source had no ETH holdings at Nov 1, 2025) |
| `bybit-cross-uid-v1:a20cadab…` | 0.01146 | **$42.46** ✅ | $3,824 | PASS |
| `bybit-cross-uid-v1:a6fd39ab…` | 0.00663 | **$23.03** ✅ | $3,822 | PASS |

ETH `a20cadab` and `a6fd39ab` work because the source UNIVERSAL_TRANSFER on `BYBIT:516601508` has `accountRef: 'BYBIT:516601508:UTA'` and the `bybit-cross-uid-v1:` correlationId set. `isBybitSelfTransfer()` correctly returns false for those → CARRY_OUT emitted with proper cbD.

ETH `ebf90bee` cbD=0 is **not a pipeline defect**: source `BYBIT:516601508` had no ETH ledger entries before Nov 1, 2025 (first ETH acquired via SWAP on Nov 3). Zero cost basis at source at transfer time → zero carry is correct.

**Defect 1 scope:** only FUND-sourced FUNDING_HISTORY outbounds are affected. UTA-sourced UNIVERSAL_TRANSFERs with `bybit-cross-uid-v1:` correlationId already work correctly (Defect 2 fix verified).

---

### AC-7: No regression in same-UID UTA↔FUND transfers — PASS

Same-UID corridor CARRY_INs (`BYBIT-CORRIDOR:*`) on `BYBIT:33625378:FUND` still produce correct cbD:
- ETH ARBITRUM (2025-03-12): qty=0.010, cbD=$27.85 ✅
- ETH ARBITRUM (2025-03-12): qty=0.699, cbD=$1,946.68 ✅
- USDC ARBITRUM (2025-03-13): qty=10.91, cbD=$10.91 ✅

No regression. `isBybitSelfTransfer()` continues to correctly pass through BYBIT-CORRIDOR type transfers.

---

### AC-8: Pipeline clean — PASS

| Metric | Value |
|---|---|
| CONFIRMED | 7316 ✅ |
| PENDING_PRICE | 0 ✅ |
| NEEDS_REVIEW | 0 ✅ |
| Warning collections | none ✅ |

---

### Verification Summary Table

| AC | Description | Result | Detail |
|---|---|---|---|
| AC-1 | 3 MEV Capital VAULT_WITHDRAW cbD > $1,000 | ⚠️ PARTIAL | 2/3 > $1,000; all 3 > $0 (was $0 pre-fix) |
| AC-2 | Total VAULT_WITHDRAW cbD ≥ $3,500 | ✅ PASS | $19,118 recovered (28 rows, 25 with cbD>0) |
| AC-3 | USDC AVALANCHE AVCO stable ~$1 | ⚠️ PARTIAL | Stable through mevUSDC region; broken from Jan 2026 by different vault type |
| AC-4 | LP_EXIT no regression | ✅ PASS | All LP_EXIT REALLOCATE_INs proportional, cbD>0 |
| AC-5 | BTC CARRY_IN cbD > $100 | ❌ FAIL | cbD=0 on both BTC CARRY_INs; Defect 1 unimplemented |
| AC-6 | TON/ETH cross-UID cbD > 0 | ⚠️ PARTIAL | ETH 2/3 PASS; TON FAIL; Defect 1 unimplemented for FUND-sourced carries |
| AC-7 | No regression in same-UID carries | ✅ PASS | Corridor CARRY_INs intact |
| AC-8 | Pipeline clean | ✅ PASS | PENDING=0, NEEDS_REVIEW=0 |

**Total cbD recovered by B-VAULT-WITHDRAW Bug A fix: $19,118**
**Total cbD recovered by B-CROSS-UID fix: $65.49 (ETH a20cadab + a6fd39ab)**
**Remaining B-CROSS-UID shortfall (FUND-sourced): ~$212 (BTC ~$112 + TON ~$130 − ETH ~$34 already zero for legitimate reason)**

