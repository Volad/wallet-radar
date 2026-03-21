# WalletRadar — Domain Model

> **Version:** MVP v3 target
> **Last updated:** 2026-03-21

---

## Glossary

| Term | Definition |
|------|------------|
| **Wallet** | Read-only blockchain address tracked by the system (no signing, no private keys). |
| **Network** | Supported chain (`NetworkId`) processed independently per wallet. |
| **Raw Transaction** | Source transaction document stored in `raw_transactions` with provider payload in `rawData`. |
| **Protocol Registry** | Classpath lookup table loaded from `backend/src/main/resources/protocol-registry.json` for high-confidence contract-aware classification. |
| **Special Handler** | Deterministic registry-linked classifier for multi-function contracts. Receives the raw normalization view plus already extracted legs and returns one canonical result for the raw tx. |
| **External Ledger Row** | Immutable Bybit source row stored in `external_ledger_raw`. |
| **Tracked Wallet Universe** | Installation-wide projection of all tracked wallet addresses used by canonical normalization heuristics. |
| **Normalization Status** | Raw processing state: `PENDING` or `COMPLETE`. |
| **Normalized Transaction** | Canonical operation document (`1 tx = 1 doc`) with `flows[]` used by pricing/stat/accounting pipeline. |
| **Flow** | Canonical movement row inside normalized transaction (`quantityDelta`, role, asset, price fields). |
| **Normalized Status Pipeline** | Receipt-clarifiable rows may pass through `PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED`; low-confidence rows without receipt gaps may go directly to `PENDING_PRICE` or `NEEDS_REVIEW`. |
| **User Session** | Persisted wallet set keyed by client-generated `sessionId` in `user_sessions`. |
| **AVCO** | Weighted average acquisition cost used for cost basis and realised PnL. |
| **perWalletAvco** | AVCO computed from one wallet only. |
| **crossWalletAvco** | AVCO computed on request across selected wallets; never stored. |
| **Override** | User-defined replacement price stored in `cost_basis_overrides`, applied in replay. |
| **Manual Compensating Transaction** | Synthetic normalized transaction (`type=MANUAL_COMPENSATING`) for reconciliation fixes, idempotent by `clientId`. |
| **Reconciliation** | Comparison between derived quantity (`asset_positions`) and on-chain quantity (`on_chain_balances`). |

---

## Supported NetworkId Values

Accounting scope for the v3 milestone:

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

Additional backend enum value currently present but out of v3 accounting scope:

- `SOLANA`

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
  source: ON_CHAIN | BYBIT
  txHash: String
  networkId: NetworkId
  walletAddress: String
  blockTimestamp: DateTime
  transactionIndex: Int
  type: NormalizedTransactionType
  status: NormalizedTransactionStatus
  classifiedBy: ClassificationSource?
  flows: Flow[]
  missingDataReasons: String[]
  confidence: Decimal      // [0..1]
  correlationId: String?
  clarificationAttempts: Int
  pricingAttempts: Int
  statAttempts: Int
  createdAt: DateTime
  updatedAt: DateTime
  confirmedAt: DateTime?
  clientId: String?        // for MANUAL_COMPENSATING idempotency
}

Flow {
  role: BUY | SELL | TRANSFER | FEE
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

Status intent:

- `PENDING_CLARIFICATION` is reserved for rows that are missing receipt-safe metadata
  (`txreceipt_status`, gas fields, created contract address) and can still benefit
  from bounded clarification.
- Low confidence alone does not justify clarification.
- If classification is economically coherent but not receipt-clarifiable, the row
  proceeds to `PENDING_PRICE`.
- If semantic meaning is still unresolved after deterministic classifier rules,
  the row goes directly to `NEEDS_REVIEW`.

### ExternalLedgerRaw (`external_ledger_raw`)

Immutable Bybit evidence layer.

```text
ExternalLedgerRaw {
  _id: String            // BYBIT:uid:filename:rowIndex
  sourceFileType: String
  timeUtc: DateTime
  assetSymbol: String?
  quantityRaw: Decimal?
  canonicalType: String?
  basisRelevant: Boolean
  outOfScope: Boolean
  txHash: String?
  networkId: NetworkId?
  onChainCorrelation: {
    status: PENDING | MATCHED | UNMATCHED
    correlationId: String?
    matchedDocId: String?
  }
  status: RAW | CONFIRMED
}
```

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

### UserSession (`user_sessions`)

Persisted session wallet settings:

```text
UserSession {
  id: String             // client-generated sessionId
  wallets: [
    {
      address: String
      label: String
      color: String
      networks: NetworkId[]
    }
  ]
  createdAt: DateTime
  updatedAt: DateTime
  lastSeenAt: DateTime
}
```

### TrackedWallet (`tracked_wallets`)

Installation-wide projection used by normalization and transfer correlation:

```text
TrackedWallet {
  address: String
  refCount: Int
  firstSeenAt: DateTime
  lastSeenAt: DateTime
}
```

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

---

## Canonical Transaction Types

`NormalizedTransactionType`:

| Type |
|------|
| `SWAP` |
| `STAKING_DEPOSIT` |
| `STAKING_WITHDRAW` |
| `LP_ENTRY` |
| `LP_EXIT` |
| `LP_EXIT_PARTIAL` |
| `LP_EXIT_FINAL` |
| `LP_ADJUST` |
| `LP_POSITION_STAKE` |
| `LP_POSITION_UNSTAKE` |
| `LP_FEE_CLAIM` |
| `LENDING_DEPOSIT` |
| `LENDING_WITHDRAW` |
| `BORROW` |
| `REPAY` |
| `VAULT_DEPOSIT` |
| `VAULT_WITHDRAW` |
| `BRIDGE_OUT` |
| `BRIDGE_IN` |
| `PROTOCOL_CUSTODY_DEPOSIT` |
| `PROTOCOL_CUSTODY_WITHDRAW` |
| `REWARD_CLAIM` |
| `EXTERNAL_TRANSFER_OUT` |
| `EXTERNAL_INBOUND` |
| `INTERNAL_TRANSFER` |
| `APPROVE` |
| `ADMIN_CONFIG` |
| `WRAP` |
| `UNWRAP` |
| `UNKNOWN` |
| `MANUAL_COMPENSATING` |

`MANUAL_COMPENSATING` is synthetic and is not produced by raw normalization.

---

## Registry Classification Contract

- The authoritative runtime source for protocol-registry data is
  `backend/src/main/resources/protocol-registry.json`.
- `event_topics` may exist in the JSON as reference metadata, but they are not loaded into
  the runtime classifier and never participate in on-chain classification.
- Registry entries may declare `decomposeByLegs=true` together with `specialHandler`
  when one contract can produce multiple canonical outcomes depending on `methodId`,
  `functionName`, and extracted legs.
- For the current v3 milestone, a `SpecialHandler` must return exactly one canonical
  result for one raw transaction so the `1 tx = 1 doc` invariant stays intact.
- If a special handler does not support the observed method/function combination, the
  classifier must emit `UNKNOWN`, set `status=NEEDS_REVIEW`, and add
  `HANDLER_UNSUPPORTED_METHOD`.

Conceptual handler contract:

```text
SpecialHandler.classify(
  ProtocolRegistryEntry entry,
  RawTransactionNormalizationView view,
  List<RawLeg> legs
) -> SpecialHandlerResult

SpecialHandlerResult {
  type
  flows[]
  confidence
  status
  missingDataReasons[]
}
```

Handler rules:

- deterministic and side-effect free
- no RPC access
- no Mongo reads/writes
- no synthetic `rawData.logs[]`

### `EXTERNAL_INBOUND` fallback policy

`EXTERNAL_INBOUND` is a transfer-fallback classification and is used only when higher-priority
protocol classifiers do not match.

For claim/withdraw/refund-like calls, `EXTERNAL_INBOUND` may be emitted even when
`rawData.from == walletAddress`, because accounting uses net asset movement semantics rather than
transaction sender role.

In this case classifier output must contain only positive net inbound legs for the wallet after
neutralizing paired wrap/burn mechanics in the same transaction.

Additional constraints:

- Plain positive inbound `transfer(address,uint256)` / `transferFrom(...)` legs default to
  `EXTERNAL_INBOUND` unless contract-aware reward or bridge evidence exists.
- Promo/phishing token metadata excludes the tx from both `REWARD_CLAIM` and ordinary
  `EXTERNAL_INBOUND`; the tx must be filtered upstream or surfaced as explicit review.
- Explorer page summaries are audit aids only. Canonical production classification is derived
  from backfill-available raw evidence and extracted legs, not from human-readable explorer labels.
- Zero-amount token-only movements are non-economic and must not normalize into
  `EXTERNAL_INBOUND` or `EXTERNAL_TRANSFER_OUT`.

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
| `WRAPPER` | Wrapped/native asset price inherited from the underlying native asset | 3 |
| `COINGECKO` | Historical resolver fallback | 4 |
| `MANUAL` | User override | override layer |
| `UNKNOWN` | Unresolved pricing | last resort |

---

## Domain Invariants

| # | Invariant |
|---|-----------|
| INV-01 | AVCO replay order is deterministic: `blockTimestamp ASC -> transactionIndex ASC -> _id ASC`. |
| INV-02 | `raw_transactions` are append/enrich only during normalization; source identity (`txHash`,`networkId`,`walletAddress`) is immutable. |
| INV-03 | Canonical accounting input is `normalized_transactions` with `status=CONFIRMED`, regardless of `source=ON_CHAIN` or `source=BYBIT`. |
| INV-04 | `crossWalletAvco` is computed on request and never persisted. |
| INV-05 | Numeric values use `BigDecimal`/`Decimal128`; no floating-point arithmetic in domain calculations. |
| INV-06 | Realised PnL for outbound sell flows is computed atomically with replay and stored on flow (`realisedPnlUsd`, `avcoAtTimeOfSale`). |
| INV-07 | Active `cost_basis_overrides` supersede original flow price during replay. |
| INV-08 | On-chain normalized tx idempotency key is `(txHash, networkId, walletAddress)` (unique one doc per tx). |
| INV-09 | Production classification may use only evidence that exists in `raw_transactions` at backfill time or receipt-safe clarification time; explorer summary text is never canonical evidence. |
| INV-10 | Manual compensating idempotency key is `clientId` (unique when provided). |
| INV-11 | GET endpoints must be snapshot/data-store based (no RPC in request path). |
| INV-12 | Protocol-registry special handlers are pure deterministic functions over raw view + extracted legs and return exactly one canonical result or `NEEDS_REVIEW`. |

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
3. Sort by `blockTimestamp ASC`, then `transactionIndex ASC`, then `_id ASC`.
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
