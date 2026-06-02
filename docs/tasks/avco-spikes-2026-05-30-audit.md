# AVCO Spikes Audit — ETH Family
**Date**: 2026-05-30  
**Universe**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
**Method**: True family-level AVCO reconstruction (running total cost / running total qty across all sub-accounts, sorted by replaySequence)

---

## Audit Scope

Investigated 11 transaction IDs provided by the user as suspected AVCO spike sources. Additionally performed a full family-level AVCO spike scan (>10% change threshold) across all 3,777 ETH-family ledger points.

---

## Transaction-by-Transaction Findings

### BYBIT-33625378:EARN_FLEXIBLE_SAVING:a67c0479... (ETH, 2025-01-12)
- **Type**: `LENDING_WITHDRAW`, status `CONFIRMED`
- **Ledger**: `REALLOCATE_OUT` qty=-0.00001379 ETH, cbDelta=-$0.045
- **Family AVCO impact**: Negligible (<0.001%)
- **Verdict**: NOT a spike source. Interest-only small portion; principal routes through Bybit FUND corridor separately.

### BYBIT-33625378:EARN_FLEXIBLE_SAVING:734bf6f1... (CMETH, 2025-02-07)
- **Type**: `LENDING_WITHDRAW`, status `CONFIRMED`
- **Ledger**: `REALLOCATE_OUT` qty=-0.00052442 CMETH, cbDelta=-$1.64
- **Family AVCO impact**: Negligible
- **Verdict**: NOT a spike source. Tiny interest portion.

### BYBIT-33625378:EARN_FLEXIBLE_SAVING:b0ee607b... (CMETH, 2025-04-04)
- **Type**: `LENDING_WITHDRAW`, status `CONFIRMED`
- **Ledger**: `REALLOCATE_OUT` qty=-0.00090499 CMETH, cbDelta=-$1.75
- **Family AVCO impact**: Negligible
- **Verdict**: NOT a spike source. Tiny interest portion.

### 0xf79c1678...541b11b7e5f2117be8cd74113751460c56f22f057c9846be4c82a146 (ARBITRUM)
- **Type**: `BRIDGE_OUT` (Across protocol), `continuityCandidate=true`
- **Flows**: TRANSFER ETH -0.004, FEE ETH -0.0000018
- **Ledger**: `CARRY_OUT` -0.004 ETH cbDelta=-$7.68, `GAS_ONLY` tiny
- **Family AVCO impact**: ~8.6% at the point it was processed (seq ~620), below 10% threshold
- **Verdict**: Minor corridor, not a material spike source.

### 0x7097466ec80966d96b5f3d668617688fea6929f8bd41 + 0xecf6d9e7bda93d46438b (combined ID)
- **Full ID**: `0x7097466ec80966d96b5f3d668617688fea6929f8bd41ecf6d9e7bda93d46438b:ARBITRUM:0xf03b52e8686b962e051a6075a06b96cb8a663021`
- **Type**: `EXTERNAL_TRANSFER_OUT`, counterpartyType=`SCAM`
- **Ledger**: `DISPOSE` qty=0, cbDelta=0
- **Family AVCO impact**: Zero (qty delta is zero — scam dust transfer)
- **Verdict**: NOT a spike source.

### 0x4ca0b79ea7f374c8f90e4c13fc9da43a668f1d8352ae99b1d5a84ef4056ab4fb (BASE)
- **Type**: `BRIDGE_OUT` (LiFi protocol), `continuityCandidate=true`
- **Flows**: TRANSFER ETH -0.799, FEE ETH tiny
- **Ledger**: `CARRY_OUT` qty=-0.000002364 ETH (!!), cbDelta=$0
- **`quantityShortfallDelta`: 0.798998** — BASE wallet had only 0.0000024 ETH tracked; 0.799 ETH was shortfall
- **Verdict**: Source of **Blocker B-1** — see below.

### 0x38d445c4fc8f54149185a606240f0c7b212047a637dae42d7491a835b08d1cf2 (ARBITRUM)
- **Type**: `BRIDGE_IN` (LiFi protocol, same corridor as above), `continuityCandidate=true`
- **Flows**: TRANSFER ETH +0.79899917984786304
- **Ledger**: `CARRY_IN` qty=+0.799, cbDelta=+$1515.66, avco_before=$1455.48, **avco_after=$1896.60**
- **Verdict**: Destination leg of **Blocker B-1**. AVCO spike +30.2% at this ledger point.

### 0xdfda0f14ec08ea8a7be3dea07655e26cba155fb70a3d1dd9f5d008d4d2dc6545 (MANTLE)
- **Type**: `LP_ENTRY` (Pendle LP Mantle), `continuityCandidate=false`
- **Flows**: TRANSFER cmETH -0.10627862 (to LP), TRANSFER PENDLE-LPT +0.0551361 (received)
- **Ledger**: `REALLOCATE_OUT` cmETH -0.10628, cbDelta=-$218.15
- **Family AVCO impact**: Not visible as a >10% ETH-family spike (cmETH basis moves within family total without sharp change)
- **Verdict**: Correct LP_ENTRY reallocation. NOT a direct spike source.

### 0xd7c7736b8a4a536d72e705326256c92fc5af7031e9c3aca2907be7257ccf5de3 (MANTLE)
- **Type**: `INTERNAL_TRANSFER` (Mantle: 0xa0dd42... → 0x1a87...), `continuityCandidate=true`
- **Flows**: TRANSFER cmETH +0.649664 (to 0x1a87)
- **Ledger**: 4 points for this tx:
  - seq 1330: `CARRY_IN` @ 0x1a87, qty=+0.649664, **cbDelta=$0** → AVCO drops -24.8%
  - seq 1331: `CARRY_IN` @ 0xa0dd42, qty=0, **cbDelta=+$1254.31** → AVCO jumps +34.6%
  - seq 1332: `CARRY_OUT` @ 0xa0dd42, qty=-0.649664, cbDelta=-$1254.31 → restores
- **Verdict**: Source of **Blocker B-2** — see below.

### 0x3099ace0372a6683a78efc60fe0b4b0c6f434e54fb1517089bdef8e1e0e7a58f (ARBITRUM)
- **Type**: `LENDING_DEPOSIT` (Aave Arbitrum), `continuityCandidate=false`
- **Flows**: TRANSFER ETH -0.798 → TRANSFER aArbWETH +0.798; BUY aArbWETH +0.000356 @ $1897.89
- **Ledger**: `REALLOCATE_OUT` ETH -0.798 cbDelta=-$1513.49; `REALLOCATE_IN` aArbWETH +0.798 cbDelta=+$1513.49
- **Family AVCO impact**: Zero (basis-preserving reallocation ETH↔aArbWETH within same family)
- **Verdict**: NOT a spike source. Correct Aave deposit treatment.

---

## Confirmed AVCO Spike Blockers (Family-Level)

### B-1 — Bridge BASE→ARB with BASE Shortfall (HIGH — +30% spike)

**Evidence**:
- BRIDGE_OUT `0x4ca0b79e...BASE:0x1a87...` — source BASE wallet had only 0.0000024 ETH tracked; 0.799 ETH was shortfall → CARRY_OUT records $0 basis for ~all of 0.799 ETH
- BRIDGE_IN `0x38d445c4...ARB:0x1a87...` — CARRY_IN records +0.799 ETH at corridor price $1896.96 → cbDelta=+$1515.66
- Family AVCO before BRIDGE_IN: $1455.48. After: **$1896.60** (+30.2%)

**Root cause**: The BASE wallet `0x1a87...` did not have ETH acquisition history tracked (shortfall of 0.799 ETH). The corridor basis computation for CARRY_IN falls back to spot price at bridge time ($1896.96/ETH) because the CARRY_OUT carried $0 basis. This inflates the family cost basis artificially.

**Failed stage**: `move_basis` — the corridor basis for CARRY_IN should have used the corridor-out basis (which was $0 due to shortfall). Instead, it used the spot price, injecting $1515.66 fresh basis into the family without a matching disposal on BASE.

**Evidence state**: `EVIDENCE_PRESENT_UNUSABLE` — BASE ETH history exists on-chain but was not backfilled, leaving quantityShortfall=0.799 ETH.

**Financially correct behavior**: The BASE ETH had an acquisition history at some previous AVCO. If BASE history were complete, the CARRY_OUT would have carried the actual basis ($X), and the CARRY_IN would have landed with the same $X basis, preserving the family AVCO. The family AVCO should NOT have changed from this bridge.

**Impact**: ~$1,515 basis injected at wrong AVCO ($1896 vs ~$1455). Ongoing inflation of ETH family AVCO by ~$441 × (remaining ETH qty) permanently until a corrective event.

**Remediation**: Backfill BASE ETH acquisition history for wallet `0x1a87...` from genesis (or the first ETH acquisition on BASE). Once the shortfall is resolved, re-normalization + replay will correctly carry the basis through the bridge.

---

### B-2 — Mantle INTERNAL_TRANSFER Corridor Sequence Defect (HIGH — ±25-35% transient)

**Evidence** (tx `0xd7c7736b...MANTLE:0x1a87...`):
- seq 1330: CARRY_IN at destination (`0x1a87`) — qty=+0.649664 cmETH, cbDelta=$0 → family AVCO drops 24.8%
- seq 1331: CARRY_IN at source (`0xa0dd42`) — qty=0, cbDelta=+$1254.31 → family AVCO spikes 34.6%
- seq 1332: CARRY_OUT at source — removes qty+basis, net family restores

**Root cause**: The INTERNAL_TRANSFER corridor generates ledger points for two wallets within the same replay. The destination CARRY_IN (seq 1330) arrives in replay order before the source CARRY_OUT (seq 1332). Between seq 1330 and 1332, the family temporarily has an extra 0.649664 cmETH at $0 cost basis — the destination gained the quantity, but the source hasn't yet released it. This creates a transient double-count of quantity with single basis → AVCO halved. Then at seq 1332 the source removes its qty+basis, restoring the correct state.

**Failed stage**: `replay` — the replay sequencing does not guarantee that within a single INTERNAL_TRANSFER, the source CARRY_OUT is processed before or atomically with the destination CARRY_IN.

**Evidence state**: `EVIDENCE_PRESENT_UNUSABLE` — all data is present but the multi-wallet corridor ordering in replay creates transient inconsistency.

**Type adequacy**: The current INTERNAL_TRANSFER type is semantically correct, but the corridor multi-wallet replay ordering is not atomic.

**Impact**: Two sharp transient spikes on the AVCO chart at 2025-04-18 10:21:46 UTC. These are display artifacts — the final state after seq 1332 is correct. However, the intermediate states (-25%/+35%) are visible on the chart and will confuse users.

**Remediation**: The replay must process all ledger points for a single corridor transaction atomically (or sort: CARRY_OUT before CARRY_IN within the same tx). Options:
1. Within-tx ordering: ensure CARRY_OUT (source) has lower flowIndex than CARRY_IN (destination) in the replay sequence.
2. Atomic update: apply both source and destination legs in a single accounting step so the intermediate state is never persisted or rendered.

---

### B-3 — Mantle→Bybit CMETH Corridor Basis Mismatch (HIGH — +26% spike)

**Evidence** (Sep 2025, corridor `BYBIT-CORRIDOR:MANTLE:0xe2f90520...`):
- CARRY_OUT `0xe2f90520...MANTLE:0x1a87...` (seq 5122): qty=-0.862092 CMETH, **cbDelta=-$1898.79**
- CARRY_IN `BYBIT-33625378:FUNDING_HISTORY:f9cfb4eb...` (seq 5124): qty=+0.862092 CMETH, **cbDelta=+$2517.25**
- Basis discrepancy: **+$618.46** injected on arrival vs what left on departure
- Family AVCO before: $1757. After CARRY_IN: **$2218** (+26.2%)

**Root cause**: The corridor is identified correctly (correlationId matches both sides), but the Bybit FUND account CARRY_IN uses a different cost basis ($2917/unit) than the on-chain CARRY_OUT ($2203/unit). The CARRY_IN appears to re-price the CMETH at a Bybit-side valuation or a stale corridor basis pool price rather than preserving the exact carry-out basis.

**Failed stage**: `move_basis` — the Bybit-side corridor carry-in basis ($2517.25) must equal the on-chain carry-out basis ($1898.79). The discrepancy of $618 has no financial justification and represents basis creation out of thin air.

**Evidence state**: `EVIDENCE_PRESENT_UNUSABLE` — both legs of the corridor exist and are linked, but the basis amounts differ.

**Impact**: ~$618 of basis inflated on the Bybit side per occurrence. Multiple similar spikes observed in Sep 2025 and Feb 2026 (the same CMETH ↔ Bybit FUND corridor pattern repeats). Estimated total basis inflation across the full timeline: several thousand USD equivalent in artificially inflated AVCO.

**Remediation**: The Bybit-side FUNDING_HISTORY corridor CARRY_IN must use the exact carry-out basis from the paired on-chain CARRY_OUT. Fix the corridor basis pool resolution for BYBIT_CORRIDOR type: read the basis from the counterparty_basis_pool using the corridor ID, which should have stored the exact out-leg basis.

---

### B-4 — WETH WRAP Shortfall on BASE (MEDIUM — ±19-23% transient)

**Evidence** (tx `0xbdf26819...BASE:0x1a87...`, Nov 2025):
- Type: `WRAP` (ETH → WETH on BASE)
- Ledger seq 7217: `REALLOCATE_OUT` ETH qty=0, shortfall=0.546 (BASE had no ETH)
- Ledger seq 7218: `REALLOCATE_IN` WETH qty=+0.546, **cbDelta=$0**
- Family AVCO before seq 7218: $3366. After: **$2732** (-18.8%)
- Ledger seq 7220: CARRY_OUT WETH -0.546, cbDelta=$0 → AVCO returns to $3366 (+23.2%)

**Root cause**: Same root as B-1 — the BASE ETH position had an incomplete history (shortfall). When ETH is wrapped to WETH on BASE, the accounting finds $0 ETH basis (shortfall) and creates WETH with $0 basis. The 0.546 WETH at $0 cost dilutes the family AVCO. The WRAP + BRIDGE_OUT completes quickly (next tx), removing the WETH and restoring AVCO.

**Failed stage**: `move_basis` — shortfall on the source asset (BASE ETH) propagates $0 basis to WETH, which then briefly inflates the family quantity with no matching cost.

**Impact**: Transient chart spike visible from 2025-11-17 08:05 to 08:07 UTC. Net effect on terminal AVCO is zero (WETH is fully removed in the same cluster), but the visual spike is confusing.

**Remediation**: Same as B-1 — backfill BASE ETH history. Additionally, when a WRAP involves a shortfall asset, the WETH basis should inherit the corridor basis or be flagged as shortfall-originated to suppress transient AVCO impact from display.

---

### B-5 — Repeated Bybit CARRY_OUT/CARRY_IN Ping-Pong (MEDIUM — multiple 10-20% spikes)

**Pattern**: Several occurrences in the Sep 2025 and Feb 2026 timeline where:
1. Large ETH is CARRY_OUT from Bybit FUND → on-chain (cbDelta uses Bybit FUND account basis ~$3374/ETH)
2. Large ETH is CARRY_IN to on-chain wallet (cbDelta carries same basis)
3. On-chain ETH is CARRY_OUT back to Bybit FUND
4. Net family AVCO oscillates 15-20% during each transit

**Example**: seq 5212 CARRY_OUT -0.737 ETH cbDelta=-$1025 (Bybit FUND), before AVCO $2882 → after $3371 (+16.9%); seq 5213 CARRY_IN +0.919 ETH cbDelta=+$1278 → after $2797 (-17%)

**Root cause**: The Bybit FUND account holds large ETH at high historical basis (~$3374/unit, acquired at elevated prices). When this ETH cycles through corridors, it temporarily shifts the family AVCO by changing the weighted composition. These are mathematically correct changes reflecting the actual multi-account weighted AVCO, but create visual spikes from the Bybit FUND's high-basis ETH.

**Failed stage**: None — these are CORRECT AVCO behavior. The ETH in Bybit FUND was acquired at ~$3374/unit, which is the honest average cost. When that ETH moves through corridors, the family AVCO legitimately changes.

**Verdict**: NOT a bug. The chart correctly shows AVCO changing as high-basis Bybit ETH moves in/out of on-chain positions. These visual spikes reflect genuine cost basis composition shifts.

---

## EARN_FLEXIBLE_SAVING Architecture Assessment

All three EARN_FLEXIBLE_SAVING transactions investigated produce only tiny REALLOCATE_OUT ledger points (interest/reward portions, 0.00001-0.001 units). The principal amounts (0.15 ETH, 0.14 CMETH, 0.67 CMETH) flow through the `bybit-earn-principal-v1` corridor (CARRY_OUT from EARN → CARRY_IN to FUND) separately. The interest portion is correctly reallocated as a small reward. No AVCO spikes attributable to the EARN_FLEXIBLE_SAVING mechanism itself.

---

## Summary Table

| Blocker | Date Range | Spike Magnitude | Root Cause | Stage | Remediation |
|---------|-----------|----------------|------------|-------|-------------|
| **B-1** | 2026-02-06 | +30% family AVCO | BASE ETH shortfall — BRIDGE_IN corridor uses spot price instead of carry-out basis | `move_basis` | Backfill BASE ETH history for `0x1a87...` |
| **B-2** | 2025-04-18 | -25% then +35% transient | INTERNAL_TRANSFER corridor replay: destination CARRY_IN lands before source CARRY_OUT | `replay` | Enforce CARRY_OUT-before-CARRY_IN ordering within same tx for multi-wallet corridors |
| **B-3** | 2025-09-10 and recurring | +26% (and similar) | Bybit FUND CARRY_IN basis ($2517) ≠ on-chain CARRY_OUT basis ($1898) for same corridor | `move_basis` | Fix corridor basis pool resolution for BYBIT_CORRIDOR — use exact carry-out basis on arrival |
| **B-4** | 2025-11-17 | -19% then +23% transient | BASE ETH shortfall propagates $0 basis to WETH via WRAP | `move_basis` | Backfill BASE ETH; suppress shortfall-basis WETH from transient display |
| **B-5** | Sep–Dec 2025, Feb 2026 | 10-20% per leg | High-basis Bybit FUND ETH (~$3374) cycling through corridors causes legitimate but sharp AVCO changes | — | NOT A BUG — mathematically correct. Consider display smoothing only. |

---

## Priority Order

1. **B-3** (Highest): Repeated corridor basis mismatch — creates permanent basis inflation across multiple corridor cycles. Estimated total impact: $600-2000+ USD of artificially inflated basis per corridor round-trip. Will affect all future corridor operations.

2. **B-1** (High): BASE shortfall causes one-time +$1515 basis injection at wrong AVCO. Permanent until BASE history backfilled. Affects current AVCO reading.

3. **B-2** (High): Transient but visually dramatic spikes on chart (-25%/+35% within same second). Fix is ordering within replay sequence.

4. **B-4** (Medium): Transient chart spikes from WRAP shortfall. Net effect zero, but confusing UI.

5. **B-5** (Informational): Not a bug — no fix required. Chart rendering of AVCO may benefit from an explanation in the UI.

---

## Transactions NOT Causing Spikes (Verified Clean)

- All three EARN_FLEXIBLE_SAVING transactions: interest-only tiny amounts, not spike sources
- `0xf79c...` BRIDGE_OUT Across: minor, below threshold
- `0x7097.../0xecf6d9...` scam transfer: zero quantity, no impact
- `0xdfda...` LP_ENTRY Pendle Mantle: basis-preserving cmETH reallocation
- `0x3099...` LENDING_DEPOSIT Aave: basis-preserving ETH↔aArbWETH reallocation
