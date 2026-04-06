# WalletRadar — Domain Model

> **Version:** MVP v3 target
> **Last updated:** 2026-04-06

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
| **Self-canceling pair** | Same-asset same-quantity inbound and outbound legs inside one tx that net to zero and therefore represent wrapper / marker continuity rather than economic acquisition or disposal. |
| **Normalized Status Pipeline** | Receipt-clarifiable rows may pass through `PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED`; low-confidence rows without receipt gaps may go directly to `PENDING_PRICE` or `NEEDS_REVIEW`. |
| **User Session** | Persisted wallet set keyed by client-generated `sessionId` in `user_sessions`. |
| **AVCO** | Weighted average acquisition cost used for cost basis and realised PnL. |
| **perWalletAvco** | AVCO computed from one wallet only. |
| **crossWalletAvco** | AVCO computed on request across selected wallets; never stored. |
| **Override** | User-defined replacement price stored in `cost_basis_overrides`, applied in replay. |
| **Manual Compensating Transaction** | Synthetic normalized transaction (`type=MANUAL_COMPENSATING`) for reconciliation fixes, idempotent by `clientId`. |
| **Asset Ledger Point** | Immutable replay trace row in `asset_ledger_points` for one applied accounting transition on one wallet-network-asset bucket. |
| **Reconciliation** | Comparison between latest exact-bucket replay state from `asset_ledger_points` and current on-chain quantity in `on_chain_balances`. |
| **Current Holding View** | Read-time holding state derived from latest exact-bucket `asset_ledger_points` plus `on_chain_balances`; not a persisted collection. |
| **Dashboard Issue Class** | Read-time diagnostic label for one current holding row. It is derived from current coverage and latest replay-state flags, not from a separate persisted collection. |

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
  continuityCandidate: Boolean
  matchedCounterparty: String?
  excludedFromAccounting: Boolean
  accountingExclusionReason: String?
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

Flow-role intent:

- `BUY` / `SELL` are economic acquisition / disposal legs that must be priceable.
- `TRANSFER` is continuity-only movement and must not require market pricing by itself.
- A canonical tx may mix continuity and economic legs in one row when the tx is
  a real bundle and current raw evidence can still separate those legs
  deterministically.

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
- `excludedFromAccounting=true` means the row remains persisted and audit-visible,
  but it is intentionally outside the active pricing / replay scope.
- `NEEDS_REVIEW` therefore has two sub-cases:
  - blocking review rows (`excludedFromAccounting != true`)
  - audit-only excluded rows (`excludedFromAccounting = true`)
- Blocking review rows may include semantically recognized but still
  basis-incomplete async lifecycle families, such as request/execute protocol
  flows or batch transactions whose current raw / clarification evidence is not
  yet sufficient for deterministic replay.
- Deterministically recognized but currently unsupported families may also stay
  `NEEDS_REVIEW` when they are explicitly marked
  `excludedFromAccounting=true`; residual Bybit orphan / unsupported loan rows
  are the reference example.

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

### Replay Current State

WalletRadar no longer persists `asset_positions` or `reconciled_holdings`.

The replay truth is:

- immutable `asset_ledger_points`
- current live evidence in `on_chain_balances`

Latest replay state for one exact bucket is derived from the last
`AssetLedgerPoint` ordered by:

- `blockTimestamp`
- `transactionIndex`
- `normalizedTransactionId`
- `flowIndex`
- `replaySequence`

`quantityShortfallAfter` on the latest point is the conservative replay
diagnostic:

- it increases when replay tries to consume more quantity than the currently
  replayed bucket contains
- it means some historical quantity is missing or unresolved
- it must not be treated as synthetic quantity or synthetic cost basis

### Dashboard Issue Classes

Dashboard current-holding reads may expose one diagnostic `issue` per
wallet-network-family row:

- `yield_accrual`
  - current live quantity is slightly above basis-backed quantity
  - latest replay point is otherwise clean
  - expected for interest-bearing receipt balances such as `aToken` or
    liquid-staking derivatives after passive accrual
- `coverage_gap`
  - current live quantity is larger than basis-backed quantity
  - this is a real current-basis coverage problem unless a stricter
    interest-bearing policy downgrades it to `yield_accrual`
- `history_flags`
  - current quantity is fully covered
  - latest replay point still carries incomplete-history or unresolved flags
- `missing_replay_point`
  - live balance exists
  - no latest replay point exists for the exact bucket

These issue classes are read-time diagnostics for the dashboard and must not be
stored as separate mutable truth state.

### SyncState (`sync_status`, `backfill_segments`)

`SyncStatus` tracks wallet×network lifecycle. `BackfillSegment` tracks persistent segment progress and retries.

### UserSession (`user_sessions`)

Persisted session wallet settings:

```text
UserSession {
  id: String             // client-generated sessionId
  accountingUniverseId: String
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

### AccountingUniverse (`accounting_universes`)

Stable owner/accounting scope used by replay-derived history reads.

```text
AccountingUniverse {
  id: String
  members: [
    {
      ref: String                // wallet address or custody ref such as BYBIT:<uid>
      type: ON_CHAIN_WALLET | EXCHANGE_ACCOUNT
      provider: String?          // e.g. BYBIT
      networks: NetworkId[]      // on-chain wallets only
      firstSeenAt: DateTime
      lastSeenAt: DateTime
    }
  ]
  createdAt: DateTime
  updatedAt: DateTime
}
```

Rules:

- the universe is additive; historical members are not removed automatically
  when the current UI session changes its visible wallet subset
- `UserSession.wallets` is the current UI scope
- `AccountingUniverse.members` is the stable owner scope for:
  - asset-ledger timeline reads
  - continuity/debug history across on-chain wallets and custodial refs
- current `BYBIT` custody refs are synchronized from `external_ledger_raw`
  evidence for the session and persisted as universe members

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

### AssetLedgerPoint (`asset_ledger_points`)

Immutable replay-trace row for one applied accounting transition on one
`(walletAddress, networkId, accountingAssetIdentity)` bucket.

`AssetLedgerPoint` is the primary debug and history layer for:

- AVCO / cost-basis timeline
- carry-basis continuity across transfers, bridges, custody, and async lifecycles
- operator audit of the first point where replay became incomplete, uncovered, or suspicious

```text
AssetLedgerPoint {
  walletAddress: String
  networkId: String
  accountingAssetIdentity: String
  accountingFamilyIdentity: String
  familyDisplaySymbol: String
  assetSymbol: String
  assetContract: String?

  normalizedTransactionId: String
  txHash: String?
  correlationId: String?
  lifecycleChainId: String?
  matchedCounterparty: String?
  continuityCandidate: Boolean

  blockTimestamp: DateTime
  transactionIndex: Int?
  flowIndex: Int
  replaySequence: Long

  normalizedType: String
  lifecycleKind: String
  lifecycleStage: String
  basisEffect: String
  protocolName: String?

  quantityDelta: Decimal
  costBasisDeltaUsd: Decimal
  realisedPnlDeltaUsd: Decimal
  gasDeltaUsd: Decimal
  quantityShortfallDelta: Decimal
  uncoveredQuantityDelta: Decimal

  quantityBefore: Decimal
  quantityAfter: Decimal
  totalCostBasisBeforeUsd: Decimal
  totalCostBasisAfterUsd: Decimal
  avcoBeforeUsd: Decimal?
  avcoAfterUsd: Decimal?

  basisBackedQuantityAfter: Decimal
  quantityShortfallAfter: Decimal
  uncoveredQuantityAfter: Decimal
  hasIncompleteHistoryAfter: Boolean
  hasUnresolvedFlagsAfter: Boolean
  unresolvedFlagCountAfter: Int

  createdAt: DateTime
}
```

Rules:

- one point = one replay-applied state transition on one exact asset bucket
- points are immutable
- ordering is deterministic:
  - `blockTimestamp ASC`
  - `transactionIndex ASC`
  - `normalizedTransactionId ASC`
  - `flowIndex ASC`
  - `replaySequence ASC`
- `accountingAssetIdentity` is the exact replay bucket identity
- `accountingFamilyIdentity` is the continuity family used for move-basis and
  history aggregation
- when no broader family policy exists, `accountingFamilyIdentity` falls back to
  the exact asset identity
- `quantityShortfall*` is a lifetime audit metric for historical coverage gaps;
  it is not the current uncovered holding quantity
- `uncoveredQuantity*` is the current held quantity that still lacks provable
  carried basis after the replay step
- `basisBackedQuantityAfter = max(quantityAfter - uncoveredQuantityAfter, 0)`
- session asset history is not persisted separately; it is aggregated on read
  from wallet-level `asset_ledger_points` scoped by the session's
  `accountingUniverseId`

### OnChainBalance (`on_chain_balances`)

Latest observed live quantity evidence for `(wallet, network, asset)` from the
post-replay balance-refresh pass.

### Current Holding View

Current holdings are computed on read from:

- latest exact-bucket replay state in `asset_ledger_points`
- `on_chain_balances`

The logical row is keyed by `(walletAddress, networkId, accountingAssetIdentity)`
and exposes:

```text
CurrentHoldingView {
  walletAddress: String
  networkId: String
  accountingAssetIdentity: String
  accountingFamilyIdentity: String
  assetSymbol: String
  assetContract: String?
  currentQuantity: Decimal
  derivedQuantity: Decimal?
  basisBackedDerivedQuantity: Decimal
  currentCoveredQuantity: Decimal
  currentUncoveredQuantity: Decimal
  currentCostBasisProvable: Boolean
  perWalletAvco: Decimal?
  totalCostBasisUsd: Decimal?
  totalGasPaidUsd: Decimal?
  totalRealisedPnlUsd: Decimal?
  quantityShortfall: Decimal?
  hasIncompleteHistory: Boolean?
  hasUnresolvedFlags: Boolean?
  unresolvedFlagCount: Int?
  lastEventTimestamp: DateTime?
  onChainCapturedAt: DateTime?
  reconciliationStatus: MATCH | MISMATCH | NOT_APPLICABLE
}
```

Rules:

- current quantity always comes from `on_chain_balances`
- basis fields come from the latest replayed `AssetLedgerPoint`
- `basisBackedDerivedQuantity = max(derivedQuantity - uncoveredQuantity, 0)`
- `currentCoveredQuantity = min(currentQuantity, basisBackedDerivedQuantity)`
- `currentUncoveredQuantity = currentQuantity - currentCoveredQuantity`
- `currentCostBasisProvable = currentUncoveredQuantity == 0`
- historical `quantityShortfall` remains visible for audit, but must not poison
  later covered acquisitions or current provability once the previously missing
  quantity is no longer held
- the view is derived at query time and is not persisted

The runtime producer is bounded to:

- current `tracked_wallets`
- `CONFIRMED` on-chain canonical asset universe
- supported on-chain balance methods (`eth_getBalance`, ERC-20 `balanceOf`)

`Bybit` inventory never writes into this collection.

```text
OnChainBalance {
  walletAddress: String
  networkId: String
  assetSymbol: String
  assetContract: String?
  quantity: Decimal
  capturedAt: DateTime
}
```

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
| `STAKING_WITHDRAW_REQUEST` |
| `STAKING_WITHDRAW` |
| `LP_ENTRY_REQUEST` |
| `LP_ENTRY_SETTLEMENT` |
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
| `LENDING_LOOP_OPEN` |
| `LENDING_LOOP_REBALANCE` |
| `LENDING_LOOP_DECREASE` |
| `LENDING_LOOP_CLOSE` |
| `BORROW` |
| `REPAY` |
| `VAULT_DEPOSIT` |
| `VAULT_WITHDRAW` |
| `BRIDGE_OUT` |
| `BRIDGE_IN` |
| `DEX_ORDER_REQUEST` |
| `DEX_ORDER_SETTLEMENT` |
| `PROTOCOL_CUSTODY_DEPOSIT` |
| `PROTOCOL_CUSTODY_WITHDRAW` |
| `REWARD_CLAIM` |
| `EXTERNAL_TRANSFER_OUT` |
| `EXTERNAL_TRANSFER_IN` |
| `INTERNAL_TRANSFER` |
| `APPROVE` |
| `ADMIN_CONFIG` |
| `WRAP` |
| `UNWRAP` |
| `UNKNOWN` |
| `MANUAL_COMPENSATING` |

Staking families keep one canonical type name but support two lifecycle shapes:

- liquid staking conversion:
  principal and liquid-staking derivative legs remain continuity `TRANSFER`
  inside the same base-asset family; explicit reward side-flows may still stay
  economic `BUY`
- classic stake-contract custody:
  principal moves as continuity `TRANSFER`, while any same-tx harvested reward
  remains economic `BUY`

`MANUAL_COMPENSATING` is synthetic and is not produced by raw normalization.

Async request / settlement families are explicit canonical rows, not implicit
review states:

- `LP_ENTRY_REQUEST` holds request-side principal escrow / execution-fee funding
  for audited async LP-entry families such as GMX deposit requests
- `LP_ENTRY_SETTLEMENT` holds the later execute-side receipt / refund settlement
  for the same audited lifecycle
- `LP_EXIT_REQUEST` holds request-side share burn plus execution-fee escrow for
  audited async LP-exit families such as GMX / GLV withdrawal requests
- `LP_EXIT_SETTLEMENT` holds the later keeper-side payout / refund settlement
  for the same audited async LP-exit lifecycle
- `STAKING_WITHDRAW_REQUEST` holds burn-only cooldown / unbonding initiation
  before the later payout claim arrives
- `LENDING_LOOP_OPEN` holds audited borrow-backed Euler-style loop / multiply
  openings as one canonical share-position acquisition row
- `LENDING_LOOP_REBALANCE` holds audited share-to-share Euler restructures where
  basis moves between loop-share assets without wallet-visible realized exit
- `LENDING_LOOP_DECREASE` holds audited partial deleverage / partial unwind rows
  where a loop share position is reduced and wallet-visible value returns
- `LENDING_LOOP_CLOSE` holds audited final unwind rows where a loop share
  position is fully exited back into wallet-visible assets
- Euler simple vault rows remain plain lending lifecycle, not loop lifecycle,
  but only when clarification proves the lifecycle:
  - `stable -> share` is `LENDING_DEPOSIT`
  - `share -> stable` is `LENDING_WITHDRAW`
  - unproven batch rows stay `UNKNOWN / PENDING_CLARIFICATION`
  - partial vs final vault withdraw is derived later from replay/UI state, not
    from the normalized type itself
- `DEX_ORDER_REQUEST` holds audited async spot-order request / escrow funding
  before later DEX settlement, for example CoW Eth Flow request rows
- `DEX_ORDER_SETTLEMENT` holds the later settlement-side completion of the same
  audited async spot-order lifecycle and shares the same `correlationId`
- `DERIVATIVE_ORDER_REQUEST` holds audited GMX V2 order-intent funding and
  keeper-execution-fee escrow before keeper execution or cancellation
- `DERIVATIVE_POSITION_INCREASE` holds the keeper-side execution that opens or
  increases a GMX V2 position
- `DERIVATIVE_POSITION_DECREASE` holds the keeper-side execution that decreases
  or fully closes a GMX V2 position
- `DERIVATIVE_ORDER_CANCEL` holds audited GMX V2 order cancellation without a
  successful execution
- sibling stop / bracket requests may keep `type=DERIVATIVE_ORDER_REQUEST`, but
  their later auto-cancel terminal state must still be persisted once the
  terminal keeper tx already proves it

For audited async protocol lifecycles, clarification may fetch and persist
related real transactions when the currently persisted raw row already proves the
protocol family but the terminal keeper / settlement tx is still absent from
Mongo.

---

## Registry Classification Contract

- The authoritative runtime source for protocol-registry data is
  `backend/src/main/resources/protocol-registry.json`.
- `event_topics` may exist in the JSON as reference metadata, but they are not loaded into
  the runtime classifier and never participate in on-chain classification.
- Registry entries may declare `decomposeByLegs=true` together with `specialHandler`
  when one contract can produce multiple canonical outcomes depending on `methodId`,
  `functionName`, and extracted legs.
- For the current v3 milestone, a `specialHandler` registry flag is only a
  discovery/runtime-routing hint. The canonical flow is:
  `registry entry -> protocol semantic hint(s) -> family-owned final type`.
- If a special-handler entry does not support the observed method/function
  combination, the classifier must emit `UNKNOWN`, set `status=NEEDS_REVIEW`,
  and add `HANDLER_UNSUPPORTED_METHOD`.

Conceptual semantic-routing contract:

```text
ProtocolSemanticClassifier.classify(
  ProtocolSemanticContext(view, discovery, legs)
) -> ProtocolSemanticHint[]

ProtocolSemanticHint {
  protocolKey
  semanticType
  protocolName
  protocolVersion
  correlationId?
  suggestedType
  confidence
}
```

Routing rules:

- deterministic and side-effect free
- no RPC access
- no Mongo reads/writes
- no synthetic `rawData.logs[]`

### `EXTERNAL_TRANSFER_IN` fallback policy

`EXTERNAL_TRANSFER_IN` is a transfer-fallback classification and is used only when higher-priority
protocol classifiers do not match.

For claim/withdraw/refund-like calls, `EXTERNAL_TRANSFER_IN` may be emitted even when
`rawData.from == walletAddress`, because accounting uses net asset movement semantics rather than
transaction sender role.

In this case classifier output must contain only positive net inbound legs for the wallet after
neutralizing paired wrap/burn mechanics in the same transaction.

Additional constraints:

- Plain positive inbound `transfer(address,uint256)` / `transferFrom(...)` legs default to
  `EXTERNAL_TRANSFER_IN` unless contract-aware reward or bridge evidence exists.
- Outbound-only aggregator/router calls without a wallet-visible inbound counter-asset do not
  persist as `SWAP`; they demote to owner-agnostic `EXTERNAL_TRANSFER_OUT` unless a higher-priority
  bridge classifier proves `BRIDGE_OUT`.
- Promo/phishing token metadata excludes the tx from both `REWARD_CLAIM` and ordinary
  `EXTERNAL_TRANSFER_IN`; the tx must be filtered upstream or surfaced as explicit review.
- Explorer page summaries are audit aids only. Canonical production classification is derived
  from backfill-available raw evidence and extracted legs, not from human-readable explorer labels.
- Zero-amount token-only movements are non-economic and must not normalize into
  `EXTERNAL_TRANSFER_IN` or `EXTERNAL_TRANSFER_OUT`.
- On networks that expose native coin movement through an audited native-token
  alias contract, tx-level native `value` is secondary evidence and must not
  mint a second native leg when the same wallet-boundary movement is already
  covered by that alias transfer.
- On `zkSync`, native-alias transfers between the fee-paying wallet and the
  audited system fee sink `0x0000000000000000000000000000000000008001`
  represent fee precharge/refund mechanics. They must not survive as principal
  movement legs; canonical movement must keep the explicit `FEE` leg only.
- Narrow audited method-aware overrides are allowed when generic selector /
  function-name fallback would misstate an otherwise deterministic protocol
  lifecycle. Current audited example: `zkSync` Aave gateway selectors
  `withdrawETH(...)`, `supplyWithPermit(...)`, and `depositETH(...)` must
  normalize as lending continuity, not as generic unwrap / LP / residual
  transfer families.
- Another audited narrow override exists for `zkSync` routed `Across` sends:
  selector `0x27ad57d5` must resolve as source-side `BRIDGE_OUT` when current
  stored raw evidence proves the same-wallet `Across` route from calldata plus
  wallet-boundary native funding, even if intermediate helper / settlement hops
  are absent from the saved explorer transfer list.

### Owner-Agnostic Transfer Semantics

`normalized_transactions.type` must stay independent from the current accounting universe.

- Plain inbound transfer facts persist as `EXTERNAL_TRANSFER_IN`.
- Plain outbound transfer facts persist as `EXTERNAL_TRANSFER_OUT`.
- Bridge facts persist as `BRIDGE_OUT` / `BRIDGE_IN`.
- `INTERNAL_TRANSFER` is a legacy normalized type and must not be emitted for new reruns.
- Ownership-aware continuity is expressed separately via:
  - `correlationId`
  - `continuityCandidate`
  - `matchedCounterparty`
- For async lifecycle families, `correlationId` must persist the highest-scope
  protocol lifecycle key already proved by current raw plus persisted
  clarification evidence.
- For exact async request/settlement pairs, `matchedCounterparty` must be
  materialized on both rows once both sides exist in the active lane.

Implications:

- Wallet-to-wallet and wallet-to-Bybit movements do not become persisted `INTERNAL_TRANSFER`
  merely because the current installation can correlate both sides.
- The same canonical transfer row may behave as carry-over continuity in one accounting universe
  and as external disposal/acquisition in another; that decision belongs to replay, not to raw normalization.

LP extensions for concentrated-liquidity (CL) protocols:

- `LP_POSITION_ENTRY` (internal classifier event) maps to canonical `type=LP_ENTRY` for mint-style position creation.
- `LP_POSITION_EXIT` (internal classifier event) maps to canonical `type=LP_EXIT` for burn-style position close.
- For v3/v4 position mint tx, canonical `LP_ENTRY` must include economic principal outflows (wallet token spends), not only NFT lifecycle markers.
- Underlying principal legs inside `LP_ENTRY`, `LP_EXIT`, `LP_EXIT_PARTIAL`, and `LP_EXIT_FINAL` are continuity-only `TRANSFER` flows, not `BUY` / `SELL`.
- LP receipt markers (`LP token`, `BPT`, CL NFT) may still appear in canonical flows, but they remain continuity markers rather than independent basis assets.
- For v3/v4 custody transfer (`wallet <-> farm/strategy`) canonical types are `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE`.
- `LP_EXIT_PARTIAL` / `LP_EXIT_FINAL` represent decrease-liquidity exits with and without final position close boundary.
- `LP_ADJUST` is deprecated and kept as backward-compatible alias for legacy data only.
- `LP_FEE_CLAIM` maps to canonical `type=LP_FEE_CLAIM`.

---

## Price Sources

| PriceSource | Meaning | Priority |
|-------------|---------|----------|
| `STABLECOIN` | Hardcoded stablecoin parity | 1 |
| `EXECUTION` | Exact execution price carried by canonical source evidence such as a Bybit trade fill | 2 |
| `SWAP_DERIVED` | Ratio inferred from the net swap counterpart inside the same canonical tx | 3 |
| `WRAPPER` | Wrapped/native asset price inherited from the underlying native asset | 4 |
| `ECB` | Official EUR/USD FX resolver used for euro-backed stablecoins such as `EURC` | 5 |
| `BYBIT` | Historical market-data resolver preferred before Binance/CoinGecko when tx-local price is absent | 6 |
| `BINANCE` | Historical market-data resolver for assets that have deterministic Binance symbol mapping | 7 |
| `COINGECKO` | Historical resolver fallback when primary external coverage is unavailable or insufficient | 8 |
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
| INV-12 | Protocol-registry special-handler entries resolve through deterministic protocol semantics plus family-owned final mapping; unsupported methods become `UNKNOWN -> NEEDS_REVIEW`. |
| INV-13 | Continuity replay is chronology-safe: an inbound same-universe transfer becomes immediately spendable when it appears in ordered replay, and a later matched source carry may attach basis only; it must never mint quantity a second time. |
| INV-14 | `SWAP_DERIVED` may price a canonical asset only when that canonical symbol appears once among non-fee swap flows; multi-leg same-canonical swaps must fall back to safer pricing. |
| INV-15 | Audited `zkSync` Aave gateway selectors `0x80500d20`, `0x02c205f0`, and `0x474cf53d` must resolve to `LENDING_WITHDRAW` / `LENDING_DEPOSIT` before generic LP / unwrap / heuristic fallback when the expected `ETH/WETH/aZksWETH` movement shape is present. |
| INV-16 | On native-alias networks, an audited native-alias transfer that exactly matches `gasUsed * gasPrice` to the audited system fee sink is fee evidence and must not survive as both a principal `TRANSFER` leg and a separate `FEE` leg. |
| INV-17 | A routed outbound tx with deterministic wallet-boundary principal movement proven by raw transfer evidence may not remain a hash-specific `UNKNOWN` stop-condition; if stronger protocol identity is still absent, canonical normalization must preserve the proven outbound principal through a deterministic fallback type. |
| INV-18 | Audited `zkSync` routed `Across` source tx `0x27ad57d5` must resolve to `BRIDGE_OUT` when raw route evidence proves the helper path plus same-wallet destination parameters; the tx must not remain `UNKNOWN / NEEDS_REVIEW`. |
| INV-19 | When `ACCOUNTING_REPLAY` stops before replay/materialization because active blockers still exist, session pipeline state must persist as `BLOCKED`, not `COMPLETE`. |
| INV-20 | Provider-native balances that arrive with `contractAddress = 0x0000000000000000000000000000000000000000` must still normalize to native accounting identity `NATIVE:<NETWORK>`, not to an ERC-20 contract identity. |
| INV-21 | A stale `ACCOUNTING_REPLAY / RUNNING` session may be healed to `COMPLETE` only when the session has no pending raw/clarification/pricing/stat work and derived replay outputs are already materialized. |

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
derivedQuantity  = latest(asset_ledger_points.quantityAfter) for exact bucket
onChainQuantity  = on_chain_balances.quantity
balanceDiff      = onChainQuantity - derivedQuantity
status           = MATCH | MISMATCH | NOT_APPLICABLE
```

For "young" histories (within backfill horizon), `MISMATCH` should surface warning and guide user to manual compensating transaction.

### Current holdings projection

```text
currentQuantity  = on_chain_balances.quantity
currentHolding   = currentQuantity > 0
basisFields      = latest(asset_ledger_points.*After) for exact bucket
```

User-facing current holdings must be derived from `asset_ledger_points` plus
`on_chain_balances`, not from a persisted compatibility snapshot.

User-facing asset history and debug reads must use `asset_ledger_points`.

For continuity transfers, replay may materialize inbound quantity before the
matched source carry is seen in the ordered stream. In that case, the later
carry may attach basis only and must not add duplicate quantity.
