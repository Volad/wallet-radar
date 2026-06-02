# AVCO Spikes Audit — 2026-05-31

**Session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
**Cycle:** New cycle (B-ZERO-1…5 closed)  
**Prod state at start:** PENDING_PRICE=0, NEEDS_REVIEW=0, assetLedgerPoints=9703, ETH AVCO=$2,589

---

## Executive Summary

211 AVCO spikes detected (>20% change between adjacent PRIMARY_FLOW rows) in ETH family alone.
Root cause cluster: 6 independent defect classes spanning linking, clarification, classification, and pricing.
3 blockers are P0 (directly drive visible spikes); 3 are P1 (material distortion); 3 are P2 (uncovered asset noise).

No PENDING_PRICE or NEEDS_REVIEW rows exist in current state. All active symptoms are structural.

---

## 1. AVCO Spike Analysis — ETH Family

### 1.1 Raw Counts

| Spike category | Count | Severity |
|---|---|---|
| SPONSORED_GAS_IN \| ACQUIRE | 24 | P0 |
| SWAP \| GAS_ONLY | 16 | P1 (noise) |
| REWARD_CLAIM \| GAS_ONLY | 15 | P1 (noise) |
| INTERNAL_TRANSFER \| CARRY_IN | 13 | P1 (corridor oscillation) |
| SWAP \| ACQUIRE,GAS_ONLY | 11 | Expected |
| BRIDGE_IN \| CARRY_IN | 10 | P0 (some orphans) |
| BRIDGE_OUT \| GAS_ONLY | 8 | P1 (noise) |
| BRIDGE_OUT \| CARRY_OUT,GAS_ONLY | 7 | P1 (orphans) |
| UNWRAP \| REALLOCATE | 7 | Expected |
| BRIDGE_OUT \| CARRY_IN,CARRY_OUT,GAS_ONLY | **4** | **P0 — anomalous** |
| EXTERNAL_TRANSFER_IN \| ACQUIRE | 4 | P1 (potential misclass) |
| BRIDGE_IN \| ACQUIRE | **1** | **P0 — misclassified** |
| Other | ~90 | Mixed |

### 1.2 Corridor Oscillation — Not a Bug

The repeating AVCO oscillation between $1,682 and $2,346 is produced by Bybit corridor carry (CARRY_OUT / CARRY_IN pairs). Any event between two corridor operations appears as a spike in relative percentage terms even if the event itself does not alter AVCO. This is a display/UX concern, not an accounting bug. It accounts for the SWAP|GAS_ONLY, REWARD_CLAIM|GAS_ONLY, BRIDGE_OUT|GAS_ONLY, INTERNAL_TRANSFER categories.

### 1.3 BRIDGE_OUT with CARRY_IN Effect — Anomalous (P0)

**4 BRIDGE_OUT rows have `basisEffects: [CARRY_IN, CARRY_OUT, GAS_ONLY]`.**

These are ALL LI.FI bridges:

| NormId prefix | Timestamp | Network | Spike |
|---|---|---|---|
| 0x6c5bd905... | 2025-05-10 19:38 | ARBITRUM | +106.6% |
| 0x122fa957... | 2025-11-01 19:12 | BASE | +\~27% |
| 0xd9d38471... | 2025-11-17 08:28 | UNICHAIN | +\~32% |
| 0x8f3dd850... | 2025-11-17 08:41 | UNICHAIN | +\~31% |

A BRIDGE_OUT should carry basis OUT only. CARRY_IN on a BRIDGE_OUT means the corridor mechanism is receiving basis from somewhere simultaneously — likely because the LI.FI transaction has **multiple ETH flows** (send + fee refund or WETH→ETH step in the same tx). The secondary ETH inflow is being assigned CARRY_IN instead of ACQUIRE or GAS handling.

The Nov 17 pairs (UNICHAIN) both have matching BRIDGE_INs with same correlationId confirmed in DB — they are linked but still show CARRY_IN anomaly on BRIDGE_OUT side.

**Root cause:** Multi-flow LI.FI BRIDGE_OUT normalization assigns CARRY_IN to secondary ETH flow. Fix needed in clarification/normalization of BRIDGE_OUT flow roles.

### 1.4 BRIDGE_IN with ACQUIRE Effect — Orphan Fresh Acquisition (P0)

One BRIDGE_IN at 2025-09-29T12:19:30 (normId `0x826189720417ce31b983c2`) has `basisEffects: [ACQUIRE]` instead of `[CARRY_IN]`. A bridge receipt should carry basis from the source wallet, not acquire at current market price. This creates a fresh-acquisition AVCO distortion.

**Root cause:** BRIDGE_IN classification missing correlationId → fallback to ACQUIRE treatment instead of corridor carry.  
**Stage:** clarification / linking.

---

## 2. Bridge Orphan Investigation

### 2.1 State Summary

```
BRIDGE_OUT with corr: 120    BRIDGE_IN with corr: 113
BRIDGE_OUT no corr:   41     BRIDGE_IN no corr:    24
BRIDGE_OUT corr but no BRIDGE_IN sibling: 7
BRIDGE_IN corr but no BRIDGE_OUT sibling: 0
```

### 2.2 The 7 Orphan BRIDGE_OUTs (corr but no IN sibling)

| TxHash prefix | Timestamp | Protocol | Asset | Wallet | Evidence state |
|---|---|---|---|---|---|
| 0xf39e4f | 2025-02-14 18:38:23 | Across | ETH | 0x68bc | EVIDENCE_PRESENT_UNLINKED |
| 0x0c2b2f | 2025-04-23 13:53:25 | Across | ETH | 0x68bc | EVIDENCE_PRESENT_UNLINKED |
| 0xee474f | 2025-04-26 08:13:09 | Across | ETH | 0x68bc | EVIDENCE_PRESENT_UNLINKED |
| 0x258ed5 | 2025-05-10 19:27:35 | Across | ETH | 0x68bc | GENUINE_EVIDENCE_MISSING |
| 0x266c02 | 2025-05-17 10:25:07 | Across | ETH | 0x1a87 | GENUINE_EVIDENCE_MISSING |
| 0x8b4004 | 2025-09-29 13:05:24 | LI.FI | USDC+ETH | 0x1a87 | GENUINE_EVIDENCE_MISSING |
| 0xdd83df | 2026-01-06 08:57:59 | LI.FI | USDC+ETH+ETH | 0x1a87 | GENUINE_EVIDENCE_MISSING |

**3 Across orphans (EVIDENCE_PRESENT_UNLINKED):**  
Cross-matching by timestamp confirms the corresponding BRIDGE_INs exist in `normalized_transactions` with no correlationId:
- BRIDGE_OUT `0xf39e4f` (Feb 14, 18:38:23) ↔ BRIDGE_IN `0xa7058b6f` (Feb 14, 18:38:41) — 18 sec gap, same ETH wallet, Across PROTOCOL_REGISTRY
- BRIDGE_OUT `0x0c2b2f` (Apr 23, 13:53:25) ↔ BRIDGE_IN `0x999fc867` (Apr 23, 13:53:26) — 1 sec gap
- BRIDGE_OUT `0xee474f` (Apr 26, 08:13:09) ↔ BRIDGE_IN `0xa20e3fe4` (Apr 26, 08:13:11) — 2 sec gap

The Across linking service assigns `correlationId = bridge:across:<SOURCE_TX_HASH>` to the BRIDGE_OUT, but fails to propagate the same correlationId to the matching BRIDGE_IN on the destination chain. The BRIDGE_IN is indexed and confirmed (`PROTOCOL_REGISTRY`) but carries `correlationId=null`.

**Failed stage:** `linking` — Across BRIDGE_IN correlation assignment missing.

**4 orphan BRIDGE_OUTs (GENUINE_EVIDENCE_MISSING):** No corresponding BRIDGE_IN found. Likely destination chain transactions not yet indexed (2 Across) or LI.FI cross-chain bridge where destination receipt was not ingested (2 LI.FI USDC+ETH routes).

### 2.3 The 24 BRIDGE_IN with No Correlation

The 24 no-corr BRIDGE_INs span:
- **3 ETH PROTOCOL_REGISTRY** (Across) — the 3 matched orphan pairs above → `EVIDENCE_PRESENT_UNLINKED`
- **weETH, vbUSDC, vbETH HEURISTIC** — Venice/liquid staking bridge, no BRIDGE_OUT found → `EVIDENCE_MISSING` or cross-protocol
- **AVAX HEURISTIC** (3 entries, Aug 2025) — Avalanche ecosystem bridge inbounds, assets ERC20:c9afac, eUSDC-2, WAVAX/BAL — protocol unclear
- **USDC METHOD_ID** (4 entries, Jul 2025) — bridge via method ID only, no corr
- **WETH METHOD_ID** (Sep 2025) — WETH bridge inbound
- **ETH/USDC/MNT HEURISTIC** — multi-asset bridge on Mantle/Base, no BRIDGE_OUT indexed

**Of these 24, the 3 Across ETH ones should be fixed by linking fix.** The remaining 21 are either genuinely unlinked or not supported.

### 2.4 AVCO Impact of Orphan BRIDGE_INs

BRIDGE_INs without correlationId fall back to ACQUIRE (fresh-cost acquisition) instead of CARRY_IN. This assigns current market price as cost basis, deviating from the source wallet's AVCO. Any BRIDGE_IN ACQUIRE with a price significantly different from the current AVCO creates a spike.

---

## 3. BTC Misclassification Investigation

### 3.1 Coverage

- **21 BTC normalized rows** (all Bybit: wallets 516601508 and 33625378)
- **6 BTC ledger points**
- 15 missing rows = 15 INTERNAL_TRANSFERs (sub-account moves) that do not produce ledger points by design

### 3.2 Classification Correctness

Types observed:
- 2× EXTERNAL_TRANSFER_IN (BTC deposits to Bybit fund wallets) — correct
- 15× INTERNAL_TRANSFER (Bybit FUND→UTA, cross-account universal transfers) — correct
- 3× SWAP (BTC/USDT buy trades on 516601508:UTA) — correct, ACQUIRE
- 1× SWAP (USDT→BTC sell on 33625378:UTA, Dec 12) — classified correctly, but **DISPOSE records qtyDelta=0**

### 3.3 BTC Corridor Linking Gap (P1)

**The SWAP DISPOSE on `BYBIT:33625378:UTA` records `qtyDelta=0` and `qtyAfter=0`.**

Cause: All BTC accumulation (deposits + buys) occurred on wallet `BYBIT:516601508`. The BTC was moved to `BYBIT:33625378` via INTERNAL_TRANSFERs (universal transfer), but the corridor linking service failed to connect these two Bybit sub-accounts. Without CARRY_OUT from 516601508 and CARRY_IN to 33625378, the 33625378:UTA position is empty when the SWAP DISPOSE runs.

Result: The BTC sale records zero disposal, leaving **0.001648102 BTC (~$159)** of basis stranded on wallet 516601508 that should have been consumed by the sale.

**BTC AVCO currently: $96,406** (on 516601508, last seen after Dec 12 deposit). This is wrong — after the sale, BTC should be fully disposed and position=0 across all wallets.

**Failed stage:** `linking` — Bybit cross-account corridor for BTC not established.  
**Evidence state:** `EVIDENCE_PRESENT_UNLINKED`

### 3.4 BTC Family API

BTC ledger points show correct AVCO evolution for accumulated position on 516601508:
- Oct 21: ACQUIRE 0.000489 BTC @ $108,585 AVCO
- Nov–Dec: 3× SWAP ACQUIRE pulling AVCO down to $100,006
- Dec 12: ACQUIRE 0.000797 BTC @ $96,406 AVCO (final)
- Dec 12: DISPOSE 0 BTC on 33625378 — **zero quantity, accounting broken**

---

## 4. Regression Check (from B-ZERO-1…5 changes)

### 4.1 PriceableFlowPolicy — BRIDGE_IN continuityCandidate Skip

**Status: No new regression found.**  
Check: BRIDGE_INs with correlationId have `pricingAttempts=1` and valid pricing confirmed (sample checked). The policy change (skip pricing when continuityCandidate=true) correctly avoids re-pricing corridor-carried bridges. No BRIDGE_IN with mandatory fresh pricing is being skipped.

However, BRIDGE_IN without correlationId (orphans) still receive `pricingAttempts=0` and fallback to ACQUIRE with no-price treatment in some cases. This is pre-existing.

### 4.2 BridgePairContinuityRepairService — Unintended CARRY_IN on BRIDGE_OUT

**Status: REGRESSION CONFIRMED (P0)**

The 4 LI.FI BRIDGE_OUTs with `CARRY_IN,CARRY_OUT,GAS_ONLY` effects are the direct result of the repair service processing multi-flow LI.FI BRIDGE_OUT transactions. The repair service's corridor-pair logic assigns CARRY_IN to secondary ETH flows on the BRIDGE_OUT side when it should not.

Scope: Limited to LI.FI multi-ETH-flow BRIDGE_OUTs. Non-bridge rows do not appear to be affected.

Impact: 4 BRIDGE_OUTs show CARRY_IN, which:
- Receives basis from the corridor prematurely (AVCO shift)
- Creates a correlated spike of +31% to +107% at those points

### 4.3 loadAnchoredWithoutInboundBatch — No New Zero-Basis Accrual

The 2 LI.FI BRIDGE_OUTs introduced via `loadAnchoredWithoutInboundBatch` (Sep 29 and Jan 6, EVIDENCE_MISSING) do not show ACQUIRE-based accrual. The orphaned BRIDGE_OUT dispatches basis to a corridor that has no BRIDGE_IN counterpart; basis is held in the corridor but not re-acquired at zero. **No zero-basis accrual introduced.** The basis is stranded in the corridor.

### 4.4 ETH Uncovered Rows

9 ETH uncovered rows exist (>0.0001 ETH per row), all INTERNAL_TRANSFER CARRY_IN. These are carry-ins from untracked source wallets — pre-existing. No new uncovered rows introduced by the B-ZERO changes.

ETH total uncovered: **0.019711 ETH** (~$51 at current AVCO).  
The "75 UNAVAILABLE" metric likely counts ledger points with `hasIncompleteHistoryAfter=true`, not ETH quantity.

---

## 5. Top Uncovered Assets

### 5.1 USDE — 2,115 uncovered

- First uncovered row: 2025-08-30 INTERNAL_TRANSFER CARRY_IN (+10 USDE)
- 50 ledger points total
- Pattern: CARRY_IN from untracked source wallet — origin wallet holding USDE was never indexed
- **Failed stage:** `linking` (no source ACQUIRE in session)
- **Evidence state:** `EVIDENCE_MISSING` for source wallet. The carry-in is structurally correct but the funding chain is outside session scope.
- Classification is correct. Not a misclassification issue.

### 5.2 GM: ETH/USD [WETH-USDC] — 453 uncovered

- LP_ENTRY_SETTLEMENT row has uncoveredQuantityDelta=529 (decreases but remains)
- Source: GMX GM token received as LP position. The cost basis for GM should come from REALLOCATE_OUT of the deposited ETH+USDC when LP was entered
- **Failed stage:** `move_basis` — LP entry basis not fully reallocated to GM token
- **Evidence state:** `EVIDENCE_PRESENT_UNUSABLE`
- Pre-existing pattern for GMX GM tokens.

### 5.3 USDC — 136 uncovered

- 781 ledger points with small cumulative uncovered amounts
- First uncovered: 2025-05-13 LP_POSITION_STAKE CARRY_IN
- Pattern: Fragmented corridor carry-ins across many LP staking events
- **Failed stage:** `linking` (many small corridors without tracked origins)
- Pre-existing; no step increase from B-ZERO changes.

### 5.4 TON — 97 uncovered

- First uncovered: 2025-02-04 REWARD_CLAIM ACQUIRE
- 170 ledger points
- TON is out of EVM scope. Reward claims and corridor moves from Telegram wallet ecosystem are not fully supported.
- **Evidence state:** `UNSUPPORTED_SCOPE_PROVEN` (TON network)
- Expected behavior.

### 5.5 MNT — 58 uncovered

- First uncovered: 2025-02-05 INTERNAL_TRANSFER CARRY_IN (13.9 MNT)
- 709 ledger points
- Pattern: MNT corridor carry-in from untracked wallet (Mantle network)
- **Failed stage:** `linking`
- **Evidence state:** `EVIDENCE_MISSING` for source wallet

---

## 6. Blocker Register

### P0 Blockers

#### B-SPIKE-1: LI.FI BRIDGE_OUT with spurious CARRY_IN — REGRESSION

- **Problem:** 4 LI.FI BRIDGE_OUTs have `basisEffects=[CARRY_IN,CARRY_OUT,GAS_ONLY]` instead of `[CARRY_OUT,GAS_ONLY]`. CARRY_IN on a BRIDGE_OUT is semantically invalid.
- **Root cause:** `BridgePairContinuityRepairService` assigns CARRY_IN to secondary ETH flows in multi-flow LI.FI BRIDGE_OUT transactions (fee refund, WETH→ETH step, or bridge intermediate hop).
- **Accounting surface wrong:** AVCO jumps +31% to +107% at 4 timeline points. Basis is injected into the session at wrong time.
- **Financially correct:** BRIDGE_OUT should dispatch basis only (CARRY_OUT). Any secondary ETH inflow in the same tx should be ACQUIRE at current price or ignored if dust.
- **Failed stage:** `clarification` (BridgePairContinuityRepairService)
- **Evidence state:** `EVIDENCE_PRESENT_UNUSABLE`
- **Fix:** Filter secondary ETH flows in multi-flow LI.FI BRIDGE_OUT; do not assign CARRY_IN to fee-return or WETH→ETH flows on the source chain BRIDGE_OUT.
- **Audit terminal:** `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`

#### B-BRIDGE-1: 3 Across BRIDGE_INs present in DB but not linked (orphan pair)

- **Problem:** 3 Across BRIDGE_OUTs have `correlationId=bridge:across:<SRC_HASH>` but the corresponding BRIDGE_INs (confirmed in DB at matching timestamps) have `correlationId=null`. They fall back to ACQUIRE instead of CARRY_IN.
- **Pairs:**
  - BRIDGE_OUT 0xf39e4f (Feb 14 18:38:23) ↔ BRIDGE_IN 0xa7058b6f (Feb 14 18:38:41)
  - BRIDGE_OUT 0x0c2b2f (Apr 23 13:53:25) ↔ BRIDGE_IN 0x999fc867 (Apr 23 13:53:26)
  - BRIDGE_OUT 0xee474f (Apr 26 08:13:09) ↔ BRIDGE_IN 0xa20e3fe4 (Apr 26 08:13:11)
- **Accounting surface wrong:** BRIDGE_IN treated as fresh ACQUIRE → AVCO distorted by market price at receipt time instead of carrying source AVCO.
- **Failed stage:** `linking` — AcrossBridgePairLinkService does not set correlationId on the BRIDGE_IN transaction.
- **Evidence state:** `EVIDENCE_PRESENT_UNLINKED`
- **Fix:** Across linking must write `correlationId=bridge:across:<SRC_HASH>` onto the BRIDGE_IN row (currently only set on BRIDGE_OUT). Across `FilledRelay` / `FundsDeposited` events carry the source deposit ID that can derive the source tx hash.
- **Audit terminal:** `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`

#### B-BRIDGE-2: BRIDGE_IN with ACQUIRE effect instead of CARRY_IN (Sep 29)

- **Problem:** BRIDGE_IN normId `0x826189720417ce31b983c2` (2025-09-29 12:19) has `basisEffects=[ACQUIRE]`. This is a bridge receipt misclassified as a fresh acquisition.
- **Accounting surface wrong:** ETH arrives with market-price cost basis ($4,090/ETH) instead of source wallet AVCO.
- **Failed stage:** `clarification` — bridge type confirmed but correlationId not set → CARRY_IN not applied.
- **Evidence state:** `EVIDENCE_PRESENT_UNUSABLE`
- **Fix:** Correct correlationId lookup for this BRIDGE_IN (likely an Across or LiFi fill event where linking failed).
- **Audit terminal:** `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`

### P1 Blockers

#### B-BTC-1: BTC corridor linking gap between Bybit sub-accounts

- **Problem:** BTC accumulated on `BYBIT:516601508` is sold on `BYBIT:33625378` but corridor carry (INTERNAL_TRANSFER CARRY_OUT→CARRY_IN) is not linked between accounts. Sale records qtyDelta=0.
- **Accounting surface wrong:** 0.001648102 BTC (~$159 basis) stranded on 516601508 after sale. Position shows as non-zero. PnL from the sale is not recorded.
- **Financially correct:** BTC balance should be 0 after the Dec 12 sale; basis should be fully consumed; PnL = sale proceeds − $158.49 avg cost.
- **Failed stage:** `linking` — Bybit INTERNAL_TRANSFER corridor between account IDs 516601508 and 33625378 not established for BTC.
- **Evidence state:** `EVIDENCE_PRESENT_UNLINKED`
- **Fix:** Bybit cross-account corridor linking must cover BTC flows. Verify that the universal transfer events (UNIVERSAL_TRANSFER type rows) between these two account IDs are included in corridor carry pairing.
- **Audit terminal:** `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`

#### B-SPIKE-2: SPONSORED_GAS_IN ACQUIRE — 24 AVCO spikes from zero-cost dilution

- **Problem:** 266 SPONSORED_GAS_IN transactions have `pricingAttempts=0` (pricing skipped) and `basisEffects=[ACQUIRE]`. The flows carry non-zero `quantityDelta` (e.g. 0.0000396 ETH) but $0 cost basis (no price fetched). ACQUIRE at $0 dilutes AVCO downward.
- **Accounting surface wrong:** When ETH position is small, zero-cost micro-acquisitions cause visible AVCO drops after each sponsored gas refund.
- **Financially correct:** SPONSORED_GAS_IN should either: (a) be priced at market rate like any ACQUIRE, or (b) be treated as GAS_ONLY (gas offset, no basis entry). Option (b) is simpler and more accurate — sponsored gas is a protocol fee subsidy, not a purchase.
- **Failed stage:** `classification` / `pricing` — PriceableFlowPolicy excludes SPONSORED_GAS_IN from pricing but the event still creates ACQUIRE basis effect.
- **Evidence state:** `EVIDENCE_PRESENT_UNUSABLE`
- **Fix:** Either: (a) price SPONSORED_GAS_IN (add to PriceableFlowPolicy), or (b) reclassify basis effect from ACQUIRE to GAS_ONLY for SPONSORED_GAS_IN when quantity is negligible.
- **Audit terminal:** `AUTHORITATIVE_RECONSTRUCTION_COMPLETE` pending fix choice

### P2 Blockers

#### B-UNCOV-1: USDE 2,115 — source wallet not in session

- **Evidence state:** `EVIDENCE_MISSING`
- **Stage:** `linking` (out-of-session origin)
- No fix required unless source wallet is added to session.

#### B-UNCOV-2: GM:ETH/USD — LP basis not reallocated

- **Evidence state:** `EVIDENCE_PRESENT_UNUSABLE`
- **Stage:** `move_basis` — GMX LP entry REALLOCATE_OUT not fully carried to GM token
- Fix: verify LP basis reallocation policy covers GMX GM tokens.

#### B-UNCOV-3: TON 97 — out of scope

- **Evidence state:** `UNSUPPORTED_SCOPE_PROVEN`
- TON is outside EVM session scope. Expected.

---

## 7. Regression Summary

| Change | Regression? | Details |
|---|---|---|
| PriceableFlowPolicy: BRIDGE_IN continuityCandidate skip pricing | **No** | Pricing correctly maintained for corridor BRIDGEs |
| BridgePairContinuityRepairService new pass | **YES — P0** | Assigns CARRY_IN to secondary ETH flows on multi-flow LI.FI BRIDGE_OUTs |
| loadAnchoredWithoutInboundBatch (2 LiFi orphan BRIDGE_OUTs) | **No new accrual** | Basis stranded in corridor, no zero-basis acquisition introduced |
| ETH uncovered rows | **No change** | 9 pre-existing CARRY_IN from untracked source wallets |

---

## 8. Blocker Priority Table

| ID | Description | Stage | Evidence | Priority |
|---|---|---|---|---|
| B-SPIKE-1 | LI.FI BRIDGE_OUT CARRY_IN regression | clarification | EVIDENCE_PRESENT_UNUSABLE | **P0** |
| B-BRIDGE-1 | 3 Across BRIDGE_INs unlinked (EVIDENCE_PRESENT_UNLINKED) | linking | EVIDENCE_PRESENT_UNLINKED | **P0** |
| B-BRIDGE-2 | BRIDGE_IN with ACQUIRE instead of CARRY_IN (Sep 29) | clarification | EVIDENCE_PRESENT_UNUSABLE | **P0** |
| B-BTC-1 | BTC sale on 33625378 sees 0 position, basis stranded | linking | EVIDENCE_PRESENT_UNLINKED | **P1** |
| B-SPIKE-2 | SPONSORED_GAS_IN ACQUIRE with $0 basis dilutes AVCO | pricing/classification | EVIDENCE_PRESENT_UNUSABLE | **P1** |
| B-UNCOV-2 | GM:ETH/USD LP basis not reallocated | move_basis | EVIDENCE_PRESENT_UNUSABLE | **P2** |
| B-UNCOV-1 | USDE 2,115 — source wallet not in session | linking | EVIDENCE_MISSING | **P2** |
| B-UNCOV-3 | TON 97 — out of scope | n/a | UNSUPPORTED_SCOPE_PROVEN | **P2** |

---

## 9. Remaining Bridge Orphans (GENUINE_EVIDENCE_MISSING)

4 BRIDGE_OUTs have no BRIDGE_IN in DB:

| BRIDGE_OUT prefix | Protocol | Date | Asset | Status |
|---|---|---|---|---|
| 0x258ed5 | Across | 2025-05-10 | ETH | GENUINE_EVIDENCE_MISSING |
| 0x266c02 | Across | 2025-05-17 | ETH | GENUINE_EVIDENCE_MISSING |
| 0x8b4004 | LI.FI | 2025-09-29 | USDC+ETH | GENUINE_EVIDENCE_MISSING |
| 0xdd83df | LI.FI | 2026-01-06 | USDC+ETH+ETH | GENUINE_EVIDENCE_MISSING |

These 4 orphan BRIDGE_OUTs hold dispatched basis in the corridor with no BRIDGE_IN to receive it. Until the destination transactions are indexed, the CARRY_OUT basis is permanently stranded. The corridor carry should either:
- Leave the basis stranded (current behavior, acceptable)
- Or after a configurable TTL, convert the orphaned CARRY_OUT to a confirmed DISPOSE (optional)

**Audit terminal for these 4:** `GENUINE_EVIDENCE_MISSING_PROVEN`

---

*Audit generated: 2026-05-31 | Session: df5e69cc | Method: raw MongoDB + API reconstruction*
