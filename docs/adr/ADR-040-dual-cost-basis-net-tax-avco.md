# ADR-040: Dual cost basis — Net AVCO and Tax AVCO

| Field | Value |
|---|---|
| **Status** | Accepted (amended 2026-07-02 — net-carry conservation invariant; UI label "Market AVCO"; amended 2026-07-13 — "Tax" lane renamed to "Market" everywhere, see ADR-054; amended 2026-07-24 — cluster-carry, see ADR-083) |
| **Date** | 2026-06-30 |
| **Theme** | Cost basis / replay / UI |

## Context

WalletRadar tracks average cost (AVCO) for portfolio analytics. Rewards, airdrops, and LP fee claims increase quantity at fair-market value (FMV), which raises the displayed average cost. Users also want a **Net AVCO** view where zero-cost acquisitions (rewards at $0 basis) lower the average — closer to "what did I actually pay per coin still held?"

Both views must share the **same quantity pool** (covered/uncovered); only cost numerators differ.

## Decision

1. **Tax AVCO** (existing semantics, field `avcoUsd`): FMV at receipt for rewards/airdrops/LP fee claims; used for realised PnL (tax-style) and secondary UI.
2. **Net AVCO** (new, field `netAvcoUsd`): $0 acquisition cost for `REWARD_CLAIM`, LP fee-claim sideflows, and other types flagged by `ZeroCostAcquisitionSupport`; primary dashboard and move-basis display.
3. **Shared quantity**: `applyBuy` / `applySell` / carry paths update quantity once; net and tax cost basis updated in parallel in `GenericFlowReplayEngine`.
4. **Persistence**: `AssetLedgerPoint` stores both lanes (`netTotalCostBasis*`, `netAvco*`, `netRealisedPnlDeltaUsd`).
5. **BORROW / REPAY**: treated identically in both lanes (not zero-cost in Net). Borrowed
   principal enters **both** lanes at market-at-borrow basis; the *liability* (tracked in
   `borrow_liabilities`) is what offsets net worth — **not** a $0 asset basis. See the
   2026-07-18 amendment for the compliance fix.
6. **API contract**: `avcoUsd` remains Tax AVCO for backward-compatible API consumers; `netAvcoUsd` is explicit Net AVCO.

## Net-carry conservation invariant (amendment 2026-07-02)

A Phase-1 financial audit (`results/blockers.md`, `results/required-changes.md`) proved that, without
explicit enforcement, every `REALLOCATE_IN`/`CARRY_IN` IN-leg silently re-seeds net basis from tax —
cancelling the reward discount entirely (ETH terminal Net $2,676.99 ≈ Tax $2,677.30 despite ~$1,701 of
$0-net income). This amendment adds the net-carry conservation rule as a **first-class invariant**:

### Net-carry conservation rule

> **Net basis travels with quantity on every IN leg, transported 1:1 from the source carry. Net basis must
> never be re-derived from (nor silently defaulted to) the tax basis, except for source-less orphan
> acquisitions where no reward-discount evidence exists.**

Concretely:

1. **Apply site is the canonical seam.** `GenericFlowReplayEngine.restoreToPosition(CarryTransfer, PositionState)`
   must credit both lanes independently: tax from `carry.costBasisUsd()`/`carry.avco()`, **net from
   `carry.netCostBasisUsd()`/`carry.netAvco()`** (peg-floor applied per-lane). All IN-leg handlers
   (WRAP/UNWRAP/REALLOCATE/CARRY_IN, LP receipt entry/exit, lending, bridge late-carry, earn REALLOCATE,
   custody) route through this seam. The 5-arg tax-only overload `restoreToPosition(qty,pos,cost,uncov,avco)`
   is retired from IN-leg use. `applyAuthoritativeLateInboundCarryBasis` carries an explicit net param.
2. **Construction safety.** `CarryTransfer` net-less general constructors are deleted; any carry produced
   from a known `PositionState`/`ContinuityBucket` must pass explicit net args. `pendingInbound*` factories
   retain `net=null→tax` (unknown until refine, correct for provisional materialization).
3. **Orphan fallback (preserve).** A source-less `CARRY_IN` or fresh priced acquire with no
   reward-discount evidence → `net = tax`. This is correct and unchanged.
4. **Runtime invariant.** A finalize-time guard asserts `|Σ netCostBasisDelta| ≤ dust` on every closed
   round-trip (WRAP↔UNWRAP, spot↔receipt, REALLOCATE_IN↔OUT) on a single position key, for **all**
   families — exactly as `Σ taxCostBasisDelta = 0` is enforced for tax.
5. **Global gate.** `Net AVCO ≤ Tax AVCO` for every asset/family in every view; `Net AVCO ≥ 0`.

### Borrow net-basis compliance fix (amendment 2026-07-18)

A financial audit (`results/repay-transferout-phantom-income-audit.md`) found that
`BorrowReplayHandler` was **non-compliant with §5**: it booked borrowed assets at Net cost
`BigDecimal.ZERO` (carrying the stale ADR-012 §D2 "basis-neutral / net-$0 borrow" reasoning),
while Market cost was correctly market-at-borrow. Because nothing offsets that $0 in the Net
lane over the loan lifecycle, **any disposal of borrowed units** (`REPAY`,
`EXTERNAL_TRANSFER_OUT`, `SWAP`) booked phantom Net-lane realized income ≈ `marketAvco × qty`
of the disposed principal.

Prior-run impact: ≥ $6,669.72 of proven phantom "zero-basis income" (REPAY $3,442.84 ≈ 100%;
`EXTERNAL_TRANSFER_OUT` ≥ $3,226.88 ≈ 93%), plus a large expected reduction in the SWAP bucket
($10,089) which shares the same root cause. Example: a Bybit Crypto-Loan MNT round-trip
(borrow 1,600 MNT → repay 1,600.35 MNT) booked +$1,733.43 Net income instead of ≈ −$0.40
(interest).

**Fix:** borrowed principal is booked at **market-at-borrow basis in both lanes**
(`netAcquisitionCostUsd = marketAcquisitionCostUsd`), so a borrow→repay/transfer/swap of the
borrowed principal nets ≈ $0 in the Net lane and only genuine interest/fees and price change on
independently-held units surface. `RepayReplayHandler` additionally mirrors the Market-lane
liability-match zeroing into the Net lane (defense-in-depth). This **supersedes the ADR-012 §D2
net-$0 borrow phrasing**. Genuine zero-cost acquisitions (`REWARD_CLAIM`, `LP_FEE_CLAIM`) remain
net-$0 per `ZeroCostAcquisitionSupport` — BORROW is deliberately excluded from that whitelist.

### SWAP net-carry ("Bug B") narrowed to non-realizing disposals (amendment 2026-07-24, ADR-082)

The `swapNetRef` SWAP net-basis propagation (the "Bug B" fix: an inbound BUY inherits the net basis
released by the paired SELL, capped `min(released, market)`) was firing **unconditionally** on every
priced SWAP. On a realizing swap between **distinct** canonical instruments whose disposal
**realized-and-kept** its NET P&L on a lot with **no reward discount** (`net ≈ market`), inheriting
the released absolute net basis onto the freshly-priced acquisition **re-plants** the pre-loop
discount and realizes the same appreciation again on the next disposal (the FB-01 cmETH↔PT-cmETH
recycling double-count; portfolio-wide NET realized inflation ≈ +$5,704).

**Amendment:** the swap net-carry applies only to **non-realizing / deferred** disposals. For a
realizing distinct-canonical swap whose NET realized was kept and whose disposed lot carried no
reward discount, the acquisition **re-bases the Net lane to the market acquisition cost**
(`net = market`). The `min(released, market)` carry is preserved for genuine reward discounts
(`net ≪ market`), unpriced disposals, and counterparty-pool / CEX-corridor disposals whose NET
realized was undone (`undoSellRealisedPnl`). The §5 invariants (`0 ≤ Net AVCO ≤ Market AVCO`,
`|Σ netCostBasisDelta| ≤ dust` on closed carry round-trips) and the borrow net-basis compliance fix
are unaffected — they govern CARRY legs, not realizing DISPOSE+ACQUIRE. See ADR-082.

### Cluster-carry: both-lane carry, PnL=0, Net≤Market cap bypassed on carried lots (amendment 2026-07-24, per ADR-083)

Intra-cluster cross-canonical conversions (ETH↔mETH, AVAX↔sAVAX, SOL↔mSOL, yvVBETH↔vBETH, …) carry
basis on **both** lanes with **realized PnL = 0** (they no longer realize at market — see ADR-083,
which supersedes ADR-054 §2 and ADR-082/FB-01 for intra-cluster conversions). The carry writes basis
via `restoreToPosition`, which does **not** clamp the Net lane down to Market. Therefore on a **carried
lot** the `Net ≤ Market` cap may equalize or be exceeded by design (a down-conversion preserves the
disposed basis rather than writing it down). The §5 `0 ≤ Net AVCO ≤ Market AVCO` invariant and any
downstream assertion of `Net ≤ Market` apply to freshly-acquired lots, **not** to cluster-carried
lots. Basis conservation `|Σ netCostBasisDelta| ≤ dust` continues to hold (carry conserves basis).

### UI terminology (amendment 2026-07-02)

The Tax AVCO lane is displayed in the UI as **"Market AVCO"** (rewards/fees booked at fair-market value).
Internal field names (`avcoUsd`, `avcoAfterUsd`, etc.) are unchanged for backward compatibility.

### Lane rename: "Tax" → "Market" everywhere (amendment 2026-07-13, per ADR-054)

The gross lane is renamed **Market** consistently across the whole system — UI labels (already done),
`avcoKind`/enum values, DTOs, code comments, docs, and ADR prose. The two lanes are henceforth **Market
AVCO** and **Net AVCO**; the term "Tax AVCO" is retired. Backward-compatible **field names**
(`avcoUsd`, `avcoAfterUsd`, `totalCostBasisUsd`, `realisedPnlDeltaUsd`) are kept — they denote the Market
lane. An ArchTest/grep gate asserts no lingering "Tax" AVCO references remain in code or docs. The Net-lane
semantics (income booked at $0) are unchanged; note ADR-054 §4 additionally forbids adding quantity at $0
basis in the **Market** (AVCO-authoritative) lane.

### Live borrow-liability true-up for receipt-less networks (amendment 2026-07-20, per ADR-071)

For receipt-less (non-EVM) lending (e.g. Jupiter Lend on Solana), the live position reader (ADR-071)
becomes authoritative for the **outstanding liability**: it **SETs / overrides** `borrow_liabilities.qtyOpen`
after AVCO replay rather than stacking on top of the classification-derived principal. This preserves the
net-carry conservation invariant — the liability offsets net worth exactly once, so
`PortfolioConservationGate` never over-subtracts. Interest accrual (e.g. 210 → 233 USDT) raises the
outstanding liability as a **real expense** and books **no realised income** (BORROW stays excluded from the
`ZeroCostAcquisitionSupport` whitelist; the interest delta is not a $0 acquisition). Stablecoin debt marks at
$1. EVM debt is unchanged — it is already live via debt-token balances.

## Consequences

- Replay handlers and carry restore must propagate `CarryTransfer.netCostBasisUsd()` / `netAvco` where
  basis moves between sub-ledgers — now a compile-enforced contract (net-less constructors deleted).
- After a net-conserving re-replay: ETH Net AVCO will be materially below Market/Tax (~$2,240–$2,600 vs
  $2,677 Tax), reflecting the ~$1,701 of $0-net LP-fee/reward income. No-reward assets (LINK/LTC/DOGE)
  remain Net≈Tax.
- Dashboard "Avg Cost" and move-basis primary chart use Net AVCO; Market/Tax AVCO shown as secondary.
- Ledger points roughly double stored cost fields; query aggregation uses covered-quantity weighting for
  both lanes.
- Phase 0 Bybit Earn corridor pairing (ADR-029 family) remains a prerequisite for trustworthy MNT
  quantities before dual-basis numbers are interpreted.

## References

- `GenericFlowReplayEngine`, `ZeroCostAcquisitionSupport`, `AssetLedgerPoint`, `CarryTransfer`
- `AssetLedgerQueryService`, `SessionDashboardQueryService`
- `docs/pipeline/cost-basis/02-avco-rules.md`, `docs/pipeline/cost-basis/03-basis-pools-and-carry.md`
- `docs/tasks/net-avco-carry-conservation-and-umbrella-double-add-implementation-plan.md`
- `docs/tasks/bybit-earn-corridor-pairing-implementation-plan.md`
