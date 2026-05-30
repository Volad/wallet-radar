# AVCO Volatility Blockers — Phase 1 Classification

**Session**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
**Wallet**: `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
**Audit date**: 2026-05-29

---

## Blockers excluded from this audit (already fixed)

- P0 — Bridge BRIDGE_IN-before-BRIDGE_OUT late carry (ADR-020)
- P0-b — Cross-network corridor collision
- P1 — LENDING_LOOP_DECREASE/CLOSE pricing ($0 market price for TRANSFER inflows)

---

## BLOCKER-1 (P0) — Multi-leg SWAP: BUY price derived from first SELL flow only

### Severity
**P0** — directly causes AVCO dips by 99%+; the July 2025 instance ($99 missing basis) propagated through bridge corridors to Arbitrum, polluting ETH/WETH AVCO through September 2025.

### Problem class
SWAP pricing defect: multi-leg swap (two or more SELL flows) where the SWAP_DERIVED algorithm computes the BUY unit price from the `valueUsd` of only the **first** SELL flow, ignoring subsequent SELL flows.

### Affected transactions

| Date | Network | TxHash (short) | Actual price | Correct price | Missing basis |
|---|---|---|---|---|---|
| 2025-07-21 | BASE | `0x4b1646937d` | $9.52/WETH | $3,807.42/WETH | **$99.13** |
| 2026-01-21 | ARBITRUM | `0x5552ee1eb4` | $7.24/ETH | $2,894.94/ETH | **$19.95** |
| 2026-01-22 | LINEA | `0x4ce9ca5507` | $7.61/ETH | $3,043.13/ETH | **$0.23** |
| 2026-01-29 | ARBITRUM | `0xfcfc81d19b` | $6.96/ETH | $2,782.38/ETH | **$2.99** |

**Total direct missing basis: $122.30**

### Evidence

Transaction `0x4b1646937d...` (BASE, 2025-07-21):
```
SELL USDbC: qty=-0.248444, valueUsd=$0.248444  ← used for BUY price
SELL USDbC: qty=-99.129243, valueUsd=$99.129243 ← ignored
BUY WETH:   qty=+0.026101, unitPriceUsd=$9.52, valueUsd=$0.248444, priceSource=SWAP_DERIVED
```
Correct: totalSellUsd = $99.3777 → correctPrice = $99.3777 / 0.026101 = **$3,807.42/WETH**

Transaction `0x5552ee1eb4...` (ARBITRUM, 2026-01-21):
```
SELL USDC: qty=-0.05,   valueUsd=$0.05    ← used for BUY price
SELL USDC: qty=-19.95,  valueUsd=$19.95   ← ignored
BUY ETH:   qty=+0.00690861, unitPriceUsd=$7.24, priceSource=SWAP_DERIVED
```
Correct: totalSellUsd = $20.00 → correctPrice = $20 / 0.006909 = **$2,894.94/ETH**

Transaction `0xfcfc81d19b...` (ARBITRUM, 2026-01-29):
```
SELL USDC: qty=-0.0075, valueUsd=$0.0075  ← used for BUY price
SELL USDC: qty=-2.9925, valueUsd=$2.9925  ← ignored
BUY ETH:   qty=+0.00107821, unitPriceUsd=$6.96, priceSource=SWAP_DERIVED
```
Correct: totalSellUsd = $3.00 → correctPrice = $3 / 0.001078 = **$2,782.38/ETH**

Transaction `0x4ce9ca5507...` (LINEA, 2026-01-22):
```
SELL LINEA: qty=-0.107206, valueUsd=$0.000586, priceSource=BYBIT  ← used for BUY price
SELL LINEA: qty=-42.775018, valueUsd=$0.233937, priceSource=WRAPPER ← ignored
BUY ETH:    qty=+0.0000770664, unitPriceUsd=$7.61, priceSource=SWAP_DERIVED
```
Correct: totalSellUsd = $0.2345 → correctPrice = $0.2345 / 0.0000770664 = **$3,043.13/ETH**

### Downstream AVCO pollution

The BASE transaction (`0x4b1646937d`, July 21 2025) caused a WETH REALLOCATE_IN on BASE with AVCO=$9.52 (ledger seq 4395). This polluted WETH on BASE at quantity 0.026101, totalCostBasis=$0.248.

This low-basis WETH was subsequently bridged to ARBITRUM. At 2025-09-04T07:13:09, a WETH CARRY_IN on ARBITRUM received AVCO=$215.19 (ledger seq 5009, `0xfeca6855f3`). The downstream ETH/WETH AVCO on ARBITRUM remained depressed through September 4, 2025 (range $215–$218 per ETH/WETH), recovering only after sufficient correctly-priced ETH was accumulated.

### Earliest failed pipeline stage
`pricing` — SWAP_DERIVED price computation

### Evidence state
`EVIDENCE_PRESENT_UNUSABLE` — both SELL flows carry correct `valueUsd`, but the algorithm selects only `flows[0]`.

### Detection rule
A SWAP with multiple SELL flows is identifiable when:
- `flows.filter(f => f.role === "SELL").length > 1`
- The BUY flow has `priceSource = "SWAP_DERIVED"`
- `flows.find(f => f.role === "BUY").valueUsd` equals only one SELL flow's `valueUsd`, not the sum

False-match guard: SWAPs with a single SELL flow where SWAP_DERIVED is already correct must not be affected.

### Remediation class
Pricing defect — no type-model change required. Fix the SWAP_DERIVED price computation to sum `valueUsd` across all SELL flows before dividing by BUY quantity.

---

## BLOCKER-2 (P1) — GMX LP_EXIT_SETTLEMENT: WETH flows get `basisEffect=UNKNOWN` ($0 basis)

### Severity
**P1** — 0.160490 WETH received at $0 basis on 2026-02-06 at ~$1,897/ETH market price = **~$304 missing basis**.

### Problem class
`LP_EXIT_SETTLEMENT` TRANSFER inbound flows for WETH do not receive a market price. `PriceableFlowPolicy.requiresMarketPrice` does not cover this transaction type, so the replay engine records these as `basisEffect=UNKNOWN` with `totalCostBasisAfterUsd=$0`.

### Affected transaction
`0x977474f616af6a4227237ec7680f8c2023b7c626652ffda2349ba71f76cfb00e` — ARBITRUM, 2026-02-06T07:27:44

```
type: LP_EXIT_SETTLEMENT
protocolName: GMX, protocolVersion: V2
continuityCandidate: false
matchedCounterparty: 0xa83cc44b... (LP_EXIT_REQUEST)

flows:
  TRANSFER WETH: +0.080015470613136806  ← UNKNOWN basis, $0
  TRANSFER WETH: +0.0804747274251735    ← UNKNOWN basis, $0
  TRANSFER ETH:  +0.0000527792621632   ← REALLOCATE_IN (corridor carry, correct)
```

Ledger points at seq 8692 and 8693: `basisEffect=UNKNOWN`, `totalCostBasisAfterUsd=0`, `avcoAfterUsd=null`.

### Protocol context
GMX V2 LP exit is a two-phase keeper-based flow:
1. **LP_EXIT_REQUEST** (`0xa83cc44b`): wallet burns 200 GM tokens and pays 0.000207 ETH execution fee.
2. **LP_EXIT_SETTLEMENT** (`0x977474f6`): GMX keeper calls `executeWithdrawal`; wallet receives the underlying assets — two WETH lots (long pool + short pool portion split) and an ETH execution fee refund.

The WETH received is a fresh acquisition of underlying pool assets at market value. Under AVCO, these are new purchases at the time-of-exit market price. They are not continuity carries from the GM token cost basis (the GM tokens were disposed at LP_EXIT_REQUEST).

### Earliest failed pipeline stage
`pricing` — `PriceableFlowPolicy.requiresMarketPrice` does not return `true` for TRANSFER inbound on `LP_EXIT_SETTLEMENT`.

### Evidence state
`EVIDENCE_PRESENT_UNUSABLE` — market price is available (Bybit/CMC/CoinGecko at block timestamp); the policy simply does not request it.

### Type adequacy
The canonical type `LP_EXIT_SETTLEMENT` is semantically adequate. The accounting treatment gap is in the pricing policy, not the type model.

### Remediation class
Pricing policy defect. Add `LP_EXIT_SETTLEMENT` to the transaction types for which `requiresMarketPrice` returns `true` for TRANSFER inbound flows with positive `quantityDelta`.

---

## BLOCKER-3 (P2) — GMX LP_ENTRY_SETTLEMENT and LP_EXIT_SETTLEMENT: ETH execution fee refunds get `basisEffect=UNKNOWN`

### Severity
**P2** — 5 LP_ENTRY_SETTLEMENT + 1 LP_EXIT_SETTLEMENT with ETH TRANSFER inflows at $0. Total ETH: ~0.0002 ETH × ~$2,000 ≈ **~$0.40 missing basis**. Minor individually but same systemic root cause as BLOCKER-2.

### Affected transactions

| Date | Type | TxHash | ETH qty | Basis effect |
|---|---|---|---|---|
| 2025-09-04 | LP_ENTRY_SETTLEMENT | `0x9fab1650...` | +0.00000328 | UNKNOWN |
| 2025-12-16 | LP_ENTRY_SETTLEMENT | `0x3ad60ac2...` | +0.0000193  | UNKNOWN |
| 2026-01-29 | LP_ENTRY_SETTLEMENT | `0x61c1272c...` | +0.0000460  | UNKNOWN |
| 2026-02-06 | LP_ENTRY_SETTLEMENT | `0x1aa3438d...` | +0.0000528  | UNKNOWN |
| 2026-02-06 | LP_ENTRY_SETTLEMENT | `0x52924cd8...` | +0.0000534  | UNKNOWN |
| 2026-02-06 | LP_EXIT_SETTLEMENT  | `0x977474f6...` | +0.0000527  | REALLOCATE_IN ✓ |

Note: the LP_EXIT_SETTLEMENT ETH flow (`0x977474f6` flow 2) happens to receive REALLOCATE_IN via corridor carry, so the ETH itself is correctly carried. The gap is only for the two WETH flows in that same transaction (BLOCKER-2).

The LP_ENTRY_SETTLEMENT ETH refunds represent GMX keeper execution fee refunds (excess ETH returned after keeper costs). These are fresh ETH acquisitions at market price.

### Earliest failed pipeline stage
`pricing` — same `PriceableFlowPolicy.requiresMarketPrice` gap.

### Evidence state
`EVIDENCE_PRESENT_UNUSABLE`

### Remediation class
Pricing policy defect. Add `LP_ENTRY_SETTLEMENT` (in addition to `LP_EXIT_SETTLEMENT` from BLOCKER-2) to the types requiring market price for ETH TRANSFER inbound flows.

---

## Audit terminal states

| Blocker | Terminal state |
|---|---|
| BLOCKER-1 multi-leg SWAP | `AUTHORITATIVE_RECONSTRUCTION_COMPLETE` |
| BLOCKER-2 LP_EXIT_SETTLEMENT WETH | `AUTHORITATIVE_RECONSTRUCTION_COMPLETE` |
| BLOCKER-3 LP_ENTRY_SETTLEMENT ETH | `AUTHORITATIVE_RECONSTRUCTION_COMPLETE` |
