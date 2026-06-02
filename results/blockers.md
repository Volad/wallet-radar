# Confirmed Blockers — AVCO Audit 2026-06-02 (full audit cycle, refresh 2)

Universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
Pipeline state: CONFIRMED=7338 (+22) | PENDING_PRICE=0 | NEEDS_REVIEW=0 | ledger=9694 (+8)

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
| `0x971c8464` | ARBITRUM | 1,783 USDC | $0.001 | MCUSDC wrapper: 2nd and 3rd deposits brought USDC with cbD≈$0 (upstream shortfall at deposit time). Wrapper correctly reflects what was deposited. |
| `0xc8b94615` | AVALANCHE | 2,831 USDC | $0 | Wrapper-less vault (only a single TRANSFER USDC inflow, no wrapper token burn in flows). Different vault type — not handled by Bug A fix. Separate scope. |
| `0xb47d87fa` | ARBITRUM | 2,825 USDC | $0 | Wallet `0xf03b...`: wrapper bucket had $0 basis at deposit time (upstream). |

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
- `SYMBOL:BTC` `BYBIT:33625378`: **position disposed** (swapped 2025-12-12, qty=0). No longer active. Historical P&L impact: ~$112 missing cbD on the DISPOSE event.

---

## B-CROSS-UID — P2 — Cross-UID basis propagation (Bybit multi-UID scope)

**Severity**: P2
**Status**: PARTIALLY RESOLVED — UTA-sourced cross-UID carries now work; FUND-sourced carries remain broken (Defect 1 unimplemented)
**Verified**: 2026-06-02

### Post-fix state

**Working (Defect 2 fixed — `isBybitSelfTransfer` correlationId guard active):**

| correlationId (short) | asset | source | qty | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:a20cadab…` | ETH | BYBIT:516601508:UTA | 0.01146 | **$42.46** | ✅ FIXED |
| `bybit-cross-uid-v1:a6fd39ab…` | ETH | BYBIT:516601508:UTA | 0.00663 | **$23.03** | ✅ FIXED |

Works because source UNIVERSAL_TRANSFER has `accountRef: 'BYBIT:516601508:UTA'` and `bybit-cross-uid-v1:` correlationId → `isBybitSelfTransfer()` returns false → CARRY_OUT emitted with correct cbD.

**Still broken (Defect 1 unimplemented — FUNDING_HISTORY outbound lacks correlationId):**

| correlationId (short) | asset | source sub-acct | FUNDING_HISTORY corrId | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:866903d7…` | BTC | BYBIT:516601508:FUND | ❌ none | **$0** | ❌ FAIL |
| `bybit-cross-uid-v1:a893b645…` | BTC | BYBIT:516601508:FUND | ❌ none | **$0** | ❌ FAIL |
| `bybit-cross-uid-v1:6b956290…` | TON | BYBIT:409666492:FUND | ❌ none | **$0** | ❌ FAIL |

FUNDING_HISTORY outbound records: `continuityCandidate=false`, no `correlationId`. `isBybitSelfTransfer()` sees FUND→UTA (same UID) → returns true → CARRY_OUT skipped. `BybitInternalTransferPairer.findFundingHistoryOutbound()` / correlationId assignment step was not implemented.

**Not a pipeline defect (legitimate zero):**

| correlationId (short) | asset | reason |
|---|---|---|
| `bybit-cross-uid-v1:ebf90bee…` | ETH | Source BYBIT:516601508 had no ETH in ledger before Nov 1, 2025. First ETH acquisition was Nov 3 via SWAP. Zero cost basis at source at transfer time — correct. |

### Current position impact

`SYMBOL:BTC BYBIT:33625378`: position was fully DISPOSED (SWAP) on 2025-12-12 with cbD=0 (qty now=0). No longer an active open position, but the SWAP's P&L calculation is off by ~$112 (the missing BTC basis). Historical P&L impact only.

### Remaining shortfall (FUND-sourced Defect 1)

| asset | qty | est. missing cbD |
|---|---|---|
| BTC (×2 carries) | 0.001159 | ~$112 |
| TON | 32.442 | ~$97 (at $3/TON) |
| **Total** | | **~$209** |

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
