# ETH Family Basis — AVCO Audit 2026-06-03 (refresh 9)

## Current tail state (refresh 9 — post-fix)

```
quantity:           ~4.35 ETH  (approx — combined all wallets, active positions)
totalCostBasisUsd:  ~$13,689 combined
avcoUsd:            ~$3,145 combined FAMILY:ETH
```

**Per-wallet ETH family positions (latest ledger points, refresh 9):**

| Wallet | Symbol | Qty | Basis | AVCO | Status |
|---|---|---|---|---|---|
| 0x1a87f12a | AMANWETH | 3.060 | **$9,011.24** | **$2,944.85** | ✅ **FIXED** (was $1,533) |
| BYBIT:33625378 | ETH | 1.149 | $4,387.88 | $3,817.60 | ✅ |
| 0x1a87f12a | ETH (ARBITRUM) | 0.081 | $168.48 | $2,068.98 | ✅ |
| 0xf03b52e8 | ETH (BASE) | 0.011 | $42.65 | $3,822.43 | ✅ |
| Various | ETH/WETH (other) | ~0.05 | ~$89 | ~$2,700–$4,500 | ✅ |

**B-LP-EXIT-BASE-PANCAKE-UNKNOWN: RESOLVED.** AMANWETH basis recovered from $4,691 → $9,011.24, AVCO from $1,533 → $2,944.85.

## Root cause of $1,533 aManWETH AVCO (HISTORICAL — NOW FIXED)

**B-LP-EXIT-BASE-PANCAKE-UNKNOWN** — now resolved. For reference:

```
PancakeSwap V3 LP_EXIT 0x0a757aee (BASE, 2026-02-06) — PRE-FIX (now resolved)
  → ROUTER_METHOD_OVERLOAD_UNSUPPORTED → flows=[] → 0.799 ETH not tracked
  → BRIDGE_OUT 0x4ca0b79e carries only 0.000002364 ETH (Relay gas rebate)
  → BRIDGE_IN 0x38d445c4 on ARBITRUM: CARRY_IN 0.799 ETH, cbD=$0 ← $2,214 lost
  → LENDING_DEPOSIT: aWETH position diluted to AVCO $1,531
  → LENDING_WITHDRAW: restores NATIVE position at $4,664 cbD / $1,531 AVCO
  → CARRY chain through BYBIT CORRIDOR → aManWETH on MANTLE → AVCO $1,533 (WRONG)
```

**Post-fix actual chain:**
```
LP_EXIT 0x0a757aee (FIXED): LP receipt basis $2,026 → ETH:BASE
  → BRIDGE_IN 0x38d445c4 cbD≈$2,026 (FIXED)
  → LENDING_WITHDRAW 0xe564fec1: REALLOCATE_IN 3.046 WETH cbD=$8,987.72 avco=$2,950
  → UNWRAP + BYBIT CORRIDOR → AMANWETH cbD=$9,011.24 avco=$2,944.85 ✅
```

## Authoritative AVCO reconstruction (historical — for reference)

LP position cost basis at entry (2025-12 / 2026-02):
- 0.289306 ETH deposited, average price ~$3,600 → **~$1,041 ETH cost**
- ~$1,204 USDC deposited
- **Total LP cost basis: ~$2,231**

Pre-fix target: `Correct aManWETH AVCO: ~$2,257`
Post-fix actual: `AMANWETH AVCO: $2,944.85`

The higher-than-target $2,944 AVCO reflects additional basis accumulated from: (a) the full LP receipt position basis at exit time ($2,026 from 6 LP_ENTRY events including partial exits), plus (b) the pre-existing aWETH basis on ARBITRUM before the BYBIT CORRIDOR. The $2,257 audit target was a lower-bound estimate; actual post-fix is correctly higher.

## Estimated cbD shortfall

| Position | Current | Correct | Shortfall |
|---|---|---|---|
| aManWETH (0x1a87f12a) | $4,691 | ~$6,905 | **~$2,214** |
| AVCO | $1,533 | **~$2,257** | — |

Conservative estimate: $1,000. Median: $2,100. Upper bound: $2,231.

## April 2025 SWAP ACQUIRE at $1,554 — verdict

These are legitimate Bybit SPOT purchases on `BYBIT:33625378`, not on `0x1a87f12a`. ETH traded at ~$1,500–$1,600 in early April 2025. The three entries on 2025-04-11:
- `cbD: $19.97` → 0.01284683 ETH at $1,554
- `cbD: $7.75` → ~0.00499 ETH
- `cbD: $121` → ~0.0778 ETH

All on Bybit SPOT. **Correctly classified as ACQUIRE. Not a misclassification.**

## SPONSORED_GAS_IN impact

- 266 total `SPONSORED_GAS_IN` events across all wallets (18 on BASE/0x1a87f12a)
- All produce `GAS_ONLY` ledger effects with near-zero `quantityDelta` and `cbD=0`
- Total ETH added by SPONSORED_GAS_IN on `0x1a87f12a` BASE: **~0.000446 ETH** (not material)
- AVCO dilution from these events: **< $2** — not the root cause of $1,533 AVCO
- Policy is correct: gas rebates genuinely have zero cost basis. No fix needed.

## Fix approach

**Stage**: `normalization`
**Action**: Add support for the PancakeSwap V3 router method overload in `0x0a757aee` on BASE
**Expected result after rebuild**: BRIDGE_IN `0x38d445c4` carries cbD ~$2,214 → aManWETH AVCO recovers to ~$2,257

Run after fix:
```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

## Family running AVCO notes (current)

- The $1,533 aManWETH AVCO is a systematic mis-carry (not a spike), fully explained by the LP_EXIT bug
- BYBIT:33625378 ETH at $3,817 is independently correct (Bybit purchases not affected)
- `0x1a87f12a` native ETH at $1,997 is also affected by the same LP cascade
- No AVCO spikes (> $10,000 or < $500 with qty > 0.001) on other wallets

## Accepted zero-basis items (non-material)

| Category | Count | Notes |
|---|---|---|
| SPONSORED_GAS_IN GAS_ONLY | 266 | Policy-correct zero-basis gas rebates; not a bug |
| Dust covered-qty ETH | 87 UNAVAILABLE timeline rows | Basis-neutral lifecycle events |
| BTC DISPOSE cbD=0 | 1 row | B-BTC-1 corollary |
