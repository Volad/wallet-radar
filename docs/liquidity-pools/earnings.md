# Liquidity Pools — Earnings & Financial Reconciliation

## Authoritative vs estimate

| Field | Source | Precision |
|-------|--------|-----------|
| `costBasisUsd`, `withdrawnUsd` | `lp_receipt_basis_pools` | EXACT |
| `fees.claimedUsd`, `summary.realizedPnlUsd` | `asset_ledger_points` (`LP_FEE_CLAIM`) | EXACT |
| `tvlUsd`, `fees.unclaimedUsd`, `il`, `apr*`, `netPnlUsd` | Live snapshot + derived math | ESTIMATE |
| Range/IL for GMX/Pendle/fungible | — | NOT_APPLICABLE |
| Missing price or unreadable on-chain | — | UNAVAILABLE (never $0) |

## Two P&L anchors (do not blend)

### 1. Economic LP performance (EST, open positions)

For **open** positions, `SessionLpQueryService` isolates LP-specific performance (IL + fees) from the user's pre-LP asset price history.

**openBase** selection order (first non-zero wins):

1. `depositedMarketUsd` — sum of `valueUsd` on LP entry flows (exact entry market price). **Note:** the current pricing pipeline does not set `valueUsd` on LP TRANSFER flows, so this is almost always zero today.
2. `hodlNow` — entry token quantities × current price (`entryQtyBySymbol`, not net-contributed after partial exits). Sets `openBaseIsHodl = true`.
3. AVCO `costBasisUsd` — last resort (shows asset-level gain/loss vs average purchase price; confusing when user has high-basis assets).

```
netPnlUsd (open) = tvlUsd + feesTotal + withdrawnUsd − openBase
                 ≈ IL + feesTotal   (when openBase = hodlNow)
```

When `openBaseIsHodl = true`, **price appreciation is suppressed** (`priceAppreciationUsd = null`, precision `UNAVAILABLE`) because the difference would always be zero. For GMX/Pendle single-receipt families, price appreciation is `NOT_APPLICABLE`.

**Entry vs Current (UI):** for open positions, "At entry" TVL = `entryToken0.usd + entryToken1.usd` (entry qty × current price = hodlNow components). Δ column ≈ impermanent loss. For closed positions, "At entry" uses AVCO `costBasisUsd`.

### 2. Accounting unrealized vs basis (EXACT basis, EST mark)

```
AccountingUnrealized = (V + F_u) − B_avco
```

Where `B_avco` = sum `basisHeldUsd` from `lp_receipt_basis_pools`. This reconciles to AVCO; not decomposed into IL/price appreciation.

### Closed positions

```
netPnlUsd (closed) = withdrawnUsd + feesTotal − depositsUsd
```

Where `depositsUsd = costBasisUsd` when > 0, else `depositedMarketUsd`.

Closed positions: `tvlUsd = 0`, `unclaimedUsd = 0`, IL/priceAppreciation precision = `NOT_APPLICABLE` (not merely null).

## HODL baseline

```
hodlNow = Σ entryQty(symbol) × P_now(symbol)
```

Derived from `entryQtyBySymbol` accumulated at LP entry/add flows — **original entry quantities only**, without subtracting partial exits. Used for IL calculation (`IL = tvlUsd − hodlNow`) and as openBase fallback.

Per-token `hodlUsd` on current token sides = entry qty for matching symbol × current price (for UI comparison).

## Realized P&L rule

LP-scoped realized P&L == **claimed fee income only**. LP principal exit is `REALLOCATE_IN` (basis carry), not a disposal. Never compute realized as `withdrawals − deposits`.

## APR

### Lifetime APR (`apr.avgPct`)

Computed in `buildApr()`:

```
lifetimeApr = feesForApr / aprBase / daysActive × 36500   (percent)
```

- **Open positions:** `aprBase = openBase`; period end = `Instant.now()`.
- **Closed positions:** `aprBase = depositsUsd` (or `hodlNow` fallback when no deposit basis); period end = `closedAt`.
- `daysActive = max(1, Duration.between(enteredAt, periodEnd).toDays())` — positions younger than 24h use 1 day, which annualizes short-term returns.

**feesForApr** selection for closed positions:

1. Start with `feesTotal = claimedUsd + unclaimedUsd`.
2. If earning series exists: sum only **positive** `dailyEarnedUsd` entries; use max(seriesSum, feesTotal) when seriesSum > 0.001.
3. If still zero and `withdrawnUsd > 0`: `feesProxy = max(0, withdrawnUsd − hodlNow)` — net LP advantage over HODL at current prices.

**Rewards (CAKE/AERO/VELO staking emissions) are not included** in APR — only swap-fee income from snapshot/ledger.

### Daily APR bars (`aprDaily`)

Written by `LpPositionRefreshService.upsertTodayEarningPoint()` on each refresh:

```
dailyAprPct = dailyEarnedUsd / tvlUsd × 36500   (percent)
```

Each bar reflects earnings accumulated **within that calendar day** (partial first/last days produce lower bars). This is **not** the same formula as lifetime APR — for a position open < 24h, daily bars (~7–10%) will be lower than lifetime APR (~17%) because lifetime annualizes total fees over `max(1, daysActive)` while daily bars annualize partial-day earnings. This is expected, not a bug.

### Point-in-time APR (`apr.nowPct`)

Field exists on snapshot (`snapshot.aprNow`) but is **not currently written** by enrichment — always null unless legacy DB data.

## Daily earnings

**Never use TVL deltas** — price moves would leak in as "earnings".

```
cumulativeFees(t) = ledgerClaimed(≤t) + unclaimed(snapshotAt)
dailyEarning(day) = cumulativeFees(endOfDay) − cumulativeFees(priorDay)
```

- Claim add-back mandatory: a fee claim resets unclaimed to ~0; without add-back, daily earning goes negative.
- Accrual is in **USD only** (`cumulativeEarnedUsd`, `dailyEarnedUsd`) — not token-unit accrual revalued per interval.
- `lp_earning_points` keyed `_id = correlationId:YYYY-MM-DD`, upserted idempotently on each refresh.
- **No historical ledger seed:** first earning point is created on first successful refresh; prior cumulative defaults to current `claimedUsd`. Charts start when tracking starts (forward-only).

## Fee anti-double-count

Include `unclaimedUsd` only when `snapshotAt > latest LP_FEE_CLAIM` for the position. Otherwise exclude and flag stale.

## Closed-position detection (beyond LP_EXIT_FINAL)

| Signal | Effect |
|--------|--------|
| `LP_EXIT_FINAL` transaction | Closed |
| Basis pool receipt burn (`basisPoolCount > 0`, `costBasisUsd == 0`, `withdrawnUsd > 0`) | Closed |
| Stake-only positions (`stakeUnstakeCount > 0`, `!hasLpEntry`, zero basis) | Closed (Velodrome vault pattern) |
| On-chain snapshot `status = closed` | Closed |
| GMX reader: zero balance | Closed |

## Reconciliation invariants (test suite)

1. page `costBasisUsd` == sum `basisHeldUsd` in `lp_receipt_basis_pools`
2. `fees.claimedUsd` == sum ledger `LP_FEE_CLAIM` `costBasisDeltaUsd`
3. `summary.realizedPnlUsd` == sum ledger `realisedPnlDeltaUsd` (principal flows = 0)
4. closed => `tvlUsd==0`, `unclaimedUsd==0`, IL/priceAppreciation `NOT_APPLICABLE`
5. unclaimed gated by snapshot vs latest claim timestamp

**Not enforced / not tested:**

6. `netPnlUsd == il + priceAppreciation + feesTotal` — breaks when `openBaseIsHodl` (priceApp null), partial exits (`withdrawnUsd` in netPnl), or closed positions use AVCO denominator.
7. HODL quantities == `Σ entry qty − Σ exit qty` — code uses entry qty only (see HODL baseline above).
