# WalletRadar — Product Context

> **Version:** MVP v3 target
> **Last updated:** 2026-03-27
> **Status:** Active development

---

## What is WalletRadar?

WalletRadar is a **self-hosted, privacy-first DeFi portfolio analytics platform** that tracks cost basis, unrealised and realised P&L, and transaction history across multiple EVM-compatible networks and Bybit ledger data — without requiring user registration or connecting wallets to a dApp.

Users add wallet addresses (read-only) into a persisted `user_sessions` model keyed by client-generated `sessionId`. The system ingests on-chain transaction history, normalizes economic events from on-chain raw data and Bybit raw ledger rows, applies Average Cost (AVCO) accounting, and presents a consolidated portfolio view across tracked wallets and supported accounting sources.

---

## Goals

| # | Goal |
|---|------|
| G-01 | Accurate AVCO (Average Cost) cost basis tracking per asset, per wallet, and cross-wallet |
| G-02 | Realised and unrealised P&L calculation with full audit trail |
| G-03 | Multi-wallet, multi-network portfolio aggregation without user accounts |
| G-04 | 2-year transaction history backfill from public explorer APIs (Etherscan V2-compatible) with selective receipt enrichment |
| G-05 | Transparent handling of unresolved events (flags, incomplete history, unknown prices) |
| G-06 | Portfolio value charts with hourly granularity (1D / 7D / 1M / 1Y / ALL) |
| G-07 | Manual intervention: override of cost price for any transaction (full recalculation) and manual compensating transaction to reconcile balance/AVCO when derived quantity does not match on-chain |
| G-08 | Infrastructure cost ≤ $28/month (fully self-hosted, zero managed SaaS) |

---

## Not Goals (MVP)

| # | Explicitly excluded |
|---|---------------------|
| NG-01 | Tax reporting or jurisdiction-specific tax calculations |
| NG-02 | Real-time price feeds (hourly snapshot is sufficient) |
| NG-03 | Rebase token support (stETH, aUSDC quantity changes not tracked) |
| NG-04 | Fiat on/off ramp transaction detection |
| NG-05 | NFT portfolio tracking |
| NG-06 | Additional CEX providers or end-user CEX onboarding flows beyond the existing Bybit raw ledger source |
| NG-07 | User registration, authentication, or multi-user access control |
| NG-08 | Automated tax-loss harvesting recommendations |
| NG-09 | Transactions older than 2 years from current date |
| NG-10 | Generic derivative / perpetual accounting beyond the audited GMX V2 order, position, close, cancel, and GM / GLV pool lifecycle currently covered by normalization and clarification |

Manual compensating transactions may be **positive or negative** (reducing quantity); both are in scope for MVP to allow full reconciliation.

---

## Constraints & Limitations

### Technical Constraints
- **Monorepo** — backend (Spring Boot) and frontend (Angular) are in the same repository; backend is built with **Gradle** (not Maven).
- **No user accounts** — session identity is a client-generated UUID, persisted server-side in `user_sessions`
- **Reconciliation UX** — when derived quantity does not match on-chain balance (e.g. for wallets with history within the 2-year window), the UI shows a warning on the asset and the user can add a manual compensating transaction to align balance and AVCO
- **Read-only access** — system never requests wallet signing or private keys
- **Public/free data sources only** — no dependency on paid indexers (Alchemy Growth, The Graph paid, Moralis paid)
- **Pricing sources** — event-local tx pricing first, Binance market data as the primary external source for listed assets, CoinGecko as bounded historical fallback only
- **Transaction workflow** — status-driven `normalized_transactions` pipeline where only receipt-clarifiable rows enter `PENDING_CLARIFICATION`; low-confidence rows without receipt gaps proceed directly to pricing or review
- **Bybit source** — current v3 runtime primarily uses immutable `integration_raw_events`
  plus rebuildable `bybit_extracted_events`; `external_ledger_raw` may be absent
  in fresh environments and must not be assumed as loaded unless the source
  contract explicitly restores it. New interactive import UX is not required now.
- **2-year backfill window** — transactions before this window require manual entry
- **EVM ingestion source (MVP v2)** — explorer-first (Etherscan V2-compatible API per network) with `page/offset` fetch and selective `getReceipt` enrichment for ambiguous transactions.
- **Explorer paging default** — use `offset=5000` by default (configurable), with provider-aware fallback and retry/backoff on rate-limit/timeout.

### Financial Constraints
- Gas is converted to USD at the native token price at block time
- Gas is included in cost basis for BUY events by default (configurable per override)
- AVCO is recalculated deterministically — same inputs always produce same output
- `crossWalletAvco` is always computed across **all networks** (network filter does not change AVCO)

### Infrastructure Constraints
- Single VPS deployment for MVP (Hetzner CX31/CX41)
- No Kubernetes, no managed databases, no paid cloud services
- MongoDB single node (replica set in Phase 2)
- Caffeine in-process cache (Redis in Phase 2 for horizontal scaling)

---

## Supported Networks (MVP)

| Network | Type | NetworkId | Primary Source (MVP v2) |
|---------|------|-----------|--------------------------|
| Ethereum Mainnet | EVM | `ETHEREUM` | Etherscan V2 API |
| Arbitrum One | EVM | `ARBITRUM` | Arbiscan API |
| Optimism | EVM | `OPTIMISM` | Optimistic Etherscan API |
| Base | EVM | `BASE` | Basescan API |
| BNB Chain | EVM | `BSC` | BscScan API |
| Polygon | EVM | `POLYGON` | Polygonscan API |
| Avalanche C-Chain | EVM | `AVALANCHE` | Snowtrace API |
| Mantle | EVM | `MANTLE` | Mantlescan API |
| Linea | EVM | `LINEA` | Etherscan V2 API (`chainid=59144`) |
| Katana | EVM | `KATANA` | Etherscan-compatible API |
| Plasma | EVM | `PLASMA` | Etherscan-compatible API |
| zkSync Era | EVM | `ZKSYNC` | Blockscout API |
| Unichain | EVM | `UNICHAIN` | Etherscan V2 API (`chainid=130`) |
| Solana | SVM | `SOLANA` | Out of scope for MVP v2 |

Bybit scope:
- `integration_raw_events` and `bybit_extracted_events` are the active v3 Bybit
  acquisition/rebuild source; `external_ledger_raw` remains a legacy/audit source
  only when present
- only Bybit is in scope for CEX data in v3

---

## User Journey (High Level)

```
1. Open WalletRadar in browser
2. Create or reuse a `sessionId`
3. Add wallet addresses and selected networks into the persisted session
4. Existing backfill pipeline collects raw on-chain history
5. Normalization and pricing convert raw on-chain and Bybit evidence into canonical accounting events
6. AVCO replay computes per-wallet positions and reconciliation flags
7. User reviews unresolved or incomplete-history cases
```

---

## Related Documents

| Document | Description |
|----------|-------------|
| [Domain & glossary](02-domain-glossary.md) | Entities, glossary, invariants |
| [Architecture](03-architecture.md) | Module architecture, data flows |
| [Cost basis (AVCO)](../pipeline/cost-basis/01-overview.md) | AVCO policy, P&L, cost basis rules |
| [API reference](../reference/api.md) | API contract |
| [ADR index](../adr/INDEX.md) | Architecture decisions |
