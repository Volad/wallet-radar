# Warnings — AVCO Audit 2026-06-02 (full audit cycle, refresh 2)

## ~~W-1~~ — RESOLVED (B-SHORTFALL-1 fix explains and reverses the drop)

ETH AVCO drop from $2,589 → $1,527 is now explained: many BRIDGE_INs were zero-basis (shortfall sources) and diluted AVCO. B-SHORTFALL-1 repriced them at market, restoring AVCO to $2,539.

## W-3 — ETH DISPOSE qty=0 (7 rows, INFO only)

Seven ETH DISPOSE with quantityDelta=0 exist. All are on wallets with qtyBefore=0 (empty positions):
- 4 rows: `DisperseClone:Scam` drain attempts on already-empty wallets
- 1 row: REPAY on empty NATIVE chain position
- 1 row: NFT_MINT with no local ETH tracked
- 1 row: duplicate scam drain

Not a pipeline defect. Expected coverage_gap artefacts.

## W-4 — MANTLE Aave WETH yield accrual uncovered bucket

MANTLE Aave WETH receipt (`0xeac30ed8...`): 3.078 total qty, 3.060 covered, 0.019 uncovered (`yield_accrual`). Policy-expected; yield interest accrual on Aave is not tracked as acquired cost basis.

## W-5 — BASE/OPTIMISM dust coverage gaps

Multiple wallets show `coverage_gap` on dust ETH (0.001–9e-6 ETH) with `hasIncompleteHistory=true` flags (68+ on BASE wallet, 19 on OPTIMISM wallet). Do not affect AVCO materially.

## ~~W-6~~ — RESOLVED (B-USDC-1 fixed, retained for reference)

## W-7 — BTC cross-UID CARRY_IN cbD=0 (P2, economic criterion — now tracked in B-CROSS-UID)

BTC cross-UID corridor (BYBIT:516601508 → BYBIT:33625378) has CARRY_IN with qty=0.001159 BTC and cbD=$0. This produces an **actively broken position**: `SYMBOL:BTC BYBIT:33625378` qty=0.000797, avco=$0, basis=$0 (the remaining BTC from the cross-UID transfer).

| Field | Value |
|-------|-------|
| Source (BYBIT:516601508) | SYMBOL:ETH qty=0.0137 avco=$3,471 (separately tracked) |
| Dest BTC (BYBIT:33625378) | qty=0.000797 BTC, basis=$0, avco=$0 |
| Additional BTC entry | qty=0.000362 BTC, basis=$0 |
| Failed stage hypothesis | `move_basis` / cross-UID corridor carry |
| Evidence state | `EVIDENCE_PRESENT_UNUSABLE` |
| Remediation | P2 — cross-UID basis propagation |
| Missing cbD | ~$115 for BTC + other assets in B-CROSS-UID |

## W-8 — USDC ARBITRUM AVCO history — residual low-AVCO in historical flows (P3)

Historical AVCO spikes and drops in USDC ARBITRUM (`0xaf88d065`) for wallet `0x1a87f12a` remain in the ledger. The multi-stage cascade (avco $0.02 → $49.78 → $1531.96 → $1.16 → $0.97) is a historical artifact of the vault-cascade era. **Current position avco is $1.00** (recovered via fresh acquisitions). No fix required for current display, but historical P&L is inaccurate.

## W-9 — eUSDC-2 Euler Avalanche share token qty inflation (P3)

Contract `0x39de0f00189306062d79edec6dca5bb6bfd108f9` on Avalanche is `eUSDC-2`, the Euler V2 lending receipt token. Tracked under `FAMILY:USDC`, but qty is denominated in Euler share units (not USDC).

| Field | Value |
|-------|-------|
| Reported qty in ledger | ~7.36B shares |
| Actual USDC cost basis | $1,043.60 |
| Root cause | Euler V2 share-based accounting; 1,108 USDC → ~1.07B shares |
| Family AVCO impact | FAMILY:USDC weighted average corrupted by 7B share denominator |
| Failed stage | Classification / family assignment |
| Evidence state | `EVIDENCE_PRESENT_UNUSABLE` |
| Remediation | P3 — exclude Euler receipt tokens from USDC family AVCO weighted average |

Confirmed still present: BRIDGE_IN zero-cbD entries for `0x39de0f00189306062d79edec6dca5bb6bfd108f9` (2 entries, ~1.6B + 1.9B shares) and `0x1d45674ec811f8a33c97616790bc5a81d4c9afac` (2 entries, ~1.7B + 1.8B shares) in the zero-basis CARRY_IN list. These are Euler share tokens being bridged, with inflated quantities.

## W-10 — SYMBOL:ETH BYBIT:33625378 $499.76 conservation breach (P3)

The sum of all basis-flow entries for `SYMBOL:ETH / BYBIT:33625378` totals $5,041.23, but the stored running `totalCostBasisAfterUsd` ends at $4,541.48 (+$499.76 discrepancy).

| Field | Value |
|-------|-------|
| Gap | +$499.76 |
| Root cause hypothesis | A CARRY_OUT or DISPOSE zeroing the position between first and second ACQUIRE is missing from ledger points |
| Failed stage | `replay` — replay did not emit a ledger point for the intervening basis reduction |
| Evidence state | `EVIDENCE_PRESENT_UNUSABLE` |
| Remediation | P3 |

## W-11 — NATIVE:UNICHAIN 0x68bc3b81 coverage gap (−$163.10) (P2/P3)

Wallet `0x68bc3b81` NATIVE:UNICHAIN: sum of basis flows = −$162.31 vs stored running basis = $0.80 → difference −$163.10. Root cause: multiple ETH BRIDGE_IN flows entered UNICHAIN with zero basis (shortfall sources); subsequent CARRY_OUT operations removed basis that was never fully added.

Remediation: same fix path as B-SHORTFALL-1 — check if UNICHAIN BRIDGE_INs with shortfall sources are covered by `UnmatchedBridgeInboundPricingFallbackService`.

## W-12 — LP_EXIT basisEffect=UNKNOWN instead of REALLOCATE_IN (P2)

PancakeSwap V3 LP_EXIT (`0x7441e12a`, BASE, 2025-10-01) returns 14.618701 USDC with `basisEffect=UNKNOWN` instead of `REALLOCATE_IN`. Numerically correct for USDC (price≈$1) but semantically wrong.

| Field | Value |
|-------|-------|
| Risk | For non-stablecoin LP exits, UNKNOWN basisEffect would produce a fresh ACQUIRE instead of carrying LP allocated basis → AVCO spike |
| Failed stage | `replay` — LP_EXIT replay handler assigns UNKNOWN |
| Evidence state | `EVIDENCE_PRESENT_UNUSABLE` |
| Remediation | P2 — LP_EXIT handler must emit REALLOCATE_IN for returned assets |

## W-13 — LP_POSITION_UNSTAKE CARRY_IN zero cbD (P3, negligible)

`LP_POSITION_UNSTAKE` on OPTIMISM for token `0x9560e827af36c94d2ac33a39bce1fe78631088db`: 8 CARRY_IN events with cbD=$0. Total qty ~9.66 units at avco≈$0.0146.

| Field | Value |
|-------|-------|
| Financial impact | ~$0.14 total (negligible) |
| Network | OPTIMISM |
| Count | 8 instances |
| Failed stage | `move_basis` — LP unstake handler does not carry LP receipt basis to returned asset |
| Remediation | P3 — low priority |

## W-14 — NATIVE:PLASMA zero-basis position (P3)

`NATIVE:PLASMA` wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`: qty=10.1324, basis=$0, avco=$0. Two UNWRAP CARRY_IN events with zero cbD. Financial impact depends on PLASMA asset current price (unknown). If NATIVE:PLASMA is ETH-family, this could represent ~$0–$30,000 depending on price.

| Field | Value |
|-------|-------|
| Root cause | UNWRAP CARRY_IN did not carry basis from the wrapped token position |
| Failed stage | `move_basis` — UNWRAP handler |
| Evidence state | `EVIDENCE_PRESENT_UNUSABLE` |
| Remediation | P3 — depends on PLASMA network asset identity |

## W-15 — SYMBOL:USDT BYBIT:33625378 conservation (OK)

Per-wallet aggregation confirms `sumCbD = totalLastBasis = $4,298` for SYMBOL:USDT across all wallets. No conservation gap. Previously reported $3,772 gap was a cross-position comparison artifact.

## W-16 — SYMBOL:SOL bybit-it-pair-v1 zero cbD (P3, negligible)

`bybit-it-pair-v1` internal pair matching: SYMBOL:SOL 0.3 qty, cbD=$0, ~$0.30 shortfall. (Previously documented as MNT — asset may have changed due to re-normalization.) Negligible.

## W-17 — WBTC LENDING_DEPOSIT sawtooth (visual, P3 INFO)

WBTC on wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` (ARBITRUM): 14 LENDING_DEPOSIT events (REALLOCATE_OUT) each drain the wallet WBTC position to 0 (avcoAfterUsd=null). Basis correctly moves to `AARBWBTC` receipt token (14 matching REALLOCATE_INs confirmed). 22 SWAP ACQUIREs refill position. Chart shows repeated sawtooth: drop to null → jump to fresh market AVCO.

Not a calculation bug. Accounting is correct. WBTC avco tracked in AARBWBTC family between lending cycles.

| Field | Value |
|-------|-------|
| Total WBTC deposited | 0.00427 WBTC (~$362) |
| Receipt token | AARBWBTC (Aave Arbitrum) |
| Pattern | 14 deposits + 22 buys, sawtooth visual |
| Action | Consider UI annotation for WBTC in-lending periods |

## W-18 — USDC AVCO cascade jump (P3, downstream effect)

USDC CARRY_IN via BYBIT-CORRIDOR (`0x8186161871…`, 326 USDC, 2026-03-10) caused USDC AVCO jump from $0.10 → $0.97. Root cause: the prior USDC position on wallet `0x1a87f12` had accumulated 4 zero-basis CARRY_INs (0.012 USDC, from W-9 / BRIDGE_IN eUSDC artifacts). The $0.10 AVCO was caused by these zero-basis entries blending with legitimate USDC.

| Field | Value |
|-------|-------|
| Affected position | SYMBOL:USDC 0x1a87f12 ARBITRUM |
| AVCO before 326 USDC CARRY_IN | $0.10 (incorrect — should be $1) |
| AVCO after | $0.97 (blend corrected toward $1) |
| Root cause | W-9 zero-basis eUSDC cascade |
| Current state | Position now at $0.97 blending toward $1 as more USDC acquired. No fix needed, self-corrects. |

## Summary Table

| ID | Description | Severity | Status |
|----|-------------|----------|--------|
| ~~W-1~~ | ETH AVCO unexplained drop | — | **RESOLVED** by B-SHORTFALL-1 |
| W-3 | ETH DISPOSE qty=0 (scam drains, etc.) | INFO | Open (expected) |
| W-4 | MANTLE Aave yield uncovered | INFO | Open (policy) |
| W-5 | BASE/OPTIMISM dust gaps | INFO | Open (minor) |
| ~~W-6~~ | USDC corridor anomaly | — | **RESOLVED** by B-USDC-1 |
| W-7 | BTC cross-UID CARRY_IN cbD=0 | P2 | Open (tracked in B-CROSS-UID) |
| W-8 | USDC ARBITRUM historical AVCO spikes | P3 | Open (current avco=$1 ✅) |
| W-9 | eUSDC-2 Euler share token qty inflation | P3 | Open |
| W-10 | SYMBOL:ETH BYBIT $499.76 conservation gap | P3 | Open |
| W-11 | NATIVE:UNICHAIN −$163.10 coverage gap | P2/P3 | Open |
| W-12 | LP_EXIT basisEffect=UNKNOWN | P2 | Open |
| W-13 | LP_POSITION_UNSTAKE zero cbD | P3 | Open (negligible) |
| W-14 | NATIVE:PLASMA zero-basis position | P3 | Open (impact TBD) |
| W-15 | USDT conservation | — | **OK** (per-wallet balanced) |
| W-16 | bybit-it-pair-v1 SOL zero cbD | P3 | Open (negligible) |
| W-17 | WBTC LENDING_DEPOSIT sawtooth (visual) | INFO | Open (no fix needed, tracking only) |
| W-18 | USDC AVCO cascade jump ($0.10→$0.97) | P3 | Open (self-correcting, W-9 root) |
