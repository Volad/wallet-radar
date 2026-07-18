# ADR-051 — CEX Fee Capitalization into Net AVCO (Market = clean price)

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-09 |
| **Theme** | AVCO / Cost-basis — CEX venues |

---

## Context

WalletRadar computes two parallel cost-basis lanes per asset:

- **Market AVCO** (`perWalletAvco` / `totalCostBasisUsd`) — the clean fill-price weighted average; used as the headline "average cost" metric.
- **Net AVCO** (`perWalletNetAvco` / `netTotalCostBasisUsd`) — what the user actually paid, including all fees.

Before this ADR both lanes were identical for CEX spot trades. Investigation revealed two independent fee-loss bugs:

### Dzengi (explicit USD FEE leg)

Dzengi charges a USD commission on every spot trade (typically 0.05 %). The commission is recorded as a separate `FEE` leg on the normalized transaction, which reduces the **USD position** via `applyFee`. The fee was **never added to the bought asset's cost basis** → Market AVCO = Net AVCO (incorrectly).

### Bybit (fee netted into received quantity)

`BybitCanonicalMappedRowSupport.netExecutionLegQuantity` adds `feePaid` (negative) to `quantityRaw`, so the net received quantity is correct. However, the fill-price cost is computed as `netQty × filledPrice`, which is slightly *less* than the actual USDT paid → the fee value silently disappears. Market AVCO = Net AVCO (incorrectly — the gap is ~0.1 % per trade).

### Root cause (engine)

`GenericFlowReplayEngine.applyBuyWithAcquisitionCost` writes the same market cost to **both** lanes. `applyBuyWithExplicitNetCost` already supports diverging lanes but was never called for CEX spot trades.

---

## Decision

Capitalize buy-side CEX commissions into the **Net AVCO lane only**. Market AVCO continues to reflect the clean fill price (matches venue platform's "average price" display and FIFO/AVCO comparison benchmarks).

### Per-flow fee signal

Add `BigDecimal acquisitionFeeUsd` to `NormalizedTransaction.Flow`. Null / zero = no capitalization (default for all on-chain flows and SELL legs).

### Population at normalization (venue-specific)

| Venue | Fee source | `acquisitionFeeUsd` on BUY leg |
|---|---|---|
| **Dzengi** | Explicit `FEE` leg (`commissionAsset = "USD"`) | `abs(commission)` — only for BUY, only when `isUsdAsset(commissionAsset)` |
| **Bybit** | `feePaid` on the base (BUY) row, already netted into qty | `abs(feePaid) × effectiveBuyUnitPrice` (stablecoin base → ×1.0, non-stable → ×filledPrice) |

The Dzengi FEE leg is kept as-is; it continues to reduce the USD position (outflow bookkeeping is unchanged — no double-count because the FEE leg hits the USD asset, not the bought asset).

### Replay capitalization (venue-agnostic, policy-gated)

New `AcquisitionFeeCapitalizationPolicy` support class reads `netAvcoFeeCapitalizationSources` from `CostBasisProperties` (default: `DZENGI, BYBIT`). After every BUY application in `ReplayDispatcher.applyBuyWithOptionalPool` (all branches: pool, swapNetRef, default), `capitalizeCexFeeIfApplicable` is called:

1. Policy check: source in configured set, flow is BUY, `acquisitionFeeUsd > 0`.
2. `GenericFlowReplayEngine.capitalizeFeeIntoNetLane(feeUsd, position)`:
   - Adds `feeUsd` to `netTotalCostBasisUsd`.
   - Adds `feeUsd` to `totalGasPaidUsd` (display stat — surfaces in move-basis "gas paid" header for the bought asset, not the USD position).
   - Recomputes `perWalletNetAvco`.
3. `totalCostBasisUsd` / `perWalletAvco` are **never modified** by this path.

### Configuration

```yaml
walletradar:
  costbasis:
    net-avco-fee-capitalization-sources:
      - DZENGI
      - BYBIT
```

Set to an empty list to disable globally.

---

## Consequences

### Expected AVCO deltas

| Asset | Market AVCO | Net AVCO |
|---|---|---|
| TSLA (Dzengi, ~14 fills) | 371.528 (unchanged) | ≈371.714 (+0.186 USD fee accumulated) |
| DOGE (Bybit, 1 fill, 0.1 % fee) | 0.239 (unchanged) | 0.2392395 (+~0.012 USD) |
| Any on-chain asset | unchanged | unchanged |

### Gas-paid header

TSLA's move-basis "gas paid" header now shows the accumulated CEX commissions paid on its position (was always zero before because Dzengi commissions only appeared on the USD sub-ledger). This is display-only; the fee value appears in both `totalGasPaidUsd` AND `netTotalCostBasisUsd`, which is correct (both lanes track different things).

### Conservation

The fee amount is **not** a new cash inflow. It was already booked as an outflow on the USD position (Dzengi) or silently consumed from the received quantity (Bybit). Net AVCO capitalization is a cost reallocation from one position to another, not a conservation change. The portfolio NEC (net external capital) gate is unaffected.

### On-chain flows

All on-chain flows leave `acquisitionFeeUsd = null`. `AcquisitionFeeCapitalizationPolicy` returns `null` for them. The capitalization call is a no-op.

---

## Parked / out of scope

- **Dzengi dividends**: not retrievable via any public Dzengi API endpoint. No AVCO impact; parked indefinitely.
- **FIAT_EXIT AVCO behavior**: tracked separately.
- **SELL-side CEX fees**: reduce realised PnL but do not affect AVCO of the remaining position; not capitalized here.
