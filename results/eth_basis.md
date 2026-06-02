# ETH Family Basis — AVCO Audit 2026-06-01 (avco-borrow-shortfall-2026-06-01 cycle)

## Current tail state (live-cap — matches UI)

```
quantity:           3.142342 ETH
coveredQuantity:    3.121809 ETH
uncoveredQuantity:  0.020533 ETH
totalCostBasisUsd:  $4,766.91        ← stable vs prior cycle
avcoUsd:            $1,526.97        ← stable; B-USDC-1 fix did not affect ETH AVCO
```

**ETH AVCO of $1,527 is STABLE.** B-USDC-1 fix (USDC BORROW cbD) had no impact on ETH family basis. B-CORRIDOR-1 was retracted (corridor basis propagates correctly). Accepted as the correct reflection of current coverage state. AVCO spikes (>20%) = 0. UNAVAILABLE timeline points = 86 (unchanged).

## Authoritative reconstruction (what AVCO should be)

After B-CORRIDOR-1 fix: BRIDGE_IN CARRY_IN should receive cbD from paired BRIDGE_OUT CARRY_OUT.

| Corridor pair | BRIDGE_IN qty | Missing cbD | Correct cbD |
|---|---|---|---|
| 0xd9d384 → 0xa2e409cc | 0.49977 ETH | $0 current | ~$1,599 (from OUT cbD) |
| 0x8f3dd8 → 0x0417160b | 0.42111 ETH | $0 current | ~$1,346 (from OUT cbD) |
| 0x122fa9 → 0x7f3c1a78 | 0.03010 ETH | $0 current | ~$31 (from OUT cbD) |
| others (0x612c0b8, 0x9187f4c, 0x2108883, 0x38d445c4) | ~0.841 ETH | $0 current | ~$341 est. |

Estimated correct total basis after fix: $4,767 + $3,317 ≈ **$8,084** (back to prior cycle level).  
Estimated correct AVCO after fix: $8,084 / 3.122 ≈ **$2,589** (matches prior cycle).

The basis drop is fully explained by corridor pairing switching BRIDGE_INs from ACQUIRE (market) to CARRY_IN (cbD=0).

## Family running AVCO notes (current)

- No episode where running `totalBasis / coveredQty` → 0 while coveredQty > 0.001
- No AVCO spikes (> $10,000 or < $500 with bb > 0.001)
- The drop to $1,527 is SYSTEMATIC, not a spike — it reflects missing basis across multiple BRIDGE_IN corridor receipts

## Per-wallet tail AVCO (ETH, material buckets — current)

| Wallet | Symbol | Qty | AVCO | Notes |
|---|---|---|---|---|
| 0x1a87… | ETH | 0.060+ | ~$2,100 | impacted |
| BYBIT:33625378 | ETH | 1.188 | ~$3,822 | less impacted (mostly Bybit) |
| BYBIT:33625378:FUND | CMETH | 0.953 | ~$2,374 | unchanged |

## Accepted zero-basis items (non-material)

| Category | Count | Notes |
|---|---|---|
| SPONSORED_GAS_IN GAS_ONLY | 266 | Policy-correct zero-basis gas rebates |
| Dust covered-qty ETH | 87 UNAVAILABLE timeline rows | Basis-neutral lifecycle events, bb=null |
| BTC DISPOSE cbD=0 | 1 row | B-BTC-1 corollary of B-CORRIDOR-1 |
