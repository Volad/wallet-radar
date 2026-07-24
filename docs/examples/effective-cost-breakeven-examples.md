# Effective Cost / Break-Even Examples

Synthetic walkthroughs for the two headline metrics shown on the dashboard and the move-basis header:
**Balance AVCO** and **Effective cost** (break-even). See [ADR-062](../adr/ADR-062-break-even-effective-cost-metric.md),
[AVCO rules](../pipeline/cost-basis/02-avco-rules.md), and [ledger points](../reference/ledger-points-and-basis-effects.md).

> **Convention:** synthetic placeholder hashes/addresses only. Numbers are illustrative.

---

## The two metrics in one sentence

| Metric | Question it answers | Numerator (under `offsetLane=NET`) |
|--------|---------------------|-------------------------------------|
| **Balance AVCO** | "What is the accounting average cost of the units I hold?" | **Net** cost basis of held units ÷ held qty |
| **Effective cost** (break-even) | "At what sell price do I recover **all real cash** put into this family, crediting rewards as free and banking realized profit?" | **Net** held basis **−** net realized profit, ÷ rate-adjusted covered qty (floored at $0) |

Both use the **Net** lane, so **income received and still held is credited as free**. The **Market** lane
(Balance AVCO's market twin, "unrealized" view) is kept only as a demoted diagnostic.

---

## Example 1: A reward-sourced staked asset is (almost) free — `sAVAX`-style

A staked derivative where nearly all units arrived as staking rewards (`REWARD_CLAIM`, net basis $0),
plus one tiny real purchase. Mirrors the real `FAMILY:SAVAX` defect fixed on 2026-07-24.

| Step | Event | qty Δ | Market basis Δ | **Net basis Δ** | Note |
|------|-------|------:|---------------:|----------------:|------|
| 1 | small real buy (AVAX→stake) | +0.07 | +$1.41 | **+$1.41** | the only real cash in |
| 2 | `REWARD_CLAIM` ×N | +2.35 | +$27.52 | **$0** | free staking rewards (market re-values them, net = $0) |
| Terminal | held | **2.42** | $28.93 | **$1.28** | — |

- **Balance AVCO (net)** = $1.28 ÷ 2.42 = **$0.53/unit**.
- **Effective cost** = (netBasis $1.28 − netRealized $0) ÷ 2.42 = **$0.53/unit**.
- **Market AVCO (diagnostic)** = $28.93 ÷ 2.42 = **$11.96/unit** — this is what the metric *wrongly* showed
  before the fix, because the numerator used the Market lane and rewards generated no realized P&L to
  offset. Held rewards must reduce effective cost even while held.

**Takeaway:** if an asset came from rewards, its effective cost is near $0 — you break even almost
immediately. Borrowed inflows are the opposite (see Example 4).

---

## Example 2: Buy + reward + partial sell — no double-count (mixed HELD/DISPOSED)

Proves the invariant: every unit is either **HELD** (net basis, reward = $0) **or** **DISPOSED**
(in net realized), never both.

| Step | Event | qty | price | Effect |
|------|-------|----:|------:|--------|
| 1 | BUY | +10 | $100 | net basis +$1000; held 10 |
| 2 | `REWARD_CLAIM` | +2 | $120 | net basis **+$0** (free); held 12 |
| 3 | SELL | −4 | $150 | DISPOSE 4 @ net AVCO ($1000/12 = $83.33) → net realized **+$266.67**; held 8 |

- Held net basis after step 3 = $1000 × 8/12 = **$666.67**; held qty **8**.
- **Effective cost** = (net held $666.67 − net realized $266.67) ÷ 8 = **$50.00/unit**.
- The reward unit's value is counted **once**: the 2 free units are inside the $666.67 held basis at $0,
  and the banked $266.67 is credited separately. No overlap.

---

## Example 3: Past break-even → `$0` + locked surplus

When banked realized profit exceeds the remaining real cash, effective cost floors at **$0** and the
excess is reported as **locked surplus** ("past $0 by $X").

| Step | Event | Effect |
|------|-------|--------|
| 1 | BUY 10 @ $100 | net held basis $1000 |
| 2 | SELL 6 @ $250 | net realized +$900 (cost $600, proceeds $1500); held 4, net held basis $400 |
| Result | — | effectiveBasis = $400 − $900 = **−$500** → break-even **$0**, **lockedSurplus $500** |

The remaining 4 units are pure upside — you have already recovered every dollar put in.

---

## Example 4: Borrowed inflow is **not** free income

Borrowed assets enter with **net basis = market basis** (a repayment obligation), so they do **not**
lower effective cost the way rewards do.

| Step | Event | qty Δ | Market basis Δ | Net basis Δ |
|------|-------|------:|---------------:|------------:|
| 1 | `BORROW` 1000 USDC | +1000 | +$1000 | **+$1000** | 
| — | liability `borrow_liabilities` OPEN | — | — | — |

- Effective cost of the borrowed USDC ≈ **$1.00/unit** (net ≡ market) — correctly **not** ~$0.
- Contrast with Example 1: rewards are net $0 (free); borrows are net = market (owed).

---

## Example 5: Rewards on ETH move the needle only a little

For a family that is mostly **real purchases** with a thin slice of held staking yield, effective cost
drops only marginally below market — the opposite end of the spectrum from Example 1.

| — | Market basis | Net basis | held qty | Effective cost |
|---|-------------:|----------:|---------:|---------------:|
| ETH family | $12,511 | $12,313 | 3.82 | $12,313 ÷ 3.82 ≈ **$3,223/ETH** (≈ −1.6% vs market $3,275) |

Only ~1.6% of the ETH basis is held free income, so the correction is small — which is why ETH's
break-even barely moves while sAVAX collapses by 95%.

---

## Related

- [ADR-062 — break-even effective-cost metric](../adr/ADR-062-break-even-effective-cost-metric.md) (incl. 2026-07-24 held-reward-income amendment)
- [ADR-083 — cluster-carry intra-cluster conversions](../adr/ADR-083-cluster-carry-intra-cluster-conversions.md)
- [AVCO / replay examples](avco-replay-examples.md) · [Move basis carry examples](move-basis-carry-examples.md)
- [Basis pools and carry](../pipeline/cost-basis/03-basis-pools-and-carry.md)
