# WalletRadar — Accounting Policy

> **Version:** MVP v2.1  
> **Accounting method:** AVCO (Average Cost / Weighted Average Cost)  
> **Scope:** Cost basis, realised P&L, unrealised P&L, gas treatment

---

## Accounting Method: AVCO

WalletRadar uses **AVCO (Average Cost / Weighted Average Cost)** for all cost basis calculations.

**Why AVCO:**
- Deterministic — same inputs always produce same result
- No need to track individual lot identifiers
- Well-suited for DeFi where token quantities frequently merge and split

**Scope of AVCO:**
- Calculated per `(walletAddress, assetSymbol)` pair — **perWalletAvco**
- Also calculated on-request across all session wallets — **crossWalletAvco**
- `crossWalletAvco` is **never stored** — computed fresh for exact wallet set in each API call
- `crossWalletAvco` is **always global** across all networks — a network filter does not change the AVCO value
- Replay input is canonical confirmed operation flows from `normalized_transactions` (ADR-025)

---

## AVCO Formula

### On BUY / Receive Event

```
newAvco = (currentAvco × currentQty + eventPriceUsd × eventQty) / (currentQty + eventQty)
newQty  = currentQty + eventQty
```

**Applies to:** `SWAP_BUY`, `BORROW`, `STAKE_WITHDRAWAL`, `LEND_WITHDRAWAL`, `LP_FEE_CLAIM`, `EXTERNAL_INBOUND`

### On SELL / Send Event

```
avcoAtTimeOfSale = currentAvco          // snapshot for audit trail
realisedPnlUsd   = (sellPriceUsd − avcoAtTimeOfSale) × sellQty
newAvco          = currentAvco          // AVCO does NOT change on sell
newQty           = currentQty − sellQty
```

**Applies to:** `SWAP_SELL`, `LP_EXIT`, `LP_EXIT_PARTIAL`, `LP_EXIT_FINAL`, `LEND_WITHDRAWAL` (token out)

`LP_ADJUST`, `LP_POSITION_STAKE`, `LP_POSITION_UNSTAKE` do not represent economic buy/sell legs and must not change AVCO or realised P&L.
For v3/v4 LP position opening, `LP_ENTRY` must carry outbound principal token flows so AVCO/replay sees economic cost basis movement.

### On STAKE_DEPOSIT / LEND_DEPOSIT

Treated as outflow — quantity decreases, AVCO unchanged.  
No realised P&L is generated.

### On BORROW

Quantity increases at current market price.  
Treated as acquisition for AVCO purposes.

---

## Realised P&L

```
realisedPnlUsd = (sellPriceUsd − avcoAtTimeOfSale) × |sellQuantity|
```

- Computed atomically during AVCO replay — never separately
- Stored on confirmed SELL flows (`SWAP_SELL` / `LP_EXIT*`) in canonical transaction records
- `avcoAtTimeOfSale` is the AVCO **before** the sell quantity is deducted
- Accumulated in `asset_positions.totalRealisedPnlUsd`

**Positive value** = profit (sold above average cost)  
**Negative value** = loss (sold below average cost)

---

## Unrealised P&L

```
unrealisedPnlUsd = (spotPriceUsd − perWalletAvco) × quantity
unrealisedPnlPct = (spotPriceUsd / perWalletAvco − 1) × 100
```

- Computed at snapshot time using CoinGecko `/simple/price` spot price
- Stored in `portfolio_snapshots.assets[].unrealisedPnlUsd`
- **Not computed at read time** — served from snapshot

---

## Gas Treatment

| Scenario | Default behaviour |
|----------|------------------|
| BUY event gas | **Included in cost basis** (`gasIncludedInBasis=true`) |
| SELL event gas | **Excluded from realised P&L** |
| TRANSFER gas | **Excluded from cost basis** |
| STAKE / LEND gas | **Excluded** |

Gas cost in USD:
```
gasCostUsd = gasUsed × gasPriceWei × nativeTokenPriceUsd / 1e18
```

nativeTokenPrice is resolved using the same price chain as any other token (Stablecoin → Swap-derived → CoinGecko).

**Override:** user can toggle `gasIncludedInBasis` per event via the override mechanism.

---

## Price Resolution

Prices are resolved per event at the time of the transaction (historical price):

```
Priority chain (HistoricalPriceResolver), and at ingestion (ADR-018):
  1. StablecoinResolver     USDC/USDT/DAI/GHO/USDe/FRAX → $1.00 always
  2. SwapDerivedResolver    tokenIn/tokenOut ratio from DEX event when one leg is stablecoin; applied at ingestion for stablecoin-leg swaps, else in Phase 2 (free, on-chain)
  3. CoinGeckoHistorical    /coins/{id}/history?date=DD-MM-YYYY (free, throttled)
  4. PRICE_UNKNOWN          flag event, AVCO still calculated with quantity changes
```

**CoinGecko throttle:** token bucket at 45 req/min (leaving 5 req/min headroom for spot price calls). Deduplication via 24h Caffeine cache keyed on `(contractAddress, date)`.

**PRICE_UNKNOWN events:** quantity changes are still applied to `asset_positions`. The unknown price is flagged for user review. On override, AVCO is recalculated from that operation forward.

---

## Incomplete History

When the 2-year backfill window starts with a SELL or transfer-out event (i.e., the asset was acquired before the window):

- `asset_positions.hasIncompleteHistory = true`
- AVCO is calculated from available events only — may be understated
- UI displays warning: _"Transaction history before [date] not included — cost basis may be inaccurate"_

---

## Manual Override

User can override the `priceUsd` of any **on-chain** event:

1. Override saved to `cost_basis_overrides` with `isActive=true`
2. Async AVCO recalculation triggered (`RecalcJob`)
3. All subsequent events replay with override applied (override applies to on-chain events only)
4. `realisedPnlUsd` is recomputed for all SELL events after the override point

**Revert:** deactivates override, restores original flag, and triggers the same async replay.

---

## Manual Compensating Transaction

A **manual compensating transaction** is a synthetic normalized transaction (no on-chain transaction). It is used to reconcile balance and/or AVCO when on-chain history is incomplete or incorrect.

- **Fields:** `quantityDelta` (required), `priceUsd` (required when `quantityDelta > 0`, for AVCO), optional timestamp (default or "end of timeline").
- **Storage:** Stored in `normalized_transactions` with `type=MANUAL_COMPENSATING`; idempotency by `clientId`.
- **AVCO:** Manual compensating flows are inserted into the timeline by timestamp. AVCO replay processes them in `blockTimestamp ASC` order like any other flows — same BUY/SELL formula applies. **Override** (`cost_basis_overrides`) applies only to on-chain operations; manual compensating operations carry their own `priceUsd` and are not overridden.
- **Recalc:** Creating or deleting a manual compensating transaction triggers the same async AVCO replay (e.g. `AvcoEngine.replayFromBeginning`) as override, via the same `recalc-executor`.

---

## Cross-Wallet AVCO: Detailed Example

**Setup:** User has Wallet A and Wallet B.

```
Timeline (merged, sorted by blockTimestamp):

  t1  Wallet A  BUY  2 ETH @ $1000   → AVCO_A = $1000,  qty_A = 2
  t2  Wallet B  BUY  1 ETH @ $1500   → AVCO_B = $1500,  qty_B = 1
  t3  Wallet A  TRANSFER 1 ETH → B   → modelled as standard outflow/inflow flows
  t4  Wallet B  SELL 1 ETH @ $2000   → realisedPnl on B

Cross-wallet unified timeline:
  t1  BUY  2 ETH @ $1000  → crossAvco = $1000,  crossQty = 2
  t2  BUY  1 ETH @ $1500  → crossAvco = (1000×2 + 1500×1) / 3 = $1166.67, crossQty = 3
  t4  SELL 1 ETH @ $2000  → realisedPnl = (2000 − 1166.67) × 1 = $833.33
                          → crossAvco unchanged = $1166.67, crossQty = 2
```

---

## Accounting Invariants

| # | Rule |
|---|------|
| AC-01 | AVCO replay always processes confirmed normalized flows in `blockTimestamp ASC` order |
| AC-02 | `realisedPnlUsd` is computed at the same moment as AVCO — never separately |
| AC-03 | `avcoAtTimeOfSale` is the AVCO **before** the sell quantity is subtracted |
| AC-04 | `PRICE_UNKNOWN` flows still update quantity — only price-dependent fields are left as zero/null |
| AC-05 | `crossWalletAvco` uses canonical confirmed normalized flows only |
| AC-06 | Active `cost_basis_override` supersedes `priceUsd` in all AVCO replays |
| AC-07 | All monetary arithmetic uses `Decimal128` (MongoDB) / `BigDecimal` (Java) — no floating point |
| AC-08 | `crossWalletAvco` is global across all networks — network filter does not change its value |
| AC-09 | Manual compensating transactions participate in AVCO replay in `blockTimestamp` order via their flows; they carry their own `priceUsd` (required for positive quantityDelta) and are not subject to `cost_basis_overrides` |
| AC-10 | Reconciliation MATCH/MISMATCH uses **absolute** tolerance ε (default `1e-8`); young history and `showReconciliationWarning` per **ADR-009** |
