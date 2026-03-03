# WalletRadar — Architecture

> **Version:** SAD v2.1 (as-built)  
> **Last updated:** 2026-03-02  
> **Style:** Modular monolith (Spring Boot)

---

## 1. Architectural Style

WalletRadar is a modular monolith with explicit package boundaries and event-driven internal orchestration.

Key principles:

- Canonical transaction model is `normalized_transactions` with `flows[]`.
- Backfill and normalization are decoupled (`raw fetch` phase and `raw -> normalized` phase).
- `GET` path is data-store based (no chain RPC calls on request path).
- AVCO replay is deterministic and uses only `CONFIRMED` normalized transactions.
- EVM ingestion is explorer-first with selective receipt enrichment for low-confidence cases.

---

## 2. High-Level Context

```text
Browser/API Client
    |
    v
Spring Boot (modular monolith)
    |- api/                controllers + DTO + validation
    |- ingestion/          backfill, adapters, classifier, normalization pipeline
    |- costbasis/          AVCO replay, overrides, transaction history query
    |- pricing/            historical price resolver chain
    |- domain/             documents, enums, repository interfaces
    |- common/config/      shared utilities + wiring
    |
    +--> MongoDB (raw_transactions, normalized_transactions, asset_positions, ...)
    +--> EVM Explorer APIs (txlist/tokentx/txlistinternal + selective receipt)
    +--> EVM RPC endpoints (block/timestamp/decimals/native clarification/balances)
```

---

## 3. Package Map (as-built)

```text
com.walletradar
├── api/
│   ├── controller/  WalletController, TransactionController, SyncController, BalanceController
│   ├── dto/
│   └── validation/
├── ingestion/
│   ├── adapter/
│   │   ├── evm/
│   │   │   ├── explorer/  ExplorerProvider, EtherscanV2ExplorerProvider, ExplorerEvmNetworkAdapter
│   │   │   └── rpc/       EvmRpcClient + resolvers (height/timestamp/decimals/native)
│   │   └── solana/
│   ├── classifier/        NativeTransfer, PerpOrder, BridgeCall, Lend, LP, Swap, Transfer, Stake, RawTransactionNormalizationView
│   ├── filter/            ScamFilter
│   ├── job/
│   │   ├── backfill/      BackfillJobRunner, BackfillNetworkExecutor, RawFetchSegmentProcessor
│   │   ├── classification/ RawTxNormalizationJob, ClassificationProcessor, ConfidenceScorer, ClarificationJob
│   │   ├── pricing/       NormalizedTransactionPricingJob, NormalizedTransactionStatJob
│   │   └── sync/          IncrementalSyncJob
│   ├── normalizer/        NormalizedTransactionBuilder, NormalizedTransactionValidator
│   ├── store/             IdempotentNormalizedTransactionStore
│   ├── sync/balance/      BalanceRefreshService
│   └── wallet/            command/query services
├── costbasis/
│   ├── engine/            AvcoEngine, CrossWalletAvcoAggregatorService
│   ├── override/          OverrideService
│   └── query/             TransactionHistoryQueryService
├── pricing/
│   ├── HistoricalPriceResolverChain
│   └── resolver/          Stablecoin, SwapDerived, CoinGecko, Counterpart resolvers
├── domain/
├── common/
└── config/
```

---

## 4. Canonical Data Pipeline

### 4.1 Raw fetch (Phase 1)

1. `WalletController` enqueues backfill via `WalletBackfillService`.
2. `BackfillJobRunner` orchestrates wallet×network work queue and retries.
3. `BackfillNetworkExecutor` plans and executes `backfill_segments`.
4. `RawFetchSegmentProcessor` fetches tx data from `NetworkAdapter` and upserts into `raw_transactions`.
5. `ScamFilter` can drop suspicious raw transactions before persistence.
6. Successful raw docs stay with `normalizationStatus=PENDING`.

### 4.2 Raw normalization (Phase 2)

1. `RawTxNormalizationJob` polls `raw_transactions` with `normalizationStatus=PENDING`.
2. `ClassificationProcessor`:
   - wraps each raw tx once in `RawTransactionNormalizationView`;
   - normalizes mixed BSON/JSON payload shape (`Map/List` -> `Document/List<Document>`) inside the view;
   - routes all classifier access to raw payload through view accessors (`readRawOrExplorerTx`, `logs`, `explorerTokenTransfers`, `explorerInternalTransfers`);
   - synthesizes missing ERC-20 transfer logs from explorer payload when needed;
   - runs classifier dispatcher;
   - computes numeric confidence (`[0..1]`);
   - if confidence is low, performs selective `getReceipt` enrichment via `ExplorerProvider` and re-scores.
3. `NormalizedTransactionBuilder` builds canonical `NormalizedTransaction` with `flows[]`.
4. `IdempotentNormalizedTransactionStore` upserts by `(txHash, networkId, walletAddress)`.
5. raw tx is marked `normalizationStatus=COMPLETE`.

### 4.3 Normalized pipeline

Status flow:

```text
PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED
                                     \-> NEEDS_REVIEW (if unresolved)
```

- `ClarificationJob`: optional extra enrichment for `PENDING_CLARIFICATION`.
- `NormalizedTransactionPricingJob`: resolves `unitPriceUsd`/`valueUsd` and moves to `PENDING_STAT` or `NEEDS_REVIEW`.
- `NormalizedTransactionStatJob`: final consistency checks and moves to `CONFIRMED`, then triggers wallet recalc event.

### 4.4 Cost basis replay

- `AvcoEngine` replays only `status=CONFIRMED` normalized transactions.
- Replay order is chronological and deterministic.
- `OverrideService` stores price overrides and triggers recalculation jobs.
- `CrossWalletAvcoAggregatorService` computes cross-wallet AVCO on request.

---

## 5. Classifier Chain and Ordering

`TxClassifierDispatcher` executes all `TxClassifier` beans by Spring `@Order`.

| Order | Classifier | Purpose |
|------:|------------|---------|
| 50 | `NativeTransferClassifier` | Simple native transfer and internal-transfers-only native net flow |
| 70 | `PerpOrderClassifier` | Heuristic perp order create calls without logs |
| 75 | `BridgeCallClassifier` | Bridge/native bridge calls with value outflow heuristics |
| 90 | `LendClassifier` | Deposit/withdraw/borrow/repay and vault-like flows |
| 95 | `LpClassifier` | LP lifecycle state-machine: `LP_ENTRY`, `LP_FEE_CLAIM`, `LP_EXIT_PARTIAL/FINAL`, `LP_POSITION_STAKE/UNSTAKE` |
| 99 | `SwapClassifier` | Swap detection and stablecoin leg extraction |
| 100 | `TransferClassifier` | Generic ERC-20/native transfer fallback |
| 110 | `StakeClassifier` | Stake-specific fallback patterns |

Notes:

- LP classifier includes curated protocol/router whitelist (top DeFi LP venues).
- CL/NFT LP path currently targets PancakeSwap, Uniswap, Aerodrome position NFTs.
- For CL v3/v4 position mint, LP classifier emits economic `LP_ENTRY` flows from outbound principal tokens and links them by position id group.
- Failed transactions (`status=0x0` / `isError=1`) are ignored by classifiers that require successful execution.

---

## 6. Scam Filter (ingestion-time)

`ScamFilter` applies score-based heuristics and drops transactions when score >= `dropThreshold`.

Current signals include:

- direct blocklist match;
- failed swap / failed zero-value no-transfer effects;
- unsolicited inbound-only patterns;
- mass relay/fanout and zero-amount poisoning;
- suspicious airdrop metadata (URL/claim/visit token text, tiny integer airdrop values, zero decimals + odd text, `Airdrop` function markers).

This keeps malicious spam tokens out of canonical accounting flow.

---

## 7. Storage Model (key collections)

- `raw_transactions`: explorer/RPC raw payload, normalization status, retry metadata.
- `normalized_transactions`: canonical tx-level operation docs with `flows[]`, confidence, pipeline status.
- `asset_positions`: derived AVCO and PnL state.
- `cost_basis_overrides`: active/inactive manual price overrides.
- `recalc_jobs`: async replay jobs.
- `sync_status`: wallet×network sync lifecycle.
- `backfill_segments`: persistent segment execution state.
- `on_chain_balances`: latest observed balances for reconciliation.

Key uniqueness constraints:

- raw tx: `(txHash, networkId, walletAddress)`
- normalized tx: `(txHash, networkId, walletAddress)` plus optional unique `clientId`
- segment: `(syncStatusId, segmentIndex)`

---

## 8. Module Dependency Rules

Target dependency direction:

- `api` -> application services (`ingestion`, `costbasis`), never adapters directly.
- `ingestion` -> `domain`, `common`, `pricing` (for pricing stage).
- `costbasis` -> `domain` (+ readonly queries), no dependency on ingestion jobs.
- `domain` contains data model and repository contracts only.

---

## 9. Current Boundaries vs Future

Implemented and active now:

- explorer-first EVM ingestion (ADR-026),
- canonical normalized flow pipeline,
- score-based scam filtering,
- AVCO replay on confirmed normalized docs.

Future/optional (not primary in current code path):

- broader Solana parity,
- additional snapshot/query modules,
- microservice split (only after real scaling pressure).
