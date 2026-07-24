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

## Example 5: Intra-cluster staking conversion **carries** basis (PnL=0) — `ETH → cmETH → ETH`

> **Cluster-carry (ADR-083).** ETH and cmETH are **both** in `CLUSTER:ETH_STAKING`, so an
> `ETH↔cmETH` form change is an **intra-cluster conversion**: it carries basis on both lanes with
> **realized PnL = 0** (REALLOCATE_OUT/IN), *not* a disposal at market. This supersedes the earlier
> realize-at-market model (ADR-054 §2) for intra-cluster conversions. Realization happens **only** on
> an exit to a **non-cluster** asset (USDT/fiat/BTC) or a **cross-cluster** move. See `STAKING_DEPOSIT`
> in [AVCO rules](../pipeline/cost-basis/02-avco-rules.md) and [ADR-083](../adr/ADR-083-cluster-carry-intra-cluster-conversions.md).

Lanes: **Market** (income booked at FMV) and **Net** (income booked at $0). On a carry both lanes move
identically (no income event) and the disposed basis re-averages onto the acquired quantity.

| Step | Event | Effect | Realized P&L | Resulting pool |
|------|-------|--------|--------------|----------------|
| 0 | BUY 1 ETH @ $2000 | ACQUIRE | — | ETH: 1.0, basis $2000, AVCO $2000 |
| 1 | STAKE ETH→cmETH (1 ETH → 0.9709 cmETH) | REALLOCATE_OUT ETH / REALLOCATE_IN cmETH | **$0** | cmETH: 0.9709, basis **$2000** (carried), AVCO ≈$2060 |
| 2 | UNSTAKE cmETH→ETH (0.9709 cmETH → 1.029 ETH) | REALLOCATE_OUT cmETH / REALLOCATE_IN ETH | **$0** | ETH: 1.029, basis **$2000** (carried), AVCO ≈$1944 |
| 3 | SELL 1.029 ETH → 3600 USDT (exit to non-cluster) | DISPOSE | **+$1600** | ETH: 0; realized against carried $2000 basis |

**Conservation:** the $2000 ETH basis is preserved across every intra-cluster hop (Σ carry-out == Σ
carry-in, PnL=0); the entire $1600 gain realizes **once**, at the exit to USDT — never phantom-realized
on the internal form changes.

**Down-conversion caveat (ADR-040 amendment):** because the carry writes basis via `restoreToPosition`
without a `min(market)` clamp, a carried lot may show `Net ≥ Market` — the disposed basis is preserved
rather than written down. Basis conservation still holds.

**Lane divergence appears only on income** (rebase / `REWARD_CLAIM` / lending interest / discrete
in-cluster reward inflow), never on a carried conversion — e.g. a stETH rebase of +0.05 @ $2200 books
$110 basis in the **Market** lane (AVCO ≈ stable) but $0 in the **Net** lane (Net AVCO drops → reward is
pure future profit). A discrete reward inflow into the cluster (extra mETH with no paired outbound)
enters at $0 basis = income (not a conversion).

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
