# ADR-050: Dzengi Fiat FX Pricing (BYN)

**Status:** Accepted  
**Date:** 2026-07-08  
**Theme:** Pricing — venue-native fiat FX for Dzengi ledger legs

---

## Context

Dzengi ledger and deposit flows may denominate in **BYN** (Belarusian ruble). Global market-data providers used by `PriceExternalSourceOrchestrator` do not reliably quote BYN/USD at historical event times. Dzengi itself publishes USD/BYN klines on the same API surface used for acquisition.

Pricing must resolve BYN legs on Dzengi-origin rows without inventing fiat rates in normalization.

---

## Decision

### D1. New `PriceSource.DZENGI`

Add `DZENGI` to `PriceSource` enum for venue-native FX quotes persisted on flow `priceSource`.

### D2. `DzengiFxPriceSourceAdapter`

| Property | Value |
|----------|-------|
| Package | `application.pricing.resolver.external.dzengi` |
| Order | `@Order(0)` among external sources for Dzengi rows |
| Supports | `request.transactionSource() == DZENGI` **and** normalized symbol `BYN` |
| Method | Fetch USD/BYN kline at `request.occurredAt()` via `DzengiKlineClient`, invert to USD per BYN unit |
| Quote currency | USD |

### D3. Scope limit

- **Only BYN** on Dzengi-sourced rows uses `PriceSource.DZENGI` in MVP.
- Other fiat currencies on Dzengi rows follow generic external orchestrator paths or remain `PRICE_UNRESOLVABLE` until a venue quote exists.

### D4. Normalization does not stamp BYN price

`DzengiCanonicalTransactionBuilder` leaves BYN flow `unitPriceUsd` null; pricing stage owns resolution.

### D5. Cache

Resolved quotes follow the same `historical_prices` persistence path as other external sources.

---

## Consequences

- AVCO on BYN-denominated external inflows uses deterministic venue FX at event time.
- Resolver chain documentation must list `DZENGI` external adapter ([pricing resolver chain](../pipeline/pricing/02-resolver-chain.md)).
- Adding EUR or other Dzengi fiat requires extending `supports()` with explicit kline pairs.

## Related

- [Pricing overview](../pipeline/pricing/01-overview.md)
- [Dzengi normalization](../pipeline/normalization/04-dzengi-normalization.md)
- [ADR-048 Dzengi product scope](ADR-048-dzengi-product-scope.md)
