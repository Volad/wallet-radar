# Portfolio Snapshot — Conservation Gate

> **ADR:** [ADR-014](../../adr/ADR-014-portfolio-conservation-gate.md)  
> **Class:** `PortfolioConservationGate` — `backend/.../ingestion/wallet/query/PortfolioConservationGate.java`

Evaluated at **dashboard GET time**, not during snapshot refresh.

## Formula

```
NEC = lifetimeFundInflow − lifetimeFundOutflow
      (BYBIT:*:FUND external transfers, non-universe counterparties)

MTM = dashboardPortfolioValue + Σ(member counterpartyBasisPool qtyHeld × AVCO)

adjustedMTM = MTM − openBorrowLiabilityUsd

expectedPnL = adjustedMTM − NEC
reportedPnL = totalRealisedPnL + totalUnrealisedPnL
delta = reportedPnL − expectedPnL

threshold = max($50, 1% × |adjustedMTM|)
breached = |delta| > threshold
```

## Inputs

| Source | Use |
|--------|------|
| `counterparty_basis_pools` | Non-member pool MTM contribution |
| `borrow_liabilities` | Open liability USD subtraction |
| `accounting_universes` | Member scope |
| `normalized_transactions` | FUND external in/out lifetime flows |

On breach: structured WARN log with top non-member pools and pending positions.

## Rules by transaction type

| Types affecting gate |
|---------------------|
| `EXTERNAL_TRANSFER_IN` / `OUT` on Bybit FUND vs non-universe | NEC |
| `BORROW` / `REPAY` | Open borrow liabilities |
| All types contributing to realised/unrealised PnL | reportedPnL |
| Counterparty pool movements | MTM adjustment |

## Related

- [Borrow liability](../cost-basis/04-borrow-liability.md)
- [Basis pools](../cost-basis/03-basis-pools-and-carry.md)
