# WalletRadar — Accounting Policy

> **Version:** v3 target
> **Last updated:** 2026-03-21
> **Accounting method:** AVCO

---

## 1. Canonical Accounting Input

WalletRadar computes basis only from canonical documents in:

- `normalized_transactions WHERE status = CONFIRMED`

Canonical documents may originate from:

- `source = ON_CHAIN`
- `source = BYBIT`

Raw collections remain source evidence:

- `raw_transactions`
- `external_ledger_raw`

They are used for reconstruction and audit, but never replayed directly for AVCO.

Economic meaning is derived from backfill-available raw evidence and canonical
flows, not from human-readable explorer page summaries.

---

## 2. Replay Order

Replay must be deterministic:

1. `blockTimestamp ASC`
2. `transactionIndex ASC`
3. `_id ASC` as final tie-breaker

For Bybit canonical rows, `transactionIndex = 0`.

---

## 3. AVCO Rules

### On BUY

```text
newAvco = (currentAvco * currentQty + priceUsd * deltaQty) / (currentQty + deltaQty)
newQty  = currentQty + deltaQty
```

### On SELL

```text
avcoAtSale   = currentAvco
realisedPnl  = (sellPriceUsd - avcoAtSale) * abs(deltaQty)
newQty       = currentQty - abs(deltaQty)
newAvco      = currentAvco
```

### On TRANSFER

- quantity moves
- basis carries forward
- no new acquisition lot is created
- no realised PnL is created

### On FEE

- `FEE` is stored as a separate canonical flow
- fee quantity reduces the fee asset quantity and contributes to `totalGasPaidUsd`
- when policy capitalizes gas into an acquisition, replay may allocate fee USD into the same transaction's BUY basis
- outside such capitalization, `FEE` does not create a new lot and does not create realised PnL for the target asset being acquired or disposed

### On PRICE_UNKNOWN

- quantity still updates
- price fields remain null
- `hasIncompleteHistory = true`

---

## 4. Basis Continuity Rules

Basis continuity applies when the economic owner did not dispose of the asset:

- `INTERNAL_TRANSFER`
  Basis carries between tracked wallets.
- `BRIDGE_OUT -> BRIDGE_IN`
  Basis carries across networks when the bridge pair is correlated.
- `BRIDGE_OUT(depositV3) -> BRIDGE_IN(fillV3Relay/fillRelay/redeemWithFee/execute302/directFulfill)`
  Destination-side settlement remains bridge continuity, not repay or vault semantics.
- `Bybit -> on-chain`
  Same accounting universe. Correlate by `txHash` and shared `correlationId`.
- `PROTOCOL_CUSTODY`
  Basis moves into and out of custody without creating BUY/SELL.
- `LENDING_DEPOSIT -> LENDING_WITHDRAW`
  Principal basis moves between spot balance and receipt-token/custody state without realization.
- `VAULT_DEPOSIT -> VAULT_WITHDRAW`
  Basis moves between spot balance and vault-share/custody state without realization.

For matched Bybit withdraw/deposit:

- both source sides remain traceable
- canonical replay uses `TRANSFER` semantics only
- no duplicate BUY/SELL is allowed

---

## 5. Asset Identity Rules

- WETH-like wrappers are stored as-is
- wrapper-to-native aliasing is applied only at replay/pricing time when policy allows
- `stETH`, `mETH`, `rETH`, `wstETH`, and `cbETH` remain distinct assets
- LP tokens, receipt tokens, vault shares, and custody markers are not treated as new basis lots unless explicitly modeled as economic principal

---

## 6. Pricing Policy

Historical price resolution order:

1. stablecoin parity
2. swap-derived price
3. wrapper/native mapping
4. CoinGecko historical fallback
5. unresolved price flag

Implications:

- pricing failure does not remove quantity from replay
- pricing gaps must be visible as warnings or blockers
- CoinGecko is a fallback, not the primary pricing mechanism

---

## 7. Clarification Policy

Clarification may enrich:

- execution status
- gas fields
- created contract address

Clarification is allowed only when those receipt-safe fields are actually missing.
Low confidence alone does not move a row into clarification.

Clarification may not:

- treat synthetic `rawData.logs[]` as authoritative event evidence
- silently rewrite economic meaning without traceable evidence

Implications:

- ambiguous `EXTERNAL_INBOUND` vs `REWARD_CLAIM` is not a clarification problem
- plain positive inbound transfer defaults are not a clarification problem
- promo/phishing suppression is not a clarification problem
- wrapped-native `deposit()` / `withdraw(uint256)` is not a clarification problem
- bridge entry / settlement selectors are not a clarification problem
- LP position-manager `multicall` / `modifyLiquidities` routing is not a clarification problem
- missing `transactionIndex` is a raw-repair problem before normalization, not a clarification retry

---

## 8. Supported Accounting Scope

On-chain accounting networks:

- `ETHEREUM`
- `ARBITRUM`
- `OPTIMISM`
- `POLYGON`
- `BASE`
- `BSC`
- `AVALANCHE`
- `MANTLE`
- `LINEA`
- `UNICHAIN`
- `ZKSYNC`
- `KATANA`
- `PLASMA`

CEX accounting source:

- `BYBIT`

Out of scope for this milestone:

- additional CEX providers
- tax reporting
- NFTs
- rebase-token quantity tracking

---

## 9. Outputs

The replay layer must be able to produce:

- per-wallet asset quantity
- per-wallet AVCO
- realised PnL
- incomplete-history flags
- reconciliation status against available balance data

All outputs must be derivable again from the same canonical inputs with identical ordering.

For continuity events, replay must move quantity and carried basis without creating realised PnL.
