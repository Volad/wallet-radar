# Glossary

> **Last updated:** 2026-06-05  
> Consolidated terminology for WalletRadar wiki.

| Term | Definition |
|------|------------|
| **Accounting universe** | Multi-wallet scope for replay and dashboard; `AccountingUniverse` entity |
| **AVCO** | Average Cost methodology — weighted average purchase price per asset |
| **Backfill** | Pipeline stage `BACKFILL` — raw transaction acquisition |
| **Basis effect** | `AssetLedgerPoint.BasisEffect` — ACQUIRE, DISPOSE, CARRY_*, REALLOCATE_*, GAS_ONLY, UNKNOWN |
| **Basis-backed quantity** | Quantity with provable cost basis from replay |
| **Continuity candidate** | Normalized tx flagged for move-basis (not new acquisition) when linked |
| **Correlation ID** | Links async/bridge/LP lifecycle legs across transactions |
| **Conservation gate** | Dashboard invariant check (ADR-014) — NEC vs MTM vs reported PnL |
| **Counterparty basis pool** | External in/out basis held per non-universe counterparty (ADR-015) |
| **Family identity** | Cross-network asset grouping (e.g. ETH on multiple chains) |
| **Ingestion** | Adapter subsystem for raw fetch — **not** a pipeline stage name |
| **Move basis** | UI term for asset-ledger timeline showing basis carry and AVCO |
| **Normalized transaction** | Canonical economic document in `normalized_transactions` |
| **Pass-through corridor** | Pre-planned wallet→counterparty carry (ADR-019/020) |
| **Portfolio snapshot** | Stage + live balance/quote collections — not a single stored snapshot doc |
| **Replay** | Delete-and-rebuild of ledger state from confirmed normalized txs |
| **Session** | Client-generated UUID persisted as `user_sessions` |
| **Shortfall** | Quantity consumed without sufficient basis; tracked explicitly |
| **Stat validation** | Promote priced rows to CONFIRMED before replay |
| **Tracked wallets** | Installation-wide wallet universe for normalization scope |
| **Uncovered quantity** | Live-positive qty without basis proof |

## Terminology corrections (legacy → current)

| Legacy (incorrect) | Current (code) |
|------------------|----------------|
| Pipeline stage INGESTION | BACKFILL |
| EconomicEvent entity | NormalizedTransaction |
| Persisted portfolio snapshot document | on_chain_balances + current_price_quotes + read-time join |
| Route /move-basis | /sessions/:id/assets/:familyIdentity |
| transfer_links required today | Planned ADR-003; metadata + repair services |

## Related

- [Domain glossary](../overview/02-domain-glossary.md) — full entity definitions
- [Transaction types](transaction-types.md)
- [Ledger points](ledger-points-and-basis-effects.md)
