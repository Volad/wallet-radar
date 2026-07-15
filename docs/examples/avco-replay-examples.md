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

---

## Example 5: Staking conversion realizes P&L — `ETH → cmETH → ETH`

> **Target model — PLANNED, not yet implemented.** Per [eth-family-tracking-model plan](../tasks/eth-family-tracking-model-implementation-plan.md) §6.4 / §7b (decision P1). Under the *current* engine a value-accruing LST is carried 1:1 with no conversion PnL (see `STAKING_DEPOSIT` in [AVCO rules](../pipeline/cost-basis/02-avco-rules.md)); this example documents the accepted target behavior once the per-asset-pool model ships.

cmETH is a **C2** value-accruing LST → its **own** cost-basis pool with its **own** market price. An `ETH↔cmETH` identity change is a **disposal + acquisition at market** (realizes P&L); it does **not** carry ETH's AVCO onto cmETH. A **same-token** move (cmETH corridor/bridge) still carries with no PnL.

Lanes: **Market** (formerly "Tax" — income booked at FMV) and **Net** (income booked at $0). For pure market-priced conversions both lanes move identically.

| Step | Event | Dispose @ market | Realized P&L (Market = Net) | Resulting pool |
|------|-------|------------------|------------------|----------------|
| 0 | BUY 1 ETH @ $2000 | — | — | ETH: 1.0, basis $2000, AVCO $2000 |
| 1 | STAKE ETH→cmETH (ETH $3000 → 0.9709 cmETH @ ≈$3090) | 1 ETH @ $3000 | **+$1000** | cmETH: 0.9709, basis $3000, AVCO $3090 (fresh, = market) |
| 2 | UNSTAKE cmETH→ETH (0.9709 cmETH @ ≈$3710 → 1.029 ETH @ $3500) | 0.9709 cmETH @ $3710 | **+$602** | ETH: 1.029, basis $3602, AVCO $3500 (fresh, = market) |

**Conservation:** Σ realized P&L = $1000 + $602 = **$1602** = final value $3602 − initial $2000. ✓ ETH's $2000 AVCO is **never** carried onto cmETH.

**Lane divergence appears only on income** (rebase / `REWARD_CLAIM` / lending interest), never on a market-priced conversion — e.g. a stETH rebase of +0.05 @ $2200 books $110 basis in the **Market** lane (AVCO ≈ stable) but $0 in the **Net** lane (Net AVCO drops → reward is pure future profit). The pre-model zero-cost path that added quantity at $0 basis in the AVCO-authoritative Market lane is removed.

## Example 6: Bybit Funding-History `Crypto Loans` — TON borrow → withdraw → deposit → repay

Per [ADR-055](../adr/ADR-055-fh-crypto-loans-source-authority.md). A FUND-only `Crypto Loans` cycle
(no UTA `TRANSACTION_LOG` counterpart) is now promoted at extraction and modeled by the existing loan
machinery (`bybit-pledge:<uid>:<asset>` revolving key, `borrow_liabilities`, market-at-borrow basis).
`TON`, `BYBIT:33625378`, 2025-08.

| Step | Event (FH `Crypto Loans`) | qty | Ledger / liability |
|------|---------------------------|-----|--------------------|
| 1 | BORROW `Borrow Released` (05:58:01) | +80 TON | BUY leg → +80 TON on the **umbrella** spot key (never `:FUND`), basis = market-at-borrow; `borrow_liabilities` OPEN `bybit-pledge:33625378:TON` qtyBorrowed 80 |
| 2 | Withdraw (05:59:20) | −80 TON | `EXTERNAL_TRANSFER_OUT` fully covered by the borrowed inventory → `quantityShortfallAfter = 0` (was 15.17) |
| 3 | Deposit (2025-08-22) | +80.15 TON | external inbound (basis at market) |
| 4 | REPAY `Borrow Repayment` (2025-08-22) | −80.088 TON | SELL leg → repay nets the opening BORROW on `bybit-pledge:33625378:TON` → principal realizes **$0**; liability CLOSED |

**Interest / residual:** repay 80.088 − principal 80.0 = **0.088 TON** = loan interest (realizes loan
P&L, not a spot disposal). Loan nets to ≈0; the only cost is the ≈0.088 TON interest — **not** a
genuine loss. `Loans`-product rows and all loan `FEE`/`Repay Interest` rows stay demoted (they shadow
the authoritative UTA `TRANSACTION_LOG`); the USDT `Loans / Pledge Assets` −523 (negative) is a
collateral lock, never a BORROW inflow.

## Related

- [Replay overview](../pipeline/replay/01-overview.md)
- [Borrow liability](../pipeline/cost-basis/04-borrow-liability.md)
- [ADR-055 — FH `Crypto Loans` source-authority](../adr/ADR-055-fh-crypto-loans-source-authority.md)
