# Examples Index

> **Convention:** All examples use **synthetic placeholder** hashes and addresses.  
> Never use real transaction hashes from production MongoDB.

| Placeholder | Meaning |
|-------------|---------|
| `0xEXAMPLE_TX_1` | Generic transaction hash |
| `0xWALLET_A` | User wallet A |
| `0xWALLET_B` | User wallet B |
| `0xROUTER_UNISWAP` | DEX router contract |
| `0xAAVE_POOL` | Aave pool contract |

## Walkthroughs

| Example | Stages covered |
|---------|----------------|
| [Normalization](normalization-examples.md) | Raw → classified normalized type |
| [Linking](linking-examples.md) | Correlation, continuity repair |
| [Pricing](pricing-examples.md) | USD resolution paths |
| [AVCO / replay](avco-replay-examples.md) | Ledger points, realised PnL |
| [Effective cost / break-even](effective-cost-breakeven-examples.md) | Balance AVCO vs Effective cost, held rewards free |
| [Move basis carry](move-basis-carry-examples.md) | CARRY_OUT → CARRY_IN |
| [LP cycle](lp-cycle-example.md) | Entry → fee claim → exit |
| [Lending cycle](lending-cycle-example.md) | Deposit → borrow → repay → withdraw |

Each example ends with expected dashboard / move-basis effect where applicable.

## Related pipeline docs

- [Pipeline index](../pipeline/README.md)
- [Transaction types](../reference/transaction-types.md)
- [Ledger points](../reference/ledger-points-and-basis-effects.md)
