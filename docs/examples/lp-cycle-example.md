# LP Cycle Example

End-to-end synthetic LP lifecycle on ETHEREUM, wallet `0xWALLET_A`, Uniswap V3 ETH/USDC pool.

---

## 1. LP Entry — `0xEXAMPLE_TX_3`

| Field | Value |
|-------|-------|
| Type | `LP_ENTRY` |
| Flows | SELL 0.5 ETH, SELL 1500 USDC → BUY NFT position |
| correlationId | `lp:uni:0xEXAMPLE_TX_3` |

**Replay:** Position-scoped bucket opened; principal CARRY_OUT from wallet spot to LP custody.

---

## 2. LP Fee Claim — `0xEXAMPLE_TX_12`

| Field | Value |
|-------|-------|
| Type | `LP_FEE_CLAIM` |
| Flows | BUY 0.02 ETH reward |

**Replay:** ACQUIRE REWARD — does **not** close LP bucket (ADR-021 harvest gate).

---

## 3. LP Exit — `0xEXAMPLE_TX_13`

| Field | Value |
|-------|-------|
| Type | `LP_EXIT` |
| Flows | SELL NFT, BUY 0.52 ETH + 1480 USDC |

**Replay:** `PositionScopedLpExitReplayHandler` — per-asset attribution (ADR-022); bucket cleared.

---

## Dashboard / move-basis

- LP section shows position card while open
- ETH/USDC family timelines show CARRY + DISPOSE/ACQUIRE on exit legs
- Realised PnL on exit includes fee claims as separate REWARD points

## Related

- [LP family rules](../pipeline/normalization/rules/families/lp.md)
- [PancakeSwap / Uniswap protocols](../pipeline/normalization/rules/protocols/uniswap.md)
