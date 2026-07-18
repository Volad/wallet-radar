# ADR-048: Dzengi Product Scope

**Status:** Accepted  
**Date:** 2026-07-08  
**Theme:** CEX integration — Dzengi venue boundaries

---

## Context

WalletRadar adds Dzengi as the second CEX integration after Bybit. Dzengi exposes a Binance-compatible REST API (`api-adapter.dzengi.com`) with mixed spot, fiat, and derivative history. The product must define a **narrow, auditable scope** so normalization and AVCO remain deterministic without modeling full margin/CFD lifecycle.

User-facing goal: track Dzengi custody movements, spot trades, and settlement lines that affect cost basis — not replicate the exchange trading terminal.

---

## Decision

### D1. In-scope streams

| Stream | Rationale |
|--------|-----------|
| `LEDGER` | Fiat/crypto account movements |
| `DEPOSITS` / `WITHDRAWALS` | On-chain bridge rows with `blockchainTransactionHash` |
| `MY_TRADES` (spot symbols only) | Spot acquisition/disposal |
| `TRADING_POSITIONS_HISTORY` | Realized derivative settlements |
| `EXCHANGE_INFO` | Symbol metadata for extraction |

### D2. Explicit exclusions

| Evidence | Treatment |
|----------|-----------|
| Leverage / CFD symbol fills | Excluded at extraction (`outOfScope`, `LEVERAGE_FILL_EXCLUDED`) |
| Open derivative positions (unrealized) | Not ingested in MVP |
| Earn / staking products | Not in Dzengi MVP (unlike Bybit Earn corridor) |

### D3. Canonical types emitted

- `SWAP` (spot BUY/SELL)
- `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT`
- `CEX_DERIVATIVE_SETTLEMENT`
- `FEE` (ledger fees)

### D4. Account identity

- Integration account ref: `DZENGI:<userId>` from `/api/v1/account` validation.
- One primary integration per session (same pattern as Bybit settings overwrite).

### D5. Pipeline stage

Dedicated `DZENGI_NORMALIZATION` stage between `BYBIT_NORMALIZATION` and `LINKING` when a Dzengi integration is enabled.

---

## Consequences

- Conservation and NEC treat Dzengi like any other CEX member in `AccountingUniverse`.
- Failed cross-chain links surface via pool imbalance + conservation gate (ADR-013 / ADR-049), not silent drops.
- Future margin/CFD support requires a new ADR extending extraction and canonical types.

## Related

- [Dzengi normalization](../pipeline/normalization/04-dzengi-normalization.md)
- [ADR-049 Venue-agnostic CEX transfer linking](ADR-049-venue-agnostic-cex-transfer-linking.md)
- [Add an integration](../reference/extensibility/add-an-integration.md)
