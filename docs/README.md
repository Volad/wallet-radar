# WalletRadar Wiki

Global reference for product context, domain model, backend pipeline stages, frontend pages, normalization rules, accounting policy, and worked examples. Verified against runtime code in `backend/` and `frontend/`.

**Separate from this wiki (current work, not linear reading order):**

| Location | Purpose |
|----------|---------|
| [adr/](adr/INDEX.md) | Architecture Decision Records — one decision per file |
| [tasks/](tasks/) | Implementation plans, cycle closeouts, audit artifacts |

---

## Reading order

### 1. Overview

| Doc | Description |
|-----|-------------|
| [Product context](overview/01-product-context.md) | Goals, non-goals, constraints |
| [Domain & glossary](overview/02-domain-glossary.md) | Entities, invariants, terminology |
| [Architecture](overview/03-architecture.md) | Module map, pipeline summary, terminology corrections |
| [Architecture decisions (SAD)](overview/architecture-decisions.md) | Accepted system-architecture decisions (D-xx rationale) |
| [Data model](overview/04-data-model.md) | Mongo collections, domain entities, status lifecycles |
| [Pipeline orchestration](overview/05-pipeline-orchestration.md) | Events, schedulers, stage state machine |

### 2. Backend pipeline

| Stage | Entry |
|-------|-------|
| [Pipeline index](pipeline/README.md) | End-to-end sequence diagram |
| [Backfill](pipeline/backfill/01-overview.md) | Raw acquisition |
| [Normalization](pipeline/normalization/01-overview.md) | On-chain + Bybit canonicalization |
| [Normalization rules](pipeline/normalization/rules/README.md) | Families, protocols, three-layer contract |
| [Classification spec](pipeline/normalization/classification-spec.md) | Full classification rules + leg-extraction reference |
| [Linking](pipeline/linking/01-overview.md) | Correlation, continuity, repairs |
| [Pricing](pipeline/pricing/01-overview.md) | USD resolution |
| [Cost basis (AVCO)](pipeline/cost-basis/01-overview.md) | Accounting policy |
| [Replay](pipeline/replay/01-overview.md) | Deterministic state rebuild |
| [Portfolio snapshot](pipeline/portfolio-snapshot/01-overview.md) | Live balances + dashboard read model |

### 3. Frontend

| Doc | Description |
|-----|-------------|
| [Frontend index](frontend/README.md) | SPA shell, routing |
| [Dashboard](frontend/dashboard.md) | Portfolio home |
| [Move basis](frontend/move-basis.md) | Asset ledger / AVCO timeline |
| [Settings](frontend/settings.md) | Wallets, integrations, accounting |
| [Lending market](frontend/lending-market.md) | Lending cycles UI |

### 4. Reference (searchable catalogs)

| Doc | Description |
|-----|-------------|
| [API](reference/api.md) | REST endpoints |
| [Glossary](reference/glossary.md) | Consolidated terms |
| [Networks & protocols](reference/supported-networks-and-protocols.md) | `NetworkId` matrix + protocol coverage |
| [Transaction types](reference/transaction-types.md) | All `NormalizedTransactionType` + per-stage behavior |
| [Ledger points & basis effects](reference/ledger-points-and-basis-effects.md) | `AssetLedgerPoint`, `BasisEffect`, fields |
| [RawTransaction field mapping (Explorer vs RPC)](reference/evm-rawtransaction-rpc-field-mapping.md) | `rawData.*` provenance per JSON-RPC method |
| [RawTransaction field mapping (Etherscan vs Blockscout)](reference/evm-etherscan-vs-blockscout-field-mapping.md) | Explorer API compatibility for `rawData.*` |

### 5. Examples (synthetic — no real DB hashes)

| Doc | Description |
|-----|-------------|
| [Examples index](examples/README.md) | Placeholder convention |
| [Normalization](examples/normalization-examples.md) | Classification walkthroughs |
| [Linking](examples/linking-examples.md) | Pairing and continuity |
| [Pricing](examples/pricing-examples.md) | Resolver paths |
| [AVCO / replay](examples/avco-replay-examples.md) | Ledger point math |
| [Move basis carry](examples/move-basis-carry-examples.md) | CARRY_OUT / CARRY_IN |
| [LP cycle](examples/lp-cycle-example.md) | Entry → exit |
| [Lending cycle](examples/lending-cycle-example.md) | Deposit → borrow → close |

---

## Terminology (authoritative)

| Term | Meaning |
|------|---------|
| **Backfill** | Pipeline stage `BACKFILL` — raw acquisition orchestration |
| **Ingestion** | Java subsystem (`com.walletradar.ingestion` adapters) inside backfill; **not** a pipeline stage |
| **NormalizedTransaction** | Canonical economic document in `normalized_transactions` (no separate `EconomicEvent` entity) |
| **Portfolio snapshot** | Stage `PORTFOLIO_SNAPSHOT_REFRESH` + collections `on_chain_balances`, `current_price_quotes` + read-time dashboard assembly |
| **Move basis** | UI name for asset-ledger page; route `/sessions/:sessionId/assets/:familyIdentity` |

## Pipeline stage order

```
BACKFILL
  → ON_CHAIN_NORMALIZATION
  → ON_CHAIN_CLARIFICATION
  → ON_CHAIN_RECLASSIFICATION
  → BYBIT_NORMALIZATION
  → LINKING
  → PRICING
  → ACCOUNTING_REPLAY
  → PORTFOLIO_SNAPSHOT_REFRESH
```

Source: `UserSession.PipelineStage` in `backend/src/main/java/com/walletradar/domain/session/UserSession.java`.

## Doc conventions

- **Mermaid** for sequence, class, flow, and state diagrams.
- **Tables** for provider matrices, type × stage matrices, collection catalogs.
- **Anchors**: reference docs use stable `####` headings per enum value for search.
- **Examples**: placeholder hashes only (`0xEXAMPLE_TX_1`, `0xWALLET_A`).
