# WalletRadar — Domain Model

> **Version:** MVP v2.1  
> **Last updated:** 2026-02-28

---

## Glossary

| Term | Definition |
|------|------------|
| **Wallet** | Read-only blockchain address tracked by the system (no signing, no private keys). |
| **Network** | Supported chain (`NetworkId`) processed independently per wallet. |
| **Raw Transaction** | Source transaction document stored in `raw_transactions` with provider payload in `rawData`. |
| **Normalization Status** | Raw processing state: `PENDING` or `COMPLETE`. |
| **Normalized Transaction** | Canonical operation document (`1 tx = 1 doc`) with `flows[]` used by pricing/stat/accounting pipeline. |
| **Flow** | Canonical movement row inside normalized transaction (`quantityDelta`, role, asset, price fields). |
| **Normalized Status Pipeline** | `PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED` (or `NEEDS_REVIEW`). |
| **AVCO** | Weighted average acquisition cost used for cost basis and realised PnL. |
| **perWalletAvco** | AVCO computed from one wallet only. |
| **crossWalletAvco** | AVCO computed on request across selected wallets; never stored. |
| **Override** | User-defined replacement price stored in `cost_basis_overrides`, applied in replay. |
| **Manual Compensating Transaction** | Synthetic normalized transaction (`type=MANUAL_COMPENSATING`) for reconciliation fixes, idempotent by `clientId`. |
| **Reconciliation** | Comparison between derived quantity (`asset_positions`) and on-chain quantity (`on_chain_balances`). |

---

## Core Entities

### RawTransaction (`raw_transactions`)

Immutable source payload with controlled enrichment during normalization.

```text
RawTransaction {
  txHash: String
  networkId: String
  walletAddress: String
  blockNumber: Long?    // EVM
  slot: Long?           // Solana
  normalizationStatus: PENDING | COMPLETE
  retryCount: Int
  lastError: String?
  nextRetryAt: DateTime?
  createdAt: DateTime
  rawData: Document     // tx + explorer payload; optional merged receipt/log enrichments
}
```

### NormalizedTransaction (`normalized_transactions`)

Canonical operation object used by UI and AVCO replay.

```text
NormalizedTransaction {
  txHash: String
  networkId: NetworkId
  walletAddress: String
  blockTimestamp: DateTime
  type: NormalizedTransactionType
  status: NormalizedTransactionStatus
  flows: Flow[]
  missingDataReasons: String[]
  confidence: Decimal      // [0..1]
  clarificationAttempts: Int
  pricingAttempts: Int
  statAttempts: Int
  createdAt: DateTime
  updatedAt: DateTime
  confirmedAt: DateTime?
  clientId: String?        // for MANUAL_COMPENSATING idempotency
}

Flow {
  role: BUY | SELL | TRANSFER
  assetContract: String
  assetSymbol: String
  quantityDelta: Decimal       // positive=inbound, negative=outbound
  unitPriceUsd: Decimal?
  valueUsd: Decimal?
  priceSource: PriceSource?
  isInferred: Boolean
  inferenceReason: String?
  confidence: ConfidenceLevel?
  avcoAtTimeOfSale: Decimal?
  realisedPnlUsd: Decimal?
  logIndex: Int?
}
```

Only `status=CONFIRMED` documents are used for accounting replay and shown in default history responses.

### AssetPosition (`asset_positions`)

Derived state per `(walletAddress, networkId, assetContract)`.

```text
AssetPosition {
  walletAddress: String
  networkId: String
  assetSymbol: String
  assetContract: String
  quantity: Decimal
  perWalletAvco: Decimal
  totalCostBasisUsd: Decimal
  totalGasPaidUsd: Decimal
  totalRealisedPnlUsd: Decimal
  hasIncompleteHistory: Boolean
  hasUnresolvedFlags: Boolean
  unresolvedFlagCount: Int
  lastEventTimestamp: DateTime?
  lastCalculatedAt: DateTime?
  onChainQuantity: Decimal?
  onChainCapturedAt: DateTime?
  reconciliationStatus: MATCH | MISMATCH | NOT_APPLICABLE
}
```

### SyncState (`sync_status`, `backfill_segments`)

`SyncStatus` tracks wallet×network lifecycle. `BackfillSegment` tracks persistent segment progress and retries.

### OnChainBalance (`on_chain_balances`)

Latest observed native/token quantity for `(wallet, network, asset)` from balance refresh jobs.

### Cost Basis Override (`cost_basis_overrides`)

User override by normalized flow id:

```text
CostBasisOverride {
  normalizedLegId: String
  priceUsd: Decimal
  isActive: Boolean
  note: String?
  createdAt: DateTime
}
```

### RecalcJob (`recalc_jobs`)

Async AVCO replay status tracking after override/manual compensating updates.

### SessionTransaction (`session_transactions`)

Session-scoped projection row built from canonical normalized transactions and future session-specific adjustments.

```text
SessionTransaction {
  sessionId: String
  sourceType: CHAIN | MANUAL | OVERRIDE
  sourceId: String
  txHash: String?
  networkId: NetworkId?
  walletAddress: String?
  blockTimestamp: DateTime?
  type: NormalizedTransactionType?
  sortKey: String
  bridgeStatus: BRIDGE_OUT | BRIDGE_IN | MATCHED | REVIEW?
  flows: SessionFlow[]
  realisedPnlUsdTotal: Decimal?
  avcoSnapshotVersion: Long?
}
```

---

## Canonical Transaction Types

`NormalizedTransactionType`:

| Type |
|------|
| `SWAP` |
| `STAKE_DEPOSIT` |
| `STAKE_WITHDRAWAL` |
| `LP_ENTRY` |
| `LP_EXIT` |
| `LP_EXIT_PARTIAL` |
| `LP_EXIT_FINAL` |
| `LP_ADJUST` |
| `LP_POSITION_STAKE` |
| `LP_POSITION_UNSTAKE` |
| `LP_FEE_CLAIM` |
| `LEND_DEPOSIT` |
| `LEND_WITHDRAWAL` |
| `BORROW` |
| `REPAY` |
| `EXTERNAL_TRANSFER_OUT` |
| `EXTERNAL_INBOUND` |
| `MANUAL_COMPENSATING` |

Internal classifier enum `EconomicEventType` is still used to build normalized `type`/`flows`, but canonical storage and accounting input is `normalized_transactions`.

### `EXTERNAL_INBOUND` fallback policy

`EXTERNAL_INBOUND` is a transfer-fallback classification and is used only when higher-priority
protocol classifiers do not match.

For claim/withdraw/refund-like calls, `EXTERNAL_INBOUND` may be emitted even when
`rawData.from == walletAddress`, because accounting uses net asset movement semantics rather than
transaction sender role.

In this case classifier output must contain only positive net inbound legs for the wallet after
neutralizing paired wrap/burn mechanics in the same transaction.

LP extensions for concentrated-liquidity (CL) protocols:

- `LP_POSITION_ENTRY` (internal classifier event) maps to canonical `type=LP_ENTRY` for mint-style position creation.
- `LP_POSITION_EXIT` (internal classifier event) maps to canonical `type=LP_EXIT` for burn-style position close.
- For v3/v4 position mint tx, canonical `LP_ENTRY` must include economic principal outflows (wallet token spends), not only NFT lifecycle markers.
- For v3/v4 custody transfer (`wallet <-> farm/strategy`) canonical types are `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE`.
- `LP_EXIT_PARTIAL` / `LP_EXIT_FINAL` represent decrease-liquidity exits with and without final position close boundary.
- `LP_ADJUST` is deprecated and kept as backward-compatible alias for legacy data only.
- `LP_FEE_CLAIM` maps to canonical `type=LP_FEE_CLAIM`.

---

## Price Sources

| PriceSource | Meaning | Priority |
|-------------|---------|----------|
| `STABLECOIN` | Hardcoded stablecoin parity | 1 |
| `SWAP_DERIVED` | Ratio inferred from swap counterpart | 2 |
| `COINGECKO` | Historical resolver fallback | 3 |
| `MANUAL` | User override | override layer |
| `UNKNOWN` | Unresolved pricing | last resort |

---

## Domain Invariants

| # | Invariant |
|---|-----------|
| INV-01 | AVCO replay order is deterministic by `blockTimestamp ASC`, then stable intra-timestamp ordering. |
| INV-02 | `raw_transactions` are append/enrich only during normalization; source identity (`txHash`,`networkId`,`walletAddress`) is immutable. |
| INV-03 | Canonical accounting input is `normalized_transactions` with `status=CONFIRMED`. |
| INV-04 | `crossWalletAvco` is computed on request and never persisted. |
| INV-05 | Numeric values use `BigDecimal`/`Decimal128`; no floating-point arithmetic in domain calculations. |
| INV-06 | Realised PnL for outbound sell flows is computed atomically with replay and stored on flow (`realisedPnlUsd`, `avcoAtTimeOfSale`). |
| INV-07 | Active `cost_basis_overrides` supersede original flow price during replay. |
| INV-08 | On-chain normalized tx idempotency key is `(txHash, networkId, walletAddress)` (unique one doc per tx). |
| INV-09 | Manual compensating idempotency key is `clientId` (unique when provided). |
| INV-10 | GET endpoints must be snapshot/data-store based (no RPC in request path). |

---

## Aggregate Rules

### AVCO replay (simplified)

```text
BUY (qty>0):
  newAvco = (currentAvco*currentQty + price*qty) / (currentQty + qty)
  newQty  = currentQty + qty

SELL (qty<0):
  avcoAtTimeOfSale = currentAvco
  realisedPnlUsd   = (sellPrice - currentAvco) * abs(qty)
  newAvco          = currentAvco
  newQty           = currentQty - abs(qty)
```

### Cross-wallet AVCO

```text
1. Load CONFIRMED normalized transactions for selected wallets.
2. Extract matching flows for target asset.
3. Sort by blockTimestamp ASC (stable tie-breakers).
4. Replay AVCO sequentially.
5. Return computed value; do not persist.
```

### Reconciliation

```text
derivedQuantity  = asset_positions.quantity
onChainQuantity  = on_chain_balances.quantity
balanceDiff      = onChainQuantity - derivedQuantity
status           = MATCH | MISMATCH | NOT_APPLICABLE
```

For "young" histories (within backfill horizon), `MISMATCH` should surface warning and guide user to manual compensating transaction.
