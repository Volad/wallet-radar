# ETH Basis Summary — FAMILY:ETH (Universe df5e69cc…)

ADR-017: FAMILY:ETH rollup KEPT (ETH, WETH, staked-ETH variants; LP-RECEIPT excluded). AVCO is maintained
per spot bucket `(walletAddress, networkId, accountingAssetIdentity)`; the move-basis chart selects one
authoritative point per grouped event via `TimelineAvcoAuthority`. The user's "AVCO chart" is therefore an
event-level selection across per-bucket sub-ledgers — large dips trace to a single low-basis bucket being
selected, not to a single global AVCO.

## Basis-effect ledger (FAMILY:ETH, 3883 points)
```
ACQUIRE         +$31,069.54   (+11.193 ETH)
CARRY_IN        +$68,110.49   (+34.129 ETH)
CARRY_OUT       -$59,544.16   (-26.180 ETH)   CARRY net = +$8,566.33  (+7.95 ETH)
REALLOCATE_IN   +$72,416.88   (+31.252 ETH)
REALLOCATE_OUT  -$73,780.20   (-30.797 ETH)   REALLOCATE net = -$1,363.32
DISPOSE         -$19,557.26   (-7.409 ETH)     realisedPnl = +$883.04  (GENUINE — cmETH sold 2025-09-10 @ avcoBefore ~$3,667)
GAS_ONLY            -$21.49
UNKNOWN          +$1,304.50   (+0.707 ETH)     <-- two LP_EXIT rows; fabricated, B-ETH-01 exit side
```

## Authoritative AVCO read
- FAMILY:ETH realised PnL **+$883** is verified genuine and conserved.
- The chart's anomalous dips are dominated by **LP_EXIT rows returning ETH at understated basis** because the
  receipt pool was starved by the protocol-attribution collision:
  - `0x0a757aee` (PancakeSwap 938761): **avco $151** — should be ~$2,770.
  - low-basis exits `0x2cdd7d52` ($775), `0x89b5f24e` ($920) and the UNKNOWN `0xe63ce6a8` reflect the same family of
    receipt-pool starvation / cross-asset attribution issues; most other LP exits return ETH at plausible
    $2,400–$3,600.
- Bridge-in legs: 0.97 ETH entered uncovered; F-5(a) re-priced most at market near the source AVCO. One LINEA
  inbound entered at $0 (B-ETH-03).

## Corrected-basis direction (post-RP-1/RP-2 replay)
- 938761 exit ETH avco → ~$2,770; UNKNOWN cbD → 0; REALLOCATE net → ~0; bridge CARRY net → ~0.
- FAMILY:ETH realised PnL should remain ≈ +$883 (the cmETH disposal is unaffected); the residual gain inflation
  comes from the **unrealized/transferred** low-basis ETH, removed once the LP receipt basis is correctly carried.

## Cross-wallet note
- Single audited universe; on-chain ETH+WETH now ≈ 0.0033 (inventory has migrated to Bybit / been disposed),
  so the understated basis has flowed into downstream legs rather than sitting as still-held ETH.
