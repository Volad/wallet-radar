# Lending Cycle Example

Synthetic Aave cycle on ETHEREUM, wallet `0xWALLET_A`.

---

## 1. Deposit — `0xEXAMPLE_TX_4`

| Field | Value |
|-------|-------|
| Type | `LENDING_DEPOSIT` |
| Flows | TRANSFER 10 WETH out, BUY 10 aWETH |

**Lending UI:** Cycle **OPEN** on account-pool market.

**Replay:** CARRY_OUT WETH from spot to lending custody.

---

## 2. Borrow — `0xEXAMPLE_TX_11`

| Field | Value |
|-------|-------|
| Type | `BORROW` |
| Flows | BUY 5000 USDC, debt token mint |

**Lending UI:** Same cycle; borrow leg added; health factor computed.

**Replay:** BORROW handler + liability tracker if applicable.

---

## 3. Repay — `0xEXAMPLE_TX_14`

| Field | Value |
|-------|-------|
| Type | `REPAY` |
| Flows | SELL 2000 USDC, debt reduced |

**Lending UI:** Cycle remains OPEN (supply still active).

---

## 4. Withdraw — `0xEXAMPLE_TX_15`

| Field | Value |
|-------|-------|
| Type | `LENDING_WITHDRAW` |
| Flows | SELL 10 aWETH, BUY 10 WETH |

**Lending UI:** Cycle **CLOSED** when flat (no supply, no debt).

**Replay:** CARRY_IN WETH to spot.

---

## Aave concurrent cycles note

Independent supply-only and borrow loops on same pool may appear as **separate OPEN cycles** per `SessionLendingQueryService.buildConcurrentAssetCycles`.

## Related

- [Aave protocol rules](../pipeline/normalization/rules/protocols/aave.md)
- [Lending market UI](../frontend/lending-market.md)
