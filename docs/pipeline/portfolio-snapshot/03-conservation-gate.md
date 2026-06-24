# Portfolio Snapshot — Conservation Gate

> **ADR:** [ADR-014](../../adr/ADR-014-portfolio-conservation-gate.md) (design intent), [ADR-034](../../adr/ADR-034-nec-transaction-scan.md) (NEC computation algorithm)
> **Class:** `PortfolioConservationGate` — `backend/.../ingestion/wallet/query/PortfolioConservationGate.java`
> **Last updated:** 2026-06-23

Evaluated at **dashboard GET time**, not during snapshot refresh. Produces conservation metrics exposed in the `summary` block of `GET /api/v1/sessions/{id}/dashboard`.

---

## Top-level formula

```
lifetimeInflow  = fundInflow  + evmInflow
lifetimeOutflow = fundOutflow + evmOutflow

NEC (netExternalCapitalUsd) = lifetimeInflow − lifetimeOutflow

adjustedMtM = dashboardPortfolioValue
            + Σ_{member : backfillEnabled=false, qtyHeld>0} pool.qtyHeld × pool.avcoUsd
            − totalOpenBorrowLiabilityUsd

expectedPnL       = adjustedMtM − NEC
reportedPnL       = totalRealisedPnlUsd + totalUnrealisedPnlUsd   (in-scope families only)
conservationDelta = reportedPnL − expectedPnL

threshold         = max($50, 1% × |adjustedMtM|)
conservationBreached = |conservationDelta| > threshold
```

`lifetimeExternalInflowUsd` in the dashboard response is the gross `lifetimeInflow` before netting outflows. It is the "Net Inflow" header shown in the UI.

---

## NEC computation — two passes

NEC is computed by **direct transaction scan** over `normalized_transactions`, not from `counterparty_basis_pools` deltas (see [ADR-034](../../adr/ADR-034-nec-transaction-scan.md) for rationale).

### Pass 1 — Bybit FUND

Scans `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT` on wallets matching `^BYBIT:[^:]+:FUND$`.

**Guard RC1 — stablecoin filter (inflow only)**

Only stablecoin-denominated flows count. Crypto assets deposited to Bybit FUND (MNT, DOGS, SOL, …) are excluded — they are crypto-to-crypto movements, not fiat capital injections.

Accepted stablecoins (after `normalizeStablecoinSymbol`): `USDT`, `USDC`, `USDE`, `USDS`, `USDD`, `USD`.  
`normalizeStablecoinSymbol` strips `vb`/`a`/`s` vault prefixes, replaces `₮` (U+20AE) with `T`, and strips trailing `0` suffix (e.g. `vbUSDC` → `USDC`, `USD₮0` → `USDT`).

**Guard RC-fund-dust — minimum $5 inflow**

Inbound flows < $5 USD are excluded (test deposits, fractional Earn credits).

**isNonUniverseCounterparty** — both inflow and outflow: counterparty (flow-level or transaction-level) that resolves to a universe member is excluded. Bybit sub-accounts `BYBIT:<uid>:FUND/UTA/EARN` all resolve to the root `BYBIT:<uid>` member.

---

### Pass 2 — EVM on-chain wallets

Scans `EXTERNAL_TRANSFER_IN`, `EXTERNAL_TRANSFER_OUT`, `BRIDGE_IN`, `BRIDGE_OUT` for all EVM members with `backfillEnabled = true`.

Before the per-transaction loop two pre-pass index structures are built:

#### Pre-pass A — pairedCorrelationIds

Collects the `correlationId` values that appear in **both** a `BRIDGE_IN` and a `BRIDGE_OUT` in the current universe. A correlationId present in both directions identifies a fully-tracked intra-universe bridge corridor where both wallets are known. Both legs are **excluded** from NEC (carry semantics handle cost basis for these).

#### Pre-pass B — buildCorridorPairedHashes (RC2b + RC3a)

Returns a symmetric set of txHashes covering **both** legs of corridors identified by amount+time proximity. Both legs (inbound _and_ outbound) are added so that NEC (inflow − outflow) remains unchanged — removing the overcounting from inflow without also removing the outflow would shift NEC.

**Pattern 1 (RC3a) — known solver/payout corridors**

For each `BRIDGE_IN` or `EXT_IN` whose counterparty is in `KNOWN_BRIDGE_PAYOUT_ADDRESSES`:
find a `BRIDGE_OUT` or `EXT_OUT` within ±4 h and ±1.5% USD amount. Add both hashes.

**Pattern 2 (RC2b) — bridge return / cancellation**

For each `EXT_IN` whose counterparty also appears as the counterparty of a same-wallet `BRIDGE_OUT` within ±48 h and ±1.5% USD amount: this is a bridge return or refund. Add both hashes.

---

#### Per-transaction guards (applied in order)

| Guard | Code | Applies to | Rule |
|-------|------|-----------|------|
| Skip excluded rows | — | all | `excludedFromAccounting = true` → skip |
| Intra-universe bridge corridor (corrId) | — | BRIDGE_IN, BRIDGE_OUT | `correlationId ∈ pairedCorrelationIds` → skip |
| Amount+time corridor | RC2b, RC3a | all | `txHash ∈ corridorPairedHashes` → skip |
| Known solver/payout unconditional | RC-direct | BRIDGE_IN, EXT_IN | `counterparty ∈ KNOWN_BRIDGE_PAYOUT_ADDRESSES` → skip inbound |
| DApp reward / PnL settlement | RC-dapp-reward | EXT_IN | no counterparty address + `protocolName` non-blank → skip |
| Asset class filter | RC-evm-ext-asset | EXT_IN only | for non-null symbol: only stablecoin or ETH-family passes; BRIDGE_IN exempt |
| MULTI counterparty | — | BRIDGE_IN | `counterparty = "MULTI"` → skip (multi-sender router, ambiguous) |
| Fake native token | RC-fake-native | all ETH/WETH-family flows | flow has non-null `assetContract` not in `KNOWN_WETH_CONTRACTS` → skip |
| LP exit receipt | RC3b | inbound | flow-level `counterparty ∈ KNOWN_LP_POOL_ADDRESSES` → skip |
| Non-universe counterparty | — | all | `isNonUniverseCounterparty` must be true |
| Dust threshold | RC-evm-dust | inbound | USD value < $2 → skip |

**Outflows are not dust-filtered** — even small outbound amounts represent real capital departures whose corresponding inbound may be larger.

---

## Guard constant registries

### `KNOWN_BRIDGE_PAYOUT_ADDRESSES`

Relay Protocol solvers, LiFi relayer, Hyperlane/LiFi bridge payout, EtherFi protocol address, Across Protocol SpokePool (L2), and protocol-specific solver EOAs. See class constants for current list and comments.

### `KNOWN_LP_POOL_ADDRESSES`

LP pools and vault contracts whose inbound flows are LP exit receipts (the user's own previously-deployed assets). Currently: Katana vbETH-vbUSDC LP pool and Katana vault/bridge contracts.

### `KNOWN_WETH_CONTRACTS`

Canonical WETH ERC-20 contract addresses per chain, plus chain-specific native ETH proxies (e.g. `0x000...800a` for zkSync Era). An ETH/WETH-family flow with a non-null `assetContract` outside this set is a scam/airdrop fake token.

Chains covered: Ethereum mainnet, Base / Optimism / Linea / Unichain, Arbitrum One, zkSync Era, Mantle, Linea, Cronos zkEVM, Avalanche C-Chain.

---

## `pricedFlowValueUsd` — USD resolution chain

Applied to every counted flow. Resolution order:

1. `flow.valueUsd` (non-zero) — use directly.
2. `flow.unitPriceUsd × |flow.quantityDelta|` — when unit price present.
3. Stablecoin identity — `|quantityDelta|` treated as USD (after `normalizeStablecoinSymbol`).
4. ETH-family cross-flow inference — scan sibling flows in same transaction for an ETH-family flow with a derivable unit price.
5. Historical price cache fallback — query `ETH` / `WETH` from `BINANCE → BYBIT → COINGECKO` at `blockTimestamp` (used when FEE flows carry a non-ETH native token, e.g. Mantle/MNT, so no ETH-family sibling is present).

If none of the above yields a non-zero price, the flow is skipped (not counted).

---

## Deduplication

Each transaction is keyed by `walletAddress|txHash` (or `_id` as fallback). Duplicate tx records for the same on-chain hash are counted only once.

---

## Inputs

| Source | Use |
|--------|-----|
| `normalized_transactions` | Bybit FUND + EVM capital flows |
| `counterparty_basis_pools` | Non-backfillEnabled member MtM, non-member diagnostic pools |
| `borrow_liabilities` | Open liability USD subtraction |
| `accounting_universes` | Member registry + backfillEnabled flags |
| `historical_price_cache` | ETH-family price fallback for unpriced flows |

---

## Dashboard response fields

| Field | Meaning |
|-------|---------|
| `netExternalCapitalUsd` | `lifetimeInflow − lifetimeOutflow` (NEC) |
| `lifetimeExternalInflowUsd` | Gross lifetime inflow (Bybit FUND + EVM); displayed as "Net Inflow" in UI |
| `markToMarketUsd` | `adjustedMtM` (dashboard portfolio value + non-backfill member pools − borrow liabilities) |
| `expectedPnlUsd` | `adjustedMtM − NEC` |
| `reportedPnlUsd` | `totalRealisedPnlUsd + totalUnrealisedPnlUsd` (in-scope families) |
| `conservationDeltaUsd` | `reportedPnlUsd − expectedPnlUsd` |
| `conservationThresholdUsd` | `max($50, 1% × |adjustedMtM|)` |
| `conservationBreached` | `|conservationDeltaUsd| > conservationThresholdUsd` |

---

## Rules by transaction type

| Type | NEC role |
|------|---------|
| `EXTERNAL_TRANSFER_IN` on `BYBIT:*:FUND` | `fundInflow` (after RC1, RC-fund-dust, isNonUniverseCounterparty) |
| `EXTERNAL_TRANSFER_OUT` on `BYBIT:*:FUND` | `fundOutflow` (after isNonUniverseCounterparty) |
| `EXTERNAL_TRANSFER_IN` on EVM wallets | `evmInflow` (after RC-dapp-reward, RC-evm-ext-asset, RC-fake-native, RC3b, RC-evm-dust, …) |
| `EXTERNAL_TRANSFER_OUT` on EVM wallets | `evmOutflow` (after RC-fake-native, isNonUniverseCounterparty) |
| `BRIDGE_IN` on EVM wallets | `evmInflow` (after pairedCorrelationIds, RC2b/RC3a, RC-direct, MULTI guard, RC-fake-native, RC3b, isNonUniverseCounterparty, RC-evm-dust) |
| `BRIDGE_OUT` on EVM wallets | `evmOutflow` (after pairedCorrelationIds, RC2b/RC3a, RC-fake-native, isNonUniverseCounterparty) |
| `BORROW` / `REPAY` | Open borrow liabilities (adjustedMtM only) |
| All types contributing to realised/unrealised PnL | `reportedPnL` |
| OOS family realised PnL (SOL, TON, …) | Excluded from `reportedPnL` fed to gate (RC-8) |

---

## Conservation breach logging

When `conservationBreached = true`, the gate emits a structured WARN log with:

- `conservationDelta`, `expectedPnl`, `reportedPnl`, `nec`, `lifetimeInflowUsd`, `lifetimeOutflowUsd`, `mtm`, `threshold`
- `topNonMemberPoolsByNetCapitalDelta` — top 20 non-member pools sorted by `|lifetimeIn − lifetimeOut|`
- `topMemberPoolsByQtyHeld` — top 20 member pools with `backfillEnabled=false` and positive `qtyHeld`
- `pendingPositions` — top 20 token positions by `|unrealisedPnlUsd|`

---

## Related

- [ADR-014](../../adr/ADR-014-portfolio-conservation-gate.md) — gate design intent and threshold rationale
- [ADR-034](../../adr/ADR-034-nec-transaction-scan.md) — NEC transaction-scan algorithm (supersedes ADR-014 §D2 pool-based NEC)
- [Borrow liability](../cost-basis/04-borrow-liability.md)
- [Basis pools](../cost-basis/03-basis-pools-and-carry.md)
- [Dashboard read model](02-dashboard-read-model.md)
