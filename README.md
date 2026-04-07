# WalletRadar

DeFi wallet analytics platform — cost basis tracking and portfolio statistics.

- **Stack:** Java 21, Spring Boot 3.x, MongoDB 7
- **Docs:** See [docs/](docs/) for context, domain, architecture, API, and tasks.

## Backend Schema (Mermaid)

```mermaid
flowchart LR
    UI[Client / Frontend]

    subgraph API[API Layer]
        WalletController
        SyncController
        WalletSyncStatusService
    end

    subgraph ING[Ingestion]
        WalletBackfillService
        BackfillJobRunner
        RawFetchSegmentProcessor
        RawTransactionClassifierJob
        ClassificationProcessor
        TxClassifierDispatcher
        EconomicEventNormalizer
        InlineSwapPriceEnricher
        IdempotentEventStore
        DeferredPriceResolutionJob
        IncrementalSyncJob
    end

    subgraph ACC[Accounting & Pricing]
        HistoricalPriceResolverChain
        AvcoEngine
    end

    subgraph DB["MongoDB Collections"]
        SyncStatus[(sync_status)]
        RawTx[(raw_transactions)]
        EconomicEvents[(economic_events)]
        AssetPositions[(asset_positions)]
        OnChainBalances[(on_chain_balances)]
    end

    UI --> WalletController
    UI --> SyncController
    WalletController --> WalletBackfillService
    WalletController --> WalletSyncStatusService
    WalletSyncStatusService --> SyncStatus

    WalletBackfillService --> SyncStatus
    WalletBackfillService --> BackfillJobRunner
    BackfillJobRunner --> RawFetchSegmentProcessor
    RawFetchSegmentProcessor --> RawTx

    RawTx --> RawTransactionClassifierJob
    RawTransactionClassifierJob --> ClassificationProcessor
    ClassificationProcessor --> TxClassifierDispatcher
    ClassificationProcessor --> EconomicEventNormalizer
    ClassificationProcessor --> InlineSwapPriceEnricher
    ClassificationProcessor --> HistoricalPriceResolverChain
    ClassificationProcessor --> IdempotentEventStore
    IdempotentEventStore --> EconomicEvents

    DeferredPriceResolutionJob --> EconomicEvents
    DeferredPriceResolutionJob --> HistoricalPriceResolverChain
    DeferredPriceResolutionJob --> AvcoEngine

    AvcoEngine --> EconomicEvents
    AvcoEngine --> AssetPositions

    SyncController --> IncrementalSyncJob
    IncrementalSyncJob --> RawTx
    IncrementalSyncJob --> SyncStatus

    IncrementalSyncJob --> OnChainBalances
```
