# WalletRadar — System Architecture

> **Version:** SAD v3.1
> **Date:** 2026-03-24
> **Style:** Modular monolith (Spring Boot)
> **Status:** Accepted target architecture

---

## A — Decisions & Assumptions

### Core decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D-01 | Modular monolith, not microservices | Single VPS, low operational cost, clear package boundaries, no network hop between normalization, pricing, and AVCO. |
| D-02 | Raw collection stays source-aware, but classification stays source-agnostic | Backfill may use explorer-first, provider-first, or native-repair paths per network, but v3 classification still starts only from canonicalized `raw_transactions`, not from source-specific branches. |
| D-03 | Synthetic logs are never classification evidence | Explorer-derived synthetic `rawData.logs[]` remain out of bounds. Real provider-persisted receipt logs may exist in canonical raw and may be consumed only through the normal raw view/projection. Clarification may enrich `status`, `gasUsed`, and `contractAddress`, and for an allowlisted review set it may also persist adapted receipt evidence plus the raw full receipt payload in dedicated evidence fields before classification may consume them. If a clarification source call already fetched a receipt payload, that source-native payload is persisted in full; metadata-safe versus receipt-log-backed clarification is a usage policy, not a storage-truncation policy. Clarification is not complete unless that evidence is actually persisted on the raw row in one canonical raw-level storage contract that runtime classification and live Mongo audits both read. |
| D-04 | `user_sessions` are persisted on the backend | Session state is client-generated (`sessionId`) but stored in MongoDB so wallet sets, selected networks, and backfill status survive browser restarts. |
| D-05 | Canonical normalization uses one installation-wide tracked wallet universe | `normalized_transactions` must not change meaning per session. Internal-transfer detection uses a global tracked-wallet projection, not per-session payloads. |
| D-06 | `external_ledger_raw` is the immutable Bybit import layer; `normalized_transactions` is the canonical accounting layer | Raw Bybit rows remain source evidence. Basis replay and read models consume only canonical normalized documents, regardless of source. |
| D-07 | Matched Bybit withdraw/deposit and on-chain legs stay traceable on both sides | Double-counting is prevented by correlation-linked `TRANSFER` semantics and basis carry-over, not by deleting one side of the evidence chain. |
| D-08 | UTA trade pairing uses a sliding `±5 sec` window | Bucketed pairing loses valid pairs at window boundaries and does not satisfy the current orphan-leg blocker. |
| D-09 | Continuity events use carry-over semantics, not synthetic BUY/SELL | Internal transfers, bridges, Bybit custody transfers, protocol custody, lending deposits/withdraws, and vault deposits/withdraws move basis without realization. |
| D-10 | AVCO only, no FIFO/LIFO | Deterministic replay, simpler replay state, better fit for DeFi merge/split behavior, and aligned with WalletRadar product scope. |
| D-11 | Snapshot-first reads remain the read-path rule | GET endpoints read Mongo only. No RPC, no receipt fetch, and no historical pricing lookup on the request path. |
| D-12 | Current milestone scope is limited to normalization, pricing, AVCO, and reconciliation | Backfill and `user_sessions` already exist. New onboarding flows, current-balance polling, portfolio snapshots, and broader control-plane work are deferred. |
| D-13 | Protocol-registry runtime data comes from one classpath resource only | `backend/src/main/resources/protocol-registry.json` is the authoritative source. Duplicate repo copies are not authoritative because drift would silently change classification behavior. |
| D-14 | Multi-function registry contracts use explicit special-handler dispatch | Balancer Vault, GMX V2 ExchangeRouter, Pendle routers, and similar contracts cannot be trusted through one static `event_type`. Registry marks them for handler-based dispatch instead. |
| D-15 | Special handlers return one canonical result per raw tx | The accepted v3 data model is still `1 raw tx -> 1 normalized_transaction`. Special handling may refine classification, but it does not fan out one raw tx into multiple canonical docs. |
| D-16 | Registry `event_topics` remain reference metadata only | They may stay in the JSON for offline reference, but runtime classification never loads or matches them. |
| D-17 | `PENDING_CLARIFICATION` is reserved for receipt-clarifiable rows only | Low confidence alone does not justify explorer receipt work. If receipt metadata cannot improve the record, the row must go directly to pricing or review. |
| D-18 | Missing ordering metadata is repaired before canonical normalization | `transactionIndex` is a deterministic replay prerequisite. It must be repaired from tx-level evidence, never guessed, and never deferred into generic clarification. |
| D-19 | Production classification trusts only backfill-available raw evidence | Explorer page summaries, tags, and human-readable descriptions may help audit sessions, but they never override raw legs, contract identity, or receipt-safe clarification evidence in runtime classification. |
| D-20 | Scam filtering must be composite and precision-tested | Promo/phishing suppression stays upstream, but it must not drop legitimate reward-claim routes merely because a function name contains `claim` or `reward`. |
| D-21 | Tx-level raw fields and transfer-row payloads must stay separated | Top-level tx fields such as `from`, `to`, `value`, `input`, `methodId`, and `functionName` must describe the transaction row only. Token-transfer row payloads may enrich `explorer.tokenTransfers[]`, but must never overwrite tx-level native value or counterparty identity. |
| D-22 | Direct native-flow derivation is allowed only from canonical tx-level evidence | Token transfer quantities must never be reinterpreted as native `value`. If a source returns transfer-style payloads, ingestion must canonicalize tx-level fields before persistence and normalization must ignore contaminated top-level `value`. |
| D-23 | Protocol-specific rule design follows protocol-source semantics when available | When official contracts or protocol docs exist, classifier rules should align to those method semantics rather than explorer UI labels or ad-hoc heuristics. |
| D-24 | Clarification reasons must describe the real missing receipt-safe evidence | `MISSING_CONTRACT_ADDRESS` is valid only for explicit contract-creation rows; missing `effectiveGasPrice` is not satisfied by legacy `gasPrice` fallback used for fee math. |
| D-25 | `CLAIM_WITHOUT_MOVEMENT` is a valid per-wallet terminal state | When a tracked wallet signs a known claim route but does not receive the reward transfer in persisted raw evidence, classification must not synthesize `REWARD_CLAIM`. |
| D-26 | Clarification is a bounded receipt-enrichment stage, not a generic second classifier | Metadata-only clarification remains the default. Full receipt-log enrichment is allowed only for an allowlisted review-family set. Traces, explorer UI summaries, and analyst-only notes remain out of bounds. Rows already closable from current raw stay classification work. Clarification source must follow raw-source lineage by default and may persist same-source internal transfers only for allowlisted native-bridge families that truly require those legs. |
| D-27 | Pricing-ready economic rows require persisted movement evidence | Resolved economic rows may not proceed to pricing or replay from fee-only flow. Wrapped-native continuity, bridge-entry semantics, liquidity entry/exit semantics, bridge settlement continuity, and admin/config demotion must be correct before AVCO consumes the row. |
| D-28 | Pricing readiness is validated from live data, not from task completion alone | A rerun is not pricing-ready until the post-rerun Mongo audit shows zero resolved wrapped-native leaks into `VAULT_*` / `LENDING_WITHDRAW`, zero resolved recognized bridge-entry leaks into `VAULT_DEPOSIT`, zero route-tagged bridge-initiation leaks into `EXTERNAL_TRANSFER_OUT`, zero claim-income leaks into `EXTERNAL_INBOUND`, zero self-promotional or spam-like inbound families leaking into priceable `EXTERNAL_INBOUND`, zero known Slipstream LP lifecycle leaks (`increaseLiquidity(...)`, trusted stake-contract actions) into generic transfer / inbound types, zero priceable GMX order-initiation rows leaking into `EXTERNAL_TRANSFER_OUT`, zero clarification persistence mismatches between raw and normalized state, and zero economically material review families that should already be deterministic from current raw or allowlisted clarification evidence. In the current `run/9` state, the remaining gate is the explicit four-row basis-blocking review tail, not resolved-lane leakage. |
| D-29 | Safe review-tail reduction happens before any new evidence expansion | Spam / airdrop clusters, explicit `CLAIM_WITHOUT_MOVEMENT`, failed transactions, admin / governance actions, pending-request / pending-order families, and out-of-scope NFT or attestation mints should leave `NEEDS_REVIEW` from current raw. Clarification is reserved for receipt-closeable residuals, and the remaining irreducible stop-condition must stay explicit instead of being forced into synthetic economic types. After `run/9`, safe stop-condition rows should no longer remain in review once persisted receipt evidence proves zero-effect cleanup/admin semantics, such as the audited Pancake Infinity `modifyLiquidities(...)` row with `liquidityDelta = 0`, `liquidityChange = 0`, and `feesAccrued = 0`. |

### Assumptions

- A single user or small household runs the instance. No auth and no multi-tenant isolation are required.
- `sessionId` is client-generated and stable; backend persists the associated wallet set in `user_sessions`.
- `tracked_wallets` is a derived installation-wide projection of all tracked wallet addresses.
- A 2-year backfill window is sufficient for automated reconstruction; older basis requires explicit incomplete-history handling.
- Bybit is the only CEX source in scope for v3.
- Supported on-chain networks for the accounting core are `ETHEREUM`, `ARBITRUM`, `OPTIMISM`, `POLYGON`, `BASE`, `BSC`, `AVALANCHE`, `MANTLE`, `LINEA`, `UNICHAIN`, `ZKSYNC`, `KATANA`, and `PLASMA`.
- `KATANA` and `PLASMA` use Etherscan-compatible explorer ingestion the same way as other supported EVM networks.

---

## B — Cost-Efficient Architecture Diagram

```text
┌───────────────────────────────────────────────────────────────────────┐
│ External Sources                                                      │
│                                                                       │
│ Etherscan-compatible explorers                                        │
│ ETH ARB OP POLYGON BASE BSC AVAX MANTLE LINEA UNICHAIN ZKSYNC        │
│ KATANA PLASMA                                                         │
│                                                                       │
│ CoinGecko historical API            Bybit NDJSON already in Mongo     │
└──────────────┬──────────────────────────────┬──────────────────────────┘
               │                              │
               ▼                              ▼
┌───────────────────────────────────────────────────────────────────────┐
│ Spring Boot modular monolith                                          │
│                                                                       │
│ api/                                                                  │
│   SessionController, WalletController                                 │
│                                                                       │
│ session/                                                              │
│   user_sessions persistence and session-level backfill reads          │
│                                                                       │
│ wallet-universe/                                                      │
│   tracked_wallets projection for canonical transfer heuristics        │
│                                                                       │
│ ingestion/backfill/                                                   │
│   existing raw fetch pipeline -> raw_transactions                     │
│                                                                       │
│ normalization/                                                        │
│   RawTxNormalizationJob                                               │
│   ProtocolRegistryLoader / SpecialHandlerDispatcher                   │
│   MethodId / FunctionName / Heuristics                                │
│   LegExtractor                                                        │
│   ClarificationJob (metadata-safe + allowlisted full receipt)         │
│   BybitLedgerNormalizer                                               │
│   BybitTradePairer (±5 sec)                                           │
│   BybitBridgeCorrelator                                               │
│   -> normalized_transactions                                          │
│                                                                       │
│ pricing/                                                              │
│   Stablecoin -> SwapDerived -> Wrapper -> CoinGecko historical        │
│                                                                       │
│ costbasis/                                                            │
│   AvcoEngine -> asset_positions                                       │
│   Reconciliation report / incomplete-history flags                    │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────────────┐
│ MongoDB                                                               │
│ user_sessions  tracked_wallets  sync_status  backfill_segments        │
│ raw_transactions  external_ledger_raw  normalized_transactions        │
│ asset_positions                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## C — Module Breakdown

- `api`
  Exposes session and wallet backfill endpoints only. No pricing or replay logic in controllers.
- `session`
  Owns `user_sessions` persistence, read models for session wallets, and session-level aggregation of backfill status.
- `wallet-universe`
  Owns the installation-wide `tracked_wallets` projection used by canonical normalization.
- `ingestion.backfill`
  Owns source-aware raw data collection only. Existing responsibility is preserved, but provider-first and native-repair strategies may vary by network.
- `ingestion.normalization`
  Owns raw transaction classification, leg extraction, canonical document construction, and Bybit normalization.
- `pricing`
  Owns historical USD resolution for normalized flows.
- `costbasis`
  Owns deterministic AVCO replay, basis continuity rules, and reconciliation outputs.
- `domain`
  Contains documents, enums, value objects, and repository contracts only.
- `config`
  Wiring, async executors, Mongo converters, schedulers.
- `common`
  Retry/backoff, rate limits, and small shared helpers.

Dependency rules:

```text
api            -> session, wallet-universe, ingestion.backfill
session        -> domain, wallet-universe
wallet-universe -> domain
ingestion.*    -> domain, pricing, common, config
pricing        -> domain, common
costbasis      -> domain, common
domain         -> (nothing)
config/common  -> (nothing)
```

---

## D — Mongo Collections + Index Strategy

### `user_sessions`

Purpose:
- Persist the wallet set and selected networks for a client-generated `sessionId`.

Key fields:
- `_id`
- `wallets[].address`
- `wallets[].label`
- `wallets[].color`
- `wallets[].networks[]`
- `createdAt`, `updatedAt`, `lastSeenAt`

Indexes:
- `{ _id: 1 }` unique default

### `tracked_wallets`

Purpose:
- Persist the installation-wide tracked wallet universe used by canonical normalization and reclassification triggers.

Key fields:
- `address`
- `refCount`
- `firstSeenAt`
- `lastSeenAt`

Indexes:
- `{ address: 1 }` unique

### `sync_status`

Purpose:
- Track wallet×network backfill lifecycle.

Indexes:
- `{ walletAddress: 1, networkId: 1 }` unique
- `{ status: 1, nextRetryAfter: 1 }`

### `backfill_segments`

Purpose:
- Persistent orchestration and retry state for raw collection segments.

Indexes:
- `{ syncStatusId: 1, segmentIndex: 1 }` unique
- `{ walletAddress: 1, networkId: 1, status: 1 }`

### `raw_transactions`

Purpose:
- Immutable on-chain evidence per `(txHash, networkId, walletAddress)` with controlled enrichment.

Key fields:
- `_id = txHash:networkId:walletAddress`
- `txHash`, `networkId`, `walletAddress`
- `blockNumber`
- `normalizationStatus`
- `rawData.timeStamp`
- `rawData.transactionIndex`
- `rawData.methodId`
- `rawData.functionName`
- `rawData.explorer.tokenTransfers[]`
- `rawData.explorer.internalTransfers[]`

Indexes:
- `{ txHash: 1, networkId: 1, walletAddress: 1 }` unique
- `{ walletAddress: 1, networkId: 1, normalizationStatus: 1 }`
- `{ walletAddress: 1, networkId: 1, blockNumber: 1 }`

### `external_ledger_raw`

Purpose:
- Immutable Bybit import layer.

Key fields:
- `_id = BYBIT:uid:filename:rowIndex`
- `sourceFileType`
- `timeUtc`
- `canonicalType`
- `basisRelevant`
- `outOfScope`
- `assetSymbol`, `quantityRaw`
- `filledPrice`, `cashFlow`
- `txHash`, `networkId`
- `onChainCorrelation.status`
- `onChainCorrelation.correlationId`

Indexes:
- `{ _id: 1 }` unique default
- `{ status: 1, basisRelevant: 1, outOfScope: 1 }`
- `{ uid: 1, timeUtc: 1 }`
- `{ txHash: 1 }` sparse

### `normalized_transactions`

Purpose:
- Canonical accounting stream for both `ON_CHAIN` and `BYBIT`.

Key fields:
- `_id`
- `source = ON_CHAIN | BYBIT`
- `txHash`
- `networkId`
- `walletAddress`
- `blockTimestamp`
- `transactionIndex`
- `type`
- `status`
- `confidence`
- `classifiedBy`
- `correlationId`
- `flows[]`
- `missingDataReasons[]`

Indexes:
- `{ txHash: 1, networkId: 1, walletAddress: 1 }` unique sparse
- `{ source: 1, status: 1, blockTimestamp: 1 }`
- `{ walletAddress: 1, status: 1, blockTimestamp: 1, transactionIndex: 1 }`
- `{ correlationId: 1 }` sparse
- `{ "flows.assetContract": 1 }`

### `asset_positions`

Purpose:
- Materialized per-wallet asset state after replay.

Key fields:
- `walletAddress`, `networkId`, `assetContract`, `assetSymbol`
- `quantity`
- `perWalletAvco`
- `totalCostBasisUsd`
- `totalRealisedPnlUsd`
- `hasIncompleteHistory`
- `reconciliationStatus`
- `lastEventTimestamp`
- `lastCalculatedAt`

Indexes:
- `{ walletAddress: 1, networkId: 1, assetContract: 1 }` unique
- `{ walletAddress: 1, assetSymbol: 1 }`
- `{ reconciliationStatus: 1 }`

Read paths satisfied by indexes:
- session backfill polling
- tracked wallet universe lookups for normalization heuristics
- normalization job polling for raw tx
- pricing polling for canonical documents
- deterministic replay reads per wallet and per source
- reconciliation and missing-data reporting

---

## E — Data Flow

### 1. Existing raw collection remains the starting point

- Session or wallet registration updates the `tracked_wallets` projection before normalization depends on it.
- `BackfillJobRunner`, `BackfillNetworkExecutor`, and `RawFetchSegmentProcessor` remain responsible for raw ingestion orchestration.
- Raw ingestion may be explorer-first or provider-first by network, with native RPC used as bounded repair only where configured.
- `ScamFilter` stays ingestion-time and can drop obvious poison/spam documents before persistence.
- `ScamFilter` uses composite promo/phishing signals and regression fixtures so
  legitimate claim distributors survive ingestion.
- Ingestion must canonicalize tx-level fields before persistence. Transfer-style payload rows may populate `explorer.tokenTransfers[]`, but they must not leak their `value`, `to`, or `input` into the top-level tx shape.
- Raw collection completes into `raw_transactions` with `normalizationStatus=PENDING`.

### 2. On-chain normalization

1. `RawTxNormalizationJob` starts only for wallet×network pairs whose raw backfill is complete.
2. If a raw tx lacks deterministic ordering metadata such as `transactionIndex`, it enters a bounded raw-repair path before canonical normalization continues.
3. Ready raw docs are processed in chronological order:
   - `rawData.timeStamp ASC`
   - `rawData.transactionIndex ASC`
   - `txHash ASC` as deterministic tie-breaker
4. Classification order is strict:
   - protocol registry
   - method id
   - decoded function name
   - token/internal/native transfer patterns
5. `rawData.methodId` is recovered from `rawData.input[0:10]` when the stored selector is blank.
6. Known wrapped-native selectors and known bridge/router methods are resolved before generic `deposit` / `withdraw` / `multicall` function-name fallback can assign broad `VAULT_*` or `EXTERNAL_*` types.
7. Plain positive inbound transfer legs default to `EXTERNAL_INBOUND` unless contract-aware reward or bridge evidence exists.
8. Promo/phishing inbound patterns are excluded before reward ambiguity handling and before default `EXTERNAL_INBOUND` assignment.
9. Known bridge-entry methods such as `depositV3`, route-tagged bridge-initiation families such as LI.FI / Jumper `callDiamondWith*`, `transferRemote(...)`, and bridge-settlement selectors such as `fillV3Relay`, `fillRelay`, `redeemWithFee`, `execute302`, and `directFulfill` resolve to bridge continuity semantics, not generic `REPAY`, `LENDING_WITHDRAW`, `VAULT_WITHDRAW`, or `EXTERNAL_TRANSFER_OUT`.
10. Claim-income families such as Pancake `harvest(...)` and vesting `release()` resolve to explicit claim / income semantics before generic `EXTERNAL_INBOUND` fallback can win.
11. GMX `createOrder(...)` and similar order-initiation rows are not finalized disposals and may not become priceable `EXTERNAL_TRANSFER_OUT` rows until persisted evidence proves final settlement.
12. LP position-manager router containers such as `multicall` and Uniswap-v4-style `modifyLiquidities` dispatch by contract-aware inner method rules, not broad router keywords.
13. Method-aware protocol bundles such as Morpho Bundler3 dispatch by contract-specific rules before generic `multicall` or `bundle` fallback.
14. Zero-amount token transfers with no economic counterflow never create economic movement; they resolve to contract-scoped admin/no-op handling or explicit review.
15. Protocol-registry runtime data is loaded from `backend/src/main/resources/protocol-registry.json` only.
16. Registry `event_topics` remain reference metadata and are not loaded into the classifier.
17. Registry entries with `specialHandler` dispatch into one deterministic handler result over the already extracted legs.
18. Unsupported special-handler methods become `UNKNOWN -> NEEDS_REVIEW` with explicit missing-data reasons; they do not silently fall through to generic heuristics.
19. `LegExtractor` uses:
   - canonical tx-level fields from the raw view / `explorer.tx`
   - `rawData.explorer.tokenTransfers[]`
   - `rawData.explorer.internalTransfers[]`
   - direct native tx value only when it is canonical tx-level evidence
   - persisted real receipt logs when a method-aware handler explicitly needs them
20. Synthetic `rawData.logs[]` are ignored.
21. Heuristics that depend on "own wallet" knowledge use the installation-wide `tracked_wallets` projection, never per-session wallet sets.
22. Canonical docs land in `normalized_transactions` with:
   - `PENDING_PRICE` when evidence is sufficient
   - `PENDING_CLARIFICATION` only when receipt metadata may help and is currently missing
   - `NEEDS_REVIEW` when classification is still unresolved

### 3. Clarification

1. `ClarificationJob` fetches lineage-consistent receipt metadata for receipt-clarifiable records and may fetch full receipt evidence only for an allowlisted residual-review set.
2. Allowed enrichments:
   - execution status
   - gas used / effective gas price
   - created contract address
3. Clarification may update confidence and gas handling, but it does not promote synthetic logs into classifier evidence.
4. Clarification is not a generic backlog for low-confidence heuristic classifications such as wrapped-native selector calls, ambiguous inbound-vs-reward cases, or unsupported router / LP-position methods.
5. Clarification is also out of scope for promo/phishing inbound suppression, bridge-settlement semantics, and zero-value token no-op routing.
6. Rows that enter clarification must record explicit missing receipt-safe reasons; an empty `missingDataReasons[]` list is not acceptable for a clarification-eligible row.
7. Route-tagged LI.FI / Jumper bridge-initiation leaks, Circle CCTP `redeem(...)` destination-side bridge-in semantics, explicit receiver-wallet claim payout semantics, and pending redeem-request initiation semantics remain classification-time responsibilities when current raw plus persisted clarification evidence already make those rows deterministic.
8. After the configured retry budget:
   - improved record -> `PENDING_PRICE`
   - unresolved record -> `NEEDS_REVIEW`
9. Clarification may fetch full receipt evidence only for allowlisted residual review families whose closure requires receipt logs.
10. Clarification should persist both the adapted clarification evidence and the raw full receipt payload, when the source exposes it, in dedicated clarification-evidence fields.
11. Clarification must fetch receipt evidence from the same source family that produced the raw row unless an explicit documented fallback is triggered.
12. Persisted `clarificationEvidence` on raw rows and normalized clarification attempt counters must remain live-parity safe; silent drift is a warning, not an acceptable steady state.
13. Clarification must not use traces, explorer UI labels, or manual audit notes as runtime evidence.

### 4. Pricing

Price resolution order:

1. `StablecoinResolver`
   `USDC`, `USDT`, `DAI`, `USDE`, `GHO`, `FRAX`, and configured stable variants -> `$1.00`
2. `SwapDerivedResolver`
   If a swap has a stablecoin side, derive the non-stable leg price directly from the swap ratio
3. `WrapperResolver`
   Wrapped native assets resolve to the native price path
4. `CoinGeckoHistoricalResolver`
   Last resort historical price lookup keyed by `(assetContract, date)`
5. `PRICE_UNKNOWN`
   Quantity still participates in replay; price stays null and `hasIncompleteHistory` becomes true
6. Pricing is released only after a live post-rerun audit proves there are no:
   - resolved wrapped-native leaks into `VAULT_*` / `LENDING_WITHDRAW`
   - resolved recognized bridge-entry leaks into `VAULT_DEPOSIT`
   - route-tagged bridge-initiation leaks into `EXTERNAL_TRANSFER_OUT`
   - Circle CCTP `redeem(...)` leaks into `VAULT_WITHDRAW`
   - explicit receiver-wallet claim payout leaks into `EXTERNAL_INBOUND`
   - pending redeem-request initiation leaks into priceable
     `EXTERNAL_TRANSFER_OUT`
   - claim-income leaks into `EXTERNAL_INBOUND`
   - priceable GMX `createOrder(...)` rows without finalized settlement semantics

### 5. Bybit normalization

1. `external_ledger_raw` is already loaded and remains the Bybit source of truth.
2. `BybitTradePairer` pairs `uta_derivatives` trade legs with a sliding `±5 sec` window on the same contract.
3. `BybitBridgeCorrelator` matches `withdraw_deposit` rows to on-chain raw docs by `txHash` and network.
4. When a withdraw/deposit is matched:
   - the Bybit row remains in `external_ledger_raw` as evidence
   - a `BYBIT` canonical document is still created in `normalized_transactions`
   - the canonical row uses synthetic custody wallet id `BYBIT:<uid>`
   - the corresponding on-chain normalized document carries the same `correlationId`
   - both sides use `TRANSFER` flows only
   - AVCO treats the pair as one basis-carry movement inside the same accounting universe
5. When a withdraw/deposit is unmatched:
   - unmatched withdrawal -> `EXTERNAL_TRANSFER_OUT`
   - unmatched deposit -> `EXTERNAL_INBOUND`
6. Paired trades are written as `SWAP` documents with `source=BYBIT` and `transactionIndex=0`.

### 6. AVCO replay and reconciliation

Replay input:
- `normalized_transactions WHERE status=CONFIRMED`

Replay order:
- `blockTimestamp ASC`
- `transactionIndex ASC`
- `_id ASC` as deterministic tie-breaker

Replay rules:
- `BUY`
  `newAvco = (avco * qty + price * deltaQty) / (qty + deltaQty)`
- `SELL`
  `realisedPnl = (sellPrice - currentAvco) * abs(deltaQty)`
- `TRANSFER`
  quantity moves with carried basis; no new acquisition, no realised PnL
- `FEE`
  tracked separately; included or excluded by policy

Special accounting rules:
- WETH stays stored as WETH and is aliased to the native asset only at replay time
- `stETH`, `mETH`, `rETH`, `wstETH`, and `cbETH` stay independent assets
- bridge, custody, lending, and vault continuity events preserve basis when correlation or protocol context is established

Current milestone out of scope:
- new current-balance polling architecture
- portfolio snapshots/charts
- broader control-plane redesign
- additional CEX providers beyond Bybit

---

## F — Scaling Path

- MVP
  One Spring Boot process, one MongoDB node, in-process Caffeine cache, public explorer APIs, CoinGecko free tier.
- Phase 2
  Add Redis only if cache churn or horizontal scaling appears. Add Mongo replica set only when read/write contention is proven.
- Phase 3
  Extract services only after a demonstrated module boundary exists, most likely `normalization` or `costbasis`.

---

## G — Cost Analysis

Infra estimate:
- Hetzner CX31/CX41 class VPS: approximately `$14-20/month`
- MongoDB and application on the same host for MVP
- No paid indexers, no managed cache, no managed DB

Major cost levers and mitigations:
- explorer-first ingestion avoids paid archival/indexer infrastructure
- CoinGecko historical usage stays low via dedupe and caching by `(contract, date)`
- no request-path RPC keeps API cost and latency predictable

RPC and API rate-limit plan:
- Etherscan-compatible explorers: provider-local token buckets
- CoinGecko: internal cap below free-tier ceiling
- receipt clarification: only for receipt-clarifiable records

---

## H — Risks & Mitigations

- Risk: synthetic logs accidentally re-enter classification through clarification
  Mitigation: explicit rule that clarification enriches metadata only; no synthetic logs in classifier inputs.
- Risk: Bybit and on-chain matched transfers are double-counted
  Mitigation: mandatory `correlationId`, `TRANSFER`-only modeling, synthetic custody wallet, and replay carry-over rules.
- Risk: the same tx changes meaning depending on which session opened it
  Mitigation: canonical normalization uses installation-wide `tracked_wallets`, not session-local wallet lists.
- Risk: orphan UTA trade legs remain unmatched
  Mitigation: sliding `±5 sec` pairing, blocker tracking, and orphan fallback classification.
- Risk: CoinGecko saturation during initial replay
  Mitigation: stablecoin and swap-derived fast paths first, plus historical cache.
- Risk: supported network docs drift from config
  Mitigation: keep architecture and context docs aligned with current `NetworkId` set, including `KATANA` and `PLASMA`.
- Risk: tx-level raw fields are contaminated by transfer-row payloads and create bogus native legs
  Mitigation: canonicalize tx-level fields at ingestion, read direct native value only from canonical tx-level evidence, and add regression fixtures for bridge-settlement rows.
- Risk: provider-first `BSC` ingestion silently drops approve-only rows while marking sync complete
  Mitigation: persist provider tx hashes before completeness is assumed and add parity tests on audited approve fixtures.
- Risk: clarification rows stay technically valid but carry misleading `missingDataReasons[]`
  Mitigation: compute clarification reasons from the raw view, require explicit contract-creation evidence before emitting `MISSING_CONTRACT_ADDRESS`, and keep `effectiveGasPrice` checks separate from legacy gas-price fallback.
- Risk: per-wallet claim signer rows are silently promoted into reward acquisition
  Mitigation: keep `CLAIM_WITHOUT_MOVEMENT` explicit and add duplicated-claim fixtures across multiple tracked wallets.
