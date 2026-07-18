# ADR-062 — Break-even (effective-cost) metric with configurable cross-family PnL attribution

- **Status:** Accepted
- **Date:** 2026-07-18
- **Theme:** Read-model / cost basis / dashboard + move-basis UI
- **Relates to:** ADR-040 (dual cost basis — Net/Market AVCO), ADR-054 (per-asset AVCO for staked derivatives), ADR-045 / ADR-061 (move-basis series), ADR-059 (config-plane pattern)

## Context

AVCO answers *"what is the average carried cost of the units I still hold?"*. It is deliberately **balance-only**: realized P&L that has already been taken out of an asset does not lower the cost of the units that remain.

Users think about a second, complementary question: *"at what price do I break even on this asset, given the profit I already realized on it (and on its economically-related assets)?"*. Concretely: the user sold **cmETH** for a **+$2.5k realized profit** and rotated into **ETH** via USDT. AVCO for ETH is correctly ~$3,029/ETH, but the user's *effective* cost is lower because the ETH purchase was partly funded by realized cmETH profit. cmETH and ETH are **separate accounting families** (ADR-054 keeps them separate for AVCO correctness — cmETH is C1→`FAMILY:METH`, a distinct pool from `FAMILY:ETH`), so this cross-asset relationship is invisible to AVCO.

We want a **break-even (effective-cost)** metric that:

1. Credits **realized P&L** back against the remaining basis to produce an effective per-unit cost.
2. Can attribute a child asset's realized P&L to a **parent family** (e.g. staked-ETH cluster → ETH) via configuration, so families stay separated for AVCO but roll up correctly for break-even.
3. Does **not** distort AVCO or any replay/ledger state — it is a pure read-model derivation.

### Why the Market lane (not Net)

Portfolio-wide **Net** realized P&L is ~$21k, but this includes ~$17k of **zero-basis income** (rewards, airdrops, funding, yield) that the Net lane recognizes at $0 acquisition cost. Crediting that income against basis would drive break-even below $0 for most assets and misrepresent it as "trading profit". The **Market lane** prices zero-basis inflows at fair-market-value at receipt, so its realized P&L (~$4k portfolio-wide) is **trading profit only** and already excludes income principal. Therefore break-even uses the **Market lane** realized P&L as the offset, and income is surfaced separately as an informational figure.

## Decision

Introduce a **read-model-only** break-even calculator and a **configurable attribution plane**. No changes to the AVCO replay engine, RPC usage, or Mongo mutations.

### 1. Metric definitions (per accounting family `Y`)

All inputs come from existing read-model aggregations over `AssetLedgerPoint`:

- `marketBasisUsd(Y)` = current Market-lane cost basis of covered holdings (`totalCostBasisUsd`).
- `coveredQty(Y)` = basis-backed held quantity (existing `coveredQuantity`).
- `marketRealizedPnlUsd(src)` = Σ `realisedPnlDeltaUsd` for family `src` (Market lane; trading-only, income-neutral).
- `netRealizedPnlUsd(src)` = Σ `netRealisedPnlDeltaUsd` for family `src` (Net lane; includes income principal).

Attribution (see §2) partitions every family `src` to exactly one **target** family `attributionTarget(src)`. Then:

- `attributedRealizedPnlUsd(Y)` = Σ over `src` where `attributionTarget(src) == Y` of `marketRealizedPnlUsd(src)`.
- `attributedOffsetUsd(Y)` = `max(attributedRealizedPnlUsd(Y), 0)` — **only realized net profit discounts effective cost** (see "Loss floor" below).
- `effectiveBasisUsd(Y)` = `marketBasisUsd(Y) − attributedOffsetUsd(Y)`.
- `breakEvenUsd(Y)` = `coveredQty(Y) > 0 ? max(effectiveBasisUsd(Y), 0) / coveredQty(Y) : null`.
- `lockedSurplusUsd(Y)` = `effectiveBasisUsd(Y) < 0 ? −effectiveBasisUsd(Y) : 0` — realized profit that has already fully recovered the remaining basis ("you are past break-even by this much").
- `incomeReceivedUsd(Y)` = `Σ (netRealizedPnlUsd(src) − marketRealizedPnlUsd(src))` over the same attributed `src` — informational; the zero-basis income booked against `Y`'s cluster.

**Effective cost ∈ [0, AVCO].** The metric is surfaced in the UI as **"Effective cost"** (per remaining unit).

**Loss floor (why the offset is floored at 0).** The offset only ever *reduces* effective cost by realized profit; a realized net **loss** never *raises* it. Without this floor, dividing a fixed lifetime dollar loss by a small remaining quantity produces an absurd per-unit figure — e.g. USDT that has been mostly spent (covered ≈ 7 units) with a −$116 lifetime trading loss would show ≈ $17/unit. Flooring the offset keeps effective cost ≤ AVCO and intuitively bounded: *"your average cost, reduced by the trading profit you already banked, and never worse than your average cost."* Realized losses remain fully visible in realized P&L; they simply do not inflate the cost of units still held.

**Floor at $0 (upper credit).** When banked profit exceeds the remaining basis, effective cost floors at $0 and the excess is reported as `lockedSurplusUsd`.

### 2. Configurable attribution plane

A new classpath config `break-even-attribution.json` (ADR-059 loader→service style) maps a **source key** (a `CLUSTER:*` or `FAMILY:*` identity) to a **target `FAMILY:*`**. It is explicit and applies to **every family**, not just ETH — staking clusters for SOL and AVAX roll up the same way:

```json
{
  "version": 1,
  "attributions": [
    { "source": "CLUSTER:ETH_STAKING",  "target": "FAMILY:ETH"  },
    { "source": "CLUSTER:SOL_STAKING",  "target": "FAMILY:SOL"  },
    { "source": "CLUSTER:AVAX_STAKING", "target": "FAMILY:AVAX" }
  ]
}
```

Cluster keys are defined by `AccountingAssetClassificationSupport.normalizationClusterForSymbol(...)`. A family with no matching cluster/family entry self-attributes, so effective cost degrades to `AVCO − bankedProfit/qty` for standalone assets.

**Resolution for a family `src` (with a representative asset symbol):**

1. If an explicit `FAMILY:*` → target entry exists for `src`, use it (explicit overrides cluster).
2. Else compute `cluster = normalizationClusterForSymbol(symbol)` (`AccountingAssetClassificationSupport`); if a `CLUSTER:* → target` entry exists **and** `target != src`, use `target`.
3. Else `src` maps to itself (self-attribution).

**Partition invariant (no double count):** each family resolves to exactly one target. A family that redirects to a parent does **not** also self-credit; its own break-even card shows the credit as *contributed to `<parent>`* and its own effective basis equals its market basis (no self-offset). The target receives its own trading P&L plus all redirected children.

### 3. Surfacing

Read-model fields added to existing DTOs (no new endpoints). The UI label is **"Effective cost"**.

- **Dashboard token card** (`SessionDashboardQueryService.TokenPositionView` → `SessionDashboardResponse.TokenPositionEntry` → frontend `TokenPosition`): `breakEvenUsd`, `lockedSurplusUsd`, `incomeReceivedUsd`, `attributionTargetFamily` (null when self). Rendered as its **own dashboard column**, not folded into the AVCO/avg-cost cell.
- **Move-basis header** (`AssetLedgerQueryService.CurrentStateView` → `SessionAssetLedgerResponse.CurrentState` → asset-ledger page): same fields, rendered as a fourth stat card next to Market / Balance / Blended AVCO with a `(?)` info hint. The hint lists the **real member assets** of the family actually present in the ledger (`familyMemberSymbols`), plus the definition and worked example.
- **Move-basis chart line** (`AssetLedgerChartService` timeline → `blendedNetAvcoAfterUsd`-style `effectiveCostAfterUsd` per timeline entry → asset-ledger chart): an **Effective-cost time series** for the viewed family, computed at each timeline point as `max(marketBasis(t) − max(cumulativeAttributedMarketPnl(t), 0), 0) / coveredQty(t)`. `cumulativeAttributedMarketPnl(t)` weaves the viewed family's own Market-lane realized P&L together with its attributed cluster children's realized P&L chronologically. Toggleable like the other AVCO lines; enabled by default. Its terminal value reconciles with the header `breakEvenUsd`. This works for every family page (ETH, SOL, AVAX, …) via the same config.

### Terminology

The metric is named **"Effective cost"** in the UI (not "break-even"), because with the loss floor it never exceeds AVCO — it represents the effective per-unit cost of the units you still hold after crediting banked trading profit.

## Consequences

- **Positive:** Users see a truthful effective-cost number; families remain separated for AVCO correctness (ADR-054 untouched); attribution is data-driven and extensible (AVAX/SOL staking clusters can opt in later); zero replay/RPC/schema risk.
- **Neutral:** Break-even is a *presentation* figure, not a tax basis. It intentionally differs from AVCO (balance-only) and from Net realized P&L (includes income).
- **Trade-off:** Because income is excluded from the offset, break-even ≥ AVCO − (trading profit / qty) and never reflects yield. Yield is shown separately as `incomeReceivedUsd`.
- **Guardrail:** The partition invariant must hold — a unit test asserts every family resolves to exactly one target and that Σ attributed market PnL across targets equals Σ market PnL across all families (conservation of the offset).

## References

- `AccountingAssetClassificationSupport.normalizationClusterForSymbol(...)` — source of cluster keys.
- `SessionDashboardQueryService`, `AssetLedgerReconciliationService`, `AssetLedgerQueryService` — read-model integration points.
- `backend/core/src/main/resources/break-even-attribution.json` — attribution config plane.
