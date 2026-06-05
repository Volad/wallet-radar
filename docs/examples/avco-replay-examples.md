# AVCO / Replay Examples

Synthetic ledger math. See [AVCO rules](../pipeline/cost-basis/02-avco-rules.md) and [ledger points](../reference/ledger-points-and-basis-effects.md).

---

## Example 1: ACQUIRE then partial DISPOSE

Wallet `0xWALLET_A`, asset ETH family.

| Step | Event | qty | price | Ledger |
|------|-------|-----|-------|--------|
| 1 | BUY | +10 ETH | $2000 | ACQUIRE: qty=10, cost=$20000, avco=$2000 |
| 2 | SELL | −4 ETH | $2500 | DISPOSE: realisedPnL = 4×(2500−2000) = **$2000** |
| After 2 | — | 6 ETH | — | avco still $2000, cost=$12000 |

---

## Example 2: FEE relief

| Step | Event | Effect |
|------|-------|--------|
| 1 | BUY +10 ETH @ $2000 | avco=$2000 |
| 2 | FEE 0.01 ETH gas | DISPOSE 0.01 @ avco $2000; gasDeltaUsd separate |

---

## Example 3: Inbound shortfall

| Step | Event | Effect |
|------|-------|--------|
| 1 | SELL 5 ETH (only 3 held) | shortfall 2 ETH recorded |
| 2 | BUY 2 ETH @ $2100 | Covers shortfall; avco recomputed |

---

## Example 4: Borrow basis-neutral inflow

**Tx:** `0xEXAMPLE_TX_11` type `BORROW`, 1000 USDC.

| Component | Effect |
|-----------|--------|
| Quantity | +1000 USDC |
| Cost basis | Market price inflow (basis-neutral borrow accounting) |
| Liability | `borrow_liabilities` OPEN for orderId |

## Related

- [Replay overview](../pipeline/replay/01-overview.md)
- [Borrow liability](../pipeline/cost-basis/04-borrow-liability.md)
