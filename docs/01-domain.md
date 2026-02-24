# WalletRadar — Domain Model

> **Version:** MVP v1.0  
> **Last updated:** 2025

---

## Glossary

| Term | Definition |
|------|-----------|
| **Wallet** | A blockchain address (EVM `0x…` or Solana Base58). Read-only — no signing required. |
| **Session** | The ordered set of wallet addresses currently tracked in the browser (localStorage). No backend user concept. |
| **Network** | A supported blockchain (Ethereum, Arbitrum, Solana, etc.). Each wallet×network pair is synced independently. |
| **Raw Transaction** | Immutable on-chain data as fetched from RPC. Never mutated after ingestion. |
| **Economic Event** | A normalised, network-agnostic financial event derived from one or more raw transactions. The core domain entity. |
| **Event Type** | Classification of an economic event (see Event Types below). |
| **AVCO** | Average Cost (Weighted Average). The accounting method used for all cost basis calculations. |
| **perWalletAvco** | AVCO calculated using only events from one specific wallet address. |
| **crossWalletAvco** | AVCO calculated across all wallets in the current session, treating them as a single entity. Always computed on-request — never stored. |
| **Cost Basis** | The total acquisition cost of a held asset position: `quantity × perWalletAvco`. |
| **Unrealised P&L** | `(spotPrice − AVCO) × quantity` — profit/loss on current holdings. |
| **Realised P&L** | `(sellPrice − avcoAtTimeOfSale) × sellQuantity` — profit/loss crystallised at point of sale. |
| **Flag** | A marker on an economic event indicating it requires human review or has incomplete data. |
| **Override** | A user-supplied cost price that replaces the system-derived price for a specific **on-chain** event. Stored in `cost_basis_overrides`; triggers async AVCO recalculation. |
| **Manual Compensating Transaction** | A synthetic economic event (no on-chain tx) created by the user to reconcile balance and/or AVCO. Has `quantityDelta` and optionally `priceUsd` (required when quantityDelta > 0 for AVCO). Stored with event type `MANUAL_COMPENSATING`; idempotency by `clientId`. Participates in AVCO replay in timestamp order. |
| **Reconciliation** | Comparison of **on-chain balance** (from `on_chain_balances` / CurrentBalancePoll) with **derived quantity** (from `asset_positions.quantity`). For wallets with history younger than 2 years, a mismatch triggers a warning and suggests adding a manual compensating transaction; for older wallets, comparison is shown but correction is not required. |
| **Snapshot** | A point-in-time record of portfolio state (positions, values, P&L) stored per wallet per hour. |
| **Backfill** | The initial ingestion of 2 years of transaction history when a wallet is first added. |
| **Incremental Sync** | Hourly fetch of new transactions since the last known block. |
| **Price Source** | How the USD price for an event was determined (see Price Sources below). |
| **Internal Transfer** | A transfer between two wallets both present in the current session. Does not affect AVCO. |
| **Incomplete History** | When the 2-year backfill window starts with a SELL event — prior purchase history is unavailable. |
| **On-Chain Balance** | Current asset quantity for a (wallet, network, asset) as returned by RPC at a point in time. Tracked **independently of backfill**; updated periodically (e.g. every 10 min) and on manual refresh. Used for reconciliation with derived quantity from economic events. |

---

## Core Entities

### EconomicEvent

The central domain object. Every financial action is represented as one or more economic events.

```
EconomicEvent {
  txHash:               String?        // on-chain transaction hash; null for MANUAL_COMPENSATING (synthetic id used)
  networkId:            NetworkId      // which chain
  walletAddress:        String         // which wallet this event belongs to
  blockTimestamp:       DateTime
  eventType:            EconomicEventType
  assetSymbol:          String         // e.g. "ETH", "USDC"
  assetContract:        String         // token contract address (zero for native)
  quantityDelta:        Decimal        // positive = received, negative = sent
  priceUsd:             Decimal?       // USD price per token; required for MANUAL_COMPENSATING when quantityDelta > 0
  priceSource:          PriceSource
  totalValueUsd:        Decimal        // |quantityDelta| × priceUsd
  gasCostUsd:           Decimal
  gasIncludedInBasis:   Boolean
  realisedPnlUsd:       Decimal?       // populated on SELL events only
  avcoAtTimeOfSale:     Decimal?       // AVCO snapshot at moment of sale (audit)
  flagCode:             FlagCode?
  flagResolved:         Boolean
  counterpartyAddress:  String?        // for transfer events
  isInternalTransfer:   Boolean
  protocolName:         String?        // "Uniswap V3", "Aave V3", etc.
  clientId:             String?        // UUID for idempotency (MANUAL_COMPENSATING only)
}
```

**Manual compensating events** have no `txHash` (or use a synthetic event id). They have `quantityDelta` and optionally `priceUsd`; for positive `quantityDelta`, `priceUsd` is required for AVCO. They are stored in `economic_events` (with a marker, e.g. `eventType=MANUAL_COMPENSATING`) and participate in AVCO replay in `blockTimestamp` order like any other event.

### AssetPosition

Derived state — always recomputable from economic events.

```
AssetPosition {
  walletAddress:        String
  networkId:            String
  assetSymbol:          String
  assetContract:        String
  quantity:             Decimal        // derived from economic events (AVCO replay)
  perWalletAvco:        Decimal        // AVCO for this wallet only
  // crossWalletAvco: NOT stored — computed on-request
  totalCostBasisUsd:    Decimal        // quantity × perWalletAvco
  totalGasPaidUsd:      Decimal
  totalRealisedPnlUsd:  Decimal        // cumulative realised P&L for this asset
  hasIncompleteHistory: Boolean        // true if first event in history is SELL
  hasUnresolvedFlags:   Boolean
  unresolvedFlagCount:  Int
  lastEventTimestamp:   DateTime
  lastCalculatedAt:     DateTime
  // Reconciliation: either stored here or computed at read time from on_chain_balances vs quantity
  onChainQuantity:      Decimal?       // from on_chain_balances at last poll (optional if computed at read)
  onChainCapturedAt:    DateTime?     // when on-chain balance was captured
  reconciliationStatus: String?       // MATCH | MISMATCH | NOT_APPLICABLE
}
```

Reconciliation status is either stored on `asset_positions` (e.g. updated when balances or positions change) or computed at read time by comparing `on_chain_balances.quantity` with `asset_positions.quantity` for the same (wallet, network, asset).

### PortfolioSnapshot

Hourly point-in-time record. Per-wallet only — never aggregate.

```
PortfolioSnapshot {
  snapshotTime:     DateTime    // truncated to hour
  walletAddress:    String      // always set — no null/aggregate snapshots
  networkId:        String?     // null = all-network rollup for this wallet
  assets:           AssetSnapshot[]
  totalValueUsd:    Decimal
  unresolvedCount:  Int
}

AssetSnapshot {
  assetSymbol:      String
  quantity:         Decimal
  perWalletAvco:    Decimal
  spotPriceUsd:     Decimal
  valueUsd:         Decimal
  unrealisedPnlUsd: Decimal
  isResolved:       Boolean
}
```

---

## Event Types

| EventType | AVCO Effect | Realised P&L | In Scope MVP |
|-----------|-------------|--------------|--------------|
| `SWAP_BUY` | Increases quantity and adjusts AVCO upward | — | ✅ |
| `SWAP_SELL` | Decreases quantity; AVCO unchanged | ✅ computed | ✅ |
| `INTERNAL_TRANSFER` | No AVCO change on either wallet | — | ✅ |
| `STAKE_DEPOSIT` | Treated as transfer out (quantity decreases) | — | ✅ |
| `STAKE_WITHDRAWAL` | Treated as transfer in at original AVCO | — | ✅ |
| `LP_ENTRY` | Flagged `LP_MANUAL_REQUIRED` — complex IL | — | ⚠️ flagged |
| `LP_EXIT` | Flagged `LP_MANUAL_REQUIRED` | ✅ if resolved | ⚠️ flagged |
| `LEND_DEPOSIT` | Treated as transfer out | — | ✅ |
| `LEND_WITHDRAWAL` | Treated as transfer in | — | ✅ |
| `BORROW` | Increases quantity at current market price | — | ✅ |
| `REPAY` | Decreases quantity | — | ✅ |
| `EXTERNAL_TRANSFER_OUT` | Decreases quantity | — | ✅ |
| `MANUAL_COMPENSATING` | Increases or decreases quantity; AVCO updated if priceUsd provided (required for positive delta) | — | ✅ |

---

## Price Sources

| PriceSource | Description | Priority |
|-------------|-------------|----------|
| `STABLECOIN` | Hardcoded $1.00 for USDC/USDT/DAI/GHO/USDe/FRAX | 1 — overrides all |
| `SWAP_DERIVED` | Calculated from tokenIn/tokenOut ratio in DEX swap event | 2 — preferred |
| `COINGECKO` | CoinGecko `/coins/{id}/history` API call | 3 — fallback |
| `MANUAL` | User-supplied override price | Applied over any source |
| `UNKNOWN` | All resolvers failed — event flagged | Last resort |

---

## Flag Codes

| FlagCode | Meaning | Resolution |
|----------|---------|------------|
| `EXTERNAL_INBOUND` | Received tokens from unknown address (not in session) | Auto-resolved if sending wallet is later added to session |
| `PRICE_UNKNOWN` | No price could be determined for this event | User provides manual override |
| `LP_MANUAL_REQUIRED` | LP entry/exit requires manual review (impermanent loss complexity) | User reviews and confirms or overrides |
| `REWARD_INBOUND` | Staking/farming reward received — classify as income | User confirms |
| `UNSUPPORTED_TYPE` | Transaction shape not recognised by any classifier | Manual review |

---

## Domain Invariants

These rules must never be violated by any module:

| # | Invariant |
|---|-----------|
| INV-01 | AVCO is always calculated in strict `blockTimestamp ASC` order |
| INV-02 | Raw transactions are immutable — `rawData` field is never modified after ingestion |
| INV-03 | `INTERNAL_TRANSFER` between session wallets never contributes to AVCO on either side |
| INV-04 | `crossWalletAvco` is never stored — always computed on-request for the exact wallet set in the API call |
| INV-05 | `crossWalletAvco` is always global across all networks — network filter never changes the AVCO value |
| INV-06 | All monetary calculations use `Decimal128` / `BigDecimal` — no floating point until JSON serialisation boundary |
| INV-07 | `realisedPnlUsd = (sellPriceUsd − avcoAtTimeOfSale) × |sellQuantity|` — computed atomically with AVCO replay |
| INV-08 | A `cost_basis_override` with `isActive=true` supersedes the original `priceUsd` in all AVCO replays |
| INV-09 | `hasIncompleteHistory=true` if the chronologically earliest event for an asset is a SELL or transfer-out |
| INV-10 | GET endpoints make zero RPC calls and perform zero heavy computation on the request path |
| INV-11 | `txHash + networkId` uniqueness is the idempotency key for all ingestion |
| INV-12 | Gas cost is included in cost basis for BUY events by default; excluded for all other event types unless explicitly overridden |
| INV-13 | Manual compensating events have no txHash (or synthetic id); they are ordered by timestamp (user-provided or "end of timeline") and included in AVCO replay in `blockTimestamp ASC` order |
| INV-14 | Idempotency for manual compensating transactions is enforced by `clientId` — duplicate clientId returns same event / no duplicate event |

---

## Aggregate Rules

### AVCO Formula

```
On BUY event (quantity Q at price P):
  newAvco = (currentAvco × currentQty + P × Q) / (currentQty + Q)
  newQty  = currentQty + Q

On SELL event (quantity Q at price P):
  avcoAtTimeOfSale = currentAvco           // snapshot for audit
  realisedPnlUsd   = (P − currentAvco) × Q
  newAvco          = currentAvco           // AVCO unchanged on sell
  newQty           = currentQty − Q

On INTERNAL_TRANSFER:
  Source wallet: newQty = currentQty − Q   // quantity decreases, AVCO unchanged
  Dest wallet:   newQty = currentQty + Q   // quantity increases at source AVCO
                 newAvco = weighted average if dest already holds the asset
```

### Cross-Wallet AVCO (on-request)

```
1. Load ALL economic_events for assetSymbol across all wallets[] in request
2. Sort by blockTimestamp ASC (unified timeline)
3. Skip INTERNAL_TRANSFER events (between own wallets)
4. Run AVCO formula sequentially over merged timeline
5. Return result — never persist
```

### Reconciliation

Reconciliation compares **on-chain quantity** with **derived quantity** (sum of quantityDelta from economic events) per (wallet, network, asset). See **ADR-009** for tolerance and 2-year rule.

**Tolerance ε:** Absolute difference only (MVP). Default `ε = 1e-8` (config e.g. `walletradar.reconciliation.tolerance=1e-8`). Same units as quantity.

| Status | Condition | Product behaviour |
|--------|-----------|-------------------|
| **MATCH** | On-chain data exists and \|onChainQuantity − derivedQuantity\| ≤ ε | No warning; balance and AVCO are considered aligned. |
| **MISMATCH** | On-chain data exists and \|onChainQuantity − derivedQuantity\| > ε | If history is "young" (see below), show warning and prompt for manual compensating transaction; otherwise show comparison only, no required correction. |
| **NOT_APPLICABLE** | No row in on_chain_balances for (wallet, network, asset) | No warning; onChainQuantity null or omitted. |

**Young history (within 2 years):** For (walletAddress, networkId), history is young iff there is at least one event and **min(blockTimestamp)** ≥ now − 2 years. **showReconciliationWarning = true** only when status is **MISMATCH** and history is young. If history is not young (oldest event &lt; now − 2 years) or there are no events for that pair, **showReconciliationWarning = false**.

---
