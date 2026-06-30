# WalletRadar — Domain Model

> **Version:** MVP v3 target
> **Last updated:** 2026-04-09

---

## Glossary

| Term | Definition |
|------|------------|
| **Wallet** | Read-only blockchain address tracked by the system (no signing, no private keys). |
| **Network** | Supported chain (`NetworkId`) processed independently per wallet. |
| **Raw Transaction** | Source transaction document stored in `raw_transactions` with provider payload in `rawData`. |
| **Protocol Registry** | Classpath lookup table loaded from `backend/src/main/resources/protocol-registry.json` for high-confidence contract-aware classification. |
| **Special Handler** | Deterministic registry-linked classifier for multi-function contracts. Receives the raw normalization view plus already extracted legs and returns one canonical result for the raw tx. |
| **Session Integration** | Persisted external-system connection embedded in `user_sessions.integrations[]` and owned by one session. |
| **Integration Raw Event** | Immutable provider API payload row stored in `integration_raw_events`. |
| **Bybit Extracted Event** | Bybit-specific extracted staging row stored in `bybit_extracted_events` and rebuilt from immutable provider raw events during backfill. |
| **Bybit convert cluster** | Deterministic Bybit staging cluster representing one provider-native asset conversion lifecycle. Current audited members are `CONVERT_HISTORY / Convert` and `TRANSACTION_LOG / CURRENCY_BUY/CURRENCY_SELL`, and membership is case-insensitive at the raw enum level. |
| **Tracked Wallet Universe** | Installation-wide projection of all tracked wallet addresses used by canonical normalization heuristics. |
| **Normalization Status** | Raw processing state: `PENDING` or `COMPLETE`. |
| **Normalized Transaction** | Canonical operation document (`1 tx = 1 doc`) with `flows[]` used by pricing/stat/accounting pipeline. |
| **Flow** | Canonical movement row inside normalized transaction (`quantityDelta`, role, asset, price fields). |
| **Self-canceling pair** | Same-asset same-quantity inbound and outbound legs inside one tx that net to zero and therefore represent wrapper / marker continuity rather than economic acquisition or disposal. |
| **Normalized Status Pipeline** | Receipt-clarifiable rows may pass through `PENDING_CLARIFICATION -> PENDING_RECLASSIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED`; low-confidence rows without receipt gaps may go directly to `PENDING_PRICE` or `NEEDS_REVIEW`. |
| **User Session** | Persisted workspace record keyed by client-generated `sessionId` in `user_sessions`; stores wallets, integrations, settings, and pipeline state. |
| **AVCO** | Weighted average acquisition cost used for cost basis and realised PnL. |
| **perWalletAvco** | AVCO computed from one wallet only. |
| **crossWalletAvco** | AVCO computed on request across selected wallets; never stored. |
| **Override** | User-defined replacement price stored in `cost_basis_overrides`, applied in replay. |
| **Manual Compensating Transaction** | Synthetic normalized transaction (`type=MANUAL_COMPENSATING`) for reconciliation fixes, idempotent by `clientId`. |
| **Accounting Universe** | Stable additive owner scope persisted in `accounting_universes`; used by replay/history continuity and session-scoped reads. |
| **Asset Ledger Point** | Immutable replay trace row in `asset_ledger_points` for one applied accounting transition on one wallet-network-asset bucket inside one accounting universe. |
| **Pass-through corridor** | Deterministic replay-only reservation that preserves exact carried basis from one proven continuity inbound into one later proven outbound/custody source leg without letting that carry pool into unrelated inventory first. |
| **Async execution-fee reserve** | Replay-only request-scoped native carry reserved for later async settlement refund or net lifecycle cost, separate from principal carry. Current approved use is `GMX V2 LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT`. |
| **Correlated carry ingress repair** | Replay-only unique-fit attachment that restores already-proven same-family carry across a correlated inbound when provider rounding truncated the destination quantity. |
| **Linked bridge carry repair** | Replay-only bridge-specific carry path for already-linked same-family `BRIDGE_OUT -> BRIDGE_IN` pairs. It preserves full source cost basis on the destination leg even when bridge settlement quantity drifts, while leaving only the unmatched excess as uncovered. |
| **Asset-changing bridge settlement carry** | Replay-only conservative settlement rule for already-linked `BRIDGE_OUT(source asset) -> BRIDGE_IN(destination asset)` route pairs where `continuityCandidate = false`. It reallocates source cost basis into the destination acquisition while preserving the source covered/uncovered ratio on the received asset. |
| **Order-insensitive family custody replay** | Replay rule for simple audited family-equivalent custody transactions (`principal out + receipt in`) that applies carry atomically even when normalized flow order is `receipt in` first. This prevents false uncovered receipt inventory on Aave-style deposits. |
| **Rebasing receipt accrual split** | Canonical normalization rule for audited rebasing receipt tokens where tx-local receipt balance delta exceeds principal moved in the same continuity family. Principal quantity remains `TRANSFER`, while the excess rebasing delta materializes as a separate `BUY` acquisition that must be priced and replayed explicitly. |
| **Sponsored gas top-up** | Canonical inbound native-gas assistance funded by a verified protocol/solver sender and persisted as `SPONSORED_GAS_IN`. It increases quantity with zero default cost basis and must not be materialized as a market-priced `BUY`. |
| **Internal transfer pair promotion** | Clarification-time promotion of a simple same-tx reciprocal on-chain transfer pair into `INTERNAL_TRANSFER` once both wallet-local canonical rows exist and prove the same owner continuity path. |
| **Internal transfer raw peer repair** | Normalization-time repair that clones a missing wallet-local raw row for a simple same-universe native transfer when current raw already proves sender, recipient, value, and gas, but fetch coverage produced only one wallet-scoped raw document. |
| **Reconciliation** | Comparison between latest exact-bucket replay state from `asset_ledger_points` and current on-chain quantity in `on_chain_balances`. |
| **Current Holding View** | Read-time holding state derived from latest exact-bucket `asset_ledger_points` plus `on_chain_balances`; not a persisted collection. |
| **Dashboard Issue Class** | Read-time diagnostic label for one current holding row. It is derived from current coverage and latest replay-state flags, not from a separate persisted collection. |
| **Incremental refresh cycle** | User-triggered bounded acquisition cycle that fetches only the delta since the last completed source checkpoint and then resumes the downstream normalization/pricing/accounting pipeline without resetting the full 2-year history. |

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
- `PENDING_RECLASSIFICATION` means clarification has completed or exhausted a
  bounded evidence attempt and the same canonical classifier must run again over
  `RawTransaction` plus persisted `clarificationEvidence`.
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

### IntegrationRawEvent (`integration_raw_events`)

Immutable provider-neutral external evidence layer.

```text
IntegrationRawEvent {
  id: String
  sessionId: String
  integrationId: String
  provider: String
  accountRef: String
  stream: String
  providerEventKey: String
  occurredAt: DateTime?
  fetchedAt: DateTime
  segmentId: String
  payload: Document
  ingestHash: String
}
```

### BybitExtractedEvent (`bybit_extracted_events`)

Provider-owned extracted staging rebuilt from `integration_raw_events` during
Bybit backfill.

```text
BybitExtractedEvent {
  id: String
  integrationRawEventId: String
  providerEventKey: String
  sourceStream: String
  sourceFileType: String
  uid: String
  sessionId: String
  integrationId: String
  timeUtc: DateTime?
  assetSymbol: String?
  quantityRaw: Decimal?
  canonicalType: String?
  bybitType: String?
  chain: String?
  txHash: String?
  networkId: NetworkId?
  receivedAddress: String?
  walletRef: String?
  onChainCorrelation: {
    status: String?
    correlationId: String?
    matchedDocId: String?
  }
  status: RAW | CONFIRMED
  importedAt: DateTime
}
```

### Pass-through corridor reservation (replay-only)

`Pass-through corridor` is not a persisted collection. It is a deterministic
replay planning artifact built from already-confirmed canonical rows before AVCO
replay starts.

Purpose:

- preserve exact carry for proven transit paths
- avoid poisoning that carry through pooled inventory when continuity inbound is
  followed by one later deterministic outbound/custody source leg

First approved slice:

- venue transit on custodial refs such as `BYBIT:<uid>`
- same-wallet same-network `BRIDGE_IN -> custody-deposit source leg`

Guardrails:

- corridor planning is replay-only
- it never upgrades or rewrites canonical transaction type
- it may use only current canonical fields already persisted on
  `normalized_transactions`
- if uniqueness is not provable, no corridor reservation is created

Rules:

- this is a rebuildable provider staging layer, not the immutable source of truth
- Bybit normalization reads `bybit_extracted_events` first
- for Bybit funding-account semantics, `sourceFileType=fund_asset_changes`
  must be rebuilt primarily from the `Funding Account Transaction History`
  stream, not from a stitched mix of narrower transfer / convert / earn
  endpoints
- extracted staging must preserve authoritative provider business families that
  drive later deterministic pairing, for example audited `ETH 2.0`
  `Stake/Mint` liquid-staking rows
- opposite sides of one audited Bybit lifecycle may legitimately keep different
  human-readable descriptions in staging when the provider business family is
  already authoritative, for example `ETH 2.0 / Stake` and `ETH 2.0 / Mint`
- `external_ledger_raw` remains migration-only compatibility input for older
  sessions until the legacy path is deleted

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

For deterministic position-scoped LP replay, out-of-bucket reward sideflows do
not close the position bucket on their own. A reward-only `LP_EXIT*` may
materialize as `UNKNOWN`, while the remaining multi-asset principal carry stays
reserved for a later principal-return exit under the same position
`correlationId`.

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

Important target rule for manual refresh:

- `sync_status` remains the stable source-level status row for one wallet×network
  or one integration source
- a user-triggered refresh is a new bounded cycle on that same source identity,
  not a second active `sync_status` document
- already confirmed canonical history remains persisted; refresh appends new raw
  evidence and may patch deterministic linkage metadata on existing canonical
  rows when new evidence proves a connection

```text
BackfillSegment {
  id: String
  sessionId: String?
  sourceKind: ONCHAIN | INTEGRATION
  segmentKind: BLOCK_RANGE | TIME_RANGE | CURSOR_PAGE
  status: PENDING | RUNNING | COMPLETE | FAILED
  retryCount: Int
  processedCount: Long?
  updatedAt: DateTime

  // on-chain lane
  syncStatusId: String?
  walletAddress: String?
  networkId: String?
  segmentIndex: Int?
  fromBlock: Long?
  toBlock: Long?
  lastProcessedBlock: Long?

  // integration lane
  integrationId: String?
  provider: String?
  accountRef: String?
  stream: String?
  fromTime: DateTime?
  toTime: DateTime?
  cursor: String?
}
```

### UserSession (`user_sessions`)

Persisted session workspace settings:

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
  integrations: [
    {
      integrationId: String
      provider: String
      status: CONNECTED | BACKFILLING | READY | ERROR | DISABLED
      displayName: String
      accountRef: String
      encryptedCredentials: {
        keyVersion: String
        nonceB64: String
        ciphertextB64: String
        maskedKey: String
      }
      capabilities: String[]
      createdAt: DateTime
      updatedAt: DateTime
      lastValidatedAt: DateTime?
      lastSyncAt: DateTime?
      lastError: String?
    }
  ]
  settings: {
    hideSmallAssets: Boolean?
  }
  createdAt: DateTime
  updatedAt: DateTime
  lastSeenAt: DateTime
}
```

Rules:

- `UserSession.wallets` and `UserSession.integrations` together define the
  session scope
- external venue account refs such as `BYBIT:<uid>` are attached directly to
  `integrations[]`
- the new target design does not require a separate persisted
  `accounting_universes` control-plane aggregate

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
  accountingUniverseId: String
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
  protocolVersion: String?

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

`protocolName` / `protocolVersion` on canonical normalized rows are best-effort
protocol identity labels. They are not economic semantics by themselves and may
be attached in either of two safe moments:

- initial normalization when the current raw shape already has a direct
  high-confidence registry hit
- clarification-time protocol enrichment when persisted metadata / receipt
  evidence makes protocol ownership obvious without changing `type`, `status`,
  or canonical flows

For protocol enrichment specifically, the interacted tx recipient may still be
authoritative even when explorer payloads also materialize token-transfer style
top-level rows. In those cases protocol detection must read the raw tx
recipient as protocol identity evidence without changing move/cost semantics.

Registry growth must therefore remain operationally safe: older canonical rows
may be repaired to fill missing `protocolName` / `protocolVersion` without a
full normalization rerun whenever the economic classification is already
correct.
  - `normalizedTransactionId ASC`
  - `flowIndex ASC`
  - `replaySequence ASC`
- `accountingAssetIdentity` is the exact replay bucket identity
- `accountingFamilyIdentity` is the continuity family used for move-basis and
  history aggregation
- when no broader family policy exists, `accountingFamilyIdentity` falls back to
  the exact asset identity
- replay may still use a narrower correlated transfer alias key for
  already-proven `correlationId` carry matching; that replay-local key does not
  redefine persisted `accountingFamilyIdentity`
- `quantityShortfall*` is a lifetime audit metric for historical coverage gaps;
  it is not the current uncovered holding quantity
- `uncoveredQuantity*` is the current held quantity that still lacks provable
  carried basis after the replay step
- `basisBackedQuantityAfter = max(quantityAfter - uncoveredQuantityAfter, 0)`
- session asset history is not persisted separately; it is aggregated on read
  from wallet-level `asset_ledger_points` scoped by the session's stable
  `accountingUniverseId`

### OnChainBalance (`on_chain_balances`)

Latest observed live quantity evidence for `(wallet, network, asset)` from the
post-replay balance-refresh pass.

`OnChainBalance` rows are session-scoped current evidence and therefore carry
`sessionId`; they must not be shared across unrelated sessions even when the
same wallet address is tracked elsewhere.

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

Session family asset-ledger reads may also expose historical shortfall sources
for the currently selected family:

```text
FamilyShortfallSourceView {
  walletAddress: String
  networkId: String?
  txHash: String?
  blockTimestamp: DateTime?
  normalizedType: String?
  protocolName: String?
  quantityShortfall: Decimal
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
- mixed-bucket sell, fee, and unknown outbound events consume current uncovered
  quantity before covered AVCO-backed quantity so the current uncovered field
  tracks the remaining held tail, not a lifetime deficit
- linked continuity carry keeps its covered-first transfer contract, moving
  available basis into the destination bucket before any unresolved transfer
  tail
- `FamilyShortfallSourceView` is a read-model diagnostic aggregated from
  positive family-level `quantityShortfallDelta` rows
- it helps explain where today's uncovered quantity was first introduced, but
  it is not a synthetic exact-parent proof for every current uncovered bucket
- provider-native rows may legitimately have `txHash = null` or `networkId = null`
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
| `SPONSORED_GAS_IN` |
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
  inside the same base-asset family; audited Bybit receipt-wrapper pairs such
  as `ETH -> METH` and `ETH -> CMETH` follow this shape; explicit reward
  side-flows may still stay economic `BUY`
- audited Bybit funding-history `ETH 2.0 / Stake` plus `ETH 2.0 / Mint`
  lifecycles also follow this liquid-staking continuity shape even when the two
  staging rows do not share the same description string
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
  - native-value EVK `batch(...)` rows may also be `LENDING_DEPOSIT` when
    clarification proves one positive native tx funding leg, one share mint to
    the tracked wallet, and one protocol-local wrapper / vault hop inside the
    clarified receipt
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
- Verified protocol/solver-funded native gas assistance does **not** stay in the
  generic `EXTERNAL_TRANSFER_IN` lane when current raw evidence proves all of:
  - wallet-boundary native inbound
  - `methodId == 0x` and no recipient-side protocol call
  - no token transfers and no internal transfers
  - sender resolves to a registry-backed `GAS_PAYER`
  - quantity fits the audited per-network gas-topup envelope
  In that case canonical type must be `SPONSORED_GAS_IN`.
- Outbound-only aggregator/router calls without a wallet-visible inbound counter-asset do not
  persist as `SWAP`; they demote to owner-agnostic `EXTERNAL_TRANSFER_OUT` unless a higher-priority
  bridge classifier proves `BRIDGE_OUT`.
- ParaSwap exact-out native-settlement routes do not stay as source-token
  spend plus source-token refund when current raw calldata already proves:
  - `dstToken = native sentinel`
  - wallet/default beneficiary semantics
  - one unique embedded wrapped-native unwrap amount equal to the exact-out
    amount
  In that case normalization must net the same-asset refund into the source
  `SELL` and materialize one positive native `BUY`.
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
  lifecycle. Current audited example: supported Aave wrapped-token gateway
  selectors must normalize as lending continuity, not as generic unwrap / LP /
  residual transfer families:
  - `zkSync`
    - `withdrawETH(...)`
    - `supplyWithPermit(...)`
    - `depositETH(...)`
  - `Base`
    - `depositETH(...)` when current raw evidence proves the wallet-boundary
      `ETH -> AWETH` mint shape
- For audited rebasing `Aave` WETH receipts (`aEthWETH`, `aArbWETH`,
  `aLinWETH`, `aManWETH`, `aZksWETH`), canonical normalization must not emit the
  full rebasing receipt delta as one continuity transfer when tx-local receipt
  balance change exceeds the principal moved in the same tx.
  Instead:
  - matched principal quantity remains continuity `TRANSFER`
  - positive receipt-side excess is a separate `BUY`
  - positive underlying-side excess on withdraw is a separate `BUY`
- For Aave-style `BORROW` / `REPAY`, `protocolName = Aave` may be attached by
  the generic selector lane only when current evidence proves both:
  - the selector is canonical `borrow(...)` or `repay(...)`
  - the same tx contains `variableDebt*` or `stableDebt*` marker movement
  Registry address discovery remains the authoritative source for exact pool
  identity and `protocolVersion`.
- Another audited narrow override exists for `zkSync` routed `Across` sends:
  selector `0x27ad57d5` must resolve as source-side `BRIDGE_OUT` when current
  stored raw evidence proves the same-wallet `Across` route from calldata plus
  wallet-boundary native funding, even if intermediate helper / settlement hops
  are absent from the saved explorer transfer list.

### Owner-Agnostic Transfer Semantics

`normalized_transactions.type` must stay independent from the current accounting universe.

- Plain inbound transfer facts persist as `EXTERNAL_TRANSFER_IN`.
- Sponsored protocol gas assistance persists as `SPONSORED_GAS_IN`.
- Plain outbound transfer facts persist as `EXTERNAL_TRANSFER_OUT`.
- Bridge facts persist as `BRIDGE_OUT` / `BRIDGE_IN`.
- Direct same-universe wallet-to-wallet on-chain pairs may still promote into
  `INTERNAL_TRANSFER` once both wallet-local canonical rows exist and prove one
  owner-continuity path.
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

- Wallet-to-Bybit movements do not become persisted `INTERNAL_TRANSFER` merely
  because the current installation can correlate both sides.
- Same-universe wallet-to-Bybit continuity is restored on external canonical
  transfer rows through deterministic `BYBIT:<NETWORK>:<txHash>` correlation
  plus matched counterpart evidence.
- The same canonical transfer row may behave as carry-over continuity in one accounting universe
  and as external disposal/acquisition in another; that decision belongs to replay, not to raw normalization.

LP extensions for concentrated-liquidity (CL) protocols:

- `LP_POSITION_ENTRY` (internal classifier event) maps to canonical `type=LP_ENTRY` for mint-style position creation.
- `LP_POSITION_EXIT` (internal classifier event) maps to canonical `type=LP_EXIT` for burn-style position close.
- For v3/v4 position mint tx, canonical `LP_ENTRY` must include economic principal outflows (wallet token spends), not only NFT lifecycle markers.
- Underlying principal legs inside `LP_ENTRY`, `LP_EXIT`, `LP_EXIT_PARTIAL`, and `LP_EXIT_FINAL` are continuity-only `TRANSFER` flows, not `BUY` / `SELL`.
- Concentrated-liquidity principal continuity is position-scoped, not
  wallet-asset-scoped. Deterministic correlation must use
  `lp-position:<network>:<protocol-slug>:<tokenId>`.
- Uniswap-v4-style direct `modifyLiquidities(bytes unlockData,uint256 deadline)`
  is a normalization-safe source of that deterministic token id when the nested
  `unlockData` action params operate on an existing position.
- If a mint-style concentrated-liquidity row does not expose the position token
  id in current raw calldata, it must stay `PENDING_CLARIFICATION` with
  `LP_POSITION_CORRELATION_REQUIRED` until receipt clarification can recover
  the NFT mint log.
- On `BLOCKSCOUT`-backed networks, a concentrated-liquidity `LP_EXIT` /
  `LP_FEE_CLAIM` is not final if wallet-scoped explorer evidence still lacks a
  native settlement leg that can be recovered from transaction-level internal
  transfers; those rows must enter receipt clarification before pricing / replay.
- LP receipt markers (`LP token`, `BPT`, CL NFT) may still appear in canonical flows, but they remain continuity markers rather than independent basis assets.
- Concentrated-liquidity exits may return a shifted principal asset mix. Basis
  may therefore move across returned principal assets inside the same position
  lifecycle, but not into unrelated reward assets.
- During position-scoped `LP_EXIT*` replay, positive transfer legs whose asset
  identity does not match any source-leg identity must not be demoted to
  immediate `UNKNOWN` purely for that reason. They remain deferred residual
  principal candidates until replay proves whether they are the only remaining
  principal-return path or merely reward-side residuals.
- Aggregate uncovered quantity across heterogeneous source carries is not a
  valid hard blocker for position-scoped `LP_EXIT*` residual principal
  allocation. Once same-asset carry has been peeled off, replay may still move
  the remaining covered basis into deterministic residual principal returns:
  - same-asset residual principal legs
  - replay-known-value residual legs, including trusted USD-stable transfer
    legs without an explicit persisted transfer-side `unitPriceUsd`
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
| INV-15 | Audited Aave wrapped-token gateway selectors must resolve to `LENDING_WITHDRAW` / `LENDING_DEPOSIT` before generic LP / unwrap / heuristic fallback when the expected supported-network movement shape is present: `zkSync` `ETH/WETH/aZksWETH` for selectors `0x80500d20`, `0x02c205f0`, `0x474cf53d`, and `Base` `ETH/AWETH` for `depositETH(...)` selector `0x474cf53d`. |
| INV-16 | On native-alias networks, an audited native-alias transfer that exactly matches `gasUsed * gasPrice` to the audited system fee sink is fee evidence and must not survive as both a principal `TRANSFER` leg and a separate `FEE` leg. |
| INV-17 | A routed outbound tx with deterministic wallet-boundary principal movement proven by raw transfer evidence may not remain a hash-specific `UNKNOWN` stop-condition; if stronger protocol identity is still absent, canonical normalization must preserve the proven outbound principal through a deterministic fallback type. |
| INV-18 | Audited `zkSync` routed `Across` source tx `0x27ad57d5` must resolve to `BRIDGE_OUT` when raw route evidence proves the helper path plus same-wallet destination parameters; the tx must not remain `UNKNOWN / NEEDS_REVIEW`. |
| INV-19 | When `ACCOUNTING_REPLAY` stops before replay/materialization because active blockers still exist, session pipeline state must persist as `BLOCKED`, not `COMPLETE`. |
| INV-20 | Provider-native balances that arrive with `contractAddress = 0x0000000000000000000000000000000000000000` must still normalize to native accounting identity `NATIVE:<NETWORK>`, not to an ERC-20 contract identity. |
| INV-21 | A stale `ACCOUNTING_REPLAY / RUNNING` session may be healed to `COMPLETE` only when the session has no pending raw/clarification/reclassification/pricing/stat work and derived replay outputs are already materialized. |
| INV-22 | Generic selector fallback may attach `protocolName = Aave` for `BORROW` / `REPAY` only when the same tx also proves Aave debt-marker continuity through `variableDebt*` or `stableDebt*` movement; selector hit alone is insufficient. |
| INV-23 | Audited rebasing Aave WETH receipt drift must normalize as `principal TRANSFER + accrual BUY`, not as one oversized receipt continuity transfer. |
| INV-24 | A route-funded asset-changing `BRIDGE_OUT` source tx with one token principal leg plus one native tx-value funding leg must normalize the token leg as `TRANSFER` and the native funding leg as `FEE`; the native route funding must not survive as a second principal transfer leg. |

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

## External Integrations Policy

- External venues are connected through session-owned integrations, not through
  manual file preloading.
- Provider-specific enrichment belongs to `BACKFILL`.
- External-provider normalization must consume only persisted provider evidence.
- Post-normalization provider clarification is out of scope for the new model.
- If a provider event is still ambiguous after the full provider backfill pass,
  it becomes explicit review work rather than a deferred provider lookup.

## Authentication & Identity

**IdentityProvider** — Supported SSO provider enum (`GOOGLE`). Extensible: new providers added as new enum values + Spring OAuth2 client registration.

**IdentityBinding** — Embedded sub-document on `UserSession` linking the session to an identity provider account. Fields: `provider`, `subject` (Google "sub", stable idpId), `email`, `emailVerified`, `displayName`, `pictureUrl`, `linkedAt`.

**Canonical Session** — The unique `UserSession` bound to a given `IdentityBinding`. Enforced by a unique sparse MongoDB index on `{identity.provider, identity.subject}`. On each login, the backend resolves (or creates) this session and encodes its `_id` as the `sessionId` claim in the JWT.

**wr_auth cookie** — HttpOnly; Secure; SameSite=Lax HS256 JWT cookie issued after successful Google OAuth2 login. Contains `sub`, `sessionId`, `provider`, `email`, `name`, `picture`. Validated by Spring Security `NimbusReactiveJwtDecoder` on every API request when `walletradar.auth.enabled=true`.

**auth.enabled flag** — `walletradar.auth.enabled` (default `false`). When `false`, a permit-all filter chain runs, preserving backward-compatible anonymous access. Set to `true` in prod with `WALLETRADAR_AUTH_ENABLED=true`.
