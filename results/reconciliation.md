# Reconciliation

Universe `df5e69cc…`. Source coverage: raw 3348 (0 PENDING), normalized 7414 (7414 CONFIRMED, 0 NEEDS_REVIEW),
ledger 10151 (single universe). No classification backlog to clear this cycle.

## FAMILY:ETH
| Quantity | Value |
|----------|-------|
| On-chain ETH+WETH balance (now) | ~0.00327 ETH (inventory migrated to Bybit / disposed) |
| Lifetime FAMILY:ETH net qtyDelta | +12.89 ETH (cumulative, not a holding) |
| Realised PnL | +$883.04 (GENUINE) |
| CARRY net | +$8,566 (+7.95 ETH) — bridge inflows; some unlinked-but-market-repriced |
| REALLOCATE net | −$1,363 — localized to LP receipt starvation (RP-1) |
| UNKNOWN basis | +$1,304 — fabricated on 2 LP_EXIT rows (RP-1) |

## LP receipt pools (mismatched identity)
| Pool | Held basis | Disposition |
|------|-----------|-------------|
| base:uniswap:938761 (phantom) | $782 ETH + $1,304 USDC | should be $0 |
| arbitrum:uniswap:204401 (phantom) | $1,669 ETH + $8 USDC | should be $0 |
| base:5248110 / unichain:4529… (genuine Uniswap) | $599 / $4,037 | OK |

## Status
- Reconciliation tolerance not formally closed because RP-1 mis-routes basis (per-bucket terminal quantities are
  distorted). After RP-1/RP-2 replay, re-run reconciliation: expect REALLOCATE net→0, UNKNOWN→0, LP pool
  collisions→0.
