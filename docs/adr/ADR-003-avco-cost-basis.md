# ADR-003: AVCO (Average Cost) as Cost Basis Method

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect + Product Owner

---

## Context

WalletRadar must calculate cost basis for each asset position. In DeFi, users frequently buy the same token multiple times at different prices, then sell portions. The system must assign a cost to each sold unit.

Three standard methods exist:
- **FIFO** (First In First Out) — oldest lots sold first
- **LIFO** (Last In First Out) — newest lots sold first  
- **AVCO** (Average Cost / Weighted Average) — all lots merged into a running average

## Decision

**AVCO — Average Cost / Weighted Average.**

```
On BUY:  newAvco = (currentAvco × currentQty + buyPrice × buyQty) / (currentQty + buyQty)
On SELL: avcoUnchanged; realisedPnl = (sellPrice − avcoAtTimeOfSale) × sellQty
```

## Rationale

| Concern | FIFO | LIFO | AVCO |
|---------|------|------|------|
| Lot tracking required | Yes — must tag each purchase | Yes — must tag each purchase | No — running average |
| Deterministic replay | Yes | Yes | Yes |
| DeFi suitability | Poor — tokens merge in LP pools, staking | Poor | Excellent |
| Implementation complexity | High | High | Low |
| Result stability across partial sells | Varies lot-by-lot | Varies lot-by-lot | Stable — same AVCO until new purchase |
| Cross-wallet aggregation | Complex — lot identity must survive transfer | Complex | Simple — merge timelines, re-run formula |

FIFO and LIFO require tracking the identity of individual lots. In DeFi this breaks down immediately: tokens pooled in Uniswap LP positions, staked in validators, or bridged across networks lose their "lot" identity. AVCO is the only method that remains meaningful after these operations.

AVCO also makes cross-wallet aggregation tractable: merge the event timelines from all session wallets, skip internal transfers, re-run the AVCO formula. The result is deterministic regardless of which wallet holds the tokens at any given moment.

## Consequences

- **Positive:** No lot-tracking infrastructure. A single `perWalletAvco` field per `(wallet, asset)` is sufficient.
- **Positive:** Cross-wallet AVCO is a simple timeline merge + formula replay.
- **Positive:** Fully deterministic — same event sequence always produces same AVCO.
- **Positive:** Manual overrides are naturally handled: replace price on one event, replay from that point forward.
- **Negative:** AVCO is not FIFO/LIFO. Users who require specific lot identification for tax purposes in jurisdictions that mandate FIFO will need a different tool.
- **Negative:** AVCO spreads the cost of a bad purchase across all holdings — a user cannot choose to "use" a specific lot for tax optimisation.

## Scope

AVCO applies to:
- All `SWAP_BUY`, `SWAP_SELL` events
- `STAKE_WITHDRAWAL` / `LEND_WITHDRAWAL` (treated as inflow at original AVCO)
- `BORROW` events (treated as inflow at market price)
- `INTERNAL_TRANSFER` (destination wallet receives at source AVCO — cost is preserved, not reset to market price)

AVCO does **not** apply to:
- `LP_ENTRY` / `LP_EXIT` — flagged `LP_MANUAL_REQUIRED` due to impermanent loss complexity
- Rebase tokens (out of scope MVP)
