# WalletRadar — Product Context

> **Version:** MVP v1.0  
> **Last updated:** 2025  
> **Status:** Active development

---

## What is WalletRadar?

WalletRadar is a **self-hosted, privacy-first DeFi portfolio analytics platform** that tracks cost basis, unrealised and realised P&L, and transaction history across multiple EVM-compatible networks and Solana — without requiring user registration or connecting wallets to a dApp.

Users add wallet addresses (read-only). The system ingests on-chain transaction history, classifies economic events, applies Average Cost (AVCO) accounting, and presents a consolidated portfolio view across all tracked wallets and networks.

---

## Goals

| # | Goal |
|---|------|
| G-01 | Accurate AVCO (Average Cost) cost basis tracking per asset, per wallet, and cross-wallet |
| G-02 | Realised and unrealised P&L calculation with full audit trail |
| G-03 | Multi-wallet, multi-network portfolio aggregation without user accounts |
| G-04 | 2-year transaction history backfill from public RPC endpoints |
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
| NG-06 | CEX (Centralised Exchange) import |
| NG-07 | User registration, authentication, or multi-user access control |
| NG-08 | Automated tax-loss harvesting recommendations |
| NG-09 | Transactions older than 2 years from current date |

Manual compensating transactions may be **positive or negative** (reducing quantity); both are in scope for MVP to allow full reconciliation.

---

## Constraints & Limitations

### Technical Constraints
- **Monorepo** — backend (Spring Boot) and frontend (Angular) are in the same repository; backend is built with **Gradle** (not Maven).
- **No user accounts** — session is a browser-local ordered list of wallet addresses
- **Reconciliation UX** — when derived quantity does not match on-chain balance (e.g. for wallets with history within the 2-year window), the UI shows a warning on the asset and the user can add a manual compensating transaction to align balance and AVCO
- **Read-only access** — system never requests wallet signing or private keys
- **Public RPC only** — no dependency on paid indexers (Alchemy Growth, The Graph paid, Moralis paid)
- **CoinGecko Free tier** — 50 req/min; throttled to 45 req/min internally; historical price fallback only
- **2-year backfill window** — transactions before this window require manual entry

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

| Network | Type | RPC Source |
|---------|------|-----------|
| Ethereum Mainnet | EVM | Cloudflare / Ankr Free |
| Arbitrum One | EVM | Ankr Free / LlamaRPC |
| Optimism | EVM | Ankr Free / LlamaRPC |
| Base | EVM | Ankr Free / Official |
| BNB Chain | EVM | Ankr Free |
| Polygon | EVM | Ankr Free |
| Avalanche C-Chain | EVM | Ankr Free |
| Mantle | EVM | Official RPC |
| Solana | SVM | Helius Free (100k req/day) |

---

## User Journey (High Level)

```
1. Open WalletRadar in browser
2. Add wallet address (EVM 0x… or Solana Base58)
3. System starts 2-year backfill (background, shows progress banner)
4. User sees partial portfolio as assets are indexed
5. On backfill complete — full portfolio view with AVCO, P&L, charts
6. Add more wallets → system reclassifies internal transfers automatically
7. Flag resolution — review events with unknown price or unsupported type
8. Manual override — correct cost price for any event → async AVCO recalculation
9. Reconciliation — when derived quantity does not match on-chain balance (e.g. for wallets with history within 2 years), the UI shows a warning on the asset and the user can add a manual compensating transaction to align balance and AVCO
```

---

## Related Documents

| Document | Description |
|----------|-------------|
| `docs/01-domain.md` | Entities, glossary, invariants |
| `docs/02-architecture.md` | Module architecture, data flows |
| `docs/03-accounting.md` | AVCO policy, P&L, cost basis rules |
| `docs/04-api.md` | API contract |
| `docs/adr/` | Architecture Decision Records |
